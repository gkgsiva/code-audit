"""Scripts (sh/ksh/bash, Windows batch, Perl, Python) and crontab files.

Finds invoked scripts and programs (refs the linker resolves), database client calls with
their inline SQL, file transfers and mail (``ftp/sftp/scp/curl/mailx/CFTUTIL``) as integrations,
and cron entries as batch jobs.
"""

from __future__ import annotations

import re
import shlex
from typing import ClassVar

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import tech
from legacy_audit.analyzers.cft import cftutil_integration
from legacy_audit.analyzers.integrations import classify_url, integration
from legacy_audit.analyzers.sqltools import split_statements, sql_fact, statement_type
from legacy_audit.analyzers.textutil import basename
from legacy_audit.model import BatchJob, Evidence, FileRecord, make_id

SCRIPT_EXT = (".sh", ".ksh", ".bash", ".csh", ".pl", ".py", ".bat", ".cmd", ".awk", ".sql", ".ctl")
DB_CLIENTS = {
    "sqlplus": "SQL*Plus",
    "sqlldr": "SQL*Loader",
    "db2": "DB2 CLP",
    "isql": "isql",
    "osql": "osql",
    "sqlcmd": "sqlcmd",
    "bteq": "Teradata BTEQ",
    "psql": "psql",
    "mysql": "mysql client",
    "dbaccess": "Informix dbaccess",
}
PREFIXES = {
    "nohup",
    "exec",
    "time",
    "sudo",
    "nice",
    "command",
    "env",
    "eval",
    "then",
    "do",
    "else",
    "{",
    "(",
    "!",
    "call",
    "start",
    "su",
    "timeout",
}
INTERPRETERS = {
    "sh",
    "ksh",
    "bash",
    "csh",
    "perl",
    "python",
    "python3",
    "awk",
    "nawk",
    "cmd",
    "cmd.exe",
    ".",
    "source",
}
SHELL_WORDS = {
    "if",
    "fi",
    "for",
    "done",
    "while",
    "until",
    "case",
    "esac",
    "echo",
    "print",
    "printf",
    "export",
    "set",
    "unset",
    "read",
    "return",
    "exit",
    "test",
    "[",
    "[[",
    "cd",
    "ls",
    "cp",
    "mv",
    "rm",
    "mkdir",
    "rmdir",
    "chmod",
    "chown",
    "cat",
    "grep",
    "egrep",
    "sed",
    "awk",
    "cut",
    "sort",
    "uniq",
    "wc",
    "head",
    "tail",
    "tr",
    "date",
    "sleep",
    "find",
    "xargs",
    "touch",
    "tee",
    "basename",
    "dirname",
    "expr",
    "let",
    "typeset",
    "local",
    "declare",
    "trap",
    "wait",
    "kill",
    "true",
    "false",
    "function",
    "shift",
    "getopts",
    "rem",
    "goto",
    "setlocal",
    "endlocal",
    "pause",
    "copy",
    "del",
    "move",
    "type",
    "dir",
    "md",
    "rd",
    "if exist",
    "my",
    "use",
    "sub",
    "gzip",
    "gunzip",
    "compress",
    "uncompress",
    "tar",
    "zip",
    "unzip",
    "ps",
    "logger",
    "mkfifo",
}
_ASSIGN = re.compile(r"^\s*(?:export\s+|typeset\s+(?:-x\s+)?|set\s+)?([A-Za-z_]\w*)=(.*)$")
_HEREDOC = re.compile(r"<<-?\s*['\"]?(\w+)['\"]?")
_VAR = re.compile(r"\$\{?(\w+)\}?")
_REMOTE = re.compile(r"^(?:[\w.-]+@)?([\w.-]+):(.*)$")
_PERL_SYSTEM = re.compile(
    r"""(?:system|exec)\s*\(?\s*(["'])(.+?)\1|`([^`]+)`|qx\s*[({/](.+?)[)}/]"""
)
_PY_SYSTEM = re.compile(
    r"""os\.system\(\s*(["'])(.+?)\1|subprocess\.\w+\(\s*\[\s*(["'])([^"']+)\3"""
)
_CRON = re.compile(r"^\s*((?:[\d*/,\-]+\s+){4}[\d*/,\-A-Za-z]+|@\w+)\s+(.+)$")


