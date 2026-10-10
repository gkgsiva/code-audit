"""Java sources (tree-sitter-java): classes, methods, annotations, imports, entry points,
SQL in strings (JDBC, JdbcTemplate, Hibernate/JPA, Spring Data), endpoints (HTTP/SOAP, sockets,
FTP, MQ/JMS) and services exposed by annotations (Spring MVC, JAX-WS, JAX-RS, EJB, Struts 2).

Spring and Java EE annotations are handled here so each Java file is parsed once; Spring XML
configuration is handled by the ``spring`` analyzer.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field, replace
from typing import ClassVar

import tree_sitter_java
from tree_sitter import Language, Node, Parser

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import tech
from legacy_audit.analyzers.dynsql import fill
from legacy_audit.analyzers.integrations import classify_url, integration, parse_jdbc
from legacy_audit.analyzers.sqltools import hole_kind, looks_like_hql, looks_like_sql, sql_fact
from legacy_audit.model import EntryPoint, Evidence, FileRecord, Service, make_id

_LANG = Language(tree_sitter_java.language())

JDBC_METHODS = {
    "prepareStatement": "jdbc",
    "prepareCall": "jdbc",
    "executeQuery": "jdbc",
    "executeUpdate": "jdbc",
    "execute": "jdbc",
    "addBatch": "jdbc",
    "nativeSQL": "jdbc",
}
TEMPLATE_METHODS = {
    "query",
    "queryForObject",
    "queryForList",
    "queryForMap",
    "queryForRowSet",
    "queryForInt",
    "queryForLong",
    "update",
    "batchUpdate",
    "execute",
    "call",
}
JMS_TEMPLATE_METHODS = {
    "send": "producer",
    "convertAndSend": "producer",
    "receive": "consumer",
    "receiveAndConvert": "consumer",
    "receiveSelected": "consumer",
}
HQL_METHODS = {"createQuery": "hql", "find": "hql", "iterate": "hql", "bulkUpdate": "hql"}
NATIVE_METHODS = {"createSQLQuery": "native", "createNativeQuery": "native"}

MAPPING_ANNOTATIONS = {
    "RequestMapping": None,
    "GetMapping": "GET",
    "PostMapping": "POST",
    "PutMapping": "PUT",
    "DeleteMapping": "DELETE",
    "PatchMapping": "PATCH",
}
JAXRS_METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"}
EJB_LIFECYCLE = {
    "ejbCreate",
    "ejbRemove",
    "ejbActivate",
    "ejbPassivate",
    "ejbLoad",
    "ejbStore",
    "setSessionContext",
    "setEntityContext",
    "unsetEntityContext",
    "setMessageDrivenContext",
}
SPRING_DATA_BASES = {
    "JpaRepository",
    "CrudRepository",
    "PagingAndSortingRepository",
    "Repository",
    "MongoRepository",
    "JpaSpecificationExecutor",
    "ListCrudRepository",
}
SOAP_IMPORTS = (
    "org.apache.axis",
    "javax.xml.rpc",
    "javax.xml.ws",
    "jakarta.xml.ws",
    "org.springframework.ws",
    "org.apache.cxf",
)
MQ_IMPORTS = ("com.ibm.mq", "javax.jms", "jakarta.jms", "org.springframework.jms")
JAVAX_EE = re.compile(
    r"^javax\.(servlet|ejb|persistence|jms|mail|faces|ws|xml\.ws|xml\.bind|annotation|"
    r"transaction|validation|inject|enterprise|resource|security\.jacc|websocket)\b"
)


@dataclass
class _Expr:
    """A string value rebuilt from code. ``text`` includes every fragment (optional ones too);
    ``minimal`` leaves out fragments added only on some code paths (inside if/loop/switch)."""

    node: Node
    text: str
    dynamic: bool
    line: int
    source: str | None = None
    hql: bool = False
    minimal: str | None = None
    value_holes: int = 0
    structural_holes: int = 0
    fragments: int = 1
    optional: int = 0
    how: set[str] = field(default_factory=set)
    lines: set[int] = field(default_factory=set)
    variants: list[str] = field(default_factory=list)

    @property
    def base(self) -> str:
        return self.text if self.minimal is None else self.minimal


MAX_VARIANTS = 5
STRING_METHODS = {"format", "formatted", "valueOf", "concat", "append", "toString", "trim"}
_FORMAT_SPEC = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])")
COND_STATEMENTS = {
    "if_statement",
    "while_statement",
    "for_statement",
    "enhanced_for_statement",
    "do_statement",
    "switch_expression",
    "switch_statement",
    "catch_clause",
    "lambda_expression",
}
BUILDER_TYPES = {"StringBuilder", "StringBuffer", "StringJoiner"}


def _hole(node: Node, before: str) -> _Expr:
    kind = hole_kind(before)
    line = node.start_point[0] + 1
    return _Expr(
        node, "?", True, line,
        value_holes=1 if kind == "value" else 0,
        structural_holes=1 if kind == "structural" else 0,
        fragments=0, lines={line},
    )  # fmt: skip


def _concat(a: _Expr, b: _Expr, node: Node, optional: bool = False) -> _Expr:
    return _Expr(
        node=node,
        text=a.text + b.text,
        dynamic=a.dynamic or b.dynamic or optional,
        line=a.line,
        source=a.source or b.source,
        hql=a.hql or b.hql,
        minimal=a.base + ("" if optional else b.base),
        value_holes=a.value_holes + b.value_holes,
        structural_holes=a.structural_holes + b.structural_holes,
        fragments=a.fragments + b.fragments,
        optional=a.optional + b.optional + (b.fragments if optional else 0),
        how=a.how | b.how | ({"conditional"} if optional else set()),
        lines=a.lines | b.lines,
        # Variants are whole alternative texts: combine each side's alternatives with the other.
        variants=[*(v + b.text for v in a.variants), *(a.text + v for v in b.variants)][
            :MAX_VARIANTS
        ],
    )


@dataclass
class _ClassInfo:
    name: str
    fqn: str
    line: int
    annotations: dict[str, dict[str, str]] = field(default_factory=dict)
    supertypes: list[str] = field(default_factory=list)
    is_interface: bool = False


def _unquote(raw: str) -> str:
    if raw.startswith('"""'):
        return raw[3:-3]
    body = raw[1:-1]
    return body.replace('\\"', '"').replace("\\n", " ").replace("\\t", " ").replace("\\\\", "\\")


