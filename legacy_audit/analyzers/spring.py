"""Spring XML configuration and XML-mapped data access.

Covers bean definitions, XML URL mappings and view resolvers (Spring MVC), JMS / IBM MQ wiring,
remoting, Spring Security, Spring Batch jobs, Web Flow, Hibernate mappings and configuration,
MyBatis / iBATIS mappers and ``persistence.xml``. Annotations are handled by the ``java``
analyzer.
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from typing import ClassVar

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import tech
from legacy_audit.analyzers.integrations import classify_url, integration
from legacy_audit.analyzers.sqltools import hole_kind, looks_like_sql, sql_fact
from legacy_audit.analyzers.textutil import attrs, child_text, line_of, ns_of, parse_xml, text_of
from legacy_audit.model import BatchJob, Evidence, FileRecord, Service, make_id

VIEW_RESOLVERS = (
    "InternalResourceViewResolver",
    "UrlBasedViewResolver",
    "VelocityViewResolver",
    "FreeMarkerViewResolver",
    "TilesViewResolver",
    "ResourceBundleViewResolver",
)
# Bean properties holding SQL (JdbcCursorItemReader, JdbcPagingItemReader, MappingSqlQuery...).
SQL_PROPERTIES = {"sql", "query", "queryString", "selectSql", "updateSql", "insertSql"}
VIEW_PROPERTIES = {"formView", "successView", "viewName", "cancelView", "errorView", "pages"}
LISTENER_CONTAINERS = ("DefaultMessageListenerContainer", "SimpleMessageListenerContainer")
DYNAMIC_TAGS = {
    "if",
    "choose",
    "when",
    "otherwise",
    "where",
    "foreach",
    "trim",
    "set",
    "bind",
    "dynamic",
    "isNotNull",
    "isNull",
    "isNotEmpty",
    "isEmpty",
    "isEqual",
    "isNotEqual",
    "iterate",
    "isPropertyAvailable",
    "isParameterPresent",
}
DIALECTS = {
    "oracle": "Oracle",
    "db2": "DB2",
    "sqlserver": "SQL Server",
    "sybase": "Sybase",
    "mysql": "MySQL",
    "postgres": "PostgreSQL",
    "informix": "Informix",
    "h2": "H2",
    "hsql": "HSQLDB",
}


def _props(bean: ET.Element) -> dict[str, tuple[str, str, ET.Element]]:
    """property name -> ('value'|'ref'|'list', value, element)."""
    out: dict[str, tuple[str, str, ET.Element]] = {}
    for p in bean.findall("property"):
        a = attrs(p)
        name = a.get("name", "")
        if "value" in a:
            out[name] = ("value", a["value"], p)
        elif "ref" in a:
            out[name] = ("ref", a["ref"], p)
        else:
            v = p.find("value")
            r = p.find("ref")
            if v is not None:
                out[name] = ("value", text_of(v), p)
            elif r is not None:
                out[name] = ("ref", attrs(r).get("bean") or attrs(r).get("local", ""), p)
            else:
                out[name] = ("list", "", p)
    return out


def _ctor_value(bean: ET.Element) -> str | None:
    for c in bean.findall("constructor-arg"):
        a = attrs(c)
        if "value" in a:
            return a["value"]
        v = c.find("value")
        if v is not None:
            return text_of(v)
    return None


def _short(cls: str) -> str:
    return cls.rsplit(".", 1)[-1]


class SpringAnalyzer:
    name: ClassVar[str] = "spring"
    version: ClassVar[str] = "2"

    def supports(self, file: FileRecord) -> bool:
        return file.language == "xml" and file.category in ("config", "source", "other")

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        facts = Facts()
        text = ctx.text or ""
        facts.coverage.lines = text.count("\n") + 1
        head = text[:3000]
        if not re.search(
            r"<(beans|flow|mapper|sqlMap|hibernate-mapping|hibernate-configuration|"
            r"persistence|job)\b|springframework",
            head,
        ):
            return facts
        try:
            root = parse_xml(text)
        except ET.ParseError as err:
            facts.coverage.gap(1, facts.coverage.lines, f"XML parse error: {err}")
            return facts
        path = file.path
        tag = root.tag
        if tag == "beans" or (tag == "job" and "batch" in ns_of(root)):
            self._beans(path, root, facts, ctx)
        elif tag == "flow":
            self._flow(path, root, facts)
        elif tag == "mapper":
            self._mybatis(path, root, facts, ctx, "mybatis")
        elif tag == "sqlMap":
            self._mybatis(path, root, facts, ctx, "ibatis")
        elif tag == "hibernate-mapping":
            self._hbm(path, root, facts, ctx)
        elif tag == "hibernate-configuration":
            facts.add("tech_stack", tech("Hibernate", None, "framework", path, line_of(root)))
            for p in root.iter("property"):
                if attrs(p).get("name", "").endswith("dialect"):
                    self._dialect(path, text_of(p), line_of(p), facts)
        elif tag == "persistence":
            facts.add(
                "tech_stack", tech("JPA", root.get("version"), "framework", path, line_of(root))
            )
            for prov in root.iter("provider"):
                facts.add(
                    "tech_stack",
                    tech(_short(text_of(prov)), None, "framework", path, line_of(prov)),
                )
            for p in root.iter("property"):
                if attrs(p).get("name", "").endswith("dialect"):
                    self._dialect(path, attrs(p).get("value", ""), line_of(p), facts)
        return facts

    def _dialect(self, path: str, value: str, line: int | None, facts: Facts) -> None:
        low = value.lower()
        for key, name in DIALECTS.items():
            if key in low:
                facts.add("tech_stack", tech(name, None, "database", path, line))
                return

    # ---- beans --------------------------------------------------------------------------------
    def _beans(self, path: str, root: ET.Element, facts: Facts, ctx: AnalyzerContext) -> None:
        facts.add(
            "tech_stack",
            tech("Spring Framework (XML configuration)", None, "framework", path, line_of(root)),
        )
        beans: dict[str, ET.Element] = {}
        for b in root.iter("bean"):
            a = attrs(b)
            for key in [a.get("id", ""), *re.split(r"[,; ]+", a.get("name", ""))]:
                if key:
                    beans[key] = b
        queues: dict[str, str] = {}
        for bid, b in beans.items():
            cls = attrs(b).get("class", "")
            if _short(cls) in ("MQQueue", "ActiveMQQueue") or cls.endswith("Queue"):
                q = _ctor_value(b) or _props(b).get("baseQueueName", ("", "", b))[1]
                if q:
                    queues[bid] = q.split("///")[-1]

        for b in root.iter("bean"):
            a = attrs(b)
            bid = a.get("id") or a.get("name", "")
            cls = a.get("class", "")
            line = line_of(b)
            short = _short(cls)
            props = _props(b)
            if bid:
                facts.symbol("spring-bean", bid, path, line, data={"class": cls})
            if cls and bid:
                facts.ref(
                    kind="uses",
                    source=f"bean:{path}#{bid}",
                    source_label=bid,
                    target=cls,
                    target_kinds=["java-class"],
                    file=path,
                    line=line,
                    report_unresolved=False,
                )
            # MVC
            if short in VIEW_RESOLVERS:
                facts.symbol(
                    "view-resolver",
                    short,
                    path,
                    line,
                    data={
                        "prefix": props.get("prefix", ("", "", b))[1],
                        "suffix": props.get("suffix", ("", "", b))[1],
                        "tiles": "true" if "Tiles" in short else "",
                    },
                )
            if short == "SimpleUrlHandlerMapping":
                self._url_mappings(path, b, beans, facts)
            if bid.startswith("/") or (a.get("name", "").startswith("/")):
                url = a.get("name") if a.get("name", "").startswith("/") else bid
                self._handler(path, url or bid, b, facts)
            for pname in SQL_PROPERTIES & set(props):
                kind, value, el = props[pname]
                if kind == "value" and looks_like_sql(value):
                    facts.add(
                        "sql_statements",
                        sql_fact(
                            file=path,
                            line=line_of(el) or line or 1,
                            text=value,
                            source="spring-xml",
                            dialect=ctx.config.sql.dialect,
                        ),
                    )
            for pname in VIEW_PROPERTIES & set(props):
                kind, value, el = props[pname]
                if kind == "value" and value and not value.startswith("redirect:"):
                    facts.ref(
                        kind="renders",
                        source=f"bean:{path}#{bid}",
                        source_label=bid or cls,
                        target=value,
                        target_kinds=["view"],
                        file=path,
                        line=line_of(el),
                    )
            # JMS / MQ
            if short in ("JmsTemplate", "JmsTemplate102"):
                dest = props.get("defaultDestination") or props.get("defaultDestinationName")
                if dest:
                    q = queues.get(dest[1], dest[1]) if dest[0] == "ref" else dest[1]
                    facts.add(
                        "integrations",
                        integration(
                            type="MQ",
                            target=q,
                            direction="unknown",
                            file=path,
                            line=line,
                            role="producer",
                            protocol="JMS",
                        ),
                    )
            if short in LISTENER_CONTAINERS:
                dest = props.get("destination") or props.get("destinationName")
                if dest:
                    q = queues.get(dest[1], dest[1]) if dest[0] == "ref" else dest[1]
                    facts.add(
                        "integrations",
                        integration(
                            type="MQ",
                            target=q,
                            direction="unknown",
                            file=path,
                            line=line,
                            role="consumer",
                            protocol="JMS",
                        ),
                    )
                listener = props.get("messageListener")
                if listener and listener[0] == "ref" and listener[1] in beans:
                    lcls = attrs(beans[listener[1]]).get("class", "")
                    facts.ref(
                        kind="uses",
                        source=f"bean:{path}#{bid}",
                        source_label=bid,
                        target=lcls,
                        target_kinds=["java-class"],
                        file=path,
                        line=line,
                    )
            if short in (
                "MQQueueConnectionFactory",
                "MQConnectionFactory",
                "MQXAConnectionFactory",
            ):
                qm = props.get("queueManager", ("", "", b))[1]
                host = props.get("hostName", ("", "", b))[1]
                port = props.get("port", ("", "", b))[1]
                target = qm + (f"@{host}" if host else "") + (f":{port}" if port else "")
                if target:
                    facts.add(
                        "integrations",
                        integration(
                            type="MQ-QMGR",
                            target=target,
                            direction="outbound",
                            file=path,
                            line=line,
                            details={"channel": props.get("channel", ("", "", b))[1]},
                        ),
                    )
                facts.add("tech_stack", tech("IBM MQ (JMS)", None, "middleware", path, line))
            # Remoting
            if short in (
                "HttpInvokerProxyFactoryBean",
                "RmiProxyFactoryBean",
                "JaxWsPortProxyFactoryBean",
                "JaxRpcPortProxyFactoryBean",
                "HessianProxyFactoryBean",
            ):
                url = (props.get("serviceUrl") or props.get("wsdlDocumentUrl") or ("", "", b))[1]
                if url.startswith("rmi:"):
                    m = re.match(r"rmi://([^/:]+)(?::(\d+))?", url)
                    if m:
                        facts.add(
                            "integrations",
                            integration(
                                type="SOCKET",
                                target=f"{m.group(1)}:{m.group(2) or 1099}",
                                direction="outbound",
                                file=path,
                                line=line,
                                protocol="RMI",
                            ),
                        )
                else:
                    c = classify_url(url, soap_hint="Jax" in short)
                    if c:
                        facts.add(
                            "integrations",
                            integration(
                                type=c[0],
                                target=c[1],
                                direction="outbound",
                                file=path,
                                line=line,
                                protocol=short.replace("ProxyFactoryBean", ""),
                            ),
                        )
            if short in (
                "HttpInvokerServiceExporter",
                "RmiServiceExporter",
                "HessianServiceExporter",
                "SimpleJaxWsServiceExporter",
            ):
                name = props.get("serviceName", ("", bid, b))[1] or bid
                sid = make_id("service", "remoting", path, name)
                facts.add(
                    "services",
                    Service(
                        id=sid,
                        name=name,
                        kind="remoting",
                        file=path,
                        handler=short,
                        evidence=[Evidence(file=path, line=line)],
                    ),
                )
            # Security
            if short in ("DefaultSpringSecurityContextSource", "LdapContextSource"):
                url = _ctor_value(b) or props.get("url", ("", "", b))[1]
                self._ldap(path, url, line, facts)
            if short == "RequestHeaderAuthenticationFilter":
                header = props.get("principalRequestHeader", ("", "SM_USER", b))[1]
                facts.add(
                    "tech_stack",
                    tech(f"Header pre-authentication ({header})", None, "auth", path, line),
                )

        for el in root.iter():
            ns = ns_of(el)
            line = line_of(el)
            a = attrs(el)
            if el.tag == "listener" and "jms" in ns and a.get("destination"):
                q = a["destination"]
                facts.add(
                    "integrations",
                    integration(
                        type="MQ",
                        target=queues.get(q, q),
                        direction="unknown",
                        file=path,
                        line=line,
                        role="consumer",
                        protocol="JMS",
                    ),
                )
                if a.get("ref") in beans:
                    facts.ref(
                        kind="uses",
                        source=f"file:{path}",
                        source_label=path,
                        target=attrs(beans[a["ref"]]).get("class", ""),
                        target_kinds=["java-class"],
                        file=path,
                        line=line,
                    )
            elif el.tag == "http" and "security" in ns:
                facts.add("tech_stack", tech("Spring Security", None, "auth", path, line))
            elif el.tag == "form-login" and "security" in ns:
                facts.add(
                    "tech_stack", tech("Spring Security (form login)", None, "auth", path, line)
                )
            elif el.tag in ("ldap-server", "ldap-authentication-provider") and "security" in ns:
                facts.add("tech_stack", tech("Spring Security (LDAP)", None, "auth", path, line))
                if a.get("url"):
                    self._ldap(path, a["url"], line, facts)
            elif el.tag == "job" and ("batch" in ns or el is root):
                self._batch_job(path, el, beans, facts)
            elif el.tag == "component-scan":
                facts.symbol("component-scan", a.get("base-package", ""), path, line)
            elif el.tag == "view-controller" and "mvc" in ns:
                facts.ref(
                    kind="renders",
                    source=f"file:{path}",
                    source_label=a.get("path", path),
                    target=a.get("view-name", a.get("path", "").strip("/")),
                    target_kinds=["view"],
                    file=path,
                    line=line,
                )

    def _ldap(self, path: str, url: str, line: int | None, facts: Facts) -> None:
        for u in url.split():
            c = classify_url(u)
            if c:
                facts.add(
                    "integrations",
                    integration(
                        type="LDAP", target=c[1], direction="outbound", file=path, line=line
                    ),
                )

    def _handler(self, path: str, url: str, bean: ET.Element, facts: Facts) -> None:
        cls = attrs(bean).get("class", "")
        sid = make_id("service", "spring-handler", path, url)
        facts.add(
            "services",
            Service(
                id=sid,
                name=url,
                kind="spring-handler",
                file=path,
                handler=cls or None,
                url=url,
                evidence=[Evidence(file=path, line=line_of(bean))],
            ),
        )
        if cls:
            facts.ref(
                kind="calls",
                source=f"service:{sid}",
                source_label=url,
                target=cls,
                target_kinds=["java-class"],
                file=path,
                line=line_of(bean),
            )
        bid = attrs(bean).get("id") or attrs(bean).get("name", "")
        facts.symbol("service", f"bean:{path}#{bid}", path, line_of(bean), owner=sid)

    def _url_mappings(
        self, path: str, mapping: ET.Element, beans: dict[str, ET.Element], facts: Facts
    ) -> None:
        pairs: list[tuple[str, str, int | None]] = []
        for prop in mapping.iter("prop"):
            pairs.append((attrs(prop).get("key", ""), text_of(prop), line_of(prop)))
        for p in mapping.findall("property"):
            if attrs(p).get("name") in ("mappings", "urlMap"):
                v = p.find("value")
                if v is not None:
                    for ln in text_of(v).splitlines():
                        if "=" in ln:
                            k, val = ln.split("=", 1)
                            pairs.append((k.strip(), val.strip(), line_of(v)))
                for e in p.iter("entry"):
                    a = attrs(e)
                    pairs.append(
                        (a.get("key", ""), a.get("value-ref") or a.get("value", ""), line_of(e))
                    )
        for url, bean_id, line in pairs:
            if not url:
                continue
            bean = beans.get(bean_id)
            cls = attrs(bean).get("class", "") if bean is not None else ""
            sid = make_id("service", "spring-handler", path, url)
            facts.add(
                "services",
                Service(
                    id=sid,
                    name=url,
                    kind="spring-handler",
                    file=path,
                    handler=cls or bean_id,
                    url=url,
                    evidence=[Evidence(file=path, line=line)],
                ),
            )
            if cls:
                facts.ref(
                    kind="calls",
                    source=f"service:{sid}",
                    source_label=url,
                    target=cls,
                    target_kinds=["java-class"],
                    file=path,
                    line=line,
                )
            facts.symbol("service", f"bean:{path}#{bean_id}", path, line, owner=sid)

    def _batch_job(
        self, path: str, job: ET.Element, beans: dict[str, ET.Element], facts: Facts
    ) -> None:
        a = attrs(job)
        name = a.get("id", "")
        line = line_of(job)
        steps: list[str] = []
        programs: list[str] = []
        jid = make_id("batch_job", "spring-batch", name)
        for step in job.iter("step"):
            sid = attrs(step).get("id", "")
            if sid:
                steps.append(sid)
            for el in step.iter():
                ea = attrs(el)
                for key in ("ref", "reader", "writer", "processor"):
                    ref = ea.get(key)
                    if ref and ref in beans:
                        cls = attrs(beans[ref]).get("class", "")
                        if cls and cls not in programs:
                            programs.append(cls)
                            facts.ref(
                                kind="calls",
                                source=f"batch_job:{jid}",
                                source_label=name,
                                target=cls,
                                target_kinds=["java-class"],
                                file=path,
                                line=line_of(el),
                                report_unresolved=False,
                            )
        facts.add(
            "batch_jobs",
            BatchJob(
                id=jid,
                name=name,
                scheduler="spring-batch",
                steps=steps,
                programs=programs,
                evidence=[Evidence(file=path, line=line)],
            ),
        )
        facts.symbol("spring-batch-job", name, path, line, owner=jid)
        facts.add("tech_stack", tech("Spring Batch", None, "framework", path, line))

    # ---- web flow -----------------------------------------------------------------------------
    def _flow(self, path: str, root: ET.Element, facts: Facts) -> None:
        name = path.rsplit("/", 1)[-1].rsplit(".", 1)[0]
        sid = make_id("service", "webflow", path, name)
        facts.add(
            "services",
            Service(
                id=sid,
                name=name,
                kind="webflow",
                file=path,
                evidence=[Evidence(file=path, line=line_of(root))],
            ),
        )
        facts.add("tech_stack", tech("Spring Web Flow", None, "framework", path, line_of(root)))
        for vs in root.iter("view-state"):
            a = attrs(vs)
            view = a.get("view") or a.get("id", "")
            if view and not view.startswith(("externalRedirect:", "flowRedirect:")):
                facts.ref(
                    kind="renders",
                    source=f"service:{sid}",
                    source_label=name,
                    target=view,
                    target_kinds=["view", "path"],
                    file=path,
                    line=line_of(vs),
                )

    # ---- data access mappings -----------------------------------------------------------------
    def _mybatis(
        self, path: str, root: ET.Element, facts: Facts, ctx: AnalyzerContext, kind: str
    ) -> None:
        facts.add(
            "tech_stack",
            tech(
                "MyBatis" if kind == "mybatis" else "iBATIS", None, "framework", path, line_of(root)
            ),
        )
        ns = root.get("namespace", "")
        for el in root:
            if el.tag not in ("select", "insert", "update", "delete", "statement", "procedure"):
                continue
            raw = " ".join(t.strip() for t in el.itertext() if t.strip())
            dynamic = (
                any(c.tag in DYNAMIC_TAGS for c in el.iter())
                or "${" in raw
                or (kind == "ibatis" and re.search(r"\$\w+\$", raw) is not None)
            )
            sql = re.sub(r"#\{[^}]*\}|#[\w.]+(:\w+)?#", "?", raw)
            holes = {"value": 0, "structural": 0}

            def _subst(m: re.Match[str], text: str = sql, counts: dict[str, int] = holes) -> str:
                counts[hole_kind(text[: m.start()])] += 1
                return "?"

            sql = re.sub(r"\$\{[^}]*\}|\$[\w.]+\$", _subst, sql)
            optional = sum(
                1
                for c in el.iter()
                if c.tag in ("if", "when", "otherwise", "foreach") or str(c.tag).startswith("is")
            )
            fact = sql_fact(
                file=path,
                line=line_of(el) or 1,
                text=sql,
                source=kind,
                dialect=ctx.config.sql.dialect,
                dynamic=dynamic,
            )
            fact.evidence[0].snippet = f"{ns}.{attrs(el).get('id', '')}"
            fact.value_holes, fact.structural_holes = holes["value"], holes["structural"]
            fact.optional_fragments = optional
            if optional:
                fact.built_with = [f"{kind}-dynamic-tags"]
            facts.add("sql_statements", fact)

    def _hbm(self, path: str, root: ET.Element, facts: Facts, ctx: AnalyzerContext) -> None:
        facts.add("tech_stack", tech("Hibernate", None, "framework", path, line_of(root)))
        pkg = root.get("package", "")
        for c in root.iter("class"):
            a = attrs(c)
            name = a.get("name", "")
            short = _short(name)
            fqn = f"{pkg}.{name}" if pkg and "." not in name else name
            facts.symbol(
                "jpa-entity",
                short,
                path,
                line_of(c),
                data={"table": (a.get("table") or short).upper(), "fqn": fqn},
            )
        for q in root.iter("query"):
            facts.add(
                "sql_statements",
                sql_fact(
                    file=path,
                    line=line_of(q) or 1,
                    text=text_of(q),
                    source="hibernate",
                    dialect=None,
                    hql=True,
                ),
            )
        for q in root.iter("sql-query"):
            facts.add(
                "sql_statements",
                sql_fact(
                    file=path,
                    line=line_of(q) or 1,
                    text=" ".join(t.strip() for t in q.itertext() if t.strip()),
                    source="native",
                    dialect=ctx.config.sql.dialect,
                ),
            )
        _ = child_text
