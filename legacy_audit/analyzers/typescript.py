"""TypeScript and JavaScript (tree-sitter-typescript): Angular, AngularJS and Node.js code.

One parse per file finds:

- **Angular** (2+): components (``@Component``) with their template and selector, the router
  (``Routes`` arrays, lazy ``loadComponent``), the bootstrap component, ``HttpClient`` calls;
- **AngularJS** (1.x): modules, controllers, components, ``$routeProvider``/``$stateProvider``
  routes and their templates, ``$http``/``$resource`` calls;
- **Node.js services**: Express/Koa/Fastify/hapi routes and NestJS controllers as services, the
  server start-up as an entry point, ``app.use('/prefix', router)`` mounts (applied by the
  linker), outgoing calls (``fetch``, ``axios``, ...);
- **database access**: SQL in strings and template literals (rebuilt like the Java analyzer:
  ``+``, ``+=`` and ``${...}`` pieces become holes), TypeORM, Prisma, Sequelize and Knex calls as
  ORM statements on their entity (the SQL is generated at run time), entity-to-table mappings,
  database connection settings;
- **scheduled jobs**: ``node-cron``, ``cron``, ``node-schedule``, Agenda and NestJS
  ``@Cron``/``@Interval``/``@Timeout`` as batch jobs (scheduler ``node-cron``);
- URLs in strings as integrations (HTTP, SOAP, FTP, database and AMQP connection strings).

Prisma schemas (``schema.prisma``) are read here too: their models map entities to tables.

Templates (``.html``) are read by the ``web`` analyzer, which marks Angular and AngularJS ones;
a component points at its template with a ``renders`` reference and routes point at components
with ``forwards`` references, so the linker can tell routed screens from sub-components
(decision 130).
"""

from __future__ import annotations

import posixpath
import re
from dataclasses import dataclass, field
from typing import ClassVar

import tree_sitter_typescript
from tree_sitter import Language, Node, Parser

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import tech
from legacy_audit.analyzers.dynsql import Built, concat, fill, hole, lit, update
from legacy_audit.analyzers.integrations import classify_url, integration, mask_userinfo
from legacy_audit.analyzers.sqltools import looks_like_sql, sql_fact
from legacy_audit.analyzers.web import count_fields
from legacy_audit.model import (
    BatchJob,
    Category,
    EntryPoint,
    Evidence,
    FileRecord,
    Screen,
    Service,
    make_id,
)

_TS = Language(tree_sitter_typescript.language_typescript())
_TSX = Language(tree_sitter_typescript.language_tsx())

HTTP_VERBS = {"get", "post", "put", "delete", "patch", "head", "options", "all", "del"}
NEST_VERBS = {
    "Get": "GET",
    "Post": "POST",
    "Put": "PUT",
    "Delete": "DELETE",
    "Patch": "PATCH",
    "Options": "OPTIONS",
    "Head": "HEAD",
    "All": "ANY",
}
# Receivers of Express-style route definitions: app.get(...), router.post(...), ...
_ROUTER_OBJ = re.compile(r"^(app|api|server|router|\w*Router|routes|fastify|instance|r)$", re.I)
_HTTP_CLIENT_OBJ = re.compile(
    r"(^|\.)(http|httpClient|\$http|axios|client|api|ky|got|superagent)$", re.I
)
_HTTP_FUNCS = {"fetch", "axios", "got", "request", "needle", "ky"}
_DB_CALLS = {
    "query",
    "execute",
    "raw",
    "$queryRaw",
    "$executeRaw",
    "$queryRawUnsafe",
    "$executeRawUnsafe",
    "none",
    "one",
    "oneOrNone",
    "many",
    "manyOrNone",
    "any",
    "result",
}
_DB_TAGS = {"sql", "SQL", "$queryRaw", "$executeRaw", "raw"}
_TYPEORM_OPS = {
    "find": "SELECT",
    "findOne": "SELECT",
    "findBy": "SELECT",
    "findOneBy": "SELECT",
    "findAndCount": "SELECT",
    "findOneOrFail": "SELECT",
    "findByIds": "SELECT",
    "count": "SELECT",
    "countBy": "SELECT",
    "exist": "SELECT",
    "exists": "SELECT",
    "save": "INSERT",
    "insert": "INSERT",
    "update": "UPDATE",
    "upsert": "MERGE",
    "delete": "DELETE",
    "remove": "DELETE",
    "softDelete": "UPDATE",
    "increment": "UPDATE",
    "decrement": "UPDATE",
    "createQueryBuilder": "SELECT",
}
_PRISMA_OPS = {
    "findMany": "SELECT",
    "findUnique": "SELECT",
    "findFirst": "SELECT",
    "findUniqueOrThrow": "SELECT",
    "findFirstOrThrow": "SELECT",
    "count": "SELECT",
    "aggregate": "SELECT",
    "groupBy": "SELECT",
    "create": "INSERT",
    "createMany": "INSERT",
    "update": "UPDATE",
    "updateMany": "UPDATE",
    "upsert": "MERGE",
    "delete": "DELETE",
    "deleteMany": "DELETE",
}
_SEQUELIZE_OPS = {
    "findAll": "SELECT",
    "findByPk": "SELECT",
    "findAndCountAll": "SELECT",
    "findOrCreate": "MERGE",
    "bulkCreate": "INSERT",
    "destroy": "DELETE",
}
_SEQUELIZE_WEAK_OPS = {
    "findOne": "SELECT",
    "create": "INSERT",
    "update": "UPDATE",
    "count": "SELECT",
    "upsert": "MERGE",
    "max": "SELECT",
    "min": "SELECT",
    "sum": "SELECT",
}
_DB_TYPES = {
    "postgres": "PostgreSQL",
    "postgresql": "PostgreSQL",
    "pg": "PostgreSQL",
    "mysql": "MySQL",
    "mariadb": "MariaDB",
    "oracle": "Oracle",
    "oracledb": "Oracle",
    "mssql": "SQL Server",
    "sqlserver": "SQL Server",
    "sqlite": "SQLite",
    "better-sqlite3": "SQLite",
    "mongodb": "MongoDB",
    "cockroachdb": "CockroachDB",
    "db2": "DB2",
}
_DB_URL = re.compile(
    r"\b(postgres(?:ql)?|mysql|mariadb|mongodb(?:\+srv)?|mssql|sqlserver|oracle|redis|rediss|"
    r"amqps?)://[^\s\"'`<>()\\]+",
    re.I,
)
_URL = re.compile(r"\b(?:https?|ftps?|sftp|ldaps?|smtp)://[^\s\"'`<>()\\]+", re.I)
# NestJS CronExpression names in plain cron (minute hour day month weekday).
NEST_CRON = {
    "EVERY_MINUTE": "* * * * *",
    "EVERY_5_MINUTES": "*/5 * * * *",
    "EVERY_10_MINUTES": "*/10 * * * *",
    "EVERY_30_MINUTES": "*/30 * * * *",
    "EVERY_HOUR": "0 * * * *",
    "EVERY_DAY_AT_MIDNIGHT": "0 0 * * *",
    "EVERY_DAY_AT_1AM": "0 1 * * *",
    "EVERY_DAY_AT_2AM": "0 2 * * *",
    "EVERY_DAY_AT_3AM": "0 3 * * *",
    "EVERY_DAY_AT_4AM": "0 4 * * *",
    "EVERY_DAY_AT_5AM": "0 5 * * *",
    "EVERY_DAY_AT_6AM": "0 6 * * *",
    "EVERY_DAY_AT_NOON": "0 12 * * *",
    "EVERY_WEEK": "0 0 * * 0",
    "EVERY_WEEKDAY": "0 0 * * 1-5",
    "EVERY_WEEKEND": "0 0 * * 6,0",
    "EVERY_1ST_DAY_OF_MONTH_AT_MIDNIGHT": "0 0 1 * *",
    "EVERY_YEAR": "0 0 1 1 *",
}
_PRISMA_MODEL = re.compile(r"^\s*model\s+(\w+)\s*\{(.*?)^\s*\}", re.M | re.S)
_PRISMA_MAP = re.compile(r"@@map\(\s*\"([^\"]+)\"")
_PRISMA_PROVIDER = re.compile(r"datasource\s+\w+\s*\{[^}]*provider\s*=\s*\"(\w+)\"", re.S)