def _txt(node: Node | None) -> str:
    return node.text.decode("utf-8", "replace") if node is not None and node.text else ""


class JavaAnalyzer:
    name: ClassVar[str] = "java"
    version: ClassVar[str] = "3"

    def __init__(self) -> None:
        self.parser = Parser(_LANG)

    def supports(self, file: FileRecord) -> bool:
        return file.language == "java"

    # ------------------------------------------------------------------------------------------
    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        text = ctx.text or ""
        facts = Facts()
        facts.coverage.lines = text.count("\n") + 1
        tree = self.parser.parse(text.encode("utf-8"))
        root = tree.root_node
        if root.has_error:
            self._errors(root, facts)
        _Visitor(file, ctx, facts).run(root)
        return facts

    def _errors(self, root: Node, facts: Facts) -> None:
        stack = [root]
        while stack:
            n = stack.pop()
            if n.type == "ERROR" or n.is_missing:
                facts.coverage.gap(n.start_point[0] + 1, n.end_point[0] + 1, "Java syntax error")
                continue
            if n.has_error:
                stack.extend(n.children)


class _Visitor:
    def __init__(self, file: FileRecord, ctx: AnalyzerContext, facts: Facts) -> None:
        self.file = file
        self.path = file.path
        self.ctx = ctx
        self.facts = facts
        self.package = ""
        self.imports: list[str] = []
        self.classes: list[_ClassInfo] = []
        self.constants: dict[str, _Expr] = {}  # field name -> expression
        self.exprs: dict[int, _Expr] = {}  # start_byte -> top-level string expression
        self.var_keys: dict[str, int] = {}  # local variable -> key of its value in exprs
        # Values used only as a piece of a larger statement (a constant prefix, a literal in a
        # concatenation) are not statements of their own unless a SQL call uses them directly.
        self.used_as_fragment: set[int] = set()
        self.type_names: set[str] = set()
        self.dialect = ctx.config.sql.dialect

    # ---- driving ------------------------------------------------------------------------------
    def _type_references(self, root: Node) -> None:
        """Types named anywhere (fields, parameters, extends, casts) and classes used as static
        receivers (``Factory.get()``): same-package classes need no import, so this is how
        the linker sees them."""
        stack = [root]
        while stack:
            n = stack.pop()
            if n.type == "type_identifier":
                self.type_names.add(_txt(n))
            elif n.type in ("method_invocation", "field_access"):
                obj = n.child_by_field_name("object")
                if obj is not None and obj.type == "identifier" and _txt(obj)[:1].isupper():
                    self.type_names.add(_txt(obj))
            stack.extend(n.children)

    def run(self, root: Node) -> None:
        for child in root.children:
            if child.type == "package_declaration":
                self.package = _txt(child.named_children[0]) if child.named_children else ""
            elif child.type == "import_declaration":
                self.imports.append(_txt(child).replace("import", "", 1).strip(" ;"))
        for child in root.children:
            if child.type in (
                "class_declaration",
                "interface_declaration",
                "enum_declaration",
                "record_declaration",
                "annotation_type_declaration",
            ):
                self._class(child, prefix=self.package)
        self._type_references(root)
        self._imports_and_signals()
        self._emit_sql()
        self._class_refs()

    # ---- classes ----------------------------------------------------------------------------
    def _class(self, node: Node, prefix: str, outer: _ClassInfo | None = None) -> None:
        name = _txt(node.child_by_field_name("name"))
        fqn = f"{prefix}.{name}" if prefix else name
        info = _ClassInfo(
            name=name,
            fqn=fqn,
            line=node.start_point[0] + 1,
            is_interface=node.type == "interface_declaration",
        )
        mods = next((c for c in node.children if c.type == "modifiers"), None)
        info.annotations = self._annotations(mods)
        for fld in ("superclass", "interfaces"):
            sup = node.child_by_field_name(fld)
            if sup is not None:
                info.supertypes += [_txt(t).split("<")[0] for t in self._type_ids(sup)]
        for c in node.children:
            if c.type == "extends_interfaces":
                info.supertypes += [_txt(t).split("<")[0] for t in self._type_ids(c)]
        self.classes.append(info)
        self.facts.symbol("java-class", fqn, self.path, info.line)
        self._class_level(info)
        body = node.child_by_field_name("body")
        if body is None:
            return
        # Fields first: constants are referenced by methods that may come earlier.
        for member in body.children:
            if member.type in ("field_declaration", "constant_declaration"):
                self._field(member)
        for member in body.children:
            if member.type in ("method_declaration", "constructor_declaration"):
                self._method(member, info)
            elif member.type in (
                "class_declaration",
                "interface_declaration",
                "enum_declaration",
                "record_declaration",
            ):
                self._class(member, prefix=fqn, outer=info)
            elif member.type not in ("field_declaration", "constant_declaration"):
                self._scan(member, None)

    def _type_ids(self, node: Node) -> list[Node]:
        out = []
        stack = [node]
        while stack:
            n = stack.pop()
            if n.type in ("type_identifier", "scoped_type_identifier"):
                out.append(n)
                continue
            stack.extend(reversed(n.children))
        return out

    def _annotations(self, mods: Node | None) -> dict[str, dict[str, str]]:
        out: dict[str, dict[str, str]] = {}
        if mods is None:
            return out
        for a in mods.children:
            if a.type not in ("annotation", "marker_annotation"):
                continue
            aname = _txt(a.child_by_field_name("name")).rsplit(".", 1)[-1]
            args: dict[str, str] = {}
            arglist = a.child_by_field_name("arguments")
            if arglist is not None:
                for arg in arglist.named_children:
                    if arg.type == "element_value_pair":
                        key = _txt(arg.child_by_field_name("key"))
                        args[key] = self._annotation_value(arg.child_by_field_name("value"))
                    else:
                        args["value"] = self._annotation_value(arg)
            out[aname] = args
        return out

    def _annotation_value(self, node: Node | None) -> str:
        if node is None:
            return ""
        if node.type == "element_value_array_initializer":
            vals = [self._annotation_value(c) for c in node.named_children]
            return vals[0] if vals else ""
        e = self._eval(node)
        return e.text if e else _txt(node)

    def _class_level(self, info: _ClassInfo) -> None:
        ann = info.annotations
        line = info.line
        if "SpringBootApplication" in ann:
            self._entry(info.fqn, "spring-boot-app", line)
            self.facts.add("tech_stack", tech("Spring Boot", None, "framework", self.path, line))
        if "Entity" in ann:
            table = ann.get("Table", {}).get("name") or info.name
            self.facts.symbol(
                "jpa-entity",
                info.name,
                self.path,
                line,
                data={"table": table.upper(), "fqn": info.fqn},
            )
        sup = {s.rsplit(".", 1)[-1] for s in info.supertypes}
        if sup & {"Job", "StatefulJob"} and any("quartz" in i for i in self.imports):
            self._entry(info.fqn, "scheduler", line, "Quartz job")
        if "TimerTask" in sup:
            self._entry(info.fqn, "scheduler", line, "java.util.TimerTask")
        if "MessageListener" in sup or "MessageDriven" in ann:
            self._entry(info.fqn, "message-listener", line)
        if info.is_interface and sup & SPRING_DATA_BASES:
            self.facts.symbol("spring-data-repository", info.fqn, self.path, line)
        if sup & {"HttpServlet", "GenericServlet"}:
            self.facts.symbol("servlet-class", info.fqn, self.path, line)

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

    # ---- members ------------------------------------------------------------------------------
    def _field(self, node: Node) -> None:
        mods = next((c for c in node.children if c.type == "modifiers"), None)
        is_static = mods is not None and "static" in _txt(mods)
        for decl in node.children:
            if decl.type != "variable_declarator":
                continue
            value = decl.child_by_field_name("value")
            name = _txt(decl.child_by_field_name("name"))
            if value is not None:
                e = self._top_expr(value)
                if e is not None:
                    self.constants[name] = e
                else:
                    self._scan(value, None)
            if is_static:
                self.type_names.add(name)

    def _method(self, node: Node, cls: _ClassInfo) -> None:
        name = _txt(node.child_by_field_name("name"))
        line = node.start_point[0] + 1
        mods = next((c for c in node.children if c.type == "modifiers"), None)
        ann = self._annotations(mods)
        mods_text = _txt(mods) if mods is not None else ""
        params = _txt(node.child_by_field_name("parameters"))
        if name == "main" and "static" in mods_text and "String" in params:
            self._entry(cls.fqn, "main", line)
            self.facts.symbol("java-main", cls.fqn, self.path, line)
        if "Scheduled" in ann:
            self._entry(f"{cls.fqn}.{name}", "scheduler", line, "@Scheduled")
        self._services(cls, name, line, ann, mods_text, node)
        for k in ("Query", "NamedQuery", "NamedNativeQuery"):
            q = ann.get(k, {}).get("value") or ann.get(k, {}).get("query")
            if q:
                native = ann[k].get("nativeQuery") == "true" or k == "NamedNativeQuery"
                self._sql(q, line, "native" if native else "jpql", hql=not native)
        bases = {s.rsplit(".", 1)[-1] for s in cls.supertypes}
        derived = re.match(r"(find|read|get|query|count|delete|exists|stream)\w*By", name)
        if cls.is_interface and bases & SPRING_DATA_BASES and "Query" not in ann and derived:
            self._derived_query(name, line, cls)
        body = node.child_by_field_name("body")
        if body is not None:
            local: dict[str, _Expr] = {}
            self.var_keys = {}
            self._scan(body, local, cls=cls)

    def _services(
        self,
        cls: _ClassInfo,
        name: str,
        line: int,
        ann: dict[str, dict[str, str]],
        mods_text: str,
        node: Node,
    ) -> None:
        ca = cls.annotations
        public = "public" in mods_text or cls.is_interface
        handler = f"{cls.fqn}.{name}"
        # Spring MVC
        if {"Controller", "RestController"} & set(ca):
            for a, verb in MAPPING_ANNOTATIONS.items():
                if a in ann:
                    base = ca.get("RequestMapping", {}).get("value") or ca.get(
                        "RequestMapping", {}
                    ).get("path", "")
                    sub = ann[a].get("value") or ann[a].get("path", "")
                    method = verb or ann[a].get("method", "").rsplit(".", 1)[-1] or "ANY"
                    url = "/" + "/".join(p.strip("/") for p in (base, sub) if p.strip("/"))
                    self._service(f"{method} {url}", "spring-handler", handler, url, line)
                    self._views(node, f"service:{handler}")
        # JAX-RS
        if "Path" in ca or "Path" in ann:
            verbs = JAXRS_METHODS & set(ann)
            if verbs:
                url = "/" + "/".join(
                    p.strip("/")
                    for p in (
                        ca.get("Path", {}).get("value", ""),
                        ann.get("Path", {}).get("value", ""),
                    )
                    if p.strip("/")
                )
                self._service(f"{sorted(verbs)[0]} {url}", "rest-operation", handler, url, line)
        # JAX-WS
        if (
            "WebService" in ca
            and public
            and name != cls.name
            and ("WebMethod" in ann or not any("WebMethod" in str(x) for x in ca))
            and ann.get("WebMethod", {}).get("exclude") != "true"
        ):
            self._service(f"{cls.name}.{name}", "soap-operation", handler, None, line)
        # EJB (remote business methods)
        sup = {s.rsplit(".", 1)[-1] for s in cls.supertypes}
        if (
            public
            and name not in EJB_LIFECYCLE
            and name != cls.name
            and ("Remote" in ca or "SessionBean" in sup)
            and not cls.is_interface
        ):
            self._service(f"{cls.name}.{name}", "ejb-method", handler, None, line)
        # Struts 2 annotations
        if "Action" in ann and any("struts2" in i for i in self.imports):
            url = ann["Action"].get("value", name)
            self._service(url, "struts-action", handler, url, line)

    def _service(self, name: str, kind: str, handler: str, url: str | None, line: int) -> None:
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

    def _views(self, method: Node, source: str) -> None:
        """View names returned by a Spring handler: ``return "x"`` / ``new ModelAndView("x")``."""
        stack = [method]
        while stack:
            n = stack.pop()
            view = None
            if n.type == "return_statement":
                e = next((c for c in n.named_children), None)
                if e is not None and e.type == "string_literal":
                    view = _unquote(_txt(e))
            elif n.type == "object_creation_expression" and _txt(
                n.child_by_field_name("type")
            ).endswith("ModelAndView"):
                args = n.child_by_field_name("arguments")
                first = args.named_children[0] if args is not None and args.named_children else None
                if first is not None and first.type == "string_literal":
                    view = _unquote(_txt(first))
            if view and not view.startswith(("redirect:", "forward:")):
                self.facts.ref(
                    kind="renders",
                    source=source,
                    source_label=source.split(":", 1)[1],
                    target=view,
                    target_kinds=["view"],
                    file=self.path,
                    line=n.start_point[0] + 1,
                )
            stack.extend(n.children)

    def _derived_query(self, name: str, line: int, cls: _ClassInfo) -> None:
        verb = "DELETE" if name.startswith("delete") else "SELECT"
        m = re.search(r"Repository\s*<\s*(\w+)", self.ctx.text or "")
        entity = m.group(1) if m else ""
        fact = sql_fact(
            file=self.path,
            line=line,
            text=f"{verb} /* derived: {name} */ FROM {entity}",
            source="spring-data",
            dialect=None,
            hql=True,
        )
        fact.type = verb
        fact.parsed = False
        fact.reason = "Spring Data derived query: SQL is generated at runtime"
        self.facts.add("sql_statements", fact)

    # ---- expressions --------------------------------------------------------------------------
    def _eval(self, node: Node, local: dict[str, _Expr] | None = None) -> _Expr | None:
        """Evaluate a string expression: literals, concatenation, constants and (in a method)
        local variables, ``StringBuilder``s, ``String.format``, ternaries. Parts that are not
        known become holes (``?``). None when the expression is not text at all."""
        t = node.type
        line = node.start_point[0] + 1
        if t in ("string_literal", "text_block"):
            return _Expr(node, _unquote(_txt(node)), False, line, lines={line})
        if t == "character_literal":
            return _Expr(node, _unquote(_txt(node)), False, line, lines={line})
        if t == "parenthesized_expression" and node.named_children:
            return self._eval(node.named_children[0], local)
        if t == "binary_expression" and _txt(node.child_by_field_name("operator")) == "+":
            left = node.child_by_field_name("left")
            right = node.child_by_field_name("right")
            le = self._eval(left, local) if left is not None else None
            re_ = self._eval(right, local) if right is not None else None
            if le is None and re_ is None:
                return None
            for part in (le, re_):
                if part is not None:
                    self.used_as_fragment.add(id(part))
            if le is None:
                le = _hole(left or node, "")
            if re_ is None:
                re_ = _hole(right or node, le.text)
            out = _concat(le, re_, node)
            if le.dynamic or re_.dynamic:
                out.how.add("concatenation")
            return out
        if t == "identifier":
            name = _txt(node)
            return (local or {}).get(name) or self.constants.get(name)
        if t == "ternary_expression":
            a = node.child_by_field_name("consequence")
            b = node.child_by_field_name("alternative")
            ea = self._eval(a, local) if a is not None else None
            eb = self._eval(b, local) if b is not None else None
            if ea is None or eb is None:
                return None
            out = _concat(_Expr(node, "", False, line, fragments=0), ea, node)
            out.dynamic = True
            out.how.add("ternary")
            out.variants = [eb.text, *ea.variants, *eb.variants][:MAX_VARIANTS]
            out.fragments = ea.fragments + eb.fragments
            out.lines |= eb.lines
            return out
        if t == "object_creation_expression":
            typ = _txt(node.child_by_field_name("type")).split("<")[0].rsplit(".", 1)[-1]
            if typ in BUILDER_TYPES:
                args = node.child_by_field_name("arguments")
                argv = list(args.named_children) if args is not None else []
                first = self._eval(argv[0], local) if argv and typ != "StringJoiner" else None
                if first is None:
                    return _Expr(node, "", False, line, fragments=0, how={"string-builder"})
                self.used_as_fragment.add(id(first))
                return replace(first, node=node, line=line, how=first.how | {"string-builder"},
                               lines=first.lines | {line})  # fmt: skip
            return None
        if t == "method_invocation":
            return self._eval_call(node, local)
        return None

    def _eval_call(self, node: Node, local: dict[str, _Expr] | None) -> _Expr | None:
        name = _txt(node.child_by_field_name("name"))
        obj = node.child_by_field_name("object")
        args = node.child_by_field_name("arguments")
        argv = list(args.named_children) if args is not None else []
        if name in ("toString", "trim", "strip", "intern") and obj is not None and not argv:
            return self._eval(obj, local)
        if name == "valueOf" and _txt(obj) == "String" and len(argv) == 1:
            return self._eval(argv[0], local)
        if name == "concat" and obj is not None and len(argv) == 1:
            a = self._eval(obj, local)
            if a is None:
                return None
            b = self._eval(argv[0], local) or _hole(argv[0], a.text)
            out = _concat(a, b, node)
            out.how.add("concatenation")
            return out
        fmt_node: Node | None = None
        values: list[Node] = []
        if name == "format" and _txt(obj) == "String" and argv:
            fmt_node, values = argv[0], argv[1:]
        elif name == "formatted" and obj is not None:
            fmt_node, values = obj, argv
        if fmt_node is not None:
            fmt = self._eval(fmt_node, local)
            if fmt is None:
                return None
            return self._format(node, fmt, values, local)
        return None

    def _format(
        self, node: Node, fmt: _Expr, values: list[Node], local: dict[str, _Expr] | None
    ) -> _Expr:
        out = _Expr(node, "", fmt.dynamic, fmt.line, fragments=0, lines=set(fmt.lines))
        pos, k = 0, 0
        for m in _FORMAT_SPEC.finditer(fmt.text):
            lit = fmt.text[pos : m.start()]
            out = _concat(out, _Expr(node, lit, False, fmt.line, fragments=0), node)
            if m.group(1) == "%":
                out = _concat(out, _Expr(node, "%", False, fmt.line, fragments=0), node)
            elif m.group(1) == "n":
                out = _concat(out, _Expr(node, " ", False, fmt.line, fragments=0), node)
            else:
                v = self._eval(values[k], local) if k < len(values) else None
                out = _concat(out, v or _hole(values[k] if k < len(values) else node, out.text),
                              node)  # fmt: skip
                k += 1
            pos = m.end()
        out = _concat(out, _Expr(node, fmt.text[pos:], False, fmt.line, fragments=0), node)
        out.fragments = max(out.fragments, 1)
        out.how |= {"string-format"}
        return out

    def _top_expr(self, node: Node, local: dict[str, _Expr] | None = None) -> _Expr | None:
        e = self._eval(node, local)
        if e is None or not e.text.strip():
            return None
        if local and any(e is v for v in local.values()):
            return e  # a tracked variable: already registered where it was declared
        self.exprs[node.start_byte] = e
        return e

    def _track(self, local: dict[str, _Expr], name: str, e: _Expr, key: int | None) -> None:
        """Remember a local string/builder variable; its latest value replaces the earlier one
        in the list of expressions (keyed where the variable got its first value)."""
        local[name] = e
        if key is None:
            key = self.var_keys.get(name, e.node.start_byte)
        self.var_keys[name] = key
        if e.text.strip():
            self.exprs[key] = e
        else:
            self.exprs.pop(key, None)

    def _update(self, local: dict[str, _Expr], name: str, new: _Expr, cond: bool,
                append: bool) -> None:  # fmt: skip
        prev = local.get(name)
        if prev is None:
            self._track(local, name, new, new.node.start_byte)
            return
        if append:
            out = _concat(prev, new, new.node, optional=cond)
        elif not cond:
            out = new  # plain reassignment replaces the value
        else:  # reassigned on some paths only: keep both
            out = _concat(prev, _Expr(new.node, "", False, prev.line, fragments=0), prev.node)
            out.dynamic = True
            out.how |= {"reassignment"}
            out.variants = [*prev.variants, new.text][:MAX_VARIANTS]
            out.fragments += new.fragments
            out.lines |= new.lines
        out.node, out.line = prev.node, prev.line
        out.source = out.source or prev.source
        self._track(local, name, out, None)

    def _statement(self, n: Node, local: dict[str, _Expr], cond: bool) -> bool:
        """String building statements (``sql += ...``, ``sb.append(...)``, ``s = ...``).
        True when handled (its children need no further scan)."""
        expr = n.named_children[0] if n.named_children else None
        if expr is None:
            return False
        if expr.type == "assignment_expression":
            left = expr.child_by_field_name("left")
            right = expr.child_by_field_name("right")
            op = _txt(expr.child_by_field_name("operator"))
            if left is None or right is None or left.type != "identifier":
                return False
            name = _txt(left)
            prev = local.get(name)
            value = self._eval(right, local)
            if value is None:
                if prev is None:
                    return False
                value = _hole(right, prev.text)
            if op == "+=":
                self._update(local, name, value, cond, append=True)
            elif op == "=":
                if prev is not None and value is not prev and value.text.startswith(prev.text) \
                        and prev.text and value.lines >= prev.lines:  # fmt: skip
                    # s = s + "..." is an append of the rest
                    delta = _Expr(
                        right, value.text[len(prev.text) :], value.dynamic, value.line,
                        value_holes=value.value_holes - prev.value_holes,
                        structural_holes=value.structural_holes - prev.structural_holes,
                        fragments=max(value.fragments - prev.fragments, 1),
                        how=value.how, lines=value.lines - prev.lines or {value.line},
                    )  # fmt: skip
                    self._update(local, name, delta, cond, append=True)
                else:
                    self._update(local, name, value, cond, append=False)
            else:
                return False
            return True
        if expr.type == "method_invocation":
            chain: list[Node] = []
            cur: Node | None = expr
            while cur is not None and cur.type == "method_invocation":
                chain.append(cur)
                cur = cur.child_by_field_name("object")
            if cur is None or cur.type != "identifier" or _txt(cur) not in local:
                return False
            names = [_txt(c.child_by_field_name("name")) for c in chain]
            if not all(x in ("append", "add", "setLength") for x in names):
                return False
            name = _txt(cur)
            for call in reversed(chain):  # innermost first
                cname = _txt(call.child_by_field_name("name"))
                args = call.child_by_field_name("arguments")
                argv = list(args.named_children) if args is not None else []
                if cname == "setLength":
                    if not cond:
                        empty = _Expr(call, "", False, call.start_point[0] + 1, fragments=0)
                        empty.how = {"string-builder"}
                        self._update(local, name, empty, False, append=False)
                    continue
                if not argv:
                    continue
                v = self._eval(argv[0], local) or _hole(argv[0], local[name].text)
                v.how = v.how | {"string-builder"}
                self._update(local, name, v, cond, append=True)
            return True
        return False

    def _scan(
        self, node: Node, local: dict[str, _Expr] | None, cls: _ClassInfo | None = None
    ) -> None:
        """Walk a method body in source order: string expressions and the variables that build
        SQL text, SQL call sites, endpoints. Code inside if/loop/switch/catch is conditional."""
        stack: list[tuple[Node, bool]] = [(node, False)]
        while stack:
            n, cond = stack.pop()
            t = n.type
            if (
                t == "expression_statement"
                and local is not None
                and self._statement(n, local, cond)
            ):
                for c in n.named_children:  # calls on the line (e.g. executeQuery) still count
                    self._calls_in(c, local)
                continue
            if t in ("string_literal", "binary_expression") and n.start_byte not in self.exprs:
                e = self._top_expr(n, local)
                if e is not None:
                    continue
            if t == "local_variable_declaration" and local is not None:
                for decl in n.children:
                    if decl.type == "variable_declarator":
                        v = decl.child_by_field_name("value")
                        if v is not None:
                            e = self._eval(v, local)
                            if e is not None:
                                self._track(local, _txt(decl.child_by_field_name("name")), e,
                                            v.start_byte)  # fmt: skip
                                self._calls_in(v, local)
                                continue
                            stack.append((v, cond))
                continue
            if t == "method_invocation":
                self._call(n, local or {})
            elif t == "object_creation_expression":
                self._new(n)
            elif t == "type_identifier":
                self.type_names.add(_txt(n))
            inner = cond or t in COND_STATEMENTS
            for c in reversed(n.children):
                is_test = t in ("if_statement", "while_statement") and c == n.child_by_field_name(
                    "condition"
                )
                stack.append((c, cond if is_test else inner))

    def _calls_in(self, node: Node, local: dict[str, _Expr]) -> None:
        """SQL call sites and object creations inside an expression that was evaluated."""
        stack = [node]
        while stack:
            n = stack.pop()
            if n.type == "method_invocation":
                self._call(n, local)
            elif n.type == "object_creation_expression":
                self._new(n)
            stack.extend(n.children)

    def _arg_expr(self, arg: Node, local: dict[str, _Expr]) -> _Expr | None:
        if arg.type == "identifier":
            name = _txt(arg)
            return local.get(name) or self.constants.get(name)
        if arg.start_byte in self.exprs:
            return self.exprs[arg.start_byte]
        return self._top_expr(arg, local)

    def _call(self, n: Node, local: dict[str, _Expr]) -> None:
        name = _txt(n.child_by_field_name("name"))
        if name in STRING_METHODS:
            return  # its arguments are pieces of text, evaluated with the expression
        obj = _txt(n.child_by_field_name("object"))
        args = n.child_by_field_name("arguments")
        argv = list(args.named_children) if args is not None else []
        first = self._arg_expr(argv[0], local) if argv else None
        if first is not None and first.source is None:
            if name in NATIVE_METHODS:
                first.source = "native"
            elif name in HQL_METHODS and (obj or name == "createQuery"):
                first.source, first.hql = "hql", True
            elif name in TEMPLATE_METHODS and re.search(r"(?i)template|jdbc", obj):
                first.source = "jdbc-template"
            elif name in JDBC_METHODS:
                first.source = "jdbc"
        line = n.start_point[0] + 1
        if name == "accessQueue" and first is not None:
            opts = " ".join(_txt(a) for a in argv[1:2])
            role = (
                "producer"
                if "OUTPUT" in opts
                else "consumer"
                if "INPUT" in opts or "BROWSE" in opts
                else None
            )
            self.facts.add(
                "integrations",
                integration(
                    type="MQ",
                    target=first.text,
                    direction="unknown",
                    file=self.path,
                    line=line,
                    role=role,
                    protocol="MQ Java",
                ),
            )
        elif name == "createQueue" and first is not None and re.match(r"^[\w.]+$", first.text):
            self.facts.add(
                "integrations",
                integration(
                    type="MQ",
                    target=first.text.split("///")[-1],
                    direction="unknown",
                    file=self.path,
                    line=line,
                    role=self._jms_role(),
                    protocol="JMS",
                ),
            )
        elif (
            name in JMS_TEMPLATE_METHODS
            and re.search(r"(?i)jms", obj)
            and first is not None
            and not first.dynamic
            and re.match(r"^[\w.]+$", first.text)
        ):
            self.facts.add(
                "integrations",
                integration(
                    type="MQ",
                    target=first.text.split("///")[-1],
                    direction="unknown",
                    file=self.path,
                    line=line,
                    role=JMS_TEMPLATE_METHODS[name],
                    protocol="JMS",
                ),
            )
        elif name == "connect" and first is not None and "ftp" in obj.lower():
            self.facts.add(
                "integrations",
                integration(
                    type="FTP",
                    target=f"ftp://{first.text}",
                    direction="outbound",
                    file=self.path,
                    line=line,
                ),
            )

    def _jms_role(self) -> str | None:
        text = self.ctx.text or ""
        prod = re.search(r"createProducer|createSender|\.send\(|convertAndSend", text)
        cons = re.search(r"createConsumer|createReceiver|\.receive\(|onMessage\(", text)
        if prod and not cons:
            return "producer"
        if cons and not prod:
            return "consumer"
        return None

    def _new(self, n: Node) -> None:
        typ = _txt(n.child_by_field_name("type")).split("<")[0].rsplit(".", 1)[-1]
        args = n.child_by_field_name("arguments")
        argv = list(args.named_children) if args is not None else []
        line = n.start_point[0] + 1
        vals = [self._eval(a) for a in argv]
        if typ == "Socket" and len(argv) >= 2:
            host = vals[0].text if vals[0] and not vals[0].dynamic else "?"
            port = _txt(argv[1]) if argv[1].type == "decimal_integer_literal" else "?"
            self.facts.add(
                "integrations",
                integration(
                    type="SOCKET",
                    target=f"{host}:{port}",
                    direction="outbound",
                    file=self.path,
                    line=line,
                ),
            )
        elif typ == "ServerSocket" and argv:
            port = _txt(argv[0]) if argv[0].type == "decimal_integer_literal" else "?"
            self.facts.add(
                "integrations",
                integration(
                    type="SOCKET",
                    target=f"*:{port}",
                    direction="inbound",
                    file=self.path,
                    line=line,
                ),
            )
        elif typ == "MQQueueManager" and vals and vals[0] and not vals[0].dynamic:
            self.facts.add(
                "integrations",
                integration(
                    type="MQ-QMGR",
                    target=vals[0].text,
                    direction="outbound",
                    file=self.path,
                    line=line,
                ),
            )

    # ---- results ------------------------------------------------------------------------------
    def _sql(
        self,
        text: str,
        line: int,
        source: str,
        dynamic: bool = False,
        hql: bool = False,
        expr: _Expr | None = None,
    ) -> None:
        fact = sql_fact(
            file=self.path,
            line=line,
            text=text,
            source=source,
            dialect=self.dialect,
            dynamic=dynamic,
            hql=hql,
        )
        if expr is not None:
            fill(fact, expr)
        self.facts.add("sql_statements", fact)

    def _emit_sql(self) -> None:
        soap = any(i.startswith(SOAP_IMPORTS) for i in self.imports)
        for e in sorted(self.exprs.values(), key=lambda e: e.node.start_byte):
            if id(e) in self.used_as_fragment and e.source is None:
                continue
            text = e.text.strip()
            if e.hql or (e.source == "hql" and looks_like_hql(text)):
                self._sql(text, e.line, "hql", e.dynamic, hql=True, expr=e)
            elif looks_like_sql(text, strict=not e.source):
                self._sql(text, e.line, e.source or "java-string", e.dynamic, expr=e)
            elif e.source in ("hql",) and looks_like_hql(text):
                self._sql(text, e.line, "hql", e.dynamic, hql=True, expr=e)
            if "://" in text or text.lower().startswith("jdbc:"):
                for m in re.finditer(r"(jdbc:[^\s\"']+|[a-z]+://[^\s\"'<>]+)", text, re.I):
                    url = m.group(1)
                    if url.lower().startswith("jdbc:"):
                        product, target = parse_jdbc(url)
                        self.facts.add(
                            "integrations",
                            integration(
                                type="DB",
                                target=target,
                                direction="outbound",
                                file=self.path,
                                line=e.line,
                                protocol="jdbc",
                            ),
                        )
                        self.facts.add(
                            "tech_stack", tech(product, None, "database", self.path, e.line)
                        )
                        continue
                    c = classify_url(url, soap_hint=soap)
                    if c:
                        self.facts.add(
                            "integrations",
                            integration(
                                type=c[0],
                                target=c[1],
                                direction="outbound",
                                file=self.path,
                                line=e.line,
                            ),
                        )

    def _imports_and_signals(self) -> None:
        javax = [i for i in self.imports if JAVAX_EE.match(i)]
        if javax:
            item = tech(
                "javax.* namespace (Jakarta EE migration)", None, "upgrade-signal", self.path, None
            )
            self.facts.add("tech_stack", item)
        if any(i.startswith("javax.mail") for i in self.imports):
            self.facts.add("tech_stack", tech("JavaMail", None, "library", self.path, None))
        if any(i.startswith(MQ_IMPORTS[:1]) for i in self.imports):
            self.facts.add("tech_stack", tech("IBM MQ client", None, "middleware", self.path, None))

    def _class_refs(self) -> None:
        if not self.classes:
            return
        primary = self.classes[0]
        source = f"class:{primary.fqn}"
        seen: set[str] = {c.fqn for c in self.classes}
        for imp in self.imports:
            if imp.startswith("static ") or imp.endswith(".*"):
                continue
            if imp not in seen:
                seen.add(imp)
                self.facts.ref(
                    kind="uses",
                    source=source,
                    source_label=primary.fqn,
                    target=imp,
                    target_kinds=["java-class"],
                    file=self.path,
                    report_unresolved=False,
                )
        imported_simple = {i.rsplit(".", 1)[-1] for i in self.imports}
        for name in sorted(self.type_names):
            if name in imported_simple or not name[:1].isupper():
                continue
            fqn = f"{self.package}.{name}" if self.package else name
            if fqn not in seen:
                seen.add(fqn)
                self.facts.ref(
                    kind="uses",
                    source=source,
                    source_label=primary.fqn,
                    target=fqn,
                    target_kinds=["java-class"],
                    file=self.path,
                    report_unresolved=False,
                )
