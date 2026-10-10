"""Java web tier: Struts 1/2 configuration, ``web.xml``, Tiles, JSF ``faces-config.xml`` and
pages (JSP, JSF facelets, HTML, Velocity, FreeMarker).

A page is a ``Screen``; ``.jspf``/``.tag`` files are partials, and the linker turns pages that are
only ever included (never forwarded to) into partials too. HTML pages count as screens only when
something links or forwards to them.
"""

from __future__ import annotations

import posixpath
import re
import xml.etree.ElementTree as ET
from typing import ClassVar

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import tech
from legacy_audit.analyzers.textutil import (
    LineIndex,
    attrs,
    child_text,
    line_of,
    parse_xml,
    text_of,
)
from legacy_audit.model import EntryPoint, Evidence, FileRecord, Screen, Service, make_id

FRAMEWORK_SERVLETS = {
    "org.apache.struts.action.ActionServlet": "Struts 1",
    "org.springframework.web.servlet.DispatcherServlet": "Spring MVC",
    "javax.faces.webapp.FacesServlet": "JSF",
    "jakarta.faces.webapp.FacesServlet": "JSF",
    "org.apache.axis.transport.http.AxisServlet": "Apache Axis",
    "org.apache.cxf.transport.servlet.CXFServlet": "Apache CXF",
    "com.sun.xml.ws.transport.http.servlet.WSServlet": "JAX-WS RI",
    "org.springframework.ws.transport.http.MessageDispatcherServlet": "Spring-WS",
    "org.apache.tiles.web.startup.TilesServlet": "Tiles",
}
FRAMEWORK_FILTERS = {
    "org.apache.struts2.dispatcher.FilterDispatcher": "Struts 2",
    "org.apache.struts2.dispatcher.ng.filter.StrutsPrepareAndExecuteFilter": "Struts 2",
    "org.apache.struts2.dispatcher.filter.StrutsPrepareAndExecuteFilter": "Struts 2",
    "org.springframework.web.filter.DelegatingFilterProxy": "Spring Security",
}
FRAMEWORK_LISTENERS = {"org.springframework.web.context.ContextLoaderListener": "Spring Framework"}

PAGE_LANGS = {"jsp", "html", "velocity", "freemarker"}

_FIELD_TAGS = re.compile(
    r"<\s*(?:"
    r"html:(?:text|password|textarea|select|checkbox|radio|file|multibox)"
    r"|form:(?:input|password|select|textarea|checkbox|checkboxes|radiobutton|radiobuttons)"
    r"|s:(?:textfield|password|select|textarea|checkbox|radio|file|datetimepicker)"
    r"|h:(?:inputText|inputSecret|inputTextarea|selectOneMenu|selectManyMenu|selectOneListbox|"
    r"selectManyListbox|selectOneRadio|selectBooleanCheckbox|selectManyCheckbox)"
    r"|p:(?:inputText|password|inputTextarea|selectOneMenu|calendar|autoComplete)"
    r"|select|textarea"
    r")\b",
    re.I,
)
_INPUT = re.compile(r"<\s*input\b([^>]*)>", re.I)
_INPUT_TYPE = re.compile(r"type\s*=\s*[\"']?(\w+)", re.I)
_FORM_ACTION = re.compile(
    r"<\s*(?:html:form|s:form|form:form|form)\b[^>]*?\baction\s*=\s*[\"']([^\"']+)", re.I
)
_INCLUDE_DIRECTIVE = re.compile(r"<%@\s*include\s+file\s*=\s*[\"']([^\"']+)", re.I)
_INCLUDE_ACTION = re.compile(
    r"<\s*(?:jsp:include|c:import|tiles:insert|tiles:insertTemplate|ui:include|ui:composition"
    r"|ui:decorate)\b[^>]*?\b(?:page|url|template|src)\s*=\s*[\"']([^\"'$#]+)",
    re.I,
)
_TILES_DEF = re.compile(
    r"<\s*tiles:insert(?:Definition)?\b[^>]*?\b(?:definition|name)\s*=\s*[\"']([^\"'$]+)", re.I
)
_HREF_ACTION = re.compile(
    r"\bhref\s*=\s*[\"']([^\"'?#$<]+?\.(?:do|action|htm|html|jsp|xhtml|jsf))", re.I
)
_VELOCITY_INCLUDE = re.compile(r"#(?:parse|include)\s*\(\s*[\"']([^\"']+)", re.I)
_FTL_INCLUDE = re.compile(r"<#(?:include|import)\s+[\"']([^\"']+)", re.I)
_JSF_MARK = re.compile(r"xmlns:h\s*=|<f:view|<h:form|java\.sun\.com/jsf|xmlns\.jcp\.org/jsf", re.I)


