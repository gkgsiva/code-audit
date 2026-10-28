"""One row per web service (decision 133).

**Offered** web services come from the ``Service`` facts that are web service operations
(``rest-operation``, ``soap-operation``, Node.js and NestJS routes, Spring handlers that return
data): operations of the same web service (``Service.web_service``, else the class or router
file) in one component are one row. Where the code is published (CXF ``jaxws:endpoint``,
``sun-jaxws.xml``, web.xml servlets) gives the row its address, and front-end or other code
calling a route is listed in ``callers``.

**Called** web services come from the client call sites the analyzers found and from the HTTP
and SOAP integrations (identity providers are login, not web services): one row per SOAP
endpoint address, per REST host, or per client name when the address is only known at run time.
A client whose name matches one with an address joins it.

**WSDL files** describe a service the application offers when an offered SOAP service has the
same name (``CustomerService``, ``CustomerServicePort``, ``CustomerServiceImpl`` are one name)
or most of its operations; else one it calls (same name or address); else, alone, a called
service when its address names another host, an offered one otherwise. A WSDL that describes
an offered service is not an outbound integration: its address is where the application is
called, so such integrations become inbound.
"""

from __future__ import annotations

import re
from collections import Counter
from collections.abc import Callable, Iterable

from legacy_audit.analyzers.base import Symbol
from legacy_audit.analyzers.integrations import describe, identity_provider
from legacy_audit.analyzers.websvc import split_url
from legacy_audit.model import (
    CodeEdge,
    Evidence,
    Integration,
    Service,
    WebOperation,
    WebService,
    make_id,
)

WS_KINDS = {
    "rest-operation": "REST",
    "soap-operation": "SOAP",
    "node-route": "REST",
    "nest-route": "REST",
}
KIND_FRAMEWORK = {"node-route": "Express", "nest-route": "NestJS"}
DECLARED_CLIENTS = {"Feign", "MicroProfile REST Client", "Retrofit"}
_SUFFIXES = (
    "soapbindingstub", "bindingstub", "soapbinding", "binding", "stub", "locator",
    "httpsoap11endpoint", "httpsoap12endpoint", "soap11", "soap12", "soapport", "porttype",
    "port", "soap", "implservice", "impl", "service", "endpoint", "proxy", "client", "ws",
)  # fmt: skip
_DISPLAY_SUFFIXES = re.compile(r"(SoapBindingStub|BindingStub|Stub|Locator|PortType|Port)$")
MAX_EVIDENCE = 50
ADDRESS_ONLY = "address in code or configuration"


def norm(name: str) -> str:
    """A web service name without the suffixes generated code adds: OrderService,
    OrderServicePort, OrderServiceSoapBindingStub and OrderServiceLocator are one name."""
    n = re.sub(r"[^a-z0-9]", "", name.lower())
    changed = True
    while changed:
        changed = False
        for suf in _SUFFIXES:
            if n.endswith(suf) and len(n) > len(suf):
                n = n[: -len(suf)]
                changed = True
    return n or name.lower()


def _union(a: list[str], b: Iterable[str]) -> list[str]:
    out = list(a)
    seen = set(a)
    for x in b:
        if x and x not in seen:
            seen.add(x)
            out.append(x)
    return out


def _ev(a: list[Evidence], b: Iterable[Evidence]) -> list[Evidence]:
    keys = {(e.file, e.line) for e in a}
    out = list(a)
    for e in b:
        if len(out) >= MAX_EVIDENCE:
            break
        if (e.file, e.line) not in keys:
            keys.add((e.file, e.line))
            out.append(e)
    return out


def _add_ops(ws: WebService, ops: Iterable[WebOperation]) -> None:
    have = {(o.name, o.handler) for o in ws.operations}
    names = {o.name for o in ws.operations}
    for o in ops:
        if (o.name, o.handler) in have or (o.name in names and o.service_id is None):
            continue
        have.add((o.name, o.handler))
        names.add(o.name)
        ws.operations.append(o.model_copy())


def _common_path(paths: list[str]) -> str | None:
    split = [[s for s in p.split("/") if s] for p in paths if p]
    if not split:
        return None
    first = split[0]
    n = 0
    while all(len(s) > n and s[n] == first[n] and not first[n].startswith((":", "{"))
              for s in split):  # fmt: skip
        n += 1
    for i, seg in enumerate(first[:n]):
        if re.search(r"\.(asmx|svc)$", seg, re.I):  # .NET: the service file is the address
            n = i + 1
            break
    return "/" + "/".join(first[:n])


