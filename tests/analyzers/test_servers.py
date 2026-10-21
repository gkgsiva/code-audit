"""servers analyzer: application and web servers from descriptors, config, images and code."""

from __future__ import annotations

from legacy_audit.analyzers.servers import ServersAnalyzer
from tests.analyzers.helpers import entities, run


def _tech(facts) -> set[tuple[str, str | None, str]]:
    return {(t.name, t.version, t.category) for t in entities(facts, "tech_stack")}


def test_weblogic_descriptor_with_dtd_version() -> None:
    text = """<?xml version="1.0"?>
<!DOCTYPE weblogic-web-app PUBLIC "-//BEA Systems, Inc.//DTD Web Application 8.1//EN"
  "http://www.bea.com/servers/wls810/dtd/weblogic810-web-jar.dtd">
<weblogic-web-app><context-root>/portal</context-root></weblogic-web-app>
"""
    facts = run(ServersAnalyzer(), "app/WEB-INF/weblogic.xml", text)
    assert ("Oracle WebLogic Server", "8.1", "app-server") in _tech(facts)
    assert [f.rule_id for f in entities(facts, "findings")] == ["server-specific-config"]


def test_tomcat_server_xml_and_nginx_image() -> None:
    server = '<Server port="8005" shutdown="SHUTDOWN"><Service name="Catalina"/></Server>'
    facts = run(ServersAnalyzer(), "deploy/tomcat/server.xml", server)
    assert any(t[0] == "Apache Tomcat" for t in _tech(facts))
    docker = "FROM nginx:1.25-alpine\nCOPY dist/ /usr/share/nginx/html\n"
    facts = run(ServersAnalyzer(), "deploy/nginx/Dockerfile", docker)
    assert ("Nginx", "1.25", "web-server") in _tech(facts)


def test_jboss_vendor_api_in_java() -> None:
    # org.jboss.logging is portable (Hibernate uses it); org.jboss.ejb3 ties the code to JBoss.
    java = "package a;\nimport org.jboss.ejb3.annotation.SecurityDomain;\nclass A {}\n"
    facts = run(ServersAnalyzer(), "src/A.java", java)
    assert any(t[0] == "JBoss / WildFly" for t in _tech(facts))
    assert [f.rule_id for f in entities(facts, "findings")] == ["server-specific-api"]


def test_apache_httpd_with_weblogic_plugin() -> None:
    conf = "LoadModule weblogic_module modules/mod_wl.so\n<IfModule mod_weblogic.c>\n</IfModule>\n"
    facts = run(ServersAnalyzer(), "deploy/apache/httpd.conf", conf)
    names = {t[0] for t in _tech(facts)}
    assert {"Apache HTTP Server", "Oracle WebLogic Server"} <= names
