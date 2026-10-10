"""C and C++ sources (tree-sitter-c / tree-sitter-cpp): functions, ``main()`` (a ``c-executable``
service named after the file), includes, called functions, ``system()``/``exec*``/``popen``
commands, file I/O, sockets and IBM MQ MQI calls.

The Pro*C analyzer blanks ``EXEC SQL`` blocks and calls :func:`analyze_c` on the rest.
"""

from __future__ import annotations

import re
from typing import ClassVar

import tree_sitter_c
import tree_sitter_cpp
from tree_sitter import Language, Node, Parser

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import tech
from legacy_audit.analyzers.cft import cftutil_integration
from legacy_audit.analyzers.integrations import integration
from legacy_audit.analyzers.textutil import basename, stem
from legacy_audit.model import EntryPoint, Evidence, FileRecord, Service, make_id

_C = Language(tree_sitter_c.language())
_CPP = Language(tree_sitter_cpp.language())

EXEC_FUNCS = {
    "system",
    "popen",
    "execl",
    "execlp",
    "execle",
    "execv",
    "execvp",
    "execve",
    "_popen",
    "_system",
    "spawnl",
    "spawnlp",
    "spawnv",
    "spawnvp",
    "CreateProcess",
}
FILE_FUNCS = {"fopen": 1, "fopen64": 1, "freopen": 1, "open": 1, "creat": 1, "_open": 1}
MQ_PRODUCE = {"MQPUT", "MQPUT1"}
MQ_CONSUME = {"MQGET"}
MQI = {
    "MQCONN",
    "MQCONNX",
    "MQOPEN",
    "MQPUT",
    "MQPUT1",
    "MQGET",
    "MQCLOSE",
    "MQDISC",
    "MQINQ",
    "MQSET",
    "MQBEGIN",
    "MQCMIT",
    "MQBACK",
}
LIBC = {
    "printf",
    "fprintf",
    "sprintf",
    "snprintf",
    "scanf",
    "sscanf",
    "fscanf",
    "puts",
    "fputs",
    "fgets",
    "gets",
    "getc",
    "fgetc",
    "putc",
    "fputc",
    "fread",
    "fwrite",
    "fclose",
    "fflush",
    "fseek",
    "ftell",
    "rewind",
    "feof",
    "ferror",
    "perror",
    "malloc",
    "calloc",
    "realloc",
    "free",
    "memset",
    "memcpy",
    "memmove",
    "memcmp",
    "strcpy",
    "strncpy",
    "strcat",
    "strncat",
    "strcmp",
    "strncmp",
    "strlen",
    "strchr",
    "strrchr",
    "strstr",
    "strtok",
    "strdup",
    "atoi",
    "atol",
    "atof",
    "strtol",
    "strtoul",
    "strtod",
    "exit",
    "abort",
    "getenv",
    "setenv",
    "time",
    "localtime",
    "strftime",
    "mktime",
    "sleep",
    "close",
    "read",
    "write",
    "toupper",
    "tolower",
    "isdigit",
    "isalpha",
    "isspace",
    "sizeof",
    "assert",
    "va_start",
    "va_end",
    "errno",
    "strerror",
    "qsort",
    "htons",
    "htonl",
    "ntohs",
    "ntohl",
    "unlink",
    "rename",
    "remove",
    "fork",
    "wait",
    "waitpid",
    "signal",
    "pclose",
    "rand",
    "srand",
    "abs",
    "isalnum",
    "memchr",
}
SYSTEM_HEADERS = re.compile(r"^(std|sys/|netinet/|arpa/|linux/|bits/|c\+\+|win|cmq)", re.I)


def _str(node: Node | None) -> str | None:
    """Literal value of a string or concatenated string; None if not a literal."""
    if node is None:
        return None
    if node.type == "string_literal":
        return "".join(
            (c.text or b"").decode("utf-8", "replace")
            for c in node.children
            if c.type in ("string_content", "escape_sequence")
        )
    if node.type == "concatenated_string":
        parts = [_str(c) for c in node.named_children]
        return None if any(p is None for p in parts) else "".join(p or "" for p in parts)
    return None


def _txt(node: Node | None) -> str:
    return (node.text or b"").decode("utf-8", "replace") if node is not None else ""


def _function_name(node: Node) -> str | None:
    decl = node.child_by_field_name("declarator")
    while decl is not None and decl.type != "function_declarator":
        decl = decl.child_by_field_name("declarator")
    if decl is None:
        return None
    name = decl.child_by_field_name("declarator")
    return _txt(name) if name is not None else None


