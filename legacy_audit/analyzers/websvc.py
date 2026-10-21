"""Web services (REST and SOAP): the shared builders analyzers use, and the analyzer for the XML
files that describe or deploy web services.

Services the application **offers** are ``Service`` facts (``rest-operation``,
``soap-operation``, routes) found by the language analyzers; the XML deployment files add the
ones written only in configuration (Apache Axis ``*.wsdd``, Axis2 ``services.xml``). Services it
**calls** are ``web_services`` facts with ``side: called``, one per call site; WSDL files are
``side: contract`` until the linker knows whether the application offers or calls the service
they describe. The linker (``linker/websvc.py``) folds all of them into one row per web service
(decision 133).

This analyzer reads:

- WSDL 1.1 and 2.0 files: service, ports and their address, port type operations, namespace;
- Apache Axis 1 deployment descriptors (``server-config.wsdd``, ``deploy.wsdd``);
- Apache Axis2 ``services.xml``;
- JAX-WS RI ``sun-jaxws.xml`` and Apache CXF ``jaxws:``/``jaxrs:`` Spring XML (where each
  implementation class is published, and CXF clients);
- ``web.xml`` servlets of web service frameworks (Axis, Axis2, CXF, Jersey, RESTEasy, JAX-WS RI,
  Spring-WS) and the URL they are mapped to.
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from pathlib import PurePosixPath
from typing import ClassVar
from urllib.parse import urlsplit

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import tech
from legacy_audit.analyzers.integrations import IGNORED_HOSTS, mask_userinfo
from legacy_audit.analyzers.textutil import attrs, child_text, line_of, parse_xml, text_of
from legacy_audit.model import Evidence, FileRecord, Service, WebOperation, WebService, make_id

HTTP_VERBS = ("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
WSDL_NS = ("schemas.xmlsoap.org/wsdl", "www.w3.org/ns/wsdl")

# web.xml servlet class -> framework of the services it publishes (the ``web`` analyzer adds
# the technology and keeps them out of the servlet count)
SERVLETS = {
    "org.apache.axis.transport.http.AxisServlet": "Apache Axis",
    "org.apache.axis2.transport.http.AxisServlet": "Apache Axis2",
    "org.apache.cxf.transport.servlet.CXFServlet": "Apache CXF",
    "com.sun.jersey.spi.container.servlet.ServletContainer": "JAX-RS",
    "org.glassfish.jersey.servlet.ServletContainer": "JAX-RS",
    "org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher": "JAX-RS",
    "org.apache.wink.server.internal.servlet.RestServlet": "JAX-RS",
    "com.sun.xml.ws.transport.http.servlet.WSServlet": "JAX-WS",
    "org.springframework.ws.transport.http.MessageDispatcherServlet": "Spring-WS",
}


# ---- builders used by every analyzer ------------------------------------------------------------
def split_url(text: str) -> tuple[str | None, str]:
    """(``scheme://host[:port]`` or None, path) of a URL found in code. The base is None when the
    text is a path, or when the host is built at run time (``?``) or is a placeholder."""
    text = mask_userinfo(text.strip().strip("'\"`"))
    if "://" not in text:
        # a base URL from configuration or a variable: ${payment.url}/charges, ?/orders
        text = re.sub(r"^(\$\{[^}]*\}|\{\{[^}]*\}\}|\?(?=/))+", "", text)
        return None, re.split(r"[#]|\?(?=\w+=)", text, maxsplit=1)[0]
    scheme, rest = text.split("://", 1)
    netloc, _, tail = rest.partition("/")
    path = re.split(r"[#]|\?(?=\w+=)|\?wsdl$", "/" + tail if tail else "", maxsplit=1,
                    flags=re.I)[0].rstrip("/")  # fmt: skip
    if "?" in netloc or "$" in netloc or "{" in netloc or "%s" in netloc:
        return None, path
    host = urlsplit(f"{scheme}://{netloc}").hostname or ""
    if not host or scheme.lower() not in ("http", "https") or IGNORED_HOSTS.search(host):
        return None, path
    return f"{scheme.lower()}://{netloc.rsplit('@', 1)[-1].lower()}", path


def operation(
    name: str,
    *,
    method: str | None = None,
    path: str | None = None,
    handler: str | None = None,
    file: str | None = None,
    line: int | None = None,
) -> WebOperation:
    return WebOperation(
        name=name, method=method, path=path, handler=handler, file=file, line=line
    )


def rest_op_name(method: str | None, path: str | None) -> str:
    return f"{(method or 'ANY').upper()} {path or '/'}"


def called(
    *,
    style: str,
    name: str,
    framework: str,
    file: str,
    line: int | None,
    url: str | None = None,
    op: str | None = None,
    method: str | None = None,
    handler: str | None = None,
    wsdl: str | None = None,
    namespace: str | None = None,
) -> WebService:
    """One call site of another system's web service. ``url`` is the address as written (an
    absolute URL, a path or a ``?`` hole); ``op`` the SOAP operation, when known."""
    base, path = split_url(url) if url else (None, "")
    ops: list[WebOperation] = []
    if style == "REST" and (url or method):
        ops.append(
            operation(rest_op_name(method, path or None), method=(method or "ANY").upper(),
                      path=path or None, handler=handler, file=file, line=line)
        )  # fmt: skip
    elif op:
        ops.append(operation(op, handler=handler, file=file, line=line, path=path or None))
    full = (base or "") + path if base else (url or None)
    return WebService(
        id=make_id("websvc-use", "called", style, file, line, name, op or "", method or "", url),
        name=name,
        style=style,
        side="called",
        frameworks=[framework],
        url=full,
        wsdl=wsdl,
        namespace=namespace,
        files=[file],
        callers=[file],
        operations=ops,
        evidence=[Evidence(file=file, line=line)],
    )


def offered_service(
    facts: Facts,
    *,
    name: str,
    kind: str,
    file: str,
    line: int | None,
    handler: str,
    url: str | None,
    protocol: str,
    framework: str,
    web_service: str | None,
) -> None:
    """A web service operation the application offers, as a ``Service`` fact."""
    sid = make_id("service", kind, file, name)
    facts.add(
        "services",
        Service(
            id=sid,
            name=name,
            kind=kind,
            file=file,
            handler=handler,
            url=url,
            protocol=protocol,
            framework=framework,
            web_service=web_service,
            evidence=[Evidence(file=file, line=line)],
        ),
    )
    facts.symbol("service", handler, file, line, owner=sid)
    if file.endswith((".wsdd", ".xml")) and "." in handler:
        cls = handler if handler.rsplit(".", 1)[-1][:1].isupper() else handler.rsplit(".", 1)[0]
        facts.ref(kind="calls", source=f"service:{sid}", source_label=name, target=cls,
                  target_kinds=["java-class"], file=file, line=line)  # fmt: skip


# ---- the XML analyzer ------------------------------------------------------------------------
def _is_wsdl(path: str, text: str) -> bool:
    if path.lower().endswith(".wsdl"):
        return True
    head = text[:4000]
    return bool(re.search(r"<(\w+:)?(definitions|description)\b", head)) and any(
        n in head for n in WSDL_NS
    )


def _local(qname: str) -> str:
    return qname.rsplit(":", 1)[-1].rsplit("}", 1)[-1]


class WebServicesAnalyzer:
    name: ClassVar[str] = "webservices"
    version: ClassVar[str] = "1"

    def supports(self, file: FileRecord) -> bool:
        return file.language == "xml" and file.category not in ("binary", "test")

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        facts = Facts()
        text = ctx.text or ""
        facts.coverage.lines = text.count("\n") + 1
        path = file.path
        low = path.lower()
        base = PurePosixPath(low).name
        wanted = (
            _is_wsdl(path, text)
            or base.endswith(".wsdd")
            or base in ("services.xml", "sun-jaxws.xml", "web.xml")
            or "cxf.apache.org/jax" in text
        )
        if not wanted:
            return facts
        try:
            root = parse_xml(text)
        except ET.ParseError:
            facts.coverage.gap(1, facts.coverage.lines, "XML parse error")
            return facts
        if _is_wsdl(path, text):
            self._wsdl(root, path, facts)
        elif base.endswith(".wsdd"):
            self._wsdd(root, path, facts)
        elif base == "services.xml":
            self._axis2(root, path, facts)
        elif base == "sun-jaxws.xml":
            for ep in root.iter("endpoint"):
                a = attrs(ep)
                if a.get("implementation"):
                    self._endpoint(facts, path, line_of(ep), a["implementation"],
                                   a.get("url-pattern", ""), "JAX-WS")  # fmt: skip
        elif base == "web.xml":
            self._web_xml(root, path, facts)
        if "cxf.apache.org/jax" in text:
            self._cxf(root, path, facts)
        return facts

    # ---- WSDL -------------------------------------------------------------------------------
    def _wsdl(self, root: ET.Element, path: str, facts: Facts) -> None:
        ns = attrs(root).get("targetNamespace")
        ops: list[WebOperation] = []
        seen: set[str] = set()
        for pt in [*root.iter("portType"), *root.iter("interface")]:
            for op in pt.findall("operation"):
                name = attrs(op).get("name")
                if name and name not in seen:
                    seen.add(name)
                    ops.append(operation(name, file=path, line=line_of(op)))
        spaces = {e.get("__ns__") or "" for e in root.iter()}
        soap = any("soap" in s for s in spaces)
        http_only = not soap and any(s.endswith(("wsdl/http/", "wsdl/http")) for s in spaces)
        services = list(root.iter("service"))
        if not services:
            name = next((attrs(p).get("name") for p in root.iter("portType")), None)
            self._contract(facts, path, name or PurePosixPath(path).stem, None, ns, ops,
                           line_of(root), http_only)  # fmt: skip
            return
        for svc in services:
            name = attrs(svc).get("name") or PurePosixPath(path).stem
            address = None
            for el in svc.iter():
                a = attrs(el)
                if el.tag == "address" and a.get("location"):
                    address = a["location"]
                    break
                if el.tag == "endpoint" and a.get("address"):
                    address = a["address"]
                    break
            self._contract(facts, path, name, address, ns, ops, line_of(svc), http_only)

    def _contract(
        self,
        facts: Facts,
        path: str,
        name: str,
        address: str | None,
        ns: str | None,
        ops: list[WebOperation],
        line: int | None,
        http_only: bool,
    ) -> None:
        url = mask_userinfo(address) if address else None
        facts.add(
            "web_services",
            WebService(
                id=make_id("websvc-use", "contract", path, name),
                name=name,
                style="REST" if http_only else "SOAP",
                side="contract",
                frameworks=["WSDL"],
                url=url,
                wsdl=path,
                namespace=ns,
                files=[path],
                operations=[o.model_copy() for o in ops],
                evidence=[Evidence(file=path, line=line)],
            ),
        )

    # ---- Axis 1 and Axis2 ---------------------------------------------------------------------
    def _wsdd(self, root: ET.Element, path: str, facts: Facts) -> None:
        if root.tag != "deployment":
            return
        for svc in root.findall("service"):
            a = attrs(svc)
            name = a.get("name")
            if not name or not str(a.get("provider", "")).lower().startswith("java:"):
                continue
            params = {attrs(p).get("name", ""): attrs(p).get("value", "") for p in
                      svc.findall("parameter")}  # fmt: skip
            cls = params.get("className") or params.get("beanJndiName") or name
            methods = re.split(r"[\s,]+", params.get("allowedMethods", "*").strip()) or ["*"]
            for m in [x for x in methods if x] or ["*"]:
                offered_service(
                    facts,
                    name=f"{name}.{m}",
                    kind="soap-operation",
                    file=path,
                    line=line_of(svc),
                    handler=f"{cls}.{m}" if m != "*" else cls,
                    url=f"/services/{name}",
                    protocol="SOAP",
                    framework="Apache Axis",
                    web_service=name,
                )
            if params.get("wsdlFile"):
                facts.symbol("ws-wsdl", name, path, line_of(svc), data={"wsdl": params["wsdlFile"]})
        facts.add("tech_stack", tech("Apache Axis", None, "framework", path, line_of(root)))

    def _axis2(self, root: ET.Element, path: str, facts: Facts) -> None:
        svcs = [root] if root.tag == "service" else list(root.iter("service"))
        if not svcs:
            return
        for svc in svcs:
            name = attrs(svc).get("name") or PurePosixPath(path).parent.parent.name
            cls = ""
            for p in svc.findall("parameter"):
                if attrs(p).get("name") in ("ServiceClass", "SpringBeanName"):
                    cls = text_of(p)
            ops = [attrs(o).get("name", "") for o in svc.findall("operation")]
            for m in [o for o in ops if o] or ["*"]:
                offered_service(
                    facts,
                    name=f"{name}.{m}",
                    kind="soap-operation",
                    file=path,
                    line=line_of(svc),
                    handler=f"{cls or name}.{m}" if m != "*" else cls or name,
                    url=f"/services/{name}",
                    protocol="SOAP",
                    framework="Apache Axis2",
                    web_service=name,
                )
        facts.add("tech_stack", tech("Apache Axis2", None, "framework", path, line_of(root)))

    # ---- where implementation classes are published ------------------------------------------
    @staticmethod
    def _endpoint(
        facts: Facts, path: str, line: int | None, cls: str, url: str, framework: str
    ) -> None:
        facts.symbol(
            "ws-endpoint", cls, path, line, data={"url": url, "framework": framework}
        )

    def _web_xml(self, root: ET.Element, path: str, facts: Facts) -> None:
        names: dict[str, tuple[str, int | None]] = {}
        for s in root.iter("servlet"):
            cls = (child_text(s, "servlet-class") or "").strip()
            sname = (child_text(s, "servlet-name") or "").strip()
            if cls in SERVLETS:
                names[sname] = (cls, line_of(s))
        for m in root.iter("servlet-mapping"):
            sname = (child_text(m, "servlet-name") or "").strip()
            if sname not in names:
                continue
            cls, line = names[sname]
            for u in m.findall("url-pattern"):
                pattern = text_of(u)
                facts.symbol(
                    "ws-servlet",
                    SERVLETS[cls],
                    path,
                    line_of(u) or line,
                    data={"url": pattern.rstrip("*").rstrip("/") or "/"},
                )

    def _cxf(self, root: ET.Element, path: str, facts: Facts) -> None:
        beans = {
            attrs(b).get("id", ""): attrs(b).get("class", "")
            for b in root.iter("bean")
            if attrs(b).get("id")
        }

        def resolve(ref: str) -> str:
            return beans.get(ref.lstrip("#"), "") if ref.startswith("#") else ref

        used = False
        for el in root.iter():
            a = attrs(el)
            ns = el.get("__ns__") or ""
            if "cxf.apache.org/jaxws" in ns and el.tag in ("endpoint", "server"):
                used = True
                impls = [resolve(a.get("implementor", "")), a.get("implementorClass", "")]
                for sb in el.iter("serviceBean"):
                    impls += [attrs(b).get("class", "") for b in sb.iter("bean")]
                    impls += [resolve("#" + attrs(r).get("bean", "")) for r in sb.iter("ref")]
                if a.get("serviceClass"):
                    impls.append(a["serviceClass"])
                for cls in {i for i in impls if i}:
                    self._endpoint(facts, path, line_of(el), cls, a.get("address", ""),
                                   "Apache CXF")  # fmt: skip
            elif "cxf.apache.org/jaxrs" in ns and el.tag == "server":
                used = True
                impls = []
                for sb in el.iter("serviceBeans"):
                    impls += [attrs(b).get("class", "") for b in sb.iter("bean")]
                    impls += [resolve("#" + attrs(r).get("bean", "")) for r in sb.iter("ref")]
                for cls in {i for i in impls if i}:
                    self._endpoint(facts, path, line_of(el), cls, a.get("address", ""),
                                   "Apache CXF")  # fmt: skip
            elif "cxf.apache.org/jaxws" in ns and el.tag == "client":
                used = True
                svc = a.get("serviceClass", "") or a.get("id", "client")
                facts.add(
                    "web_services",
                    called(
                        style="SOAP",
                        name=svc.rsplit(".", 1)[-1],
                        framework="Apache CXF",
                        file=path,
                        line=line_of(el),
                        url=a.get("address") or None,
                        wsdl=a.get("wsdlLocation") or None,
                    ),
                )
        if used:
            facts.add("tech_stack", tech("Apache CXF", None, "framework", path, line_of(root)))