def _class_of(handler: str | None) -> str | None:
    if not handler or "." not in handler or "/" in handler or ":" in handler:
        return None
    cls = handler.rsplit(".", 1)[0].rsplit(".", 1)[-1]
    return cls if cls[:1].isupper() else None


def _stem(path: str | None) -> str:
    base = (path or "").rsplit("/", 1)[-1]
    base = base.split(".", 1)[0] if "." in base else base
    return base or "routes"


def _group_name(svc: Service) -> str:
    if svc.web_service:
        return svc.web_service
    cls = _class_of(svc.handler)
    if cls and svc.kind != "node-route":
        return cls
    return _stem(svc.file)


def _op_of(svc: Service, style: str) -> WebOperation:
    line = svc.evidence[0].line if svc.evidence else None
    if style == "REST":
        method = svc.name.split(" ", 1)[0] if " " in svc.name else None
        return WebOperation(name=svc.name, method=method, path=svc.url, handler=svc.handler,
                            service_id=svc.id, file=svc.file, line=line)  # fmt: skip
    name = svc.name.rsplit(".", 1)[-1] if "." in svc.name else svc.name
    if svc.name.startswith("SOAP /"):
        name = "*"
    return WebOperation(name=name, path=svc.url, handler=svc.handler, service_id=svc.id,
                        file=svc.file, line=line)  # fmt: skip


def _plural(n: int, word: str) -> str:
    return f"{n} {word}{'' if n == 1 else 's'}"