def _txt(node: Node | None) -> str:
    return node.text.decode("utf-8", "replace") if node is not None and node.text else ""


def _line(node: Node) -> int:
    return node.start_point[0] + 1


def _string_value(node: Node | None) -> str | None:
    """The value of a plain string literal (or a template literal without ``${}``)."""
    if node is None:
        return None
    if node.type == "string":
        return "".join(
            _txt(c) for c in node.named_children if c.type in ("string_fragment", "escape_sequence")
        )
    if node.type == "template_string" and not any(
        c.type == "template_substitution" for c in node.named_children
    ):
        return _txt(node)[1:-1]
    return None


def _pairs(obj: Node) -> dict[str, Node]:
    """Properties of an object literal: key -> value node (shorthand ``{ path }`` too)."""
    out: dict[str, Node] = {}
    for c in obj.named_children:
        if c.type == "pair":
            key = c.child_by_field_name("key")
            value = c.child_by_field_name("value")
            if key is not None and value is not None:
                k = _string_value(key) if key.type == "string" else _txt(key)
                out[k or _txt(key)] = value
        elif c.type == "shorthand_property_identifier":
            out[_txt(c)] = c
    return out


def _callee(call: Node) -> tuple[str, str, Node | None]:
    """(receiver text, function name, receiver node) of a call: ``this.http.get`` ->
    (``this.http``, ``get``, node)."""
    fn = call.child_by_field_name("function")
    if fn is None:
        return "", "", None
    if fn.type == "member_expression":
        obj = fn.child_by_field_name("object")
        return _txt(obj), _txt(fn.child_by_field_name("property")), obj
    return "", _txt(fn), None


def _args(call: Node) -> list[Node]:
    args = call.child_by_field_name("arguments")
    if args is None:
        return []
    if args.type == "template_string":  # tagged template: sql`...`
        return [args]
    return [a for a in args.named_children if a.type != "comment"]


def _decorators(node: Node) -> list[Node]:
    """Decorators of a class (children, or of the wrapping ``export``) or of a class member
    (the decorator siblings just before it)."""
    out = [c for c in node.children if c.type == "decorator"]
    parent = node.parent
    if node.type in ("class_declaration", "abstract_class_declaration") and parent is not None:
        if parent.type == "export_statement":
            out = [c for c in parent.children if c.type == "decorator"] + out
    elif parent is not None and parent.type == "class_body":
        prev = node.prev_named_sibling
        while prev is not None and prev.type == "decorator":
            out.insert(0, prev)
            prev = prev.prev_named_sibling
    return out


def _decorator(dec: Node) -> tuple[str, list[Node]]:
    """(name, arguments) of ``@Name(args)`` or ``@Name``."""
    expr = next((c for c in dec.named_children), None)
    if expr is None:
        return "", []
    if expr.type == "call_expression":
        fn = expr.child_by_field_name("function")
        name = _txt(fn).rsplit(".", 1)[-1]
        return name, _args(expr)
    return _txt(expr).rsplit(".", 1)[-1], []


def url_path(text: str) -> str | None:
    """The path part of a URL built in code, for matching an API route: holes (``?``) before
    the first ``/`` are the base URL; ``http://host/x`` -> ``/x``. None for absolute URLs to
    another system (they are integrations) and for text that is not a path."""
    t = text.strip()
    if "://" in t:
        return None
    if t.startswith("?"):
        t = t.lstrip("?")
        if not t.startswith("/"):
            t = "/" + t
    t = re.sub(r"[?&][\w.-]+=.*$", "", t).split("#", 1)[0]  # query string
    if not t.startswith("/") or " " in t or len(t) < 2:
        return None
    return t


@dataclass
class _Scope:
    """String values known in a function body: variables and their rebuilt text."""

    values: dict[str, Built] = field(default_factory=dict)
    consumed: set[str] = field(default_factory=set)


class TypeScriptAnalyzer:
    name: ClassVar[str] = "typescript"
    version: ClassVar[str] = "1"

    def __init__(self) -> None:
        self.parsers = {"ts": Parser(_TS), "tsx": Parser(_TSX)}

    def supports(self, file: FileRecord) -> bool:
        if file.language == "prisma":
            return True
        return (
            file.language in ("typescript", "javascript")
            and file.category not in (Category.TEST, Category.GENERATED, Category.BINARY)
            and "node_modules/" not in file.path
            and not file.path.endswith(".d.ts")
        )

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        text = ctx.text or ""
        facts = Facts()
        facts.coverage.lines = text.count("\n") + 1
        if file.language == "prisma":
            _prisma(file.path, text, facts)
            return facts
        ext = file.path.rsplit(".", 1)[-1].lower()
        parser = self.parsers["ts" if ext in ("ts", "mts", "cts") else "tsx"]
        tree = parser.parse(text.encode("utf-8"))
        root = tree.root_node
        if root.has_error:
            stack = [root]
            while stack:
                n = stack.pop()
                if n.type == "ERROR" or n.is_missing:
                    facts.coverage.gap(_line(n), n.end_point[0] + 1, "TypeScript syntax error")
                    continue
                if n.has_error:
                    stack.extend(n.children)
        _Visitor(file, ctx, facts, text).run(root)
        return facts


def _prisma(path: str, text: str, facts: Facts) -> None:
    for m in _PRISMA_MODEL.finditer(text):
        name, body = m.group(1), m.group(2)
        mapped = _PRISMA_MAP.search(body)
        line = text.count("\n", 0, m.start()) + 1
        facts.symbol(
            "jpa-entity",
            name,
            path,
            line,
            data={"table": (mapped.group(1) if mapped else name).upper(), "orm": "prisma"},
        )
    p = _PRISMA_PROVIDER.search(text)
    if p and p.group(1).lower() in _DB_TYPES:
        line = text.count("\n", 0, p.start()) + 1
        facts.add("tech_stack", tech(_DB_TYPES[p.group(1).lower()], None, "database", path, line))


