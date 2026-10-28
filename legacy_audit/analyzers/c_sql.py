"""SQL text that C code builds in buffers, and SQL handed to database client APIs.

Buffers are followed in source order through ``strcpy``/``strncpy``/``strlcpy`` (set),
``strcat``/``strncat``/``strlcat`` (append), ``sprintf``/``snprintf`` (format; writing at
``buf + strlen(buf)`` or formatting ``"%s..."`` from the buffer itself is an append), plain
assignment of a ``char *`` and initialisers. Pro*C ``VARCHAR`` host variables (``v.arr``) are
the variable ``v``. ``#define`` string constants and file-level strings are fragments. Code
inside ``if``/loops/``switch`` is conditional, so what it adds is an optional fragment.

Pro*C looks a host variable up with :meth:`CSql.value_at` for ``PREPARE ... FROM :var`` and
``EXECUTE IMMEDIATE :var``. ODBC / DB2 CLI, OCI, libpq, MySQL and SQLite calls are SQL call
sites on their own (:attr:`CSql.calls`).
"""

from __future__ import annotations

import re

from tree_sitter import Node

from legacy_audit.analyzers.dynsql import (
    Built,
    Tracker,
    choice,
    concat,
    empty,
    format_c,
    hole,
    lit,
)

# function -> (index of the SQL text argument, source)
SQL_APIS: dict[str, tuple[int, str]] = {
    "SQLExecDirect": (1, "odbc"),
    "SQLExecDirectA": (1, "odbc"),
    "SQLExecDirectW": (1, "odbc"),
    "SQLPrepare": (1, "odbc"),
    "SQLPrepareA": (1, "odbc"),
    "SQLPrepareW": (1, "odbc"),
    "OCIStmtPrepare": (2, "oci"),
    "OCIStmtPrepare2": (3, "oci"),
    "PQexec": (1, "libpq"),
    "PQexecParams": (1, "libpq"),
    "PQprepare": (2, "libpq"),
    "mysql_query": (1, "mysql"),
    "mysql_real_query": (1, "mysql"),
    "sqlite3_exec": (1, "sqlite"),
    "sqlite3_prepare": (1, "sqlite"),
    "sqlite3_prepare_v2": (1, "sqlite"),
}
COPY_FUNCS = {"strcpy", "strncpy", "strlcpy", "lstrcpy", "strcpy_s", "strncpy_s", "_mbscpy"}
CAT_FUNCS = {"strcat", "strncat", "strlcat", "lstrcat", "strcat_s", "strncat_s", "_mbscat"}
# function -> index of the format argument
FORMAT_FUNCS = {"sprintf": 1, "snprintf": 2, "_snprintf": 2, "sprintf_s": 2, "vsprintf": 1}
COND_NODES = {
    "if_statement",
    "while_statement",
    "for_statement",
    "do_statement",
    "switch_statement",
    "case_statement",
}
_DEFINE_STRING = re.compile(r'^\s*((?:"(?:[^"\\]|\\.)*"\s*)+)$')
_C_STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')
_ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "\\": "\\", '"': '"', "'": "'", "0": ""}


def _txt(node: Node | None) -> str:
    return (node.text or b"").decode("utf-8", "replace") if node is not None else ""


def _unescape(raw: str) -> str:
    return re.sub(r"\\(.)", lambda m: _ESCAPES.get(m.group(1), m.group(1)), raw)


def _line(node: Node) -> int:
    return node.start_point[0] + 1


def _declared_name(node: Node | None) -> str | None:
    while node is not None and node.type != "identifier":
        node = node.child_by_field_name("declarator")
    return _txt(node) if node is not None else None


