"""web analyzer: Struts, web.xml, Tiles, JSP pages."""

from __future__ import annotations

from legacy_audit.analyzers.web import WebAnalyzer
from tests.analyzers.helpers import entities, run

STRUTS = """<?xml version="1.0"?>
<!DOCTYPE struts-config PUBLIC "-//Apache//DTD Struts 1.2//EN" "http://struts.apache.org/dtds/struts-config_1_2.dtd">
<struts-config>
  <global-forwards><forward name="error" path="/jsp/error.jsp"/></global-forwards>
  <action-mappings>
    <action path="/orderSearch" type="com.acme.OrderSearchAction" name="searchForm">
      <forward name="success" path="order.search"/>
      <forward name="detail" path="/orderDetail.do"/>
    </action>
    <action path="/help" forward="/jsp/help.jsp"/>
  </action-mappings>
</struts-config>
"""


def test_struts1_actions_and_forwards() -> None:
    facts = run(WebAnalyzer(), "WEB-INF/struts-config.xml", STRUTS)
    assert sorted(s.name for s in entities(facts, "services")) == ["/help", "/orderSearch"]
    forwards = {(r.target, tuple(r.target_kinds)) for r in facts.refs if r.kind == "forwards"}
    assert ("order.search", ("tiles-definition",)) in forwards
    assert ("/orderDetail", ("struts-action",)) in forwards
    assert ("/jsp/error.jsp", ("path",)) in forwards


WEB_XML = """<web-app>
  <servlet><servlet-name>action</servlet-name>
    <servlet-class>org.apache.struts.action.ActionServlet</servlet-class></servlet>
  <servlet><servlet-name>health</servlet-name>
    <servlet-class>com.acme.HealthServlet</servlet-class></servlet>
  <servlet-mapping><servlet-name>health</servlet-name><url-pattern>/health</url-pattern></servlet-mapping>
  <filter><filter-name>enc</filter-name><filter-class>com.acme.EncodingFilter</filter-class></filter>
  <welcome-file-list><welcome-file>index.jsp</welcome-file></welcome-file-list>
</web-app>
"""


def test_web_xml_servlets_skip_front_controllers() -> None:
    facts = run(WebAnalyzer(), "WebContent/WEB-INF/web.xml", WEB_XML)
    assert [(s.kind, s.name, s.url) for s in entities(facts, "services")] == [
        ("servlet", "health", "/health")
    ]
    assert [(e.kind, e.name) for e in entities(facts, "entry_points")] == [
        ("filter", "com.acme.EncodingFilter")
    ]
    assert any(r.target == "/index.jsp" for r in facts.refs)


PAGE = """<%@ include file="common/header.jspf" %>
<html:form action="/orderSearch.do">
  <html:text property="customer"/>
  <input type="text" name="from"/>
  <input type="hidden" name="token"/>
  <html:submit/>
</html:form>
<a href="orderDetail.do?id=1">detail</a>
"""


def test_jsp_page_fields_includes_and_navigation() -> None:
    facts = run(WebAnalyzer(), "WebContent/jsp/orderSearch.jsp", PAGE)
    (screen,) = entities(facts, "screens")
    assert screen.fields_count == 2 and not screen.partial and screen.action == "/orderSearch.do"
    refs = {(r.kind, r.target) for r in facts.refs}
    assert ("includes", "WebContent/jsp/common/header.jspf") in refs
    assert ("submits", "/orderSearch") in refs
    assert ("links", "/orderDetail") in refs


def test_jsp_fragment_is_partial() -> None:
    facts = run(WebAnalyzer(), "WebContent/jsp/common/header.jspf", "<div>header</div>\n")
    assert entities(facts, "screens")[0].partial


def test_tiles_definitions() -> None:
    text = """<tiles-definitions>
  <definition name="base" path="/layout.jsp"><put name="header" value="/header.jsp"/></definition>
  <definition name="order.search" extends="base"><put name="body" value="/search.jsp"/></definition>
</tiles-definitions>"""
    facts = run(WebAnalyzer(), "WEB-INF/tiles-defs.xml", text)
    assert {s.name for s in facts.symbols if s.kind == "tiles-definition"} == {
        "base",
        "order.search",
    }
    assert ("tiles:order.search", "/search.jsp") in {(r.source, r.target) for r in facts.refs}