class _Visitor:
    def __init__(self, file: FileRecord, ctx: AnalyzerContext, facts: Facts, text: str) -> None:
        self.file = file
        self.path = file.path
        self.ctx = ctx
        self.facts = facts
        self.text = text
        self.dialect = ctx.config.sql.dialect
        self.imports: dict[str, str] = {}  # local name -> module
        self.modules: set[str] = set()
        self.consts: dict[str, Built] = {}  # file constants and this.fields
        self.repos: dict[str, str] = {}  # repository variable -> entity (TypeORM)
        self.routers: set[str] = set()  # variables holding an Express/Koa router
        self.done: set[int] = set()  # start bytes of string expressions already used
        self.soap = False
        self.angularjs = False
        self.sql_seen: set[tuple[int, str]] = set()
        self.url_seen: set[tuple[int, str]] = set()

    # ---- driving ------------------------------------------------------------------------------
    def run(self, root: Node) -> None:
        self._imports(root)
        self._constants(root)
        top = _Scope()
        self._walk(root, top, cond=False)
        self._leftovers(top)
        self._file_level()

    def _imports(self, root: Node) -> None:
        stack = [root]
        while stack:
            n = stack.pop()
            if n.type == "import_statement":
                src = _string_value(n.child_by_field_name("source"))
                if src:
                    self.modules.add(src)
                    clause = next((c for c in n.named_children if c.type == "import_clause"), None)
                    for ident in _idents(clause):
                        self.imports[ident] = src
                continue
            if n.type == "call_expression":
                fn = n.child_by_field_name("function")
                if fn is not None and _txt(fn) == "require":
                    args = _args(n)
                    src = _string_value(args[0]) if args else None
                    if src:
                        self.modules.add(src)
                        decl = n.parent
                        if decl is not None and decl.type == "variable_declarator":
                            name = decl.child_by_field_name("name")
                            for ident in _idents(name):
                                self.imports[ident] = src
            stack.extend(n.children)
        for module in sorted(self.modules):
            if module.startswith("."):  # the linker resolves it to a file (uses edge)
                target = posixpath.normpath(posixpath.join(posixpath.dirname(self.path), module))
                self.facts.ref(
                    kind="uses",
                    source=f"file:{self.path}",
                    source_label=self.path,
                    target=target,
                    target_kinds=["module"],
                    file=self.path,
                    report_unresolved=False,
                )
        self.soap = any(m in ("soap", "strong-soap") for m in self.modules)
        self.angularjs = "angular.module(" in self.text or "angular" in self.modules

    def _constants(self, root: Node) -> None:
        """File-level ``const X = '...'`` and class fields ``baseUrl = '...'`` (as
        ``this.baseUrl``), and TypeORM repositories (``Repository<Order>``)."""
        for n in _iter(
            root,
            {
                "lexical_declaration",
                "variable_declaration",
                "public_field_definition",
                "required_parameter",
            },
        ):
            if n.type in ("lexical_declaration", "variable_declaration"):
                if _function_parent(n) is not None:
                    continue
                for d in n.named_children:
                    if d.type != "variable_declarator":
                        continue
                    name = _txt(d.child_by_field_name("name"))
                    value = d.child_by_field_name("value")
                    if value is not None:
                        b = self._eval(value, None)
                        if b is not None:
                            self.consts[name] = b
                    self._repo_binding(name, d, value)
            elif n.type == "public_field_definition":
                name = _txt(n.child_by_field_name("name"))
                value = n.child_by_field_name("value")
                if value is not None:
                    b = self._eval(value, None)
                    if b is not None:
                        self.consts[f"this.{name}"] = b
                self._repo_binding(f"this.{name}", n, value)
            else:  # constructor parameter: private readonly repo: Repository<Order>
                name = _txt(n.child_by_field_name("pattern"))
                self._repo_binding(f"this.{name}", n, None)
                self._repo_binding(name, n, None)

    def _repo_binding(self, name: str, node: Node, value: Node | None) -> None:
        ann = _txt(node.child_by_field_name("type"))
        m = re.search(r"\bRepository\s*<\s*(\w+)", ann)
        if m:
            self.repos[name] = m.group(1)
            return
        for dec in _decorators(node) + [c for c in node.children if c.type == "decorator"]:
            dn, dargs = _decorator(dec)
            if dn == "InjectRepository" and dargs:
                self.repos[name] = _txt(dargs[0])
                return
        if value is not None:
            m = re.search(r"getRepository\s*\(\s*(\w+)", _txt(value))
            if m:
                self.repos[name] = m.group(1)

    # ---- string expressions -------------------------------------------------------------------
    def _eval(self, node: Node, scope: _Scope | None) -> Built | None:
        """Rebuild a string expression: literals, template literals, ``+``, known constants and
        variables; unknown parts become holes. None when it is not text."""
        t = node.type
        line = _line(node)
        if t == "string":
            return lit(_string_value(node) or "", line)
        if t == "template_string":
            out: Built | None = None
            for c in node.named_children:
                if c.type == "string_fragment" or c.type == "escape_sequence":
                    piece = lit(_txt(c), _line(c))
                elif c.type == "template_substitution":
                    inner = next((x for x in c.named_children), None)
                    known = self._eval(inner, scope) if inner is not None else None
                    piece = known if known is not None else hole(out.text if out else "", line)
                else:
                    continue
                out = piece if out is None else concat(out, piece)
            return out or lit("", line)
        if t == "parenthesized_expression" and node.named_children:
            return self._eval(node.named_children[0], scope)
        if t == "binary_expression" and _txt(node.child_by_field_name("operator")) == "+":
            left = node.child_by_field_name("left")
            right = node.child_by_field_name("right")
            if left is None or right is None:
                return None
            a = self._eval(left, scope)
            b = self._eval(right, scope)
            if a is None and b is None:
                return None
            a = a if a is not None else hole("", _line(left))
            b = b if b is not None else hole(a.text, _line(right))
            return concat(a, b)
        if t == "identifier":
            name = _txt(node)
            if scope is not None and name in scope.values:
                return scope.values[name]
            return self.consts.get(name)
        if t == "member_expression":
            key = _txt(node)
            if key in self.consts:
                return self.consts[key]
            return None
        if t == "as_expression" and node.named_children:
            return self._eval(node.named_children[0], scope)
        return None

    def _is_string_expr(self, node: Node) -> bool:
        if node.type in ("string", "template_string"):
            return True
        if node.type == "binary_expression" and _txt(node.child_by_field_name("operator")) == "+":
            return any(self._is_string_expr(c) for c in node.named_children)
        return node.type == "parenthesized_expression" and any(
            self._is_string_expr(c) for c in node.named_children
        )

    # ---- walking ------------------------------------------------------------------------------
    def _walk(self, node: Node, scope: _Scope, cond: bool) -> None:
        """Visit in source order. Function bodies get their own scope; code under if, loops,
        switch, catch and ``?:`` is conditional for SQL text built in pieces."""
        t = node.type
        if t in (
            "function_declaration",
            "function_expression",
            "arrow_function",
            "method_definition",
            "generator_function_declaration",
        ):
            inner = _Scope()
            for c in node.children:
                self._walk(c, inner, False)
            self._leftovers(inner)
            if t == "method_definition":
                self._member_decorators(node)
            return
        if t in ("class_declaration", "abstract_class_declaration"):
            self._class(node)
        elif t in ("lexical_declaration", "variable_declaration"):
            if self._declaration(node, scope, cond):
                return
        elif t == "expression_statement" and self._assignment(node, scope, cond):
            return
        elif t == "call_expression":
            self._call(node, scope)
        elif t == "new_expression":
            self._new(node, scope)
        elif t == "object":
            self._object(node)
        elif t == "public_field_definition":
            self._member_decorators(node)
        elif (
            t in ("string", "template_string", "binary_expression")
            and self._is_string_expr(node)
            and node.start_byte not in self.done
        ):
            b = self._eval(node, scope)
            if b is not None:
                self._mark(node)
                self._text_found(b, None, strict=True)
                return
        branch = cond or t in (
            "if_statement",
            "for_statement",
            "for_in_statement",
            "while_statement",
            "do_statement",
            "switch_case",
            "catch_clause",
            "ternary_expression",
        )
        for c in node.children:
            self._walk(c, scope, branch)

    def _mark(self, node: Node) -> None:
        for n in _iter(node, {"string", "template_string", "binary_expression"}):
            self.done.add(n.start_byte)

    def _declaration(self, node: Node, scope: _Scope, cond: bool) -> bool:
        """``const sql = '...'``: the variable's text is tracked; other values are walked."""
        for d in node.named_children:
            if d.type != "variable_declarator":
                continue
            name_node = d.child_by_field_name("name")
            value = d.child_by_field_name("value")
            if value is None:
                continue
            name = _txt(name_node)
            if re.search(r"(^|\.)Router\(\)$|^express\.Router\b|^new (Koa)?Router\b", _txt(value)):
                self.routers.add(name)
            if (
                name_node is not None
                and name_node.type == "identifier"
                and self._is_string_expr(value)
            ):
                b = self._eval(value, scope)
                if b is not None:
                    self._mark(value)
                    scope.values[name] = update(scope.values.get(name), b, cond, append=False)
                    continue
            self._walk(value, scope, cond)
        return True

    def _assignment(self, node: Node, scope: _Scope, cond: bool) -> bool:
        expr = next((c for c in node.named_children), None)
        if expr is None or expr.type not in (
            "assignment_expression",
            "augmented_assignment_expression",
        ):
            return False
        left = expr.child_by_field_name("left")
        right = expr.child_by_field_name("right")
        if left is None or right is None or left.type != "identifier":
            return False
        name = _txt(left)
        append = expr.type == "augmented_assignment_expression" and "+=" in _txt(
            expr.child_by_field_name("operator")
        )
        if not self._is_string_expr(right) and not (append and name in scope.values):
            return False
        b = self._eval(right, scope)
        if b is None:
            prev = scope.values.get(name)
            b = hole(prev.text if prev else "", _line(right))
        self._mark(right)
        scope.values[name] = update(scope.values.get(name), b, cond, append=append)
        for c in right.children:  # calls inside the right side still count
            self._walk(c, scope, cond)
        return True

    def _leftovers(self, scope: _Scope) -> None:
        """SQL text held in a variable that no database call used (passed elsewhere)."""
        for name, b in scope.values.items():
            if name not in scope.consumed:
                self._text_found(b, None, strict=True)

    # ---- results ------------------------------------------------------------------------------
    def _text_found(self, b: Built, source: str | None, strict: bool, hql: bool = False) -> None:
        text = b.text.strip()
        if looks_like_sql(text, strict=strict and source is None):
            key = (b.line, text)
            if key not in self.sql_seen:
                self.sql_seen.add(key)
                fact = sql_fact(
                    file=self.path,
                    line=b.line,
                    text=text,
                    source=source or "ts-string",
                    dialect=self.dialect,
                    dynamic=b.dynamic,
                    hql=hql,
                )
                fill(fact, b)
                self.facts.add("sql_statements", fact)
        if "://" in text:
            self._urls(text, b.line)

    def _urls(self, text: str, line: int) -> None:
        if (line, text) in self.url_seen:
            return  # the same text seen twice (a call argument walked again)
        self.url_seen.add((line, text))
        for m in _DB_URL.finditer(text):
            url = mask_userinfo(m.group(0))
            scheme = m.group(1).lower()
            hp = re.match(r"^[\w+]+://([^/?#]+)(/[^?#]*)?", url)
            if not hp or "?" in hp.group(1).split(":")[0] or not hp.group(1).split(":")[0]:
                continue
            target = f"{scheme}://{hp.group(1)}" + (hp.group(2) or "").rstrip("/")
            kind = "AMQP" if scheme.startswith("amqp") else "DB"
            product = {"mongodb+srv": "MongoDB", "rediss": "Redis", "redis": "Redis"}.get(
                scheme, _DB_TYPES.get(scheme.replace("ql", ""), _DB_TYPES.get(scheme, ""))
            )
            self.facts.add(
                "integrations",
                integration(
                    type=kind,
                    target=target,
                    direction="outbound",
                    file=self.path,
                    line=line,
                    protocol=scheme,
                ),
            )
            if kind == "DB" and product:
                self.facts.add("tech_stack", tech(product, None, "database", self.path, line))
        for m in _URL.finditer(text):
            if "?" in (m.group(0).split("://", 1)[1].split("/", 1)[0]):
                continue  # the host is built at run time
            c = classify_url(m.group(0), soap_hint=self.soap)
            if c:
                self.facts.add(
                    "integrations",
                    integration(
                        type=c[0], target=c[1], direction="outbound", file=self.path, line=line
                    ),
                )

    def _orm(self, verb: str, entity: str, how: str, line: int, source: str) -> None:
        text = {
            "SELECT": f"SELECT /* {how} */ FROM {entity}",
            "INSERT": f"INSERT /* {how} */ INTO {entity}",
            "UPDATE": f"UPDATE {entity} /* {how} */",
            "DELETE": f"DELETE /* {how} */ FROM {entity}",
            "MERGE": f"MERGE /* {how} */ INTO {entity}",
        }[verb]
        fact = sql_fact(file=self.path, line=line, text=text, source=source, dialect=None, hql=True)
        fact.type = verb
        fact.parsed = False
        fact.reason = f"{how}: the ORM generates the SQL at run time"
        self.facts.add("sql_statements", fact)

    def _service(self, name: str, kind: str, handler: str, url: str, line: int) -> None:
        sid = make_id("service", kind, self.path, name)
        self.facts.add(
            "services",
            Service(
                id=sid,
                name=name,
                kind=kind,
                file=self.path,
                handler=handler,
                url=url,
                evidence=[Evidence(file=self.path, line=line)],
            ),
        )
        self.facts.symbol("service", handler, self.path, line, owner=sid)

    def _entry(self, name: str, kind: str, line: int, detail: str | None = None) -> None:
        self.facts.add(
            "entry_points",
            EntryPoint(
                id=make_id("entry", self.path, name, kind),
                name=name,
                kind=kind,
                file=self.path,
                line=line,
                evidence=[Evidence(file=self.path, line=line, snippet=detail)],
            ),
        )

    def _job(self, name: str, schedule: str | None, handler: str, line: int, how: str) -> None:
        jid = make_id("batch_job", "node-cron", self.path, line)
        self.facts.add(
            "batch_jobs",
            BatchJob(
                id=jid,
                name=name,
                scheduler="node-cron",
                description=f"Scheduled in the code with {how}",
                schedule=schedule,
                command=self.path,
                programs=[handler] if handler else [],
                evidence=[Evidence(file=self.path, line=line, snippet=how)],
            ),
        )
        self.facts.symbol("cron-job", name, self.path, line, owner=jid)

    def _api_call(self, b: Built, verb: str, line: int) -> None:
        """A call to an HTTP API: an integration when the URL names another host, a ``calls``
        reference to a route of the scanned services when it is a path."""
        if "://" in b.text:
            self._urls(b.text, line)
            host = b.text.split("://", 1)[1].split("/", 1)[0]
            if "?" not in host:
                return
        p = url_path(b.text)
        if p is None:
            return
        self.facts.ref(
            kind="calls",
            source=f"file:{self.path}",
            source_label=self.path,
            target=p,
            target_kinds=["url"],
            file=self.path,
            line=line,
            report_unresolved=False,
            data={"method": verb.upper(), "match": "api"},
        )

    # ---- calls --------------------------------------------------------------------------------
    def _call(self, n: Node, scope: _Scope) -> None:
        obj, name, obj_node = _callee(n)
        args = _args(n)
        line = _line(n)
        first = args[0] if args else None
        fb = self._eval(first, scope) if first is not None else None
        if first is not None and first.type == "identifier" and _txt(first) in scope.values:
            scope.consumed.add(_txt(first))
        # Express / Koa / Fastify routes
        if (
            name.lower() in HTTP_VERBS
            and first is not None
            and len(args) >= 2
            and fb is not None
            and (fb.text.startswith("/") or fb.text in ("*", ""))
        ):
            route_of = None
            if obj_node is not None and obj_node.type == "call_expression":
                o2, n2, _ = _callee(obj_node)
                a2 = _args(obj_node)
                if n2 == "route" and a2:
                    route_of = self._eval(a2[0], scope)
            if route_of is None and (_ROUTER_OBJ.match(obj) or obj in self.routers):
                self._route(name, fb.text, args, line, obj)
                self._mark(first)
                return
        if (
            name.lower() in HTTP_VERBS
            and obj_node is not None
            and obj_node.type == "call_expression"
        ):
            o2, n2, _ = _callee(obj_node)
            a2 = _args(obj_node)
            rb = self._eval(a2[0], scope) if a2 else None
            if n2 == "route" and rb is not None and rb.text.startswith("/"):
                self._route(name, rb.text, args or [obj_node], line, o2)
        if name == "route" and first is not None and first.type in ("object", "array"):
            for obj_lit in [first] if first.type == "object" else first.named_children:
                if obj_lit.type != "object":
                    continue
                p = _pairs(obj_lit)
                url = _string_value(p.get("url") or p.get("path"))
                method = _string_value(p.get("method")) or "ANY"
                if url:
                    handler = _txt(p.get("handler")) if p.get("handler") is not None else ""
                    self._route_service(method.upper(), url, handler, line)
        # app.use('/prefix', router): routes of that router get the prefix (in the linker)
        if name == "use" and len(args) >= 2 and fb is not None and fb.text.startswith("/"):
            for a in args[1:]:
                target = _txt(a)
                module = self.imports.get(target)
                if module and module.startswith("."):
                    resolved = posixpath.normpath(
                        posixpath.join(posixpath.dirname(self.path), module)
                    )
                    self.facts.symbol(
                        "node-route-mount", resolved, self.path, line, data={"prefix": fb.text}
                    )
                elif target in self.routers:
                    self.facts.symbol(
                        "node-route-mount",
                        f"{self.path}#{target}",
                        self.path,
                        line,
                        data={"prefix": fb.text},
                    )
        if name == "setGlobalPrefix" and fb is not None:
            self.facts.symbol("nest-global-prefix", fb.text.strip("/"), self.path, line)
        # Start-up
        if name == "listen" and (
            obj in ("app", "server", "httpServer", "fastify")
            or "express" in self.modules
            or "http" in self.modules
        ):
            self._entry(self.path, "node-server", line, f"{obj}.listen")
            self.facts.add("tech_stack", tech("Node.js", None, "runtime", self.path, line))
        if obj == "NestFactory" and name in ("create", "createApplicationContext"):
            self._entry(self.path, "node-server", line, "NestFactory." + name)
            self.facts.add("tech_stack", tech("Node.js", None, "runtime", self.path, line))
        if name in ("bootstrapApplication", "bootstrapModule") and first is not None:
            self._entry(_txt(first), "angular-app", line, name)
            if name == "bootstrapApplication":
                self._bootstraps(_txt(first), line)
        if obj == "angular" and name == "module" and len(args) >= 2 and fb is not None:
            self._entry(fb.text, "angularjs-app", line, "angular.module")
            self.facts.add("tech_stack", tech("AngularJS", None, "framework", self.path, line))
        # AngularJS registrations
        if (
            self.angularjs
            and obj_node is not None
            and fb is not None
            and name in ("controller", "component", "directive", "service", "factory")
        ):
            self._angularjs(name, fb.text, args, line)
        if (
            name == "when"
            and fb is not None
            and len(args) >= 2
            and args[1].type == "object"
            and ("$routeProvider" in _txt(n) or "routeProvider" in obj)
        ):
            self._angularjs_route(fb.text, args[1], line)
        if name == "state" and args and self.angularjs:
            if fb is not None and len(args) >= 2 and args[1].type == "object":
                self._angularjs_route(fb.text, args[1], line, state=True)
            elif first is not None and first.type == "object":
                p = _pairs(first)
                self._angularjs_route(_string_value(p.get("name")) or "", first, line, state=True)
        # HTTP clients
        if fb is not None and (
            (name.lower() in HTTP_VERBS | {"request", "jsonp"} and _HTTP_CLIENT_OBJ.search(obj))
            or (not obj and name in _HTTP_FUNCS)
            or name == "$resource"
        ):
            verb = name if name.lower() in HTTP_VERBS else "GET"
            self._api_call(fb, verb, line)
            if first is not None:
                self._mark(first)
        elif (
            not obj and name in ("$http", "axios") and first is not None and first.type == "object"
        ):
            p = _pairs(first)
            u = p.get("url")
            ub = self._eval(u, scope) if u is not None else None
            if ub is not None:
                self._api_call(ub, _string_value(p.get("method")) or "GET", line)
        if (
            (name == "createClientAsync" or (name == "createClient" and self.soap))
            and fb is not None
            and first is not None
        ):
            self._urls(fb.text, line)
            self._mark(first)
        # Database: raw SQL
        if (name in _DB_CALLS or (not obj and name in _DB_TAGS)) and first is not None:
            b = fb
            if first.type == "identifier" and first.text and _txt(first) in scope.values:
                b = scope.values[_txt(first)]
            if b is not None and looks_like_sql(b.text.strip()):
                self._mark(first)
                self._text_found(b, "node-db", strict=False)
        # ORMs
        self._orm_call(n, obj, name, obj_node, args, line)
        # Scheduled jobs
        self._schedule_call(obj, name, args, fb, line)

    def _route(self, verb: str, url: str, args: list[Node], line: int, obj: str) -> None:
        handler_node = args[-1] if args else None
        handler = ""
        if handler_node is not None and handler_node.type in ("identifier", "member_expression"):
            handler = _txt(handler_node)
        mount = f"{self.path}#{obj}" if obj in self.routers else ""
        self._route_service(verb.upper(), url, handler, line, mount)

    def _route_service(self, verb: str, url: str, handler: str, line: int, mount: str = "") -> None:
        verb = {"DEL": "DELETE", "ALL": "ANY"}.get(verb, verb)
        url = "/" + url.strip("/") if url not in ("*", "") else "/*"
        handler = handler or f"{self.path}:{line}"
        sid_name = f"{verb} {url}"
        self._service(sid_name, "node-route", handler, url, line)
        if mount:
            self.facts.symbol(
                "node-router",
                mount,
                self.path,
                line,
                owner=make_id("service", "node-route", self.path, sid_name),
            )
        if "express" in self.modules:
            self.facts.add("tech_stack", tech("Express", None, "framework", self.path, line))

    def _new(self, n: Node, scope: _Scope) -> None:
        ctor = _txt(n.child_by_field_name("constructor"))
        args = _args(n)
        line = _line(n)
        if ctor == "CronJob" and args:
            b = self._eval(args[0], scope)
            if args[0].type == "object":
                p = _pairs(args[0])
                b = self._eval(p["cronTime"], scope) if "cronTime" in p else None
                handler = _txt(p.get("onTick")) if p.get("onTick") is not None else ""
            else:
                handler = _txt(args[1]) if len(args) > 1 and args[1].type == "identifier" else ""
            sched = _cron5(b.text) if b is not None else None
            self._job(self._job_name(handler, line), sched, handler, line, "cron (CronJob)")
        if ctor in ("Pool", "Client", "Sequelize", "DataSource") and args:
            if args[0].type == "object":
                self._db_config(args[0], line)
            else:
                b = self._eval(args[0], scope)
                if b is not None:
                    self._urls(b.text, line)
        if ctor in ("Router", "KoaRouter", "express.Router"):
            parent = n.parent
            if parent is not None and parent.type == "variable_declarator":
                self.routers.add(_txt(parent.child_by_field_name("name")))

    def _object(self, obj: Node) -> None:
        p = _pairs(obj)
        # Angular route: { path: 'orders', component: OrderListComponent }
        if "path" in p and ("component" in p or "loadComponent" in p or "redirectTo" in p):
            path_text = _string_value(p["path"])
            if path_text is None:
                return
            full = "/".join(x for x in [*_parent_routes(obj), path_text] if x)
            comp = None
            if "component" in p and p["component"].type == "identifier":
                comp = _txt(p["component"])
            elif "loadComponent" in p:
                m = re.findall(r"\.(\w+Component)\b", _txt(p["loadComponent"]))
                comp = m[-1] if m else None
            if comp:
                self.facts.ref(
                    kind="forwards",
                    source=f"file:{self.path}",
                    source_label=self.path,
                    target=comp,
                    target_kinds=["angular-component"],
                    file=self.path,
                    line=_line(obj),
                    data={"route": "/" + full},
                )
            return
        # Database connection settings: { type: 'postgres', host: ..., database: ... }
        kind = next((k for k in ("type", "dialect", "client") if k in p), None)
        if kind is not None:
            value = (_string_value(p[kind]) or "").lower()
            if value in _DB_TYPES and (
                {"host", "database", "url", "connectString", "connection", "storage"} & set(p)
            ):
                self._db_config(obj, _line(obj))

    def _db_config(self, obj: Node, line: int) -> None:
        if obj.start_byte in self.done:
            return
        self.done.add(obj.start_byte)
        p = _pairs(obj)
        kind = next((k for k in ("type", "dialect", "client") if k in p), None)
        product = _DB_TYPES.get((_string_value(p[kind]) or "").lower()) if kind else None
        if "connectionString" in p or "url" in p:
            u = _string_value(p.get("connectionString") or p.get("url"))
            if u:
                self._urls(u, line)
        if "connectString" in p:  # oracledb: host:port/service
            cs = _string_value(p["connectString"])
            product = product or "Oracle"
            if cs and "?" not in cs:
                self.facts.add(
                    "integrations",
                    integration(
                        type="DB",
                        target=f"oracle://{cs.lstrip('/')}",
                        direction="outbound",
                        file=self.path,
                        line=line,
                        protocol="oracledb",
                    ),
                )
        host = _string_value(p.get("host")) if "host" in p else None
        db = _string_value(p.get("database")) if "database" in p else None
        port = _txt(p.get("port")) if "port" in p and p["port"].type == "number" else ""
        if product is None:
            for mod, prod in (
                ("pg", "PostgreSQL"),
                ("mysql", "MySQL"),
                ("mysql2", "MySQL"),
                ("mssql", "SQL Server"),
                ("oracledb", "Oracle"),
            ):
                if mod in self.modules:
                    product = prod
                    break
        if product:
            self.facts.add("tech_stack", tech(product, None, "database", self.path, line))
        if host and product:
            scheme = next((k for k, v in _DB_TYPES.items() if v == product), "db")
            target = f"{scheme}://{host.lower()}" + (f":{port}" if port else "")
            if db:
                target += f"/{db}"
            self.facts.add(
                "integrations",
                integration(
                    type="DB",
                    target=target,
                    direction="outbound",
                    file=self.path,
                    line=line,
                    protocol=scheme,
                ),
            )

    def _orm_call(
        self, n: Node, obj: str, name: str, obj_node: Node | None, args: list[Node], line: int
    ) -> None:
        if obj_node is None:
            return
        # TypeORM: this.orderRepo.find(...), dataSource.getRepository(Order).find(...)
        if name in _TYPEORM_OPS:
            entity = self.repos.get(obj)
            if entity is None and obj_node.type == "call_expression":
                _, inner, _ = _callee(obj_node)
                inner_args = _args(obj_node)
                if inner == "getRepository" and inner_args:
                    entity = _txt(inner_args[0])
            if (
                entity is None
                and name not in ("update", "delete", "count")
                and (obj.endswith(("manager", "Manager")) and args and args[0].type == "identifier")
            ):
                entity = _txt(args[0])
            if entity:
                self._orm(_TYPEORM_OPS[name], entity, f"TypeORM {name}", line, "typeorm")
                return
        # Prisma: prisma.order.findMany(...), this.prisma.order.create(...)
        if name in _PRISMA_OPS and obj_node.type == "member_expression":
            client = _txt(obj_node.child_by_field_name("object"))
            model = _txt(obj_node.child_by_field_name("property"))
            if re.search(r"(^|\.)(prisma|db|tx)$", client, re.I) and model[:1].isalpha():
                entity = model[0].upper() + model[1:]
                self._orm(_PRISMA_OPS[name], entity, f"Prisma {name}", line, "prisma")
                return
        # Sequelize: Order.findAll(...)
        if obj_node.type == "identifier" and obj[:1].isupper():
            op = _SEQUELIZE_OPS.get(name)
            if op is None and (
                "sequelize" in self.modules
                or "sequelize-typescript" in self.modules
                or "model" in self.imports.get(obj, "").lower()
            ):
                op = _SEQUELIZE_WEAK_OPS.get(name)
            if op is not None and obj not in ("Promise", "Object", "Array", "JSON", "Math"):
                self._orm(op, obj, f"Sequelize {name}", line, "sequelize")
                return
        # Knex: knex('orders').where(...)
        if "knex" in self.modules and obj_node.type == "call_expression":
            fn_obj, fn_name, _ = _callee(obj_node)
            inner_args = _args(obj_node)
            if not fn_obj and fn_name in ("knex", "db", "trx") and inner_args:
                table = _string_value(inner_args[0])
                verb = {
                    "insert": "INSERT",
                    "update": "UPDATE",
                    "del": "DELETE",
                    "delete": "DELETE",
                }.get(name, "SELECT")
                if table:
                    self._orm(verb, table, f"Knex {name}", line, "knex")

    def _schedule_call(
        self, obj: str, name: str, args: list[Node], fb: Built | None, line: int
    ) -> None:
        handler_node = next(
            (
                a
                for a in args[1:]
                if a.type
                in ("identifier", "member_expression", "arrow_function", "function_expression")
            ),
            None,
        )
        handler = (
            _txt(handler_node)
            if handler_node is not None and handler_node.type in ("identifier", "member_expression")
            else _called_in(handler_node)
        )
        opts = next((a for a in args[1:] if a.type == "object"), None)
        given = _string_value(_pairs(opts).get("name")) if opts is not None else None
        if name == "schedule" and re.search(r"cron", obj, re.I) and fb is not None:
            sched = _cron5(fb.text)
            self._job(given or self._job_name(handler, line), sched, handler, line, "node-cron")
        elif name == "scheduleJob" and fb is not None:
            if len(args) >= 3:  # scheduleJob(name, rule, fn)
                given = fb.text
                rb = self._eval(args[1], None) if args[1] is not None else None
                sched = _cron5(rb.text) if rb is not None else None
            else:
                sched = _cron5(fb.text)
            self._job(given or self._job_name(handler, line), sched, handler, line, "node-schedule")
        elif (
            name == "every" and re.search(r"agenda", obj, re.I) and fb is not None and len(args) > 1
        ):
            job = self._eval(args[1], None)
            jname = job.text if job is not None else self._job_name("", line)
            sched = _cron5(fb.text) or f"every {fb.text}"
            self._job(jname, sched, jname, line, "Agenda")

    def _job_name(self, handler: str, line: int) -> str:
        stem = self.path.rsplit("/", 1)[-1].rsplit(".", 1)[0]
        return (
            f"{stem}.{handler}" if handler and re.match(r"^[\w.]+$", handler) else f"{stem}:{line}"
        )

    # ---- classes and decorators ---------------------------------------------------------------
    def _class(self, node: Node) -> None:
        name = _txt(node.child_by_field_name("name"))
        line = _line(node)
        for dec in _decorators(node):
            dn, dargs = _decorator(dec)
            opts = _pairs(dargs[0]) if dargs and dargs[0].type == "object" else {}
            if dn == "Component" and opts:
                self._angular_component(name, opts, line)
            elif dn == "NgModule" and opts.get("bootstrap") is not None:
                for c in opts["bootstrap"].named_children:
                    self._bootstraps(_txt(c), line)
            elif dn == "Controller":
                base = _string_value(dargs[0]) if dargs else ""
                if dargs and dargs[0].type == "object":
                    base = _string_value(opts.get("path")) or ""
                self.facts.symbol(
                    "nest-controller", name, self.path, line, data={"path": base or ""}
                )
                self._nest_routes(node, name, base or "")
                self.facts.add("tech_stack", tech("NestJS", None, "framework", self.path, line))
            elif dn == "Entity":
                table = _string_value(dargs[0]) if dargs else None
                if dargs and dargs[0].type == "object":
                    table = _string_value(opts.get("name"))
                self.facts.symbol(
                    "jpa-entity",
                    name,
                    self.path,
                    line,
                    data={"table": (table or name).upper(), "orm": "typeorm"},
                )
            elif dn == "Table":  # sequelize-typescript
                table = _string_value(opts.get("tableName")) if opts else None
                self.facts.symbol(
                    "jpa-entity",
                    name,
                    self.path,
                    line,
                    data={"table": (table or name).upper(), "orm": "sequelize"},
                )
            elif dn == "Injectable":
                self.facts.symbol("injectable", name, self.path, line)
        heritage = _txt(next((c for c in node.children if c.type == "class_heritage"), None))
        if re.search(r"extends\s+Model\b", heritage):
            self.facts.symbol(
                "jpa-entity",
                name,
                self.path,
                line,
                data={"table": name.upper(), "orm": "sequelize"},
            )

    def _angular_component(self, name: str, opts: dict[str, Node], line: int) -> None:
        selector = _string_value(opts.get("selector")) or ""
        owner = None
        if "templateUrl" in opts:
            tpl = _string_value(opts["templateUrl"])
            if tpl:
                target = posixpath.normpath(posixpath.join(posixpath.dirname(self.path), tpl))
                self.facts.ref(
                    kind="renders",
                    source=f"file:{self.path}",
                    source_label=name,
                    target=target,
                    target_kinds=["path"],
                    file=self.path,
                    line=line,
                )
        elif "template" in opts:
            html = self._eval(opts["template"], None)
            owner = make_id("screen", f"{self.path}#{name}")
            self.facts.add(
                "screens",
                Screen(
                    id=owner,
                    name=name,
                    technology="angular",
                    file=self.path,
                    fields_count=count_fields(html.text) if html is not None else 0,
                    evidence=[Evidence(file=self.path, line=line)],
                ),
            )
            if html is not None:
                self._mark(opts["template"])
                for tag in sorted(set(re.findall(r"<([a-z][\w]*-[\w-]+)", html.text))):
                    self.facts.ref(
                        kind="includes",
                        source=f"screen:{owner}",
                        source_label=name,
                        target=tag,
                        target_kinds=["angular-selector"],
                        file=self.path,
                        line=line,
                        report_unresolved=False,
                    )
        self.facts.symbol("angular-component", name, self.path, line, owner=owner)
        for sel in [s.strip() for s in selector.split(",") if s.strip()]:
            self.facts.symbol("angular-selector", sel, self.path, line, owner=owner)
        self.facts.add("tech_stack", tech("Angular", None, "framework", self.path, line))

    def _bootstraps(self, name: str, line: int) -> None:
        self.facts.ref(
            kind="bootstraps",
            source=f"file:{self.path}",
            source_label=self.path,
            target=name,
            target_kinds=["angular-component"],
            file=self.path,
            line=line,
            report_unresolved=False,
        )

    def _nest_routes(self, cls: Node, cls_name: str, base: str) -> None:
        body = cls.child_by_field_name("body")
        if body is None:
            return
        for m in body.named_children:
            if m.type != "method_definition":
                continue
            mname = _txt(m.child_by_field_name("name"))
            for dec in _decorators(m):
                dn, dargs = _decorator(dec)
                if dn in NEST_VERBS:
                    sub = _string_value(dargs[0]) if dargs else ""
                    url = "/" + "/".join(x.strip("/") for x in (base, sub or "") if x.strip("/"))
                    verb = NEST_VERBS[dn]
                    self._service(
                        f"{verb} {url}", "nest-route", f"{cls_name}.{mname}", url, _line(m)
                    )

    def _member_decorators(self, node: Node) -> None:
        """NestJS ``@Cron``/``@Interval``/``@Timeout`` on a method."""
        mname = _txt(node.child_by_field_name("name"))
        cls = node.parent.parent if node.parent is not None else None
        cname = _txt(cls.child_by_field_name("name")) if cls is not None else ""
        for dec in _decorators(node):
            dn, dargs = _decorator(dec)
            if dn not in ("Cron", "Interval", "Timeout") or not dargs:
                continue
            first = dargs[0]
            opts = next((a for a in dargs if a.type == "object"), None)
            given = _string_value(_pairs(opts).get("name")) if opts is not None else None
            if dn == "Interval" and len(dargs) > 1:  # @Interval('name', ms)
                given, first = _string_value(dargs[0]), dargs[1]
            sched: str | None
            if dn == "Cron":
                raw = _string_value(first)
                if raw is None:
                    raw = NEST_CRON.get(_txt(first).rsplit(".", 1)[-1], _txt(first))
                sched = _cron5(raw) or raw
            elif dn == "Interval":
                sched = _every(_txt(first))
            else:
                sched = None
            handler = f"{cname}.{mname}" if cname else mname
            self._job(given or handler, sched, handler, _line(node), f"NestJS @{dn}")

    # ---- AngularJS ----------------------------------------------------------------------------
    def _angularjs(self, kind: str, name: str, args: list[Node], line: int) -> None:
        self.facts.symbol(f"angularjs-{kind}", name, self.path, line)
        kebab = re.sub(r"([a-z0-9])([A-Z])", r"\1-\2", name).lower()
        if kind in ("component", "directive") and kebab != name:
            self.facts.symbol(f"angularjs-{kind}", kebab, self.path, line)  # <order-list>
        if kind in ("component", "directive") and len(args) >= 2:
            body = args[1]
            objs = [body] if body.type == "object" else list(_iter(body, {"object"}))
            for o in objs:
                p = _pairs(o)
                if "templateUrl" in p:
                    tpl = _string_value(p["templateUrl"])
                    if tpl:
                        self.facts.ref(
                            kind="renders",
                            source=f"file:{self.path}",
                            source_label=name,
                            target=tpl,
                            target_kinds=["path"],
                            file=self.path,
                            line=_line(o),
                        )
                    break
        self.facts.add("tech_stack", tech("AngularJS", None, "framework", self.path, line))

    def _angularjs_route(self, route: str, obj: Node, line: int, state: bool = False) -> None:
        p = _pairs(obj)
        tpl = _string_value(p.get("templateUrl")) if "templateUrl" in p else None
        url = _string_value(p.get("url")) if state and "url" in p else route
        data = {"route": url or route}
        if tpl:
            self.facts.ref(
                kind="forwards",
                source=f"file:{self.path}",
                source_label=self.path,
                target=tpl,
                target_kinds=["path"],
                file=self.path,
                line=line,
                data=data,
            )
        elif "component" in p:
            comp = _string_value(p["component"])
            if comp:
                self.facts.ref(
                    kind="forwards",
                    source=f"file:{self.path}",
                    source_label=self.path,
                    target=comp,
                    target_kinds=["angularjs-component"],
                    file=self.path,
                    line=line,
                    data=data,
                )
        ctrl = _string_value(p.get("controller")) if "controller" in p else None
        if ctrl:
            self.facts.ref(
                kind="uses",
                source=f"file:{self.path}",
                source_label=self.path,
                target=ctrl.split(" as ")[0].strip(),
                target_kinds=["angularjs-controller"],
                file=self.path,
                line=line,
                report_unresolved=False,
            )

    # ---- file level ---------------------------------------------------------------------------
    def _file_level(self) -> None:
        if "@angular/core" in self.modules:
            self.facts.add("tech_stack", tech("Angular", None, "framework", self.path, None))
        if self.path.endswith((".ts", ".tsx", ".mts", ".cts")):
            self.facts.add("tech_stack", tech("TypeScript", None, "language", self.path, None))
        if re.search(r"require\.main\s*===?\s*module", self.text):
            self._entry(self.path, "node-script", 1, "require.main === module")


