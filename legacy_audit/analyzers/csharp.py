"""C# (tree-sitter-c-sharp): Windows Forms, WPF, ASP.NET, WCF, Windows services and data
access (decision 136).

One parse per file finds:

- **screens**: Windows Forms forms (``: Form``; fields from the designer's controls) and user
  controls (partials); WPF windows come from their ``.xaml`` (``dotnet`` analyzer), the code
  behind only adds navigation; ``new X().Show()``/``ShowDialog()`` and ``var f = new X();
  f.Show()`` are navigation between screens;
- **services**: ASP.NET MVC actions (``: Controller``), Web API actions (``: ApiController``,
  ``: ControllerBase``, ``[ApiController]``) with their ``[Route]``/``[HttpGet(...)]`` paths,
  ASMX ``[WebMethod]`` and WCF ``[OperationContract]`` operations;
- **entry points and jobs**: ``static Main`` (a desktop application when it runs
  ``Application.Run``/``ShowDialog``/a WPF ``App``, else a program, which makes its component
  batch work), Windows services (``: ServiceBase``) and hosted services
  (``: BackgroundService``), Hangfire ``RecurringJob.AddOrUpdate`` and Quartz.NET cron triggers
  as batch jobs;
- **database access**: ADO.NET commands and adapters (``new SqlCommand(sql, cn)``,
  ``CommandText = ...``), Dapper (``Query``, ``Execute``, ...), Entity Framework raw SQL
  (``FromSqlRaw``, ``SqlQuery``, ``ExecuteSqlRaw``, ...), Entity Framework LINQ on a
  ``DbSet`` (kept as readable ORM text, not parsed), entity-to-table mappings (``[Table]``,
  ``DbSet<T>``); SQL built in code is rebuilt (``+``, ``+=``, ``$"..."`` interpolation,
  ``StringBuilder``, ``string.Format``, ``?:``), the run-time pieces being ``?`` holes;
  stored procedures through ``CommandType.StoredProcedure``; connection strings (masked);
- **integrations**: URLs, connection strings, MSMQ queues, SMTP servers, files read or written
  (``File.*``, ``StreamReader``/``StreamWriter``/``FileStream``);
- **tech stack**: C#, the ``using`` namespaces (Windows Forms, WPF, ASP.NET, WCF, Entity
  Framework, ADO.NET providers and their databases, Quartz.NET, Hangfire, MSMQ, ...).
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import ClassVar

import tree_sitter_c_sharp
from tree_sitter import Language, Node, Parser

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import tech
from legacy_audit.analyzers.dotnet import DB_NAMESPACES, NAMESPACE_TECH, add_connection
from legacy_audit.analyzers.dynsql import Built, Tracker, choice, concat, empty, fill, hole, lit
from legacy_audit.analyzers.integrations import classify_url, integration
from legacy_audit.analyzers.sqltools import looks_like_sql, sql_fact
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

_LANG = Language(tree_sitter_c_sharp.language())

_ADO_VERBS = {
    "ExecuteReader",
    "ExecuteNonQuery",
    "ExecuteScalar",
    "ExecuteXmlReader",
    "ExecuteReaderAsync",
    "ExecuteNonQueryAsync",
    "ExecuteScalarAsync",
}
_DAPPER = {
    "Query",
    "QueryFirst",
    "QueryFirstOrDefault",
    "QuerySingle",
    "QuerySingleOrDefault",
    "QueryMultiple",
    "QueryAsync",
    "QueryFirstAsync",
    "QueryFirstOrDefaultAsync",
    "QuerySingleAsync",
    "QuerySingleOrDefaultAsync",
    "QueryMultipleAsync",
    "Execute",
    "ExecuteAsync",
    "ExecuteScalar",
    "ExecuteScalarAsync",
    "ExecuteReader",
}
_EF_RAW = {
    "FromSqlRaw",
    "FromSql",
    "FromSqlInterpolated",
    "ExecuteSqlRaw",
    "ExecuteSqlRawAsync",
    "ExecuteSqlInterpolated",
    "ExecuteSqlInterpolatedAsync",
    "ExecuteSqlCommand",
    "ExecuteSqlCommandAsync",
    "SqlQuery",
    "SqlQueryRaw",
    "ExecuteSql",
    "ExecuteSqlAsync",
}
_EF_READ = {
    "Where",
    "First",
    "FirstOrDefault",
    "Single",
    "SingleOrDefault",
    "ToList",
    "ToListAsync",
    "Find",
    "FindAsync",
    "Any",
    "AnyAsync",
    "Count",
    "CountAsync",
    "Include",
    "OrderBy",
    "OrderByDescending",
    "Select",
    "AsNoTracking",
    "FirstOrDefaultAsync",
    "SingleOrDefaultAsync",
    "ToArray",
    "Sum",
    "Max",
    "Min",
}
_EF_WRITE = {
    "Add": "INSERT",
    "AddRange": "INSERT",
    "AddAsync": "INSERT",
    "Update": "UPDATE",
    "UpdateRange": "UPDATE",
    "Remove": "DELETE",
    "RemoveRange": "DELETE",
    "ExecuteDelete": "DELETE",
    "ExecuteDeleteAsync": "DELETE",
    "ExecuteUpdate": "UPDATE",
    "ExecuteUpdateAsync": "UPDATE",
}
_CONTEXT_VAR = re.compile(r"^(?:this\.)?_?(db|ctx|context|dbContext|\w*Context|\w*Db)$", re.I)
_COMMAND_TYPES = re.compile(
    r"^(Sql|OleDb|Odbc|Oracle|MySql|Npgsql|DB2|iDB2|SQLite|Sqlite|Ase)"
    r"(Command|DataAdapter)$"
)
_NEWLINE = re.compile(r"^(System\.)?Environment\.NewLine$")
_INPUTS = re.compile(
    r"^(TextBox|ComboBox|CheckBox|RadioButton|ListBox|CheckedListBox|MaskedTextBox|"
    r"DateTimePicker|MonthCalendar|RichTextBox|NumericUpDown|TrackBar|DataGridView|DataGrid|"
    r"ListView)$"
)
_BASES = {
    "Form": "form",
    "UserControl": "usercontrol",
    "ServiceBase": "windows-service",
    "BackgroundService": "background-service",
    "Controller": "mvc",
    "ApiController": "api",
    "ControllerBase": "api",
    "WebService": "asmx",
    "DbContext": "dbcontext",
    "IHostedService": "background-service",
}
_HTTP = {
    "HttpGet": "GET",
    "HttpPost": "POST",
    "HttpPut": "PUT",
    "HttpDelete": "DELETE",
    "HttpPatch": "PATCH",
}
_COND = {
    "if_statement",
    "switch_section",
    "for_statement",
    "foreach_statement",
    "while_statement",
    "do_statement",
    "catch_clause",
    "conditional_expression",
    "switch_expression_arm",
    "lambda_expression",
}
_FILE_READ = {"ReadAllText", "ReadAllLines", "ReadAllBytes", "ReadLines", "OpenRead", "OpenText"}
_FILE_WRITE = {
    "WriteAllText",
    "WriteAllLines",
    "WriteAllBytes",
    "AppendAllText",
    "AppendAllLines",
    "OpenWrite",
    "Create",
    "AppendText",
}
_SP = re.compile(r"CommandType\.StoredProcedure|commandType:\s*CommandType\.StoredProcedure")
_PROC_NAME = re.compile(r"^[A-Za-z_][\w$#]*(\.[A-Za-z_][\w$#]*){0,2}$")
_URL = re.compile(r"\b(?:https?|ftps?|sftp|ldaps?|smtp)://[^\s\"'<>()\\{}]+", re.I)


def _txt(node: Node | None) -> str:
    return node.text.decode("utf-8", "replace") if node is not None and node.text else ""


def _line(node: Node) -> int:
    return node.start_point[0] + 1


def _string(node: Node) -> str | None:
    """The value of a plain string literal (regular, verbatim or raw)."""
    t = node.type
    if t == "string_literal":
        out = []
        for c in node.children:
            if c.type == "string_literal_content":
                out.append(_txt(c))
            elif c.type == "escape_sequence":
                e = _txt(c)
                out.append(
                    {
                        "\\n": " ",
                        "\\r": " ",
                        "\\t": " ",
                        "\\\\": "\\",
                        '\\"': '"',
                        "\\'": "'",
                        "\\0": "",
                    }.get(e, e)
                )
        return "".join(out)
    if t == "verbatim_string_literal":
        return _txt(node)[2:-1].replace('""', '"')
    if t == "raw_string_literal":
        return "".join(_txt(c) for c in node.children if c.type == "raw_string_content").strip()
    return None


def _args(call: Node) -> list[Node]:
    al = call.child_by_field_name("arguments")
    if al is None:
        al = next((c for c in call.children if c.type == "argument_list"), None)
    if al is None:
        return []
    out = []
    for a in al.named_children:
        if a.type == "argument":
            expr = a.named_children[-1] if a.named_children else None
            if expr is not None:
                out.append(expr)
    return out


def _named_arg(call: Node, name: str) -> Node | None:
    al = call.child_by_field_name("arguments")
    for a in al.named_children if al is not None else []:
        kids = a.named_children
        if a.type == "argument" and len(kids) == 2 and _txt(kids[0]) == name:
            return kids[1]
    return None


def _callee(call: Node) -> tuple[Node | None, str]:
    """(receiver, method name) of an invocation."""
    fn = call.child_by_field_name("function")
    if fn is None:
        return None, ""
    if fn.type == "member_access_expression":
        name = fn.child_by_field_name("name")
        n = (
            _txt(name.children[0])
            if name is not None and name.type == "generic_name"
            else _txt(name)
        )
        return fn.child_by_field_name("expression"), n
    if fn.type == "generic_name":
        return None, _txt(fn.children[0])
    return None, _txt(fn)


def _type_name(node: Node | None) -> str:
    """``System.Windows.Forms.TextBox`` / ``List<int>`` -> ``TextBox`` / ``List``."""
    t = _txt(node)
    t = t.split("<", 1)[0]
    return t.rsplit(".", 1)[-1].strip()


def _attributes(node: Node) -> list[tuple[str, str | None]]:
    """(name, first string argument) of each attribute on a declaration."""
    out = []
    for al in node.children:
        if al.type != "attribute_list":
            continue
        for at in al.named_children:
            if at.type != "attribute":
                continue
            name = _type_name(at.child_by_field_name("name")).removesuffix("Attribute")
            arg = None
            args = next((c for c in at.children if c.type == "attribute_argument_list"), None)
            if args is not None:
                for a in args.named_children:
                    s = next((_string(x) for x in a.named_children if _string(x) is not None), None)
                    if s is not None and "=" not in _txt(a).split('"', 1)[0]:
                        arg = s
                        break
            out.append((name, arg))
    return out


def _cron_text(node: Node | None) -> str | None:
    """A Hangfire/Quartz schedule: a cron string or ``Cron.Daily(2)`` -> readable text."""
    if node is None:
        return None
    s = _string(node)
    if s is not None:
        return s
    t = _txt(node)
    m = re.match(r"^Cron\.(\w+)\s*\(([^)]*)\)", t)
    if m:
        args = [a.strip() for a in m.group(2).split(",") if a.strip()]
        kind = m.group(1).lower()
        at = (
            f" at {int(args[0]):02d}:{int(args[1]) if len(args) > 1 else 0:02d}"
            if args and args[0].isdigit() and kind == "daily"
            else ""
        )
        return f"{kind}{at}"
    return t[:80] or None


@dataclass
class _Class:
    name: str
    bases: list[str]
    attrs: list[tuple[str, str | None]]
    kind: str = ""


@dataclass
class _Method:
    tracker: Tracker
    consumed: set[str] = field(default_factory=set)


class CSharpAnalyzer:
    name: ClassVar[str] = "csharp"
    version: ClassVar[str] = "1"

    def __init__(self) -> None:
        self.parser = Parser(_LANG)

    def supports(self, file: FileRecord) -> bool:
        return (
            file.language == "csharp"
            and file.category not in (Category.TEST, Category.GENERATED, Category.BINARY)
            and "/obj/" not in f"/{file.path}"
        )

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        text = ctx.text or ""
        facts = Facts()
        facts.coverage.lines = text.count("\n") + 1
        tree = self.parser.parse(text.encode("utf-8"))
        root = tree.root_node
        if root.has_error:
            stack = [root]
            while stack:
                n = stack.pop()
                if n.type == "ERROR" or n.is_missing:
                    facts.coverage.gap(_line(n), n.end_point[0] + 1, "C# syntax error")
                    continue
                if n.has_error:
                    stack.extend(n.children)
        _Visitor(file, ctx, facts, text).run(root)
        return facts


class _Visitor:
    def __init__(self, file: FileRecord, ctx: AnalyzerContext, facts: Facts, text: str) -> None:
        self.path = file.path
        self.facts = facts
        self.text = text
        self.dialect = ctx.config.sql.dialect
        self.fields = Tracker()
        self.fields_consumed: set[str] = set()
        self.method: _Method | None = None
        self.builders: set[str] = set()
        self.var_types: dict[str, str] = {}
        self.classes: list[_Class] = []
        self.screen_id: str | None = None
        self.sql_seen: set[tuple[int, str]] = set()
        self.url_seen: set[int] = set()
        self.stored_proc = bool(_SP.search(text))
        self.soap = bool(re.search(r"\?wsdl|\.asmx|SoapHttpClientProtocol", text, re.I))
        self.dbsets: set[str] = set(
            m.group(1) for m in re.finditer(r"DbSet<\s*[\w.]+\s*>\s+(\w+)", text)
        )

    # ---- driving ------------------------------------------------------------------------------
    def run(self, root: Node) -> None:
        self.facts.add("tech_stack", tech("C#", None, "language", self.path, None))
        top = [c for c in root.children if c.type == "global_statement"]
        if top:  # top-level statements (C# 9+): the program's Main
            web = re.search(r"WebApplication\.Create|CreateHostBuilder|Host\.Create", self.text)
            if not web:
                self._entry("Program", "main", _line(top[0]), "top-level statements")
            self.method = _Method(_copy(self.fields))
            for g in top:
                self._walk(g)
            self._end_method()
        self._decls(root)
        self._leftovers(self.fields, self.fields_consumed)

    def _decls(self, node: Node) -> None:
        for c in node.children:
            t = c.type
            if t == "using_directive":
                self._using(c)
            elif t in ("namespace_declaration", "file_scoped_namespace_declaration"):
                body = c.child_by_field_name("body")
                self._decls(body if body is not None else c)
            elif t == "declaration_list":
                self._decls(c)
            elif t in (
                "class_declaration",
                "struct_declaration",
                "record_declaration",
                "interface_declaration",
            ):
                self._class(c)

    def _using(self, node: Node) -> None:
        ns = _txt(
            next(
                (c for c in node.named_children if c.type in ("qualified_name", "identifier")), None
            )
        )
        if not ns:
            return
        for prefix, name, category in NAMESPACE_TECH:
            if ns == prefix or ns.startswith(prefix + "."):
                self.facts.add("tech_stack", tech(name, None, category, self.path, _line(node)))
                break
        if ns in DB_NAMESPACES:
            self.facts.add(
                "tech_stack", tech(DB_NAMESPACES[ns], None, "database", self.path, _line(node))
            )

    # ---- classes ------------------------------------------------------------------------------
    def _class(self, node: Node) -> None:
        name = _txt(node.child_by_field_name("name"))
        bl = next((c for c in node.children if c.type == "base_list"), None)
        bases = [_type_name(b) for b in bl.named_children] if bl is not None else []
        cls = _Class(name, bases, _attributes(node))
        cls.kind = next((_BASES[b] for b in bases if b in _BASES), "")
        if node.type == "interface_declaration":
            cls.kind = "interface"
        if any(a == "ApiController" for a, _ in cls.attrs):
            cls.kind = "api"
        if not cls.kind and self.path.lower().endswith(".designer.cs") and _designer_fields(node):
            cls.kind = "form"  # the designer half of a form: "partial class X" without its base
        line = _line(node)
        self.classes.append(cls)
        self.facts.symbol("dotnet-class", name, self.path, line)
        table = next((arg for a, arg in cls.attrs if a == "Table" and arg), None)
        if table:
            self.facts.symbol(
                "jpa-entity", name, self.path, line, data={"table": table.upper(), "orm": "ef"}
            )
        if cls.kind in ("form", "usercontrol"):
            self.screen_id = make_id("screen", "winforms", self.path.rsplit("/", 1)[0], name)
            self.facts.add(
                "screens",
                Screen(
                    id=self.screen_id,
                    name=name,
                    technology="winforms",
                    file=self.path,
                    fields_count=_designer_fields(node),
                    partial=cls.kind == "usercontrol",
                    evidence=[
                        Evidence(
                            file=self.path, line=line, snippet=f"class {name} : {', '.join(bases)}"
                        )
                    ],
                ),
            )
            self.facts.symbol("desktop-form", name, self.path, line, owner=self.screen_id)
            self.facts.add("tech_stack", tech("Windows Forms", None, "framework", self.path, line))
        elif cls.kind in ("windows-service", "background-service"):
            self._entry(name, "windows-service", line, f"class {name} : {', '.join(bases)}")
        body = node.child_by_field_name("body")
        for member in body.named_children if body is not None else []:
            t = member.type
            if t == "field_declaration":
                self._field(member)
            elif t == "property_declaration":
                self._property(member, cls)
            elif t in (
                "method_declaration",
                "constructor_declaration",
                "operator_declaration",
                "destructor_declaration",
            ):
                self._method_decl(member, cls)
            elif t in (
                "class_declaration",
                "struct_declaration",
                "record_declaration",
                "interface_declaration",
            ):
                self._class(member)
        self.classes.pop()

    def _field(self, node: Node) -> None:
        decl = next((c for c in node.named_children if c.type == "variable_declaration"), None)
        for v in decl.named_children if decl is not None else []:
            if v.type == "variable_declarator":
                self._declarator(v, cond=False, tracker=self.fields)

    def _property(self, node: Node, cls: _Class) -> None:
        t = node.child_by_field_name("type")
        name = _txt(node.child_by_field_name("name"))
        if t is not None and t.type == "generic_name" and _txt(t.children[0]) == "DbSet":
            args = next((c for c in t.children if c.type == "type_argument_list"), None)
            entity = _type_name(args.named_children[0]) if args and args.named_children else name
            self.facts.symbol(
                "jpa-entity",
                name,
                self.path,
                _line(node),
                data={"table": entity.upper(), "entity": entity, "orm": "ef"},
            )
        for acc in node.named_children:
            if acc.type == "accessor_list":
                for a in acc.named_children:
                    body = a.child_by_field_name("body")
                    if body is not None:
                        self._body(body)

    def _method_decl(self, node: Node, cls: _Class) -> None:
        name = _txt(node.child_by_field_name("name"))
        line = _line(node)
        mods = {_txt(c) for c in node.children if c.type == "modifier"}
        attrs = _attributes(node)
        body = node.child_by_field_name("body") or next(
            (c for c in node.children if c.type == "arrow_expression_clause"), None
        )
        if name == "Main" and "static" in mods and node.type == "method_declaration":
            b = _txt(body)
            kind = (
                "desktop-app"
                if re.search(
                    r"Application\.Run\b|\.ShowDialog\(|\.Show\(|"
                    r"new\s+App\s*\(",
                    b,
                )
                else "main"
            )
            self._entry(f"{cls.name}.Main", kind, line, "static Main")
        self._service_for(name, cls, attrs, mods, line, node.type)
        if body is not None:
            self._body(body)

    def _body(self, body: Node) -> None:
        self.method = _Method(_copy(self.fields))
        self._walk(body)
        self._end_method()

    def _end_method(self) -> None:
        if self.method is None:
            return
        self._leftovers(self.method.tracker, self.method.consumed, inherited=self.fields)
        self.method = None

    def _service_for(
        self,
        name: str,
        cls: _Class,
        attrs: list[tuple[str, str | None]],
        mods: set[str],
        line: int,
        node_type: str,
    ) -> None:
        names = {a for a, _ in attrs}
        handler = f"{cls.name}.{name}"
        if "WebMethod" in names or "OperationContract" in names:
            how = "ASMX" if "WebMethod" in names else "WCF"
            svc = cls.name[1:] if how == "WCF" and re.match(r"^I[A-Z]", cls.name) else cls.name
            url = f"{svc}.{'asmx' if how == 'ASMX' else 'svc'}/{name}"
            self._service(f"{how} {svc}.{name}", "soap-operation", handler, url, line)
            self.facts.add(
                "tech_stack",
                tech(
                    "WCF" if how == "WCF" else "ASP.NET Web Services (ASMX)",
                    None,
                    "framework",
                    self.path,
                    line,
                ),
            )
            return
        if cls.kind not in ("mvc", "api") or node_type != "method_declaration":
            return
        if "public" not in mods or "static" in mods or "NonAction" in names:
            return
        verb = next((_HTTP[a] for a, _ in attrs if a in _HTTP), None)
        route = next(
            (arg for a, arg in attrs if (a in _HTTP or a == "Route") and arg is not None), None
        )
        prefix = next((arg for a, arg in cls.attrs if a in ("Route", "RoutePrefix") and arg), None)
        ctrl = re.sub(r"Controller$", "", cls.name)
        if prefix is None:
            prefix = f"api/{ctrl}" if cls.kind == "api" else ctrl
        prefix = prefix.replace("[controller]", ctrl)
        if route is not None and route.startswith(("/", "~/")):
            path = "/" + route.lstrip("~/")
        else:
            tail = route if route is not None else ("" if cls.kind == "api" else name)
            path = f"/{prefix.strip('/')}" + (f"/{tail}" if tail else "")
        path = path.replace("[action]", name)
        kind = "rest-operation" if cls.kind == "api" else "aspnet-mvc"
        self._service(
            f"{verb or ('GET' if kind == 'aspnet-mvc' else 'ANY')} {path}",
            kind,
            handler,
            path,
            line,
        )
        self.facts.add(
            "tech_stack",
            tech(
                "ASP.NET Core"
                if re.search(r"Microsoft\.AspNetCore", self.text)
                else ("ASP.NET Web API" if kind == "rest-operation" else "ASP.NET MVC"),
                None,
                "framework",
                self.path,
                line,
            ),
        )

    # ---- statements ---------------------------------------------------------------------------
    def _cond(self, node: Node) -> bool:
        p = node.parent
        while p is not None and p.type not in ("method_declaration", "block_statement_root"):
            if p.type in _COND:
                return True
            if p.type in (
                "constructor_declaration",
                "accessor_declaration",
                "local_function_statement",
                "global_statement",
            ):
                return False
            p = p.parent
        return False

    def _tracker(self) -> Tracker:
        return self.method.tracker if self.method else self.fields

    def _consumed(self) -> set[str]:
        return self.method.consumed if self.method else self.fields_consumed

    def _consume(self, name: str, value: Built) -> None:
        self._consumed().add(name)
        if self.fields.get(name) is value:
            self.fields_consumed.add(name)

    def _walk(self, node: Node) -> None:
        t = node.type
        if t in ("local_function_statement", "class_declaration"):
            return
        if t == "variable_declarator":
            self._declarator(node, cond=self._cond(node), tracker=self._tracker())
            return
        if t == "assignment_expression":
            self._assignment(node)
            return
        if t == "invocation_expression":
            self._invocation(node)
            return
        if t == "object_creation_expression":
            self._creation(node)
            return
        if t in (
            "string_literal",
            "verbatim_string_literal",
            "raw_string_literal",
            "interpolated_string_expression",
        ):
            self._literal(node)
            return
        for c in node.children:
            self._walk(c)

    def _value_node(self, declarator: Node) -> Node | None:
        seen_eq = False
        for c in declarator.children:
            if seen_eq:
                return c
            if c.type == "=":
                seen_eq = True
        return None

    def _declarator(self, node: Node, cond: bool, tracker: Tracker) -> None:
        name = _txt(node.child_by_field_name("name") or node.named_children[0]).lower()
        value = self._value_node(node)
        if value is None:
            return
        if value.type in ("object_creation_expression", "implicit_object_creation_expression"):
            tname = _type_name(value.child_by_field_name("type"))
            if tname == "StringBuilder":
                self.builders.add(name)
                args = _args(value)
                first = self._eval(args[0], consume=True) if args else None
                tracker.assign(name, first or empty(_line(node)), _line(node), cond, append=False)
                return
            if tname:
                self.var_types[name] = tname
        b = self._eval(value)
        self._walk_value(value, as_value=b is not None)
        if b is not None:
            tracker.assign(name, b, _line(node), cond, append=False)

    def _walk_value(self, value: Node, as_value: bool) -> None:
        """Calls inside a right-hand side; a string value is tracked, not reported here."""
        if as_value:
            for n in _iter(value, {"invocation_expression", "object_creation_expression"}):
                (self._invocation if n.type == "invocation_expression" else self._creation)(n)
            for n in _iter(
                value,
                {
                    "string_literal",
                    "verbatim_string_literal",
                    "raw_string_literal",
                    "interpolated_string_expression",
                },
            ):
                self._literal(n, sql=False)
        else:
            self._walk(value)

    def _assignment(self, node: Node) -> None:
        left = node.child_by_field_name("left")
        right = node.child_by_field_name("right")
        op = _txt(node.child_by_field_name("operator"))
        if left is None or right is None:
            return
        line = _line(node)
        lname = _txt(left).removeprefix("this.")
        last = lname.rsplit(".", 1)[-1]
        if last == "CommandText":
            b = self._eval(right, consume=True)
            if b is not None:
                self._emit(b, "ado.net", line, strict=False, proc_ok=True)
            self._walk_value(right, as_value=True)
            return
        if last == "ConnectionString":
            b = self._eval(right)
            if b is not None:
                add_connection(self.facts, b.text, self.path, line, "ado.net")
            return
        b = self._eval(right)
        self._walk_value(right, as_value=b is not None)
        if not re.match(r"^[A-Za-z_]\w*$", lname):
            return
        name = lname.lower()
        tr = self._tracker()
        cond = self._cond(node)
        if op == "+=" and b is not None:
            tr.assign(name, b, line, cond, append=tr.get(name) is not None)
            return
        if op != "=":
            return
        if (
            right.type == "object_creation_expression"
            and _type_name(right.child_by_field_name("type")) == "StringBuilder"
        ):
            self.builders.add(name)
            args = _args(right)
            first = self._eval(args[0], consume=True) if args else None
            tr.assign(name, first or empty(line), line, cond, append=False)
            return
        # sql = sql + " AND ...": an append
        if right.type == "binary_expression" and tr.get(name) is not None:
            leftmost = right
            while leftmost.type == "binary_expression":
                leftmost = leftmost.child_by_field_name("left")  # type: ignore[assignment]
            if leftmost is not None and _txt(leftmost).lower() == name:
                rest = self._eval_without_head(right, name)
                if rest is not None:
                    tr.assign(name, rest, line, cond, append=True)
                return
        if b is not None:
            tr.assign(name, b, line, cond, append=False)
        elif tr.get(name) is not None and not cond:
            tr.current.pop(name, None)

    def _eval_without_head(self, node: Node, name: str) -> Built | None:
        """``a + b + c`` where ``a`` is the variable itself: the text of ``b + c``."""
        parts: list[Node] = []
        n = node
        while n.type == "binary_expression" and _txt(n.child_by_field_name("operator")) == "+":
            parts.append(n.child_by_field_name("right"))  # type: ignore[arg-type]
            n = n.child_by_field_name("left")  # type: ignore[assignment]
        parts.reverse()
        before = self._tracker().get(name)
        out = empty(_line(node))
        known = False
        for p in parts:
            v = self._eval(p)
            if v is None:
                v = hole((before.text if before else "") + out.text, _line(p))
            else:
                known = True
            out = concat(out, v)
        if not known:
            return None
        out.how.add("concatenation")
        return out

    def _invocation(self, node: Node) -> None:
        recv, name = _callee(node)
        args = _args(node)
        line = _line(node)
        rtext = _txt(recv)
        rname = rtext.removeprefix("this.").lower()
        handled = False
        # StringBuilder chains: sb.Append(a).Append(b)
        chain: list[Node] = []
        n: Node | None = node
        while n is not None and n.type == "invocation_expression":
            chain.append(n)
            r, _ = _callee(n)
            n = r
        base = _txt(n).removeprefix("this.").lower() if n is not None else ""
        if base in self.builders:
            for call in reversed(chain):
                _, verb = _callee(call)
                cargs = _args(call)
                v: Built | None = None
                if verb == "AppendFormat" and cargs:
                    v = self._format(cargs)
                elif verb in ("Append", "AppendLine") and cargs:
                    v = self._eval(cargs[0])
                if verb == "AppendLine":
                    v = concat(v, lit(" ", line)) if v is not None else lit(" ", line)
                if v is not None and verb in ("Append", "AppendLine", "AppendFormat"):
                    v.how.add("string-builder")
                    self._tracker().assign(base, v, line, self._cond(node), append=True)
            return
        if args and (name in _EF_RAW or name in _DAPPER):
            first = args[0]
            b = self._eval(first, consume=True)
            if b is not None:
                source = "ef-raw" if name in _EF_RAW else "dapper"
                sp = _named_arg(node, "commandType")
                if sp is not None and "StoredProcedure" in _txt(sp):
                    self._emit(b, source, line, strict=False, proc_ok=True, force_proc=True)
                else:
                    self._emit(b, source, line, strict=False, proc_ok=False)
                handled = True
        if (
            recv is not None
            and recv.type == "member_access_expression"
            and (name in _EF_READ or name in _EF_WRITE)
        ):
            ctx = _txt(recv.child_by_field_name("expression"))
            prop = _txt(recv.child_by_field_name("name"))
            if prop in self.dbsets or _CONTEXT_VAR.match(ctx):
                self._orm("SELECT" if name in _EF_READ else _EF_WRITE[name], prop, name, line)
                handled = True
        elif name in _EF_WRITE and _CONTEXT_VAR.match(rtext) and args:
            pass  # context.Add(entity): the entity type is not known here
        if name in ("Show", "ShowDialog"):
            target = None
            if recv is not None and recv.type == "object_creation_expression":
                target = _type_name(recv.child_by_field_name("type"))
            elif rname in self.var_types:
                target = self.var_types[rname]
            if target and target not in ("MessageBox", "Form"):
                self._navigate(target, line)
        if rtext in ("RecurringJob",) and name == "AddOrUpdate":
            self._hangfire(node, args, line)
        if name in ("WithCronSchedule", "CronSchedule"):
            self._quartz(node, args, line)
        if rtext == "File" and args and (name in _FILE_READ or name in _FILE_WRITE):
            b = self._eval(args[0])
            if b is not None and b.text.strip("? "):
                self._file(
                    b.text, "outbound" if name in _FILE_WRITE else "inbound", line, f"File.{name}"
                )
        fn = node.child_by_field_name("function")
        if fn is not None and fn.type == "member_access_expression":
            self._walk(fn.child_by_field_name("expression") or fn)  # receivers: chained calls
        for a in args:
            if handled:
                self._walk_value(a, as_value=True)
            else:
                self._walk(a)
                self._uses(a, line)

    def _creation(self, node: Node) -> None:
        tname = _type_name(node.child_by_field_name("type"))
        args = _args(node)
        line = _line(node)
        if _COMMAND_TYPES.match(tname):
            if args:
                b = self._eval(args[0], consume=True)
                if b is not None:
                    self._emit(b, "ado.net", line, strict=False, proc_ok=True)
            init = next((c for c in node.children if c.type == "initializer_expression"), None)
            for a in init.named_children if init is not None else []:
                if (
                    a.type == "assignment_expression"
                    and _txt(a.child_by_field_name("left")) == "CommandText"
                ):
                    b = self._eval(a.child_by_field_name("right"), consume=True)
                    if b is not None:
                        self._emit(b, "ado.net", line, strict=False, proc_ok=True)
            for a in args:
                self._walk_value(a, as_value=True)
            return
        if tname in ("StreamReader", "StreamWriter", "FileStream") and args:
            b = self._eval(args[0])
            if (
                b is not None
                and b.text.strip("? ")
                and ("\\" in b.text or "/" in b.text or "." in b.text)
            ):
                out = tname == "StreamWriter" or (
                    tname == "FileStream"
                    and re.search(r"FileMode\.(Create|Append|OpenOrCreate|CreateNew)", _txt(node))
                )
                self._file(b.text, "outbound" if out else "inbound", line, f"new {tname}")
        if tname == "MessageQueue" and args:
            b = self._eval(args[0])
            if b is not None:
                self.facts.add(
                    "integrations",
                    integration(
                        type="MQ",
                        target=b.text,
                        direction="unknown",
                        file=self.path,
                        line=line,
                        protocol="MSMQ",
                    ),
                )
        if tname == "SmtpClient" and args:
            b = self._eval(args[0])
            if b is not None and re.match(r"^[\w.-]+$", b.text):
                self.facts.add(
                    "integrations",
                    integration(
                        type="SMTP", target=b.text, direction="outbound", file=self.path, line=line
                    ),
                )
        if (
            tname
            and tname not in ("StringBuilder", "List", "Dictionary", "Exception")
            and "." not in _txt(node.child_by_field_name("type"))
        ):
            self.facts.ref(
                kind="uses",
                source=f"file:{self.path}",
                source_label=self.path,
                target=tname,
                target_kinds=["dotnet-class", "vb-class"],
                file=self.path,
                line=line,
                report_unresolved=False,
            )
        for c in node.children:
            if c.type in ("argument_list", "initializer_expression"):
                self._walk(c)

    def _uses(self, arg: Node, line: int) -> None:
        """SQL handed to a procedure of the application: ``Run(sql)``, ``Exec("SELECT ...")``."""
        if arg.type == "identifier":
            name = _txt(arg).lower()
            v = self._tracker().get(name)
            if (
                v is not None
                and name not in self._consumed()
                and looks_like_sql(v.text.strip(), strict=True)
            ):
                self._consume(name, v)
                self._emit(v, "cs-string", line, strict=True, proc_ok=False)
        elif arg.type in ("binary_expression", "interpolated_string_expression"):
            b = self._eval(arg)
            if b is not None:
                self._emit(b, "cs-string", line, strict=True, proc_ok=False)

    def _literal(self, node: Node, sql: bool = True) -> None:
        line = _line(node)
        s = _string(node)
        if s is None:  # interpolated: its fixed text
            s = "".join(_txt(c) for c in node.children if c.type == "string_content")
        if "://" in s and node.start_byte not in self.url_seen:
            self.url_seen.add(node.start_byte)
            for u in _URL.finditer(s):
                c = classify_url(u.group(0), soap_hint=self.soap)
                if c:
                    self.facts.add(
                        "integrations",
                        integration(
                            type=c[0], target=c[1], direction="outbound", file=self.path, line=line
                        ),
                    )
        elif ";" in s and "=" in s:
            add_connection(self.facts, s, self.path, line, "ado.net")
        if sql and looks_like_sql(s.strip(), strict=True):
            p = node.parent
            call = p.parent.parent if p is not None and p.parent is not None else None
            if (
                call is not None
                and call.type == "invocation_expression"
                and _callee(call)[1]
                in ("Format", "Concat", "Append", "AppendLine", "AppendFormat", "Join")
            ):
                return  # part of a text built elsewhere (reported where it is used)
            if p is not None and p.type in (
                "argument",
                "return_statement",
                "arrow_expression_clause",
            ):
                b = self._eval(node) or lit(s, line)
                self._emit(b, "cs-string", line, strict=True, proc_ok=False)

    # ---- values -------------------------------------------------------------------------------
    def _eval(self, node: Node | None, consume: bool = False) -> Built | None:
        if node is None:
            return None
        t = node.type
        line = _line(node)
        s = _string(node)
        if s is not None:
            return lit(s, line)
        if t == "interpolated_string_expression":
            out = empty(line)
            for c in node.children:
                if c.type == "string_content":
                    out = concat(out, lit(_txt(c).replace("{{", "{").replace("}}", "}"), line))
                elif c.type == "escape_sequence":
                    out = concat(
                        out, lit(" " if _txt(c) in ("\\n", "\\r", "\\t") else _txt(c)[1:], line)
                    )
                elif c.type == "interpolation":
                    inner = next(
                        (
                            x
                            for x in c.named_children
                            if x.type
                            not in (
                                "interpolation_brace",
                                "interpolation_alignment_clause",
                                "interpolation_format_clause",
                            )
                        ),
                        None,
                    )
                    v = self._eval(inner, consume) if inner is not None else None
                    out = concat(out, v if v is not None else hole(out.text, line))
            out.how.add("interpolation")
            return out
        if t == "parenthesized_expression":
            return self._eval(node.named_children[0] if node.named_children else None, consume)
        if t == "binary_expression":
            if _txt(node.child_by_field_name("operator")) != "+":
                return None
            a = self._eval(node.child_by_field_name("left"), consume)
            b = self._eval(node.child_by_field_name("right"), consume)
            if a is None and b is None:
                return None
            if a is None:
                a = hole("", line)
            if b is None:
                b = hole(a.text, line)
            out = concat(a, b)
            out.how.add("concatenation")
            return out
        if t == "conditional_expression":
            x = self._eval(node.child_by_field_name("consequence"), consume)
            y = self._eval(node.child_by_field_name("alternative"), consume)
            if x is not None and y is not None:
                return choice(x, y)
            return x or y
        if t == "identifier" or (
            t == "member_access_expression" and _txt(node).startswith("this.")
        ):
            name = _txt(node).removeprefix("this.").lower()
            v = self._tracker().get(name)
            if v is not None and consume:
                self._consume(name, v)
            return v
        if t == "member_access_expression" and _NEWLINE.match(_txt(node)):
            return lit(" ", line)
        if t == "invocation_expression":
            recv, name = _callee(node)
            r = _txt(recv)
            if name == "ToString" and r.removeprefix("this.").lower() in self.builders:
                key = r.removeprefix("this.").lower()
                v = self._tracker().get(key)
                if v is not None and consume:
                    self._consume(key, v)
                return v
            if name == "Format" and r in ("string", "String"):
                return self._format(_args(node))
            if name == "Concat" and r in ("string", "String"):
                out = empty(line)
                known = False
                for arg in _args(node):
                    part = self._eval(arg, consume)
                    known = known or part is not None
                    out = concat(out, part if part is not None else hole(out.text, line))
                return out if known else None
        return None

    def _format(self, args: list[Node]) -> Built | None:
        if not args:
            return None
        fmt = self._eval(args[0])
        if fmt is None:
            return None
        values = [self._eval(a) for a in args[1:]]
        line = fmt.line
        out = empty(line)
        pos = 0
        for m in re.finditer(r"\{(\d+)(?:[,:][^}]*)?\}", fmt.text):
            out = concat(out, lit(fmt.text[pos : m.start()], line))
            k = int(m.group(1))
            v = values[k] if k < len(values) else None
            out = concat(out, v if v is not None else hole(out.text, line))
            pos = m.end()
        out = concat(out, lit(fmt.text[pos:], line))
        out.how.add("string-format")
        return out

    # ---- results ------------------------------------------------------------------------------
    def _emit(
        self,
        b: Built,
        source: str,
        line: int,
        strict: bool,
        proc_ok: bool,
        force_proc: bool = False,
    ) -> None:
        text = b.text.strip()
        if (
            proc_ok
            and (self.stored_proc or force_proc)
            and _PROC_NAME.match(text)
            and not looks_like_sql(text)
        ):
            text = f"CALL {text}"
            b = Built(text, b.line, lines=set(b.lines))
        if not looks_like_sql(text, strict=strict):
            return
        key = (line, text)
        if key in self.sql_seen:
            return
        self.sql_seen.add(key)
        fact = sql_fact(
            file=self.path,
            line=line,
            text=text,
            source=source,
            dialect=self.dialect,
            dynamic=b.dynamic,
        )
        fill(fact, b)
        self.facts.add("sql_statements", fact)

    def _orm(self, verb: str, entity: str, how: str, line: int) -> None:
        text = {
            "SELECT": f"SELECT /* {how} */ FROM {entity}",
            "INSERT": f"INSERT /* {how} */ INTO {entity}",
            "UPDATE": f"UPDATE {entity} /* {how} */",
            "DELETE": f"DELETE /* {how} */ FROM {entity}",
        }[verb]
        if (line, text) in self.sql_seen:
            return
        self.sql_seen.add((line, text))
        fact = sql_fact(
            file=self.path, line=line, text=text, source="entity-framework", dialect=None, hql=True
        )
        fact.type = verb
        fact.parsed = False
        fact.reason = f"Entity Framework {how}: the ORM generates the SQL at run time"
        self.facts.add("sql_statements", fact)

    def _leftovers(self, tr: Tracker, consumed: set[str], inherited: Tracker | None = None) -> None:
        for name, b in tr.current.items():
            if name in consumed or (inherited is not None and inherited.get(name) is b):
                continue
            self._emit(b, "cs-string", b.line, strict=True, proc_ok=False)

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

    def _entry(self, name: str, kind: str, line: int, detail: str) -> None:
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

    def _navigate(self, target: str, line: int) -> None:
        self.facts.ref(
            kind="forwards",
            source=f"screen:{self.screen_id}" if self.screen_id else f"file:{self.path}",
            source_label=self.path,
            target=target,
            target_kinds=["desktop-form"],
            file=self.path,
            line=line,
            report_unresolved=False,
        )

    def _file(self, target: str, direction: str, line: int, how: str) -> None:
        self.facts.add(
            "integrations",
            integration(
                type="FILE",
                target=target.strip(),
                direction=direction,
                file=self.path,
                line=line,
                protocol=how,
            ),
        )

    def _hangfire(self, node: Node, args: list[Node], line: int) -> None:
        name = _string(args[0]) if args and _string(args[0]) is not None else None
        rest = args[1:] if name is not None else args
        job = rest[0] if rest else None
        cron = _cron_text(rest[1]) if len(rest) > 1 else None
        handler = _txt(job).split("=>", 1)[-1].strip()[:80] if job is not None else ""
        self._job(name or handler or f"job:{line}", cron, handler, line, "Hangfire RecurringJob")

    def _quartz(self, node: Node, args: list[Node], line: int) -> None:
        stmt = node
        while stmt.parent is not None and stmt.parent.type not in ("block", "global_statement"):
            stmt = stmt.parent
        ident = re.search(r"WithIdentity\(\s*\"([^\"]+)\"", _txt(stmt))
        cron = _cron_text(args[0]) if args else None
        self._job(
            ident.group(1) if ident else f"quartz:{line}",
            cron,
            "",
            line,
            "Quartz.NET cron trigger",
            scheduler="quartz.net",
        )

    def _job(
        self,
        name: str,
        schedule: str | None,
        handler: str,
        line: int,
        how: str,
        scheduler: str = "hangfire",
    ) -> None:
        jid = make_id("batch_job", scheduler, self.path, name)
        self.facts.add(
            "batch_jobs",
            BatchJob(
                id=jid,
                name=name,
                scheduler=scheduler,
                description=f"Scheduled in the code with {how}",
                schedule=schedule,
                command=self.path,
                programs=[handler] if handler else [],
                evidence=[Evidence(file=self.path, line=line, snippet=how)],
            ),
        )
        self.facts.symbol("cron-job", name, self.path, line, owner=jid)


def _iter(node: Node, kinds: set[str]) -> list[Node]:
    out = []
    stack = [node]
    while stack:
        n = stack.pop()
        if n.type in kinds:
            out.append(n)
            continue
        stack.extend(reversed(n.children))
    return out


def _copy(tr: Tracker) -> Tracker:
    out = Tracker()
    out.current = dict(tr.current)
    out.history = {k: list(v) for k, v in tr.history.items()}
    return out


def _designer_fields(cls: Node) -> int:
    """Input controls the class (or its designer half, ``InitializeComponent``) creates."""
    n = 0
    for c in _iter(cls, {"object_creation_expression"}):
        if _INPUTS.match(_type_name(c.child_by_field_name("type"))):
            n += 1
    return n
