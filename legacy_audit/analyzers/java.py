"""Java sources (tree-sitter-java): classes, methods, annotations, imports, entry points,
SQL in strings (JDBC, JdbcTemplate, Hibernate/JPA, Spring Data), endpoints (HTTP/SOAP, sockets,
FTP, MQ/JMS) and services exposed by annotations (Spring MVC, JAX-WS, JAX-RS, EJB, Struts 2).

Spring and Java EE annotations are handled here so each Java file is parsed once; Spring XML
configuration is handled by the ``spring`` analyzer.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field, replace
from typing import Any, ClassVar

import tree_sitter_java
from tree_sitter import Language, Node, Parser

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import tech
from legacy_audit.analyzers.dynsql import fill
from legacy_audit.analyzers.integrations import classify_url, integration, parse_jdbc
from legacy_audit.analyzers.sqltools import hole_kind, looks_like_hql, looks_like_sql, sql_fact
from legacy_audit.analyzers.websvc import called
from legacy_audit.model import EntryPoint, Evidence, FileRecord, Screen, Service, make_id

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
REST_VERBS = {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"}
REST_TEMPLATE = {
    "getForObject": "GET",
    "getForEntity": "GET",
    "postForObject": "POST",
    "postForEntity": "POST",
    "postForLocation": "POST",
    "put": "PUT",
    "delete": "DELETE",
    "patchForObject": "PATCH",
    "headForHeaders": "HEAD",
    "optionsForAllow": "OPTIONS",
    "exchange": "ANY",
    "execute": "ANY",
}
WS_TEMPLATE_METHODS = {
    "marshalSendAndReceive",
    "sendSourceAndReceiveToResult",
    "sendSourceAndReceive",
    "sendAndReceive",
}
HTTP_CLIENT_TYPES = {
    "HttpGet": "GET",
    "HttpPost": "POST",
    "HttpPut": "PUT",
    "HttpDelete": "DELETE",
    "HttpPatch": "PATCH",
    "HttpHead": "HEAD",
    "HttpOptions": "OPTIONS",
    "GetMethod": "GET",
    "PostMethod": "POST",
    "PutMethod": "PUT",
    "DeleteMethod": "DELETE",
}
MQ_IMPORTS = ("com.ibm.mq", "javax.jms", "jakarta.jms", "org.springframework.jms")
# Java desktop windows (decision 141): a top-level window is a screen, a panel a partial.
SWING_WINDOWS = {"JFrame", "JDialog", "JInternalFrame", "JWindow", "JApplet", "JRibbonFrame"}
AWT_WINDOWS = {"Frame", "Dialog", "Applet", "Window"}
SWING_PANELS = {"JPanel", "JComponent", "JScrollPane", "JLayeredPane", "JDesktopPane"}
AWT_PANELS = {"Panel", "Canvas"}
DESKTOP_INPUTS = re.compile(
    r"^(JTextField|JTextArea|JPasswordField|JFormattedTextField|JComboBox|JCheckBox|"
    r"JRadioButton|JList|JTable|JSpinner|JSlider|JTree|JEditorPane|JTextPane|JDateChooser|"
    r"TextField|TextArea|Choice|Checkbox|List|ComboBox|CheckBox|RadioButton|DatePicker|"
    r"ListView|TableView|PasswordField|Spinner|Slider|ChoiceBox)$"
)
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


def _span(e: _Expr) -> tuple[int, int]:
    return (e.node.start_byte, e.node.end_byte)


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


def _soap_framework(imports: list[str]) -> str:
    for prefix, name in (
        ("org.apache.axis2", "Apache Axis2"),
        ("org.apache.axis", "Apache Axis"),
        ("org.apache.cxf", "Apache CXF"),
        ("org.springframework.ws", "Spring-WS"),
    ):
        if any(i.startswith(prefix) for i in imports):
            return name
    return "JAX-WS client"


def _join_path(*parts: str) -> str:
    return "/" + "/".join(p.strip("/") for p in parts if p.strip("/"))


class JavaAnalyzer:
    name: ClassVar[str] = "java"
    version: ClassVar[str] = "6"  # 5: Swing, AWT and JavaFX windows; 6: web services

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
        # (start, end) bytes of expressions used inside a larger one. Keyed by position, not
        # id(): the parts of a concatenation are freed right away, and a reused id made the
        # result depend on memory layout.
        self.used_as_fragment: set[tuple[int, int]] = set()
        self.type_names: set[str] = set()
        self.dialect = ctx.config.sql.dialect
        # Web service clients: the class being walked, a base URL set on a client builder,
        # SOAP calls whose address and operations are set on separate lines, and the
        # namespace arguments of QName (namespaces, not endpoints).
        self.cur_class: _ClassInfo | None = None
        self.ws_base: str | None = None
        self.ws_soap: list[dict[str, Any]] = []
        self.qname_ns: set[int] = set()

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
        self._ws_finish()
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
        self._desktop(info, node)
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

    def _desktop(self, info: _ClassInfo, node: Node) -> None:
        """Swing, AWT and JavaFX windows as screens; the windows they open as navigation."""
        sup = {s.rsplit(".", 1)[-1] for s in info.supertypes}
        swing = any(i.startswith("javax.swing") for i in self.imports)
        javafx = any(i.startswith("javafx.") for i in self.imports)
        awt = any(i.startswith("java.awt") for i in self.imports)
        if "Application" in sup and javafx:
            self._entry(info.fqn, "desktop-app", info.line, "JavaFX Application")
            self.facts.add("tech_stack", tech("JavaFX", None, "framework", self.path, info.line))
            return
        windows = SWING_WINDOWS | (AWT_WINDOWS if awt or not swing else set())
        panels = SWING_PANELS | (AWT_PANELS if awt else set())
        if not sup & (windows | panels):
            return
        technology = "swing" if sup & (SWING_WINDOWS | SWING_PANELS) else "awt"
        fields = 0
        targets: set[str] = set()
        stack = [node.child_by_field_name("body")]
        while stack:
            n = stack.pop()
            if n is None:
                continue
            if n.type == "object_creation_expression":
                t = _txt(n.child_by_field_name("type")).split("<")[0].rsplit(".", 1)[-1]
                if DESKTOP_INPUTS.match(t):
                    fields += 1
                elif (
                    t[:1].isupper()
                    and t != info.name
                    and not t.startswith(("J", "Grid", "Border", "Flow"))
                ):
                    targets.add(t)
            if n.type == "class_declaration":
                continue
            stack.extend(n.children)
        sid = make_id("screen", "java-desktop", info.fqn)
        self.facts.add(
            "screens",
            Screen(
                id=sid,
                name=info.name,
                technology=technology,
                file=self.path,
                fields_count=fields,
                partial=not sup & windows,
                evidence=[Evidence(file=self.path, line=info.line,
                                   snippet=f"{info.name} extends {', '.join(sorted(sup))}")],
            ),
        )  # fmt: skip
        self.facts.symbol("desktop-form", info.name, self.path, info.line, owner=sid)
        name = "Java Swing" if technology == "swing" else "Java AWT"
        self.facts.add("tech_stack", tech(name, None, "framework", self.path, info.line))
        for t in sorted(targets):
            self.facts.ref(
                kind="forwards",
                source=f"screen:{sid}",
                source_label=info.name,
                target=t,
                target_kinds=["desktop-form"],
                file=self.path,
                line=info.line,
                report_unresolved=False,
            )

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
            # A main() that opens a window starts a desktop application, not a batch program.
            ui = re.search(
                r"setVisible\(\s*true|SwingUtilities\.invoke|EventQueue\.invoke|"
                r"\blaunch\(",
                _txt(node.child_by_field_name("body")),
            )
            self._entry(cls.fqn, "desktop-app" if ui else "main", line)
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
            self.cur_class = cls
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
        # Web service clients declared as interfaces (Feign, MicroProfile, Retrofit)
        if self._client_interface(cls, name, line, ann, handler):
            return
        # Spring MVC (a REST operation when it returns data, not a view)
        if {"Controller", "RestController"} & set(ca):
            rtype = _txt(node.child_by_field_name("type"))
            rest = (
                "RestController" in ca
                or "ResponseBody" in ca
                or "ResponseBody" in ann
                or rtype.startswith(("ResponseEntity", "HttpEntity", "Mono", "Flux"))
            )
            for a, verb in MAPPING_ANNOTATIONS.items():
                if a in ann:
                    sub = ann[a].get("value") or ann[a].get("path", "")
                    method = verb or ann[a].get("method", "").rsplit(".", 1)[-1] or "ANY"
                    url = _join_path(self._base_path(ca), sub)
                    self._service(
                        f"{method} {url}", "spring-handler", handler, url, line,
                        protocol="REST" if rest else None,
                        framework="Spring MVC" if rest else None,
                        web_service=cls.name if rest else None,
                    )  # fmt: skip
                    if not rest:
                        self._views(node, f"service:{handler}")
        # JAX-RS
        if "Path" in ca or "Path" in ann:
            verbs = JAXRS_METHODS & set(ann)
            if verbs:
                url = _join_path(ca.get("Path", {}).get("value", ""),
                                 ann.get("Path", {}).get("value", ""))  # fmt: skip
                self._service(f"{sorted(verbs)[0]} {url}", "rest-operation", handler, url, line,
                              protocol="REST", framework="JAX-RS",
                              web_service=cls.name)  # fmt: skip
        # JAX-WS
        if (
            "WebService" in ca
            and public
            and name != cls.name
            and ("WebMethod" in ann or not any("WebMethod" in str(x) for x in ca))
            and ann.get("WebMethod", {}).get("exclude") != "true"
        ):
            ws = ca["WebService"]
            self._service(f"{cls.name}.{name}", "soap-operation", handler, None, line,
                          protocol="SOAP", framework="JAX-WS",
                          web_service=ws.get("serviceName") or ws.get("name") or cls.name,
                          )  # fmt: skip
        # Spring Web Services endpoints
        if (
            "Endpoint" in ca
            and {"PayloadRoot", "SoapAction", "Action"} & set(ann)
            and any(i.startswith("org.springframework.ws") for i in self.imports)
        ):
            op = (
                ann.get("PayloadRoot", {}).get("localPart")
                or ann.get("SoapAction", {}).get("value", "").rsplit("/", 1)[-1]
                or name
            )
            self._service(f"{cls.name}.{op}", "soap-operation", handler, None, line,
                          protocol="SOAP", framework="Spring-WS", web_service=cls.name)  # fmt: skip
        # Apache Axis instant deployment (.jws): every public method of the class
        if (
            self.path.lower().endswith(".jws")
            and "public" in mods_text
            and "static" not in mods_text
            and name != cls.name
            and cls is self.classes[0]
        ):
            stem = self.path.rsplit("/", 1)[-1].rsplit(".", 1)[0]
            self._service(f"{stem}.{name}", "soap-operation", handler, f"/{stem}.jws", line,
                          protocol="SOAP", framework="Apache Axis (JWS)",
                          web_service=stem)  # fmt: skip
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

    @staticmethod
    def _base_path(ca: dict[str, dict[str, str]]) -> str:
        rm = ca.get("RequestMapping", {})
        return rm.get("value") or rm.get("path", "")

    def _client_interface(
        self,
        cls: _ClassInfo,
        name: str,
        line: int,
        ann: dict[str, dict[str, str]],
        handler: str,
    ) -> bool:
        """A method of a declarative REST client (Feign, MicroProfile REST Client, Retrofit):
        an operation the application calls, never one it offers. True when it was one."""
        ca = cls.annotations
        if "FeignClient" in ca:
            fc = ca["FeignClient"]
            client = fc.get("name") or fc.get("value") or cls.name
            for a, verb in MAPPING_ANNOTATIONS.items():
                if a in ann:
                    sub = ann[a].get("value") or ann[a].get("path", "")
                    method = verb or ann[a].get("method", "").rsplit(".", 1)[-1] or "ANY"
                    path = _join_path(fc.get("path", ""), self._base_path(ca), sub)
                    self._ws_called("REST", client, "Feign", line, url=fc.get("url", "") + path,
                                    method=method, handler=handler)  # fmt: skip
            return True
        if "RegisterRestClient" in ca:
            rc = ca["RegisterRestClient"]
            verbs = JAXRS_METHODS & set(ann)
            if verbs:
                path = _join_path(ca.get("Path", {}).get("value", ""),
                                  ann.get("Path", {}).get("value", ""))  # fmt: skip
                self._ws_called("REST", rc.get("configKey") or cls.name, "MicroProfile REST Client",
                                line, url=rc.get("baseUri", "") + path, method=sorted(verbs)[0],
                                handler=handler)  # fmt: skip
            return True
        if cls.is_interface and any(i.startswith("retrofit2") for i in self.imports):
            for verb in sorted(JAXRS_METHODS & set(ann)):
                self._ws_called("REST", cls.name, "Retrofit", line,
                                url=ann[verb].get("value", ""), method=verb,
                                handler=handler)  # fmt: skip
            return True
        return False

    def _service(
        self,
        name: str,
        kind: str,
        handler: str,
        url: str | None,
        line: int,
        protocol: str | None = None,
        framework: str | None = None,
        web_service: str | None = None,
    ) -> None:
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
                protocol=protocol,
                framework=framework,
                web_service=web_service,
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
                    self.used_as_fragment.add(_span(part))
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
                self.used_as_fragment.add(_span(first))
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
        self._ws_call(n, name, obj, argv, local, line)
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

    # ---- web service clients ----------------------------------------------------------------
    def _ws_called(
        self,
        style: str,
        name: str,
        framework: str,
        line: int,
        url: str | None = None,
        method: str | None = None,
        op: str | None = None,
        handler: str | None = None,
        wsdl: str | None = None,
        namespace: str | None = None,
    ) -> None:
        if url and "://" not in url and self.ws_base and style == "REST":
            url = self.ws_base.rstrip("/") + "/" + url.lstrip("/")
        self.facts.add(
            "web_services",
            called(style=style, name=name, framework=framework, file=self.path, line=line,
                   url=url, op=op, method=method, handler=handler, wsdl=wsdl,
                   namespace=namespace),
        )  # fmt: skip

    def _url_arg(self, node: Node | None, local: dict[str, _Expr]) -> str | None:
        """The text of a URL argument: a string expression, ``new URL(x)``, ``new URI(x)`` or
        ``URI.create(x)``; parts only known at run time are ``?``. None when not text."""
        if node is None:
            return None
        if node.type == "object_creation_expression":
            typ = _txt(node.child_by_field_name("type")).rsplit(".", 1)[-1]
            args = node.child_by_field_name("arguments")
            if typ in ("URL", "URI") and args is not None and args.named_children:
                return self._url_arg(args.named_children[0], local)
            return None
        if node.type == "method_invocation" and _txt(node.child_by_field_name("name")) in (
            "create",
            "toURI",
        ):
            args = node.child_by_field_name("arguments")
            if args is not None and args.named_children:
                return self._url_arg(args.named_children[0], local)
        e = self._arg_expr(node, local)
        if e is not None:
            return e.text.strip()
        return "?" if node.type in ("identifier", "method_invocation", "field_access") else None

    @staticmethod
    def _chain_up(n: Node) -> list[tuple[str, list[Node]]]:
        """Calls chained after ``n`` (``n.request().post(...)``): (name, arguments) in order."""
        out: list[tuple[str, list[Node]]] = []
        cur = n
        while (
            cur.parent is not None
            and cur.parent.type == "method_invocation"
            and (cur.parent.child_by_field_name("object") == cur)
        ):
            cur = cur.parent
            args = cur.child_by_field_name("arguments")
            out.append((_txt(cur.child_by_field_name("name")),
                        list(args.named_children) if args is not None else []))  # fmt: skip
        return out

    @staticmethod
    def _chain_down(n: Node) -> list[str]:
        """Names of the calls ``n`` is chained on (``client.get().uri(...)`` -> [get])."""
        out: list[str] = []
        cur = n.child_by_field_name("object")
        while cur is not None and cur.type == "method_invocation":
            out.append(_txt(cur.child_by_field_name("name")))
            cur = cur.child_by_field_name("object")
        return out

    def _verb(self, names: list[str], argvs: list[list[Node]] | None = None) -> str | None:
        for i, nm in enumerate(names):
            if nm.upper() in REST_VERBS and (nm.islower() or nm.isupper()):
                return nm.upper()
            if nm == "method" and argvs and argvs[i]:
                v = _txt(argvs[i][0]).rsplit(".", 1)[-1].strip('"').upper()
                if v in REST_VERBS:
                    return v
        return None

    def _client_name(self) -> str:
        return self.cur_class.name if self.cur_class else self.path.rsplit("/", 1)[-1]

    def _ws_call(
        self, n: Node, name: str, obj: str, argv: list[Node], local: dict[str, _Expr], line: int
    ) -> None:
        imports = self.imports
        first = argv[0] if argv else None
        handler = self.cur_class.fqn if self.cur_class else None
        # Spring RestTemplate / RestOperations
        if name in REST_TEMPLATE and re.search(r"(?i)rest", obj) and first is not None:
            verb = REST_TEMPLATE[name]
            if name == "exchange" and len(argv) > 1:
                verb = _txt(argv[1]).rsplit(".", 1)[-1].upper()
            url = self._url_arg(first, local)
            if url is not None:
                self._ws_called("REST", self._client_name(), "RestTemplate", line, url=url,
                                method=verb if verb in REST_VERBS else None,
                                handler=handler)  # fmt: skip
            return
        # Base URL of a client builder (WebClient, RestClient, RestTemplateBuilder, Retrofit)
        if name in ("baseUrl", "rootUri") or (
            name == "create" and obj in ("WebClient", "RestClient")
        ):
            url = self._url_arg(first, local)
            if url and "://" in url:
                self.ws_base = url
            return
        # Spring WebClient / RestClient: client.get().uri("/x")
        if (
            name == "uri"
            and first is not None
            and any(
                i.startswith(("org.springframework.web.reactive", "org.springframework.web.client"))
                for i in imports
            )
        ):
            verb = self._verb(self._chain_down(n)) or "ANY"
            url = self._url_arg(first, local)
            if url is not None:
                fw = "RestClient" if any(i.endswith("RestClient") for i in imports) else "WebClient"
                self._ws_called("REST", self._client_name(), fw, line, url=url, method=verb,
                                handler=handler)  # fmt: skip
            return
        # JAX-RS client: client.target("http://...").path("x").request().get()
        if (
            name == "target"
            and first is not None
            and any(i.startswith(("javax.ws.rs.client", "jakarta.ws.rs.client")) for i in imports)
        ):
            chain = self._chain_up(n)
            url = self._url_arg(first, local) or "?"
            for nm, a in chain:
                if nm == "path" and a:
                    url = url.rstrip("/") + "/" + (self._url_arg(a[0], local) or "?").lstrip("/")
            verb = self._verb([c[0] for c in chain], [c[1] for c in chain]) or "ANY"
            self._ws_called("REST", self._client_name(), "JAX-RS client", line, url=url,
                            method=verb, handler=handler)  # fmt: skip
            return
        # java.net.http.HttpClient: HttpRequest.newBuilder(URI.create(u)) / .uri(URI.create(u))
        if (
            ((name == "newBuilder" and obj.endswith("HttpRequest")) or name == "uri")
            and first is not None
            and any(i.startswith("java.net.http") for i in imports)
        ):
            url = self._url_arg(first, local)
            chain = self._chain_up(n)
            if url is not None:
                verb = self._verb([c[0] for c in chain], [c[1] for c in chain]) or "GET"
                self._ws_called("REST", self._client_name(), "java.net.http.HttpClient", line,
                                url=url, method=verb, handler=handler)  # fmt: skip
            return
        # OkHttp: new Request.Builder().url("...").post(body)
        if name == "url" and first is not None and any("okhttp" in i for i in imports):
            url = self._url_arg(first, local)
            chain = self._chain_up(n)
            if url is not None:
                verb = self._verb([c[0] for c in chain], [c[1] for c in chain]) or "GET"
                self._ws_called("REST", self._client_name(), "OkHttp", line, url=url,
                                method=verb, handler=handler)  # fmt: skip
            return
        soap = any(i.startswith(SOAP_IMPORTS) for i in imports)
        # Apache Axis dynamic call, CXF proxy factory, Spring WebServiceTemplate
        if soap and name in ("setTargetEndpointAddress", "setAddress", "setDefaultUri"):
            url = self._url_arg(first, local)
            if url:
                self.ws_soap.append({"url": url, "ops": [], "name": None, "line": line,
                                     "fw": _soap_framework(imports)})  # fmt: skip
            return
        if soap and name == "setOperationName" and first is not None:
            op = None
            if first.type == "object_creation_expression":
                args = first.child_by_field_name("arguments")
                qa = list(args.named_children) if args is not None else []
                if qa:
                    e = self._arg_expr(qa[-1], local)
                    op = e.text if e is not None else None
            else:
                e = self._arg_expr(first, local)
                op = e.text if e is not None else None
            if op:
                if not self.ws_soap:
                    self.ws_soap.append({"url": None, "ops": [], "name": None, "line": line,
                                         "fw": _soap_framework(imports)})  # fmt: skip
                self.ws_soap[-1]["ops"].append((op, line))
            return
        if soap and name == "setServiceClass" and first is not None:
            cls = _txt(first).removesuffix(".class").rsplit(".", 1)[-1]
            if self.ws_soap and self.ws_soap[-1]["name"] is None:
                self.ws_soap[-1]["name"] = cls
            else:
                self.ws_soap.append({"url": None, "ops": [], "name": cls, "line": line,
                                     "fw": _soap_framework(imports)})  # fmt: skip
            return
        if (
            name in WS_TEMPLATE_METHODS
            and re.search(r"(?i)template|webservice", obj)
            and any(i.startswith("org.springframework.ws") for i in imports)
        ):
            url = self._url_arg(first, local) if len(argv) > 1 else None
            if url and "://" in url:
                self._ws_called("SOAP", url.rstrip("/").rsplit("/", 1)[-1], "Spring-WS", line,
                                url=url, handler=handler)  # fmt: skip
            else:
                self.ws_soap.append({"url": None, "ops": [], "name": None, "line": line,
                                     "fw": "Spring-WS", "use": True})  # fmt: skip
            return
        # JAX-WS generated client: new XService().getXPort() / service.getPort(X.class)
        if soap and re.match(r"^get\w*Port$", name):
            o = n.child_by_field_name("object")
            svc = ""
            if o is not None and o.type == "object_creation_expression":
                svc = _txt(o.child_by_field_name("type")).rsplit(".", 1)[-1]
            elif name == "getPort" and first is not None:
                svc = _txt(first).removesuffix(".class").rsplit(".", 1)[-1]
            else:
                svc = name[3:]
            if svc:
                self._ws_called("SOAP", svc, "JAX-WS client", line, handler=handler)

    def _ws_new(self, typ: str, argv: list[Node], vals: list[_Expr | None], line: int) -> None:
        if typ == "QName" and len(argv) >= 2:
            a = argv[0]
            self.qname_ns.add(a.start_byte)
            if a.type == "identifier" and _txt(a) in self.constants:
                self.qname_ns.add(self.constants[_txt(a)].node.start_byte)
            return
        verb = HTTP_CLIENT_TYPES.get(typ)
        if verb and argv:
            url = self._url_arg(argv[0], {})
            if url is not None and any(
                i.startswith(("org.apache.http", "org.apache.hc", "org.apache.commons.httpclient"))
                for i in self.imports
            ):
                self._ws_called("REST", self._client_name(), "Apache HttpClient", line, url=url,
                                method=verb)  # fmt: skip
            return
        if typ == "URL" and argv and "openConnection" in (self.ctx.text or ""):
            parent = argv[0].parent.parent if argv[0].parent is not None else None
            gp = parent.parent if parent is not None else None
            if gp is not None and gp.type == "argument_list":
                return  # an argument (setTargetEndpointAddress(new URL(...)))
            url = self._url_arg(argv[0], {})
            if url and url.lower().startswith(("http://", "https://")):
                m = re.search(r'setRequestMethod\(\s*"(\w+)"', self.ctx.text or "")
                self._ws_called("REST", self._client_name(), "HttpURLConnection", line, url=url,
                                method=m.group(1).upper() if m else None)  # fmt: skip

    def _ws_classes(self) -> None:
        """Generated SOAP client classes: JAX-WS ``@WebServiceClient`` services and Apache Axis
        locators and stubs. Their address is the ``*_address`` constant of an Axis locator."""
        for cls in self.classes:
            ann = cls.annotations
            sup = {s.rsplit(".", 1)[-1] for s in cls.supertypes}
            imports = self.imports
            if "WebServiceClient" in ann:
                w = ann["WebServiceClient"]
                loc = w.get("wsdlLocation") or None
                self._ws_called("SOAP", w.get("name") or cls.name, "JAX-WS client", cls.line,
                                url=loc if loc and "://" in loc else None, wsdl=loc,
                                namespace=w.get("targetNamespace"))  # fmt: skip
            elif "Service" in sup and any(i.startswith("org.apache.axis.client") for i in imports):
                address = next(
                    (e.text for k, e in self.constants.items() if k.endswith("_address")), None
                )
                self._ws_called("SOAP", cls.name, "Apache Axis", cls.line, url=address)
            elif "Stub" in sup and any(i.startswith("org.apache.axis.client") for i in imports):
                self._ws_called("SOAP", cls.name, "Apache Axis", cls.line)

    def _ws_finish(self) -> None:
        self._ws_classes()
        for call in self.ws_soap:
            url = call["url"]
            if not url and not call["ops"] and not call["name"] and not call.get("use"):
                continue
            name = (
                call["name"]
                or (
                    url.rstrip("/").rsplit("/", 1)[-1].split("?", 1)[0]
                    if url and "/" in url
                    else ""
                )
                or self._client_name()
            )
            if not call["ops"]:
                self._ws_called("SOAP", name, call["fw"], call["line"], url=url)
            for op, line in call["ops"]:
                self._ws_called("SOAP", name, call["fw"], line, url=url, op=op)

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
        self._ws_new(typ, argv, vals, line)
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
            if _span(e) in self.used_as_fragment and e.source is None:
                continue
            text = e.text.strip()
            if e.hql or (e.source == "hql" and looks_like_hql(text)):
                self._sql(text, e.line, "hql", e.dynamic, hql=True, expr=e)
            elif looks_like_sql(text, strict=not e.source):
                self._sql(text, e.line, e.source or "java-string", e.dynamic, expr=e)
            elif e.source in ("hql",) and looks_like_hql(text):
                self._sql(text, e.line, "hql", e.dynamic, hql=True, expr=e)
            if e.node.start_byte in self.qname_ns:
                continue  # a QName namespace, not an endpoint
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