def _idents(node: Node | None) -> list[str]:
    if node is None:
        return []
    if node.type in ("identifier", "shorthand_property_identifier_pattern"):
        return [_txt(node)]
    out: list[str] = []
    for c in node.named_children:
        if c.type == "import_specifier":
            alias = c.child_by_field_name("alias") or c.child_by_field_name("name")
            out.append(_txt(alias))
        else:
            out.extend(_idents(c))
    return out


def _iter(node: Node | None, kinds: set[str]) -> list[Node]:
    out: list[Node] = []
    if node is None:
        return out
    stack = [node]
    while stack:
        n = stack.pop()
        if n.type in kinds:
            out.append(n)
        stack.extend(reversed(n.children))
    return out


def _function_parent(node: Node) -> Node | None:
    p = node.parent
    while p is not None:
        if p.type in (
            "function_declaration",
            "function_expression",
            "arrow_function",
            "method_definition",
            "generator_function_declaration",
        ):
            return p
        p = p.parent
    return None


def _parent_routes(obj: Node) -> list[str]:
    """Paths of the routes a child route is nested in (``children: [...]``), outermost first."""
    out: list[str] = []
    n = obj.parent
    while n is not None:
        if n.type == "pair" and _txt(n.child_by_field_name("key")) == "children":
            parent_obj = n.parent
            if parent_obj is not None and parent_obj.type == "object":
                p = _pairs(parent_obj)
                path = _string_value(p.get("path")) if "path" in p else None
                if path:
                    out.insert(0, path)
                n = parent_obj
        n = n.parent
    return out