class _Walker:
    def __init__(self, path: str, facts: Facts) -> None:
        self.path = path
        self.facts = facts
        self.functions: dict[str, int] = {}
        self.calls: dict[str, int] = {}
        self.queues: list[tuple[str, int]] = []
        self.mq_calls: set[str] = set()
        self.hosts: list[tuple[str, int]] = []
        self.ports: list[str] = []
        self.socket_calls: set[str] = set()

    def run(self, root: Node) -> None:
        stack = [root]
        while stack:
            n = stack.pop()
            t = n.type
            if t == "preproc_include":
                self._include(n)
            elif t == "function_definition":
                name = _function_name(n)
                if name:
                    self.functions.setdefault(name, n.start_point[0] + 1)
            elif t == "call_expression":
                self._call(n)
            stack.extend(reversed(n.children))

    def _include(self, n: Node) -> None:
        target = n.child_by_field_name("path")
        if target is None:
            return
        line = n.start_point[0] + 1
        if target.type == "string_literal":
            name = _str(target) or ""
            self.facts.ref(
                kind="includes",
                source=f"file:{self.path}",
                source_label=self.path,
                target=basename(name),
                target_kinds=["file-name"],
                file=self.path,
                line=line,
                report_unresolved=not SYSTEM_HEADERS.match(name),
            )
        elif _txt(target).strip("<>").lower() in ("cmqc.h", "cmqxc.h", "cmqcfc.h"):
            self.facts.add("tech_stack", tech("IBM MQ (MQI)", None, "middleware", self.path, line))

    def _call(self, n: Node) -> None:
        fn = n.child_by_field_name("function")
        name = _txt(fn).rsplit("::", 1)[-1].rsplit(".", 1)[-1].rsplit("->", 1)[-1]
        args_node = n.child_by_field_name("arguments")
        args = list(args_node.named_children) if args_node is not None else []
        line = n.start_point[0] + 1
        if not name:
            return
        self.calls.setdefault(name, line)
        if name in EXEC_FUNCS and args:
            cmd = _str(args[0])
            if cmd:
                self._command(cmd, line)
        elif name in FILE_FUNCS and args:
            fname = _str(args[0])
            mode = _str(args[1]) if len(args) > 1 else None
            if fname:
                if name.startswith("fopen") or name == "freopen":
                    direction = (
                        "inbound"
                        if (mode or "r").startswith("r") and "+" not in (mode or "")
                        else "outbound"
                    )
                else:
                    flags = _txt(args[1]) if len(args) > 1 else ""
                    direction = (
                        "outbound"
                        if re.search(r"O_WRONLY|O_RDWR|O_CREAT|O_APPEND", flags) or name == "creat"
                        else "inbound"
                    )
                self.facts.add(
                    "integrations",
                    integration(
                        type="FILE",
                        target=fname,
                        direction=direction,
                        file=self.path,
                        line=line,
                        protocol=name,
                    ),
                )
        elif name in ("strcpy", "strncpy", "memcpy", "sprintf", "snprintf") and len(args) >= 2:
            dest = _txt(args[0])
            src = _str(args[1]) if name not in ("sprintf", "snprintf") else None
            if src and dest.endswith("ObjectName"):
                self.queues.append((src, line))
        elif name in ("MQCONN", "MQCONNX") and args:
            self.mq_calls.add(name)
            qmgr = _str(args[0])
            if qmgr:
                self.facts.add(
                    "integrations",
                    integration(
                        type="MQ-QMGR",
                        target=qmgr,
                        direction="outbound",
                        file=self.path,
                        line=line,
                        protocol="MQI",
                    ),
                )
        elif name in MQI:
            self.mq_calls.add(name)
        elif name in ("gethostbyname", "inet_addr", "getaddrinfo", "inet_pton") and args:
            host = _str(args[0] if name != "inet_pton" else args[1] if len(args) > 1 else args[0])
            if host:
                self.hosts.append((host, line))
        elif name == "htons" and args and args[0].type == "number_literal":
            self.ports.append(_txt(args[0]))
        if name in ("connect", "bind", "listen", "accept", "socket"):
            self.socket_calls.add(name)
            if name in ("connect", "listen"):
                self.calls[f"__{name}_line"] = line

    def _command(self, cmd: str, line: int) -> None:
        first = cmd.strip().split()
        if not first:
            return
        prog = basename(first[0])
        if prog.lower() == "cftutil":
            integ = cftutil_integration(" ".join(first[1:]), self.path, line)
            if integ:
                self.facts.add("integrations", integ)
            return
        if prog in ("sh", "ksh", "bash", "perl") and len(first) > 1:
            prog = basename(first[1])
        if prog and not prog.startswith(("$", "%")):
            self.facts.ref(
                kind="invokes",
                source=f"file:{self.path}",
                source_label=self.path,
                target=prog,
                target_kinds=["file-name", "c-executable"],
                file=self.path,
                line=line,
                report_unresolved=prog.endswith((".sh", ".ksh", ".pl")),
            )

    def finish(self) -> None:
        path = self.path
        for name, line in self.functions.items():
            self.facts.symbol("c-function", name, path, line)
        for name, line in sorted(self.calls.items()):
            if name.startswith("__") or name in self.functions or name in LIBC or name in MQI:
                continue
            # Calls to functions defined in another scanned file become edges; library calls
            # simply stay unresolved and are not reported.
            self.facts.ref(
                kind="calls",
                source=f"file:{path}",
                source_label=path,
                target=name,
                target_kinds=["c-function"],
                file=path,
                line=line,
                report_unresolved=False,
            )
        if self.queues:
            role = None
            if self.mq_calls & MQ_PRODUCE and not self.mq_calls & MQ_CONSUME:
                role = "producer"
            elif self.mq_calls & MQ_CONSUME and not self.mq_calls & MQ_PRODUCE:
                role = "consumer"
            for q, line in self.queues:
                self.facts.add(
                    "integrations",
                    integration(
                        type="MQ",
                        target=q,
                        direction="unknown",
                        file=path,
                        line=line,
                        role=role,
                        protocol="MQI",
                    ),
                )
        if "connect" in self.socket_calls:
            cline = self.calls.get("__connect_line")
            port = self.ports[0] if self.ports else "?"
            hosts: list[tuple[str, int | None]] = list(self.hosts) or [("?", cline)]
            for host, hline in hosts:
                self.facts.add(
                    "integrations",
                    integration(
                        type="SOCKET",
                        target=f"{host}:{port}",
                        direction="outbound",
                        file=path,
                        line=hline or cline,
                    ),
                )
        if "listen" in self.socket_calls:
            port = self.ports[-1] if self.ports else "?"
            self.facts.add(
                "integrations",
                integration(
                    type="SOCKET",
                    target=f"*:{port}",
                    direction="inbound",
                    file=path,
                    line=self.calls.get("__listen_line"),
                ),
            )
        if "main" in self.functions or "WinMain" in self.functions:
            mline = self.functions.get("main") or self.functions.get("WinMain")
            program = stem(path)
            sid = make_id("service", "c-executable", path, program)
            self.facts.add(
                "services",
                Service(
                    id=sid,
                    name=program,
                    kind="c-executable",
                    file=path,
                    handler=f"{path}#main",
                    evidence=[Evidence(file=path, line=mline)],
                ),
            )
            self.facts.add(
                "entry_points",
                EntryPoint(
                    id=make_id("entry", path, program, "main"),
                    name=program,
                    kind="main",
                    file=path,
                    line=mline,
                    evidence=[Evidence(file=path, line=mline)],
                ),
            )
            self.facts.symbol("c-executable", program, path, mline, owner=sid)
            self.facts.symbol("c-main", program, path, mline, owner=sid)