def _split_segments(line: str) -> list[str]:
    """Split a command line on ; && || | outside quotes."""
    out, buf, quote = [], [], ""
    i = 0
    while i < len(line):
        ch = line[i]
        if quote:
            if ch == quote:
                quote = ""
            buf.append(ch)
        elif ch in "'\"":
            quote = ch
            buf.append(ch)
        elif ch == "&" and ((buf and buf[-1] == ">") or line[i + 1 : i + 2] == ">"):
            buf.append(ch)  # redirection: 2>&1, &>file
        elif ch in ";|&":
            out.append("".join(buf))
            buf = []
            while i + 1 < len(line) and line[i + 1] in "|&":
                i += 1
        elif ch == "#" and (not buf or buf[-1].isspace()):
            break
        else:
            buf.append(ch)
        i += 1
    out.append("".join(buf))
    return [s.strip() for s in out if s.strip()]


def _tokens(segment: str) -> list[str]:
    try:
        return shlex.split(segment, comments=False, posix=True)
    except ValueError:
        return segment.split()


class ShellAnalyzer:
    name: ClassVar[str] = "shell"
    version: ClassVar[str] = "1"
    dialect: str = "oracle"

    def supports(self, file: FileRecord) -> bool:
        return file.language in ("shell", "perl", "python", "batch", "crontab") or (
            file.path.startswith("audit-inputs/crontab/")
        )

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        facts = Facts()
        text = ctx.text or ""
        facts.coverage.lines = text.count("\n") + 1
        self.dialect = ctx.config.sql.dialect
        path = file.path
        if file.language == "crontab" or path.startswith("audit-inputs/crontab/"):
            self._crontab(path, text, facts)
            return facts
        if file.language == "perl":
            text = self._embedded(text, _PERL_SYSTEM, (2, 3, 4))
        elif file.language == "python":
            text = self._embedded(text, _PY_SYSTEM, (2, 4))
        self._script(path, text, facts, windows=file.language == "batch")
        return facts

    def _embedded(self, text: str, rx: re.Pattern[str], groups: tuple[int, ...]) -> str:
        """Keep only the shell commands a Perl/Python script runs, on their original lines."""
        out = []
        for line in text.splitlines():
            cmds = []
            for m in rx.finditer(line):
                for g in groups:
                    if m.group(g):
                        cmds.append(m.group(g))
            out.append("; ".join(cmds))
        return "\n".join(out)

    # ---- scripts ------------------------------------------------------------------------------
    def _script(self, path: str, text: str, facts: Facts, windows: bool = False) -> None:
        variables: dict[str, str] = {}
        lines = text.splitlines()
        i = 0
        while i < len(lines):
            no = i + 1
            line = lines[i]
            while line.rstrip().endswith("\\") and i + 1 < len(lines):
                i += 1
                line = line.rstrip()[:-1] + " " + lines[i].strip()
            if windows:
                line = re.sub(r"%(\w+)%", r"${\1}", line)
                if re.match(r"^\s*(@?rem\b|::)", line, re.I):
                    i += 1
                    continue
            heredoc: list[str] = []
            h = _HEREDOC.search(line)
            if h:
                end = h.group(1)
                j = i + 1
                while j < len(lines) and lines[j].strip() != end:
                    heredoc.append(lines[j])
                    j += 1
                i = j
            a = _ASSIGN.match(line)
            if a and " " not in a.group(2).strip().strip("'\""):
                variables[a.group(1)] = a.group(2).strip().strip("'\"")
            elif a and windows:
                variables[a.group(1)] = a.group(2).strip()
            for seg in _split_segments(line.split("<<")[0] if h else line):
                self._command(path, no, seg, variables, heredoc, facts)
            i += 1

    def _expand(self, value: str, variables: dict[str, str]) -> str:
        for _ in range(3):
            new = _VAR.sub(lambda m: variables.get(m.group(1), m.group(0)), value)
            if new == value:
                break
            value = new
        return value

    def _command(
        self,
        path: str,
        no: int,
        seg: str,
        variables: dict[str, str],
        heredoc: list[str],
        facts: Facts,
    ) -> None:
        toks = _tokens(seg)
        while toks and (toks[0] in PREFIXES or re.match(r"^\w+=", toks[0])):
            toks = toks[1:]
        if not toks:
            return
        cmd_raw = toks[0]
        cmd = basename(cmd_raw).lower()
        args = toks[1:]
        if cmd in INTERPRETERS and args:
            target = next((a for a in args if not a.startswith("-")), None)
            if target:
                # Sourcing an environment file (". ./app.env") is not running a program.
                explicit = cmd not in (".", "source") or target.lower().endswith(SCRIPT_EXT)
                self._invoke(path, no, self._expand(target, variables), facts, explicit=explicit)
            return
        if cmd in ("java", "javaw", "java.exe"):
            self._java(path, no, [self._expand(a, variables) for a in args], facts)
            return
        if cmd in DB_CLIENTS:
            self._db_client(path, no, cmd, args, heredoc, variables, facts)
            return
        if cmd in ("ftp", "sftp", "lftp", "ncftp", "ncftpput", "ncftpget"):
            self._ftp(path, no, cmd, args, heredoc, variables, facts)
            return
        if cmd in ("scp", "rsync", "pscp"):
            self._scp(path, no, cmd, args, variables, facts)
            return
        if cmd in ("curl", "wget"):
            for a in args:
                c = classify_url(self._expand(a, variables))
                if c:
                    facts.add(
                        "integrations",
                        integration(
                            type=c[0],
                            target=c[1],
                            direction="outbound",
                            file=path,
                            line=no,
                            protocol=cmd,
                        ),
                    )
            return
        if cmd in ("mail", "mailx", "sendmail", "mutt", "uuencode", "blat"):
            facts.add(
                "integrations",
                integration(
                    type="SMTP", target=f"mail ({cmd})", direction="outbound", file=path, line=no
                ),
            )
            return
        if cmd == "cftutil":
            integ = cftutil_integration(self._expand(" ".join(args), variables), path, no)
            if integ:
                facts.add("integrations", integ)
            return
        if cmd in SHELL_WORDS or cmd.startswith(("$(", "`")):
            return
        if "/" in cmd_raw or "\\" in cmd_raw or cmd.endswith(SCRIPT_EXT) or cmd_raw.startswith("$"):
            self._invoke(path, no, self._expand(cmd_raw, variables), facts)

    def _invoke(
        self, path: str, no: int, target: str, facts: Facts, explicit: bool = False
    ) -> None:
        name = basename(target)
        if not name or name.startswith("$") or "*" in name:
            return
        report = explicit or name.lower().endswith(SCRIPT_EXT)
        facts.ref(
            kind="invokes",
            source=f"file:{path}",
            source_label=path,
            target=name,
            target_kinds=["file-name", "c-executable"],
            file=path,
            line=no,
            report_unresolved=report,
        )

    def _java(self, path: str, no: int, args: list[str], facts: Facts) -> None:
        i = 0
        positional: list[str] = []
        while i < len(args):
            a = args[i]
            if a in ("-cp", "-classpath", "--class-path"):
                i += 2
                continue
            if a == "-jar" and i + 1 < len(args):
                facts.ref(
                    kind="invokes",
                    source=f"file:{path}",
                    source_label=path,
                    target=basename(args[i + 1]),
                    target_kinds=["file-name"],
                    file=path,
                    line=no,
                )
                return
            if a.startswith("-"):
                i += 1
                continue
            positional.append(a)
            i += 1
        if not positional:
            return
        main = positional[0]
        facts.ref(
            kind="invokes",
            source=f"file:{path}",
            source_label=path,
            target=main,
            target_kinds=["java-main", "java-class"],
            file=path,
            line=no,
            report_unresolved=not main.startswith("org.springframework"),
        )
        if main.endswith("CommandLineJobRunner") and len(positional) >= 3:
            facts.ref(
                kind="triggers",
                source=f"file:{path}",
                source_label=path,
                target=positional[2],
                target_kinds=["spring-batch-job"],
                file=path,
                line=no,
            )

    def _db_client(
        self,
        path: str,
        no: int,
        cmd: str,
        args: list[str],
        heredoc: list[str],
        variables: dict[str, str],
        facts: Facts,
    ) -> None:
        facts.add("tech_stack", tech(DB_CLIENTS[cmd], None, "database", path, no))
        for a in args:
            if a.startswith("@"):
                facts.ref(
                    kind="invokes",
                    source=f"file:{path}",
                    source_label=path,
                    target=basename(self._expand(a[1:], variables)),
                    target_kinds=["file-name"],
                    file=path,
                    line=no,
                )
            m = re.match(r"(?i)control=(.+)", a)
            if m:
                facts.ref(
                    kind="invokes",
                    source=f"file:{path}",
                    source_label=path,
                    target=basename(self._expand(m.group(1), variables)),
                    target_kinds=["file-name"],
                    file=path,
                    line=no,
                )
        inline = [
            a
            for a in args
            if statement_type(a) in ("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "CALL")
        ]
        for sql in inline:
            facts.add(
                "sql_statements",
                sql_fact(
                    file=path,
                    line=no,
                    text=sql,
                    source="script",
                    dialect="db2" if cmd == "db2" else self.dialect,
                ),
            )
        if heredoc:
            body = "\n".join(heredoc)
            for off, stmt in split_statements(body):
                st = statement_type(stmt)
                if st in ("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "CALL"):
                    facts.add(
                        "sql_statements",
                        sql_fact(
                            file=path,
                            line=no + off,
                            text=stmt,
                            source="script",
                            dialect=self.dialect,
                        ),
                    )
                elif st in ("EXEC", "EXECUTE"):
                    proc = re.match(r"(?i)EXEC(?:UTE)?\s+([\w.$#]+)", stmt)
                    if proc:
                        facts.add(
                            "sql_statements",
                            sql_fact(
                                file=path,
                                line=no + off,
                                text=f"CALL {proc.group(1)}",
                                source="script",
                                dialect=self.dialect,
                            ),
                        )
            for hl in heredoc:
                if hl.strip().startswith("@"):
                    facts.ref(
                        kind="invokes",
                        source=f"file:{path}",
                        source_label=path,
                        target=basename(hl.strip()[1:].split()[0]),
                        target_kinds=["file-name"],
                        file=path,
                        line=no,
                    )

    def _ftp(
        self,
        path: str,
        no: int,
        cmd: str,
        args: list[str],
        heredoc: list[str],
        variables: dict[str, str],
        facts: Facts,
    ) -> None:
        kind = "SFTP" if cmd == "sftp" else "FTP"
        host = next((self._expand(a, variables) for a in args if not a.startswith("-")), "")
        for hl in heredoc:
            m = re.match(r"\s*open\s+(\S+)", hl, re.I)
            if m:
                host = self._expand(m.group(1), variables)
        host = host.split("@")[-1].split(":")[0] or "?"
        body = " ".join(heredoc).lower()
        put = re.search(r"\b(m?put)\b", body)
        get = re.search(r"\b(m?get)\b", body)
        direction = "both" if put and get else "inbound" if get else "outbound"
        facts.add(
            "integrations",
            integration(
                type=kind, target=host, direction=direction, file=path, line=no, protocol=cmd
            ),
        )

    def _scp(
        self, path: str, no: int, cmd: str, args: list[str], variables: dict[str, str], facts: Facts
    ) -> None:
        files = [self._expand(a, variables) for a in args if not a.startswith("-")]
        for idx, f in enumerate(files):
            m = _REMOTE.match(f)
            if m and not re.match(r"^[A-Za-z]:\\", f):
                direction = "outbound" if idx == len(files) - 1 else "inbound"
                facts.add(
                    "integrations",
                    integration(
                        type="SCP" if cmd != "rsync" else "RSYNC",
                        target=m.group(1),
                        direction=direction,
                        file=path,
                        line=no,
                        protocol=cmd,
                    ),
                )

    # ---- cron ---------------------------------------------------------------------------------
    def _crontab(self, path: str, text: str, facts: Facts) -> None:
        facts.add("tech_stack", tech("cron", None, "scheduler", path, 1))
        for no, line in enumerate(text.splitlines(), 1):
            s = line.strip()
            if not s or s.startswith("#") or re.match(r"^\w+=", s):
                continue
            m = _CRON.match(s)
            if not m:
                facts.coverage.gap(no, no, "not a cron entry")
                continue
            schedule, command = m.group(1), m.group(2)
            toks = _tokens(_split_segments(command)[0]) if _split_segments(command) else []
            while toks and (toks[0] in PREFIXES or toks[0] in INTERPRETERS):
                toks = toks[1:]
            prog = basename(toks[0]) if toks else command
            jid = make_id("batch_job", "cron", path, no)
            facts.add(
                "batch_jobs",
                BatchJob(
                    id=jid,
                    name=prog,
                    scheduler="cron",
                    schedule=schedule,
                    command=command,
                    evidence=[Evidence(file=path, line=no)],
                ),
            )
            facts.symbol("cron-job", prog, path, no, owner=jid)
            for seg in _split_segments(command):
                ct = _tokens(seg)
                while ct and (
                    ct[0] in PREFIXES or ct[0] in INTERPRETERS or re.match(r"^\w+=", ct[0])
                ):
                    ct = ct[1:]
                if ct and ct[0] not in SHELL_WORDS:
                    facts.ref(
                        kind="invokes",
                        source=f"batch_job:{jid}",
                        source_label=prog,
                        target=basename(ct[0]),
                        target_kinds=["file-name", "c-executable"],
                        file=path,
                        line=no,
                    )


def command_facts(
    command: str, *, path: str, line: int, dialect: str, source: str, source_label: str
) -> Facts:
    """Facts for one command line run by a scheduler (Autosys ``command``), reported at
    ``path:line`` and owned by ``source`` (a batch job)."""
    sh = ShellAnalyzer()
    sh.dialect = dialect
    tmp = Facts()
    sh._script(path, command, tmp)
    out = Facts()
    for entity, items in tmp.entities.items():
        for item in items:
            ev = [e.model_copy(update={"line": line}) for e in item.evidence]
            out.add(entity, item.model_copy(update={"evidence": ev}))
    for ref in tmp.refs:
        out.refs.append(
            ref.model_copy(update={"source": source, "source_label": source_label, "line": line})
        )
    return out