def _called_in(node: Node | None) -> str:
    """The first function an inline handler calls (``() => runNightly()`` -> ``runNightly``)."""
    if node is None:
        return ""
    for c in _iter(node, {"call_expression"}):
        fn = c.child_by_field_name("function")
        name = _txt(fn)
        if name and re.match(r"^[\w.]+$", name) and not name.startswith(("console.", "logger.")):
            return name.removeprefix("this.")
    return ""


def _cron5(expr: str) -> str | None:
    """A cron expression with 5 fields (a leading seconds field is dropped); None otherwise."""
    parts = expr.split()
    if len(parts) == 6:
        parts = parts[1:]
    if len(parts) == 5 and all(re.match(r"^[\d*/,?LW#A-Za-z-]+$", p) for p in parts):
        return " ".join(parts)
    return None


def _every(ms: str) -> str | None:
    """``@Interval(60000)`` -> ``every 60 s``."""
    digits = ms.replace("_", "")
    if not digits.isdigit():
        return f"every {ms}" if ms else None
    n = int(digits)
    if n % 3_600_000 == 0:
        return f"every {n // 3_600_000} h"
    if n % 60_000 == 0:
        return f"every {n // 60_000} min"
    return f"every {n // 1000} s" if n >= 1000 else f"every {n} ms"