def _gaps(root: Node, facts: Facts, label: str) -> None:
    stack = [root]
    while stack:
        n = stack.pop()
        if n.type == "ERROR" or n.is_missing:
            facts.coverage.gap(n.start_point[0] + 1, n.end_point[0] + 1, f"{label} syntax error")
            continue
        if n.has_error:
            stack.extend(n.children)


_PARSERS: dict[str, Parser] = {}


def analyze_c(path: str, text: str, facts: Facts, cpp: bool = False) -> None:
    key = "cpp" if cpp else "c"
    parser = _PARSERS.get(key)
    if parser is None:
        parser = _PARSERS[key] = Parser(_CPP if cpp else _C)
    tree = parser.parse(text.encode("utf-8"))
    if tree.root_node.has_error:
        _gaps(tree.root_node, facts, "C++" if cpp else "C")
    walker = _Walker(path, facts)
    walker.run(tree.root_node)
    walker.finish()


class CAnalyzer:
    name: ClassVar[str] = "c_cpp"
    version: ClassVar[str] = "1"

    def supports(self, file: FileRecord) -> bool:
        return file.language in ("c", "cpp")

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        facts = Facts()
        text = ctx.text or ""
        facts.coverage.lines = text.count("\n") + 1
        cpp = file.language == "cpp" or bool(
            file.path.endswith(".h") and re.search(r"^\s*(class|namespace|template)\b", text, re.M)
        )
        analyze_c(file.path, text, facts, cpp=cpp)
        return facts