class _Builder:
    def __init__(
        self,
        raw: list[WebService],
        services: list[Service],
        integrations: list[Integration],
        symbols: list[Symbol],
        edges: list[CodeEdge],
        comp: Callable[[str | None], str | None],
        node_file: Callable[[str], str | None],
    ) -> None:
        self.raw = raw
        self.services = services
        self.integrations = integrations
        self.symbols = symbols
        self.edges = edges
        self.comp = comp
        self.node_file = node_file
        self.offered: dict[tuple[str, ...], WebService] = {}
        self.called: dict[tuple[str, ...], WebService] = {}
        self.offered_wsdl: set[str] = set()
        self.first_base: dict[tuple[str, ...], str] = {}

    # ---- offered ------------------------------------------------------------------------------
    def build_offered(self) -> None:
        by_service: dict[str, WebService] = {}
        for svc in self.services:
            style = svc.protocol or WS_KINDS.get(svc.kind)
            if style not in ("REST", "SOAP"):
                continue
            name = _group_name(svc)
            key = ("offered", style, svc.component or "", name)
            ws = self.offered.get(key)
            if ws is None:
                ws = WebService(id=make_id("web_service", *key), name=name, style=style,
                                side="offered", component=svc.component)  # fmt: skip
                self.offered[key] = ws
            fw = svc.framework or KIND_FRAMEWORK.get(svc.kind) or style
            ws.frameworks = _union(ws.frameworks, [fw])
            ws.files = _union(ws.files, [svc.file] if svc.file else [])
            ws.evidence = _ev(ws.evidence, svc.evidence)
            _add_ops(ws, [_op_of(svc, style)])
            by_service[svc.id] = ws
        # Where the implementation classes are published (CXF, sun-jaxws.xml) and the web
        # service servlets of each component.
        endpoints: dict[str, Symbol] = {s.name: s for s in self.symbols if s.kind == "ws-endpoint"}
        servlets: dict[tuple[str, str], str] = {}
        for s in self.symbols:
            if s.kind == "ws-servlet":
                servlets.setdefault((self.comp(s.file) or "", s.name), s.data.get("url", ""))
        for ws in self.offered.values():
            classes = {o.handler.rsplit(".", 1)[0] for o in ws.operations if o.handler}
            ep = next((endpoints[c] for c in sorted(classes) if c in endpoints), None)
            paths = [o.path for o in ws.operations if o.path]
            url = _common_path(paths) if paths else None
            if ep is not None:
                ws.frameworks = _union(ws.frameworks, [ep.data.get("framework", "")])
                ws.files = _union(ws.files, [ep.file])
                address = ep.data.get("url", "")
                if ws.style == "SOAP" or not url:
                    url = address or url
                elif address:
                    url = "/" + "/".join(p.strip("/") for p in (address, url) if p.strip("/"))
            for fw in ws.frameworks:
                prefix = servlets.get((ws.component or "", fw))
                if prefix and url and not url.startswith(prefix) and prefix != "/":
                    url = prefix.rstrip("/") + "/" + url.lstrip("/")
                elif prefix and not url and prefix != "/":
                    url = prefix
            ws.url = url
            ws.components = [ws.component] if ws.component else []
        # Callers: files whose calls resolve to one of the routes.
        for e in self.edges:
            if e.kind != "calls" or not e.resolved or e.target not in by_service:
                continue
            f = self.node_file(e.source)
            ws = by_service[e.target]
            if f and f not in ws.files:
                ws.callers = _union(ws.callers, [f])

    # ---- called -------------------------------------------------------------------------------
    def _key(self, style: str, url: str | None, name: str) -> tuple[str, ...]:
        base, path = split_url(url or "")
        if base:
            if style == "SOAP":
                return ("called", "SOAP", base + path.lower())
            # One REST row per host, http or https (decision 177); the id keeps the first base.
            k = ("called", "REST", host_key(base))
            self.first_base.setdefault(k, base)
            return k
        return ("called", style, "name:" + norm(name))

    def _resolve(self, uses: list[WebService]) -> None:
        """``${payment.url}/charges``: the address configured under that key."""
        by_key = {it.details["key"]: it.target for it in self.integrations
                  if it.type in ("HTTP", "SOAP") and it.details.get("key")}  # fmt: skip
        for w in uses:
            m = re.match(r"^\$\{([^}:]+)(?::[^}]*)?\}", w.url or "")
            if m and m.group(1) in by_key:
                url = by_key[m.group(1)].rstrip("/") + (w.url or "")[m.end() :]
                base, _ = split_url(url)
                w.url = url
                for o in w.operations:
                    if o.path and base:
                        o.path = split_url(by_key[m.group(1)].rstrip("/") + o.path)[1]
                        o.name = f"{o.method or 'ANY'} {o.path}"

    def build_called(self) -> None:
        uses = [w for w in self.raw if w.side == "called"]
        self._resolve(uses)
        named: dict[str, tuple[str, ...]] = {}
        for w in uses:
            k = self._key(w.style, w.url, w.name)
            if not k[2].startswith("name:"):
                named.setdefault(norm(w.name), k)
                if w.style == "SOAP":
                    base, path = split_url(w.url or "")
                    last = path.rstrip("/").rsplit("/", 1)[-1]
                    if last:
                        named.setdefault(norm(last), k)
        names: dict[tuple[str, ...], Counter[str]] = {}
        for w in uses:
            k = self._key(w.style, w.url, w.name)
            if k[2].startswith("name:") and w.style == "SOAP":
                k = named.get(norm(w.name), k)
            ws = self._called_row(k, w.style)
            ws.frameworks = _union(ws.frameworks, w.frameworks)
            ws.files = _union(ws.files, w.files)
            ws.callers = _union(ws.callers, w.callers)
            ws.evidence = _ev(ws.evidence, w.evidence)
            ws.wsdl = ws.wsdl or w.wsdl
            ws.namespace = ws.namespace or w.namespace
            if w.url and "://" in w.url and not ws.url:
                base, path = split_url(w.url)
                ws.url = (base or "") + (path if w.style == "SOAP" else "") or None
            _add_ops(ws, w.operations)
            names.setdefault(k, Counter())[self._display(w)] += 1
        own = {ws.component: ws for ws in self.offered.values() if ws.component}
        for it in self.integrations:
            if it.type not in ("HTTP", "SOAP") or it.direction == "inbound":
                continue
            if identity_provider(it):
                continue
            if all(f.lower().endswith(".wsdl") for f in it.source_files):
                continue  # a WSDL address: the contract's own row has it
            base, path = split_url(it.target)
            if not base:
                continue
            label = base.split("://", 1)[1].split(".", 1)[0].split(":", 1)[0]
            if label in own and not any(self._key(w.style, w.url, w.name)[2] == host_key(base)
                                        for w in uses):  # fmt: skip
                continue  # the application's own API, deployed under its component's name
            soap_key = ("called", "SOAP", base + path.lower())
            k = soap_key if soap_key in self.called or it.type == "SOAP" else (
                "called", "REST", host_key(base)
            )  # fmt: skip
            if k[1] == "REST":  # a SOAP row is one service of the host, named by its path
                self.first_base.setdefault(k, base)
            ws = self._called_row(k, "SOAP" if k[1] == "SOAP" else "REST")
            ws.integrations = _union(ws.integrations, [it.id])
            ws.files = _union(ws.files, it.source_files)
            ws.evidence = _ev(ws.evidence, it.evidence)
            if not ws.url:
                ws.url = base + (path if k[1] == "SOAP" else "")
            if (
                k[1] == "REST"
                and path
                and not any((o.path or "").startswith(path) for o in ws.operations)
            ):
                _add_ops(
                    ws,
                    [
                        WebOperation(
                            name=f"ANY {path}",
                            method="ANY",
                            path=path,
                            file=it.evidence[0].file if it.evidence else None,
                            line=it.evidence[0].line if it.evidence else None,
                        )
                    ],
                )
            if k not in names:
                last = path.rstrip("/").rsplit("/", 1)[-1]
                last = re.sub(r"\.(asmx|svc)$", "", last, flags=re.I)
                names[k] = Counter({(last if k[1] == "SOAP" and last else base.split("://")[1]): 1})
        for k, ws in self.called.items():
            ws.name = self._pick_name(k, names.get(k, Counter()), ws)
            if not ws.frameworks:
                ws.frameworks = [ADDRESS_ONLY]
        # Integrations without a host (CICS INVOKE WEBSERVICE names) join the row of that name.
        for it in self.integrations:
            if it.type in ("HTTP", "SOAP") and "://" not in it.target:
                for ws in self.called.values():
                    if ws.name and re.search(rf"\b{re.escape(ws.name.upper())}\b",
                                             it.target.upper()):  # fmt: skip
                        ws.integrations = _union(ws.integrations, [it.id])

    def _called_row(self, k: tuple[str, ...], style: str) -> WebService:
        ws = self.called.get(k)
        if ws is None:
            first = self.first_base.get(k) if k[1] == "REST" else None
            id_key = (*k[:2], first or k[2], *k[3:])
            ws = WebService(
                id=make_id("web_service", *id_key), name=k[2], style=style, side="called"
            )
            self.called[k] = ws
        elif style == "SOAP" and ws.style != "SOAP":
            ws.style = "SOAP"
        return ws

    @staticmethod
    def _display(w: WebService) -> str:
        return _DISPLAY_SUFFIXES.sub("", w.name) or w.name

    @staticmethod
    def _pick_name(k: tuple[str, ...], names: Counter[str], ws: WebService) -> str:
        if k[1] == "REST" and not k[2].startswith("name:"):
            declared = [n for n in names if n and ws.frameworks and
                        set(ws.frameworks) & DECLARED_CLIENTS]  # fmt: skip
            host = k[2].split("://", 1)[-1]
            if declared and set(ws.frameworks) <= DECLARED_CLIENTS:
                return sorted(declared)[0]
            return host
        if not names:
            return k[2].removeprefix("name:")
        return sorted(names.items(), key=lambda x: (-x[1], len(x[0]), x[0]))[0][0]

    # ---- WSDL contracts ------------------------------------------------------------------------
    def attach_contracts(self) -> None:
        wsdd = {s.name: s.data.get("wsdl", "") for s in self.symbols if s.kind == "ws-wsdl"}
        for c in (w for w in self.raw if w.side == "contract"):
            cname = norm(c.name)
            c_ops = {o.name.lower() for o in c.operations}
            base_name = (c.wsdl or "").rsplit("/", 1)[-1].lower()
            match = None
            for ws in self.offered.values():
                if ws.style != "SOAP":
                    continue
                ops = {o.name.lower() for o in ws.operations}
                hint = wsdd.get(ws.name, "").rsplit("/", 1)[-1].lower()
                if (
                    norm(ws.name) == cname
                    or (base_name and hint == base_name)
                    or (c_ops and len(c_ops & ops) * 2 >= len(c_ops))
                ):
                    match = ws
                    break
            if match is None:
                ckey = self._key("SOAP", c.url, c.name)
                for k, ws in self.called.items():
                    hint = (ws.wsdl or "").split("?", 1)[0].rsplit("/", 1)[-1].lower()
                    if k == ckey or norm(ws.name) == cname or (base_name and hint == base_name):
                        match = ws
                        break
            if match is None:
                base, _ = split_url(c.url or "")
                side = "called" if base else "offered"
                key = (side, c.style, "wsdl:" + (c.wsdl or c.name) + "#" + c.name)
                match = WebService(id=make_id("web_service", *key), name=c.name, style=c.style,
                                   side=side, url=c.url)  # fmt: skip
                (self.called if side == "called" else self.offered)[key] = match
                if side == "offered":
                    match.component = self.comp(c.wsdl)
            match.wsdl = c.wsdl or match.wsdl
            match.namespace = match.namespace or c.namespace
            ckey = self._key(c.style, c.url, c.name)
            for it in self.integrations:
                if (
                    c.wsdl in it.source_files
                    and self._key("SOAP" if c.style == "SOAP" else "REST", it.target, "") == ckey
                ):
                    match.integrations = _union(match.integrations, [it.id])
            match.frameworks = _union(match.frameworks, ["WSDL"])
            match.files = _union(match.files, [c.wsdl] if c.wsdl else [])
            match.evidence = _ev(match.evidence, c.evidence)
            if not match.url and c.url:
                match.url = c.url
            if match.side == "called" or not any(o.service_id for o in match.operations):
                _add_ops(match, c.operations)
            if match.side == "offered" and c.wsdl:
                self.offered_wsdl.add(c.wsdl)

    # ---- finish -------------------------------------------------------------------------------
    def finish(self) -> list[WebService]:
        out = [*self.offered.values(), *self.called.values()]
        for ws in out:
            comps = Counter(c for f in [*ws.files, *ws.callers] if (c := self.comp(f)))
            if ws.side == "called" or not ws.component:
                ws.component = ws.component or (comps.most_common(1)[0][0] if comps else None)
            ws.components = sorted(set(comps) | ({ws.component} if ws.component else set()))
            ws.operations.sort(key=lambda o: (o.path or "", o.name, o.handler or ""))
            ws.callers.sort()
            ws.files.sort()
            ws.description = _describe(ws)
        # A WSDL of an offered service names where the application is called, not a partner.
        for it in self.integrations:
            if (
                it.type in ("HTTP", "SOAP")
                and it.direction in ("outbound", "unknown", "defined")
                and it.source_files
                and set(it.source_files) <= self.offered_wsdl
            ):
                it.direction = "inbound"
                it.purpose, it.description = describe(it)
        return sorted(out, key=lambda w: (w.side != "offered", w.style, w.name.lower(), w.id))