def _rel_target(base_file: str, target: str) -> str:
    """Resolve a page reference: '/x' is webapp-relative; else relative to the page folder."""
    if target.startswith("/"):
        return target
    return posixpath.normpath(posixpath.join(posixpath.dirname(base_file), target))


class WebAnalyzer:
    name: ClassVar[str] = "web"
    version: ClassVar[str] = "1"

    def supports(self, file: FileRecord) -> bool:
        base = file.path.rsplit("/", 1)[-1].lower()
        if file.language in PAGE_LANGS:
            return True
        return file.language == "xml" and (
            base in ("web.xml", "struts.xml", "faces-config.xml", "struts-default.xml")
            or base.startswith("struts-config")
            or "tiles" in base
        )

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        facts = Facts()
        text = ctx.text or ""
        facts.coverage.lines = text.count("\n") + 1
        if file.language in PAGE_LANGS:
            self._page(file, text, facts)
            return facts
        try:
            root = parse_xml(text)
        except ET.ParseError as err:
            facts.coverage.gap(1, facts.coverage.lines, f"XML parse error: {err}")
            return facts
        tag = root.tag
        if tag == "struts-config":
            self._struts1(file.path, root, facts)
        elif tag == "struts":
            self._struts2(file.path, root, facts)
        elif tag == "web-app":
            self._web_xml(file.path, root, facts)
        elif tag in ("tiles-definitions", "component-definitions"):
            self._tiles(file.path, root, facts)
        elif tag == "faces-config":
            self._faces(file.path, root, facts)
        return facts

    # ---- Struts -------------------------------------------------------------------------------
    def _forward_ref(
        self, facts: Facts, source: str, label: str, path: str, target: str, line: int | None
    ) -> None:
        target = target.strip()
        if not target:
            return
        clean = target.split("?", 1)[0]
        if clean.endswith((".do", ".action")) or (
            "." not in clean.rsplit("/", 1)[-1] and clean.startswith("/")
        ):
            facts.ref(
                kind="forwards",
                source=source,
                source_label=label,
                target=re.sub(r"\.(do|action)$", "", clean),
                target_kinds=["struts-action"],
                file=path,
                line=line,
            )
        elif (
            "/" not in clean
            and "." in clean
            and not clean.endswith((".jsp", ".html", ".vm", ".ftl"))
        ):
            facts.ref(
                kind="forwards",
                source=source,
                source_label=label,
                target=clean,
                target_kinds=["tiles-definition"],
                file=path,
                line=line,
            )
        else:
            facts.ref(
                kind="forwards",
                source=source,
                source_label=label,
                target=clean,
                target_kinds=["path"],
                file=path,
                line=line,
            )

    def _struts1(self, path: str, root: ET.Element, facts: Facts) -> None:
        facts.add("tech_stack", tech("Struts 1", None, "framework", path, line_of(root)))
        for fb in root.iter("form-bean"):
            a = attrs(fb)
            if a.get("type"):
                facts.ref(
                    kind="uses",
                    source=f"file:{path}",
                    source_label=path,
                    target=a["type"],
                    target_kinds=["java-class"],
                    file=path,
                    line=line_of(fb),
                )
        gf = root.find("global-forwards")
        for fwd in gf.iter("forward") if gf is not None else []:
            self._forward_ref(
                facts, f"file:{path}", path, path, attrs(fwd).get("path", ""), line_of(fwd)
            )
        for action in root.iter("action"):
            a = attrs(action)
            apath = a.get("path", "")
            if not apath:
                continue
            line = line_of(action)
            sid = make_id("service", "struts-action", path, apath)
            handler = a.get("type") or (f"forward:{a['forward']}" if a.get("forward") else None)
            facts.add(
                "services",
                Service(
                    id=sid,
                    name=apath,
                    kind="struts-action",
                    file=path,
                    handler=handler,
                    url=apath,
                    inputs=[a["name"]] if a.get("name") else [],
                    evidence=[Evidence(file=path, line=line)],
                ),
            )
            facts.symbol("struts-action", apath, path, line, owner=sid)
            source = f"service:{sid}"
            if a.get("type"):
                facts.ref(
                    kind="calls",
                    source=source,
                    source_label=apath,
                    target=a["type"],
                    target_kinds=["java-class"],
                    file=path,
                    line=line,
                )
            for key in ("forward", "include", "input"):
                if a.get(key):
                    self._forward_ref(facts, source, apath, path, a[key], line)
            for fwd in action.iter("forward"):
                self._forward_ref(
                    facts, source, apath, path, attrs(fwd).get("path", ""), line_of(fwd)
                )

    def _struts2(self, path: str, root: ET.Element, facts: Facts) -> None:
        facts.add("tech_stack", tech("Struts 2", None, "framework", path, line_of(root)))
        for pkg in root.iter("package"):
            ns = attrs(pkg).get("namespace", "/").rstrip("/")
            for action in pkg.iter("action"):
                a = attrs(action)
                name = a.get("name", "")
                apath = f"{ns}/{name}"
                line = line_of(action)
                sid = make_id("service", "struts-action", path, apath)
                handler = a.get("class")
                if handler and a.get("method"):
                    handler += "." + a["method"]
                facts.add(
                    "services",
                    Service(
                        id=sid,
                        name=apath,
                        kind="struts-action",
                        file=path,
                        handler=handler,
                        url=apath,
                        evidence=[Evidence(file=path, line=line)],
                    ),
                )
                facts.symbol("struts-action", apath, path, line, owner=sid)
                if a.get("class"):
                    facts.ref(
                        kind="calls",
                        source=f"service:{sid}",
                        source_label=apath,
                        target=a["class"],
                        target_kinds=["java-class"],
                        file=path,
                        line=line,
                    )
                for result in action.iter("result"):
                    target = text_of(result) or attrs(result).get("location", "")
                    if attrs(result).get("type") in ("redirectAction", "chain"):
                        target = f"{ns}/{target}.action"
                    self._forward_ref(facts, f"service:{sid}", apath, path, target, line_of(result))

    def _tiles(self, path: str, root: ET.Element, facts: Facts) -> None:
        facts.add("tech_stack", tech("Tiles", None, "framework", path, line_of(root)))
        for d in root.iter("definition"):
            a = attrs(d)
            name = a.get("name", "")
            line = line_of(d)
            facts.symbol("tiles-definition", name, path, line)
            source = f"tiles:{name}"
            for target in (
                [a.get("path") or a.get("template") or ""]
                + [
                    attrs(p).get("value", "")
                    for p in d.iter("put")
                    if attrs(p).get("value", "").startswith("/")
                ]
                + [
                    attrs(p).get("value", "")
                    for p in d.iter("put-attribute")
                    if attrs(p).get("value", "").startswith("/")
                ]
            ):
                if target:
                    facts.ref(
                        kind="includes",
                        source=source,
                        source_label=name,
                        target=target,
                        target_kinds=["path"],
                        file=path,
                        line=line,
                    )
            if a.get("extends"):
                facts.ref(
                    kind="includes",
                    source=source,
                    source_label=name,
                    target=a["extends"],
                    target_kinds=["tiles-definition"],
                    file=path,
                    line=line,
                )

    def _faces(self, path: str, root: ET.Element, facts: Facts) -> None:
        facts.add("tech_stack", tech("JSF", root.get("version"), "framework", path, line_of(root)))
        for bean in root.iter("managed-bean"):
            cls = child_text(bean, "managed-bean-class")
            name = child_text(bean, "managed-bean-name")
            if cls and name:
                facts.symbol("jsf-bean", name, path, line_of(bean), data={"class": cls})
                facts.ref(
                    kind="uses",
                    source=f"file:{path}",
                    source_label=path,
                    target=cls,
                    target_kinds=["java-class"],
                    file=path,
                    line=line_of(bean),
                )
        for view in root.iter("to-view-id"):
            facts.ref(
                kind="forwards",
                source=f"file:{path}",
                source_label=path,
                target=text_of(view),
                target_kinds=["path"],
                file=path,
                line=line_of(view),
            )

    # ---- web.xml ------------------------------------------------------------------------------
    def _web_xml(self, path: str, root: ET.Element, facts: Facts) -> None:
        version = root.get("version")
        if version:
            facts.add("tech_stack", tech("Servlet API", version, "runtime", path, line_of(root)))
        mappings: dict[str, list[str]] = {}
        for m in root.iter("servlet-mapping"):
            name = child_text(m, "servlet-name") or ""
            mappings.setdefault(name, []).extend(text_of(u) for u in m.iter("url-pattern"))
        for s in root.iter("servlet"):
            name = child_text(s, "servlet-name") or ""
            cls = child_text(s, "servlet-class")
            jsp = child_text(s, "jsp-file")
            line = line_of(s)
            if cls in FRAMEWORK_SERVLETS:
                facts.add(
                    "tech_stack", tech(FRAMEWORK_SERVLETS[cls], None, "framework", path, line)
                )
                facts.symbol(
                    "front-controller",
                    name,
                    path,
                    line,
                    data={"class": cls or "", "patterns": ",".join(mappings.get(name, []))},
                )
                continue
            sid = make_id("service", "servlet", path, name)
            facts.add(
                "services",
                Service(
                    id=sid,
                    name=name,
                    kind="servlet",
                    file=path,
                    handler=cls or jsp,
                    url=", ".join(mappings.get(name, [])) or None,
                    evidence=[Evidence(file=path, line=line)],
                ),
            )
            if cls:
                facts.ref(
                    kind="calls",
                    source=f"service:{sid}",
                    source_label=name,
                    target=cls,
                    target_kinds=["java-class"],
                    file=path,
                    line=line,
                )
            if jsp:
                self._forward_ref(facts, f"service:{sid}", name, path, jsp, line)
        for kind, tag, cls_tag, known in (
            ("filter", "filter", "filter-class", FRAMEWORK_FILTERS),
            ("listener", "listener", "listener-class", FRAMEWORK_LISTENERS),
        ):
            for el in root.iter(tag):
                cls = child_text(el, cls_tag)
                if not cls:
                    continue
                line = line_of(el)
                if cls in known:
                    facts.add("tech_stack", tech(known[cls], None, "framework", path, line))
                    continue
                facts.add(
                    "entry_points",
                    EntryPoint(
                        id=make_id("entry", path, cls, kind),
                        name=cls,
                        kind=kind,
                        file=path,
                        line=line,
                        evidence=[Evidence(file=path, line=line)],
                    ),
                )
                facts.ref(
                    kind="uses",
                    source=f"file:{path}",
                    source_label=path,
                    target=cls,
                    target_kinds=["java-class"],
                    file=path,
                    line=line,
                )
        for el in list(root.iter("welcome-file")) + list(root.iter("location")):
            target = text_of(el)
            if target:
                facts.ref(
                    kind="forwards",
                    source=f"file:{path}",
                    source_label=path,
                    target=target if target.startswith("/") else "/" + target,
                    target_kinds=["path"],
                    file=path,
                    line=line_of(el),
                    report_unresolved=el.tag == "location",
                )
        if (
            root.find(".//security-constraint") is not None
            and root.find(".//login-config") is not None
        ):
            auth = next((text_of(a) for a in root.iter("auth-method")), None)
            facts.add(
                "tech_stack",
                tech(f"Container security ({auth or 'unknown'})", None, "auth", path, None),
            )

    # ---- pages --------------------------------------------------------------------------------
    def _page(self, file: FileRecord, text: str, facts: Facts) -> None:
        path = file.path
        lower = path.lower()
        tech_name = file.language
        if file.language in ("jsp", "html") and (
            lower.endswith((".xhtml", ".jsf")) or _JSF_MARK.search(text)
        ):
            tech_name = "jsf"
        partial = lower.endswith((".jspf", ".tag", ".inc", ".tagx"))
        fields = len(_FIELD_TAGS.findall(text))
        for m in _INPUT.finditer(text):
            t = _INPUT_TYPE.search(m.group(1))
            if not t or t.group(1).lower() not in ("hidden", "submit", "button", "reset", "image"):
                fields += 1
        action_m = _FORM_ACTION.search(text)
        action = action_m.group(1) if action_m else None
        sid = make_id("screen", path)
        facts.add(
            "screens",
            Screen(
                id=sid,
                name=path,
                technology=tech_name,
                file=path,
                action=action,
                fields_count=fields,
                partial=partial,
                evidence=[Evidence(file=path, line=1)],
            ),
        )
        facts.symbol("page", path, path, 1, owner=sid)
        source = f"screen:{sid}"
        lines = LineIndex(text)
        for rx in (_INCLUDE_DIRECTIVE, _INCLUDE_ACTION, _VELOCITY_INCLUDE, _FTL_INCLUDE):
            for m in rx.finditer(text):
                facts.ref(
                    kind="includes",
                    source=source,
                    source_label=path,
                    target=_rel_target(path, m.group(1))
                    if not m.group(1).startswith("/")
                    else m.group(1),
                    target_kinds=["path"],
                    file=path,
                    line=lines.line(m.start()),
                    data={"relative_to": path},
                )
        for m in _TILES_DEF.finditer(text):
            facts.ref(
                kind="includes",
                source=source,
                source_label=path,
                target=m.group(1),
                target_kinds=["tiles-definition"],
                file=path,
                line=lines.line(m.start()),
            )
        for m in _FORM_ACTION.finditer(text):
            self._nav(facts, source, path, m.group(1), lines.line(m.start()), "submits")
        for m in _HREF_ACTION.finditer(text):
            self._nav(facts, source, path, m.group(1), lines.line(m.start()), "links")

    def _nav(self, facts: Facts, source: str, path: str, target: str, line: int, kind: str) -> None:
        if "://" in target or target.startswith(("javascript:", "mailto:")):
            return
        clean = target.split("?", 1)[0]
        if clean.endswith((".jsp", ".html", ".xhtml", ".jsf")):
            facts.ref(
                kind=kind,
                source=source,
                source_label=path,
                target=_rel_target(path, clean),
                target_kinds=["path"],
                file=path,
                line=line,
                report_unresolved=False,
                data={"relative_to": path},
            )
            return
        name = re.sub(r"\.(do|action|htm)$", "", clean)
        if not name.startswith("/"):
            name = "/" + name.rsplit("/", 1)[-1]
        facts.ref(
            kind=kind,
            source=source,
            source_label=path,
            target=name,
            target_kinds=["struts-action", "url"],
            file=path,
            line=line,
            report_unresolved=False,
        )