class CSql:
    """Walks a C syntax tree once; see the module docstring."""

    def __init__(self) -> None:
        self.tracker = Tracker()
        self.constants: dict[str, Built] = {}
        self.calls: list[tuple[int, str, str, Built | None, str]] = []  # line, api, source, ...
        self.consumed: set[str] = set()
        self.used_constants: set[str] = set()

    # ---- lookup -------------------------------------------------------------------------------
    def value_at(self, name: str, line: int) -> Built | None:
        key = name.lstrip(":").split(":")[0].strip()  # ":var:indicator" -> var
        self.consumed.add(key)
        return self.tracker.at(key, line)

    def leftovers(self) -> list[Built]:
        """Final values of buffers no SQL call site consumed (call sites behind a wrapper
        function) and string constants never used as a fragment."""
        values = [v for k, v in self.tracker.current.items() if k not in self.consumed]
        values += [v for k, v in self.constants.items() if k not in self.used_constants]
        return [v for v in values if v.text.strip()]

    # ---- walk ---------------------------------------------------------------------------------
    def run(self, root: Node) -> None:
        stack: list[tuple[Node, bool]] = [(root, False)]
        while stack:
            n, cond = stack.pop()
            t = n.type
            if t == "preproc_def":
                self._define(n)
                continue
            if t == "declaration":
                self._declaration(n)
            elif t == "expression_statement" and self._statement(n, cond):
                continue
            elif t == "call_expression":
                self._api(n)
            inner = cond or t in COND_NODES
            test = n.child_by_field_name("condition") if t in COND_NODES else None
            for c in reversed(n.children):
                stack.append((c, cond if c == test else inner))

    def _define(self, n: Node) -> None:
        name = _txt(n.child_by_field_name("name"))
        value = _txt(n.child_by_field_name("value"))
        if name and _DEFINE_STRING.match(value):
            text = "".join(_unescape(m.group(1)) for m in _C_STRING.finditer(value))
            self.constants[name] = lit(text, _line(n))

    def _declaration(self, n: Node) -> None:
        for d in n.named_children:
            if d.type != "init_declarator":
                continue
            name = _declared_name(d.child_by_field_name("declarator"))
            value = d.child_by_field_name("value")
            b = self.eval(value) if value is not None else None
            if name and b is not None:
                self.tracker.assign(name, b, _line(d), cond=False, append=False)

    def _statement(self, n: Node, cond: bool) -> bool:
        expr = n.named_children[0] if n.named_children else None
        if expr is None:
            return False
        if expr.type == "assignment_expression":
            left = expr.child_by_field_name("left")
            right = expr.child_by_field_name("right")
            if left is None or right is None or left.type != "identifier":
                return False
            if _txt(expr.child_by_field_name("operator")) != "=":
                return False
            name = _txt(left)
            b = self.eval(right)
            if b is None:
                if self.tracker.get(name) is None:
                    return False
                b = hole("", _line(right))
            self.tracker.assign(name, b, _line(n), cond, append=False)
            return True
        if expr.type == "call_expression":
            self._api(expr)
            self._build(expr, cond)
            return True
        return False

    def _build(self, call: Node, cond: bool) -> None:
        fn = _txt(call.child_by_field_name("function"))
        args_node = call.child_by_field_name("arguments")
        argv = list(args_node.named_children) if args_node is not None else []
        if not argv or fn not in COPY_FUNCS | CAT_FUNCS | FORMAT_FUNCS.keys():
            return
        target = self._target(argv[0])
        if target is None:
            return
        name, at_end = target
        prev = self.tracker.get(name)
        line = _line(call)
        before = prev.text if prev is not None and (at_end or fn in CAT_FUNCS) else ""
        if fn in FORMAT_FUNCS:
            k = FORMAT_FUNCS[fn]
            fmt = self.eval(argv[k]) if k < len(argv) else None
            if fmt is None:
                if prev is None:
                    return
                self.tracker.assign(name, hole(before, line), line, cond, append=at_end)
                return
            rest = argv[k + 1 :]
            first = self._target(rest[0]) if rest else None
            if not at_end and first is not None and first[0] == name and fmt.text.startswith("%s"):
                # sprintf(sql, "%s AND x = %d", sql, v): append the rest
                fmt = lit(fmt.text[2:], fmt.line)
                rest, at_end = rest[1:], True
                before = prev.text if prev is not None else ""
            values = [self.eval(a) for a in rest]
            value = format_c(fmt, values, line, before=before)
            self.tracker.assign(name, value, line, cond, append=at_end)
            return
        k = 2 if fn.endswith("_s") else 1  # strcpy_s(dst, size, src)
        got = self.eval(argv[k]) if k < len(argv) else None
        if got is None:
            if prev is None:
                return
            got = hole(before, line)
        value = got
        if fn in CAT_FUNCS:
            value = concat(empty(line), value)
            value.how.add("concatenation")
        self.tracker.assign(name, value, line, cond, append=at_end or fn in CAT_FUNCS)

    def _target(self, node: Node) -> tuple[str, bool] | None:
        """The buffer a pointer expression writes to, and whether it writes at its end."""
        t = node.type
        if t == "identifier":
            return _txt(node), False
        if t in ("parenthesized_expression", "cast_expression"):
            inner = node.child_by_field_name("value") or (
                node.named_children[-1] if node.named_children else None
            )
            return self._target(inner) if inner is not None else None
        if t == "field_expression":
            arg = node.child_by_field_name("argument")
            if _txt(node.child_by_field_name("field")) == "arr" and arg is not None:
                return self._target(arg)  # Pro*C VARCHAR: v.arr is v
            return _txt(node), False
        if t == "pointer_expression":
            arg = node.child_by_field_name("argument")
            return self._target(arg) if arg is not None else None
        if t == "subscript_expression":
            arg = node.child_by_field_name("argument")
            index = _txt(node.child_by_field_name("index"))
            got = self._target(arg) if arg is not None else None
            if got is None:
                return None
            return got[0], index.strip() not in ("0", "")
        if t == "binary_expression" and _txt(node.child_by_field_name("operator")) == "+":
            left = node.child_by_field_name("left")
            got = self._target(left) if left is not None else None
            return (got[0], True) if got is not None else None
        return None

    # ---- values -------------------------------------------------------------------------------
    def eval(self, node: Node | None) -> Built | None:
        if node is None:
            return None
        t = node.type
        line = _line(node)
        if t == "string_literal":
            return lit(
                "".join(
                    _unescape(_txt(c)) if c.type == "escape_sequence" else _txt(c)
                    for c in node.children
                    if c.type in ("string_content", "escape_sequence")
                ),
                line,
            )
        if t == "concatenated_string":
            parts: list[Built] = []
            for c in node.named_children:
                b = self.eval(c)
                parts.append(b if b is not None else hole("".join(p.text for p in parts), line))
            out = empty(line)
            for p in parts:
                out = concat(out, p)
            return out
        if t == "identifier":
            name = _txt(node)
            got = self.tracker.get(name)
            if got is None and name in self.constants:
                self.used_constants.add(name)
                return self.constants[name]
            return got
        if t == "cast_expression":
            return self.eval(node.child_by_field_name("value"))
        if t == "parenthesized_expression":
            return self.eval(node.named_children[0]) if node.named_children else None
        if t == "field_expression":
            target = self._target(node)
            return self.tracker.get(target[0]) if target is not None and not target[1] else None
        if t == "conditional_expression":
            a = self.eval(node.child_by_field_name("consequence"))
            b = self.eval(node.child_by_field_name("alternative"))
            return choice(a, b) if a is not None and b is not None else None
        return None

    def _api(self, call: Node) -> None:
        fn = _txt(call.child_by_field_name("function")).rsplit("->", 1)[-1]
        api = SQL_APIS.get(fn)
        if api is None:
            return
        args_node = call.child_by_field_name("arguments")
        argv = list(args_node.named_children) if args_node is not None else []
        index, source = api
        if index >= len(argv):
            return
        arg = argv[index]
        target = self._target(arg)
        if target is not None:
            self.consumed.add(target[0])
            self.used_constants.add(target[0])
        self.calls.append((_line(call), fn, source, self.eval(arg), _txt(arg)))