def host_key(base: str) -> str:
    """``host[:port]`` of a base URL without its scheme or default port: http and https calls to
    one host are one service."""
    netloc = base.split("://", 1)[-1].lower()
    for default in (":80", ":443"):
        netloc = netloc.removesuffix(default)
    return netloc


def _describe(ws: WebService) -> str:
    n = len(ws.operations)
    fw = ", ".join(f for f in ws.frameworks if f) or ws.style
    if ws.side == "offered":
        if not any(o.service_id for o in ws.operations):
            return (
                f"{ws.style} service described by {ws.wsdl or 'a WSDL file'}"
                f" ({_plural(n, 'operation')}); no implementation was found in the scanned code."
            )
        where = f" under {ws.url}" if ws.url else ""
        callers = (
            f" Called by {_plural(len(ws.callers), 'file')} of the scanned code."
            if ws.callers
            else ""
        )
        return f"Offers {_plural(n, ws.style + ' operation')}{where} ({fw}).{callers}"
    at = f" at {ws.url}" if ws.url else " (address set at run time or in configuration)"
    ops = f", {_plural(n, 'operation')} seen" if n else ""
    places = len(ws.callers) or len(ws.files)
    return f"Calls the {ws.name} {ws.style} service{at} from {_plural(places, 'file')} ({fw}{ops})."


def build_web_services(
    raw: list[WebService],
    services: list[Service],
    integrations: list[Integration],
    symbols: list[Symbol],
    edges: list[CodeEdge],
    comp: Callable[[str | None], str | None],
    node_file: Callable[[str], str | None],
) -> list[WebService]:
    b = _Builder(raw, services, integrations, symbols, edges, comp, node_file)
    b.build_offered()
    b.build_called()
    b.attach_contracts()
    return b.finish()
