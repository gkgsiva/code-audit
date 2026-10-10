"""Java sources (tree-sitter-java): classes, methods, annotations, imports, entry points,
SQL in strings (JDBC, JdbcTemplate, Hibernate/JPA, Spring Data), endpoints (HTTP/SOAP, sockets,
FTP, MQ/JMS) and services exposed by annotations (Spring MVC, JAX-WS, JAX-RS, EJB, Struts 2).

Spring and Java EE annotations are handled here so each Java file is parsed once; Spring XML
configuration is handled by the ``spring`` analyzer.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import ClassVar

import tree_sitter_java
from tree_sitter import Language, Node, Parser

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import tech
from legacy_audit.analyzers.integrations import classify_url, integration, parse_jdbc
from legacy_audit.analyzers.sqltools import looks_like_hql, looks_like_sql, sql_fact
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
    node: Node
    text: str
    dynamic: bool
    line: int
    source: str | None = None
    hql: bool = False


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
    version: ClassVar[str] = "1"

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
        self.type_names: set[str] = set()
        self.dialect = ctx.config.sql.dialect

    # ---- driving ------------------------------------------------------------------------------
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
    def _eval(self, node: Node) -> _Expr | None:
        """Evaluate a string expression: literals, concatenation, constants. None if not text."""
        t = node.type
        line = node.start_point[0] + 1
        if t == "string_literal":
            return _Expr(node, _unquote(_txt(node)), False, line)
        if t == "parenthesized_expression" and node.named_children:
            return self._eval(node.named_children[0])
        if t == "binary_expression" and _txt(node.child_by_field_name("operator")) == "+":
            left = node.child_by_field_name("left")
            right = node.child_by_field_name("right")
            le = self._eval(left) if left is not None else None
            re_ = self._eval(right) if right is not None else None
            if le is None and re_ is None:
                return None
            lt = le.text if le else "?"
            rt = re_.text if re_ else "?"
            dyn = (le is None or le.dynamic) or (re_ is None or re_.dynamic)
            return _Expr(node, lt + rt, dyn, line)
        if t == "identifier":
            c = self.constants.get(_txt(node))
            if c is not None:
                return _Expr(node, c.text, c.dynamic, line)
        return None

    def _top_expr(self, node: Node) -> _Expr | None:
        e = self._eval(node)
        if e is None or not e.text.strip():
            return None
        self.exprs[node.start_byte] = e
        return e

    def _scan(
        self, node: Node, local: dict[str, _Expr] | None, cls: _ClassInfo | None = None
    ) -> None:
        """Walk a method body: string expressions, SQL call sites, endpoints."""
        stack = [node]
        while stack:
            n = stack.pop()
            t = n.type
            if t in ("string_literal", "binary_expression") and n.start_byte not in self.exprs:
                e = self._top_expr(n)
                if e is not None:
                    continue
            if t == "local_variable_declaration" and local is not None:
                for decl in n.children:
                    if decl.type == "variable_declarator":
                        v = decl.child_by_field_name("value")
                        if v is not None:
                            e = self._top_expr(v)
                            if e is not None:
                                local[_txt(decl.child_by_field_name("name"))] = e
                                continue
                            stack.append(v)
                continue
            if t == "method_invocation":
                self._call(n, local or {})
            elif t == "object_creation_expression":
                self._new(n)
            elif t == "type_identifier":
                self.type_names.add(_txt(n))
            stack.extend(reversed(n.children))

    def _arg_expr(self, arg: Node, local: dict[str, _Expr]) -> _Expr | None:
        if arg.type == "identifier":
            name = _txt(arg)
            return local.get(name) or self.constants.get(name)
        if arg.start_byte in self.exprs:
            return self.exprs[arg.start_byte]
        return self._top_expr(arg)

    def _call(self, n: Node, local: dict[str, _Expr]) -> None:
        name = _txt(n.child_by_field_name("name"))
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
        self, text: str, line: int, source: str, dynamic: bool = False, hql: bool = False
    ) -> None:
        self.facts.add(
            "sql_statements",
            sql_fact(
                file=self.path,
                line=line,
                text=text,
                source=source,
                dialect=self.dialect,
                dynamic=dynamic,
                hql=hql,
            ),
        )

    def _emit_sql(self) -> None:
        soap = any(i.startswith(SOAP_IMPORTS) for i in self.imports)
        for e in sorted(self.exprs.values(), key=lambda e: e.node.start_byte):
            text = e.text.strip()
            if e.hql or (e.source == "hql" and looks_like_hql(text)):
                self._sql(text, e.line, "hql", e.dynamic, hql=True)
            elif looks_like_sql(text):
                self._sql(text, e.line, e.source or "java-string", e.dynamic)
            elif e.source in ("hql",) and looks_like_hql(text):
                self._sql(text, e.line, "hql", e.dynamic, hql=True)
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
