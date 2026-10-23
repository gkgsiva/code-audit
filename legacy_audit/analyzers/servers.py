"""Application servers and web servers: which ones the application is built for or runs on.

Read from the files that only make sense on one product (decision 131):

- deployment descriptors in the code: ``weblogic.xml`` and the other ``weblogic-*.xml``,
  ``ibm-web-bnd``/``ibm-web-ext`` and the other ``ibm-*`` bindings (``.xml`` or the older
  ``.xmi``), ``jboss-web.xml`` and the other ``jboss-*.xml``, ``*-ds.xml``, Tomcat's
  ``context.xml``, GlassFish/Payara, Jetty, Resin and OC4J descriptors;
- server configuration (in the code or in ``audit-inputs/server-config/``): WebLogic
  ``config.xml``, JBoss/WildFly ``standalone.xml``/``domain.xml``, Tomcat ``server.xml`` and
  ``catalina.properties``, WebSphere Liberty ``server.xml``, Apache HTTP Server / IBM HTTP Server
  / Oracle HTTP Server ``httpd.conf`` (and their WebLogic / WebSphere plug-ins, ``mod_jk``),
  Nginx ``nginx.conf``, IIS ``web.config``;
- container images (``Dockerfile``: ``FROM tomcat:9.0``, ``FROM nginx:1.25``, ``FROM node:18``);
- start and admin scripts (``startWebLogic.sh``, ``wsadmin``, ``catalina.sh``, ``standalone.sh``,
  ``asadmin``, ``apachectl``);
- vendor APIs imported by Java code (``weblogic.*``, ``com.ibm.websphere.*``, ``org.jboss.ejb3``).

Each is a tech-stack item (category ``app-server`` or ``web-server``, ``runtime`` for Node.js and
Java images) with the file and line as evidence, and a version only when the file states one
(``<domain-version>``, a DTD naming the release, an image tag). Server-specific descriptors and
vendor APIs are also ``server-specific`` findings. What becomes of them depends on the target
(``audit.yaml > target.profile``), so their texts carry ``[[server-move]]``,
``[[server-api]]`` and ``[[server-remediation]]``, filled in by the assess stage
(``rules/target-mapping.yaml > phrases``).
"""

from __future__ import annotations

import re
from pathlib import PurePosixPath
from typing import ClassVar

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import tech
from legacy_audit.analyzers.textutil import LineIndex
from legacy_audit.model import Evidence, FileRecord, Finding, Severity, make_id

WEBLOGIC = "Oracle WebLogic Server"
WEBSPHERE = "IBM WebSphere Application Server"
LIBERTY = "IBM WebSphere Liberty"
JBOSS = "JBoss / WildFly"
TOMCAT = "Apache Tomcat"
GLASSFISH = "GlassFish / Payara"
JETTY = "Eclipse Jetty"
RESIN = "Caucho Resin"
OC4J = "Oracle OC4J / iAS"
APACHE = "Apache HTTP Server"
IHS = "IBM HTTP Server"
OHS = "Oracle HTTP Server"
NGINX = "Nginx"
IIS = "Microsoft IIS"
APP, WEB = "app-server", "web-server"

# Deployment descriptors: file name -> (server, what it holds).
DESCRIPTORS: list[tuple[re.Pattern[str], str, str]] = [
    (
        re.compile(r"^weblogic(-application|-ejb-jar|-webservices|-ra|-cmp-rdbms-jar)?\.xml$"),
        WEBLOGIC,
        "WebLogic deployment descriptor",
    ),
    (
        re.compile(
            r"^ibm-(web|application|ejb-jar|webservices|webservicesclient)-(bnd|ext)\.(xml|xmi)$"
        ),
        WEBSPHERE,
        "WebSphere binding/extension file",
    ),
    (
        re.compile(r"^jboss(-web|-app|-deployment-structure|-ejb3|-service|-client|-all)?\.xml$"),
        JBOSS,
        "JBoss deployment descriptor",
    ),
    (re.compile(r"^[\w.-]+-ds\.xml$"), JBOSS, "JBoss datasource definition"),
    (
        re.compile(r"^(glassfish|sun|payara)-(web|application|ejb-jar|resources)\.xml$"),
        GLASSFISH,
        "GlassFish/Payara deployment descriptor",
    ),
    (re.compile(r"^jetty(-web|-env)?\.xml$"), JETTY, "Jetty configuration"),
    (re.compile(r"^resin(-web)?\.xml$"), RESIN, "Resin configuration"),
    (
        re.compile(r"^(orion-web|orion-application|orion-ejb-jar|data-sources)\.xml$"),
        OC4J,
        "OC4J deployment descriptor",
    ),
    (
        re.compile(r"^(tomcat-users|catalina)\.(xml|properties|policy)$"),
        TOMCAT,
        "Tomcat configuration",
    ),
]
_DTD_VERSION = [
    (
        re.compile(r"-//BEA Systems, Inc\.//DTD (?:Web|Application|EJB)[^/]*?(\d+(?:\.\d+)+)//"),
        WEBLOGIC,
    ),
    (re.compile(r"-//JBoss//DTD [^/]*?(\d+(?:\.\d+)+)//"), JBOSS),
    (
        re.compile(
            r"-//Sun Microsystems, Inc\.//DTD (?:Application Server|Sun ONE Application Server) "
            r"(\d+(?:\.\d+)*)"
        ),
        GLASSFISH,
    ),
]
_DOMAIN_VERSION = re.compile(r"<(?:\w+:)?domain-version>\s*([\d.]+)\s*<")
_LIBERTY = re.compile(r"<featureManager\b|<server\s+description=", re.I)
_TOMCAT_SERVER = re.compile(r"<Server\b[^>]*\bport\s*=|<Connector\b[^>]*\bprotocol\s*=|Catalina")
_TOMCAT_CONTEXT = re.compile(r"^\s*(<\?xml[^>]*>\s*)?(<!--.*?-->\s*)*<Context\b", re.S)
_JBOSS_DOMAIN = re.compile(r"urn:jboss:domain:(\d+(?:\.\d+)?)")
_WAS_TRADITIONAL = re.compile(r"xmlns:process=\"http://www\.ibm\.com/websphere/appserver")
_PLUGIN_CFG = re.compile(r"<Config\b[^>]*>.*?<ServerCluster\b", re.S)
_HTTPD = re.compile(r"^\s*(LoadModule|ServerRoot|<VirtualHost|Listen\s+\d|DocumentRoot)\b", re.M)
_IHS = re.compile(r"IBM HTTP Server|mod_ibm_ssl|WebSpherePluginConfig|mod_was_ap\d+", re.I)
_OHS = re.compile(r"Oracle HTTP Server|mod_wl_ohs|weblogic_module|WebLogicHost|WebLogicCluster")
_MOD_JK = re.compile(r"\bJkMount\b|mod_jk|^\s*worker\.\w+\.type\s*=\s*ajp", re.M)
_NGINX = re.compile(
    r"^\s*(http|server|upstream)\s*\{|^\s*proxy_pass\s+\S+;|^\s*worker_processes\b", re.M
)
_IIS = re.compile(r"<system\.webServer\b|<aspNetCore\b")
_DOCKER_FROM = re.compile(r"^\s*FROM\s+(?:--platform=\S+\s+)?([^\s]+)", re.M | re.I)
# Container base images: (image name, product, category); the tag is the version when numeric.
IMAGES: list[tuple[re.Pattern[str], str, str]] = [
    (re.compile(r"(^|/)tomcat$"), TOMCAT, APP),
    (re.compile(r"(^|/)(jboss/)?wildfly$|(^|/)wildfly/wildfly$"), JBOSS, APP),
    (re.compile(r"(^|/)jboss-eap[\w-]*$|jboss-eap-\d"), JBOSS, APP),
    (re.compile(r"(^|/)(websphere-liberty|open-liberty)$"), LIBERTY, APP),
    (re.compile(r"(^|/)websphere-traditional$"), WEBSPHERE, APP),
    (re.compile(r"(^|/)weblogic$|middleware/weblogic$"), WEBLOGIC, APP),
    (re.compile(r"(^|/)(payara/server-\w+|glassfish)$"), GLASSFISH, APP),
    (re.compile(r"(^|/)jetty$"), JETTY, APP),
    (re.compile(r"(^|/)nginx$|nginxinc/nginx-unprivileged$"), NGINX, WEB),
    (re.compile(r"(^|/)httpd$"), APACHE, WEB),
    (re.compile(r"(^|/)node$"), "Node.js", "runtime"),
    (
        re.compile(r"(^|/)(openjdk|eclipse-temurin|amazoncorretto|ibmjava|ibm-semeru-runtimes)$"),
        "Java",
        "runtime",
    ),
]
# Scripts that start or administer one product.
SCRIPT_SIGNS: list[tuple[re.Pattern[str], str, str]] = [
    (
        re.compile(
            r"\b(startWebLogic|stopWebLogic|startManagedWebLogic|setDomainEnv)\.(sh|cmd)\b|"
            r"\bweblogic\.(Deployer|WLST|Server)\b|\bwlst\.sh\b"
        ),
        WEBLOGIC,
        APP,
    ),
    (
        re.compile(
            r"\bwsadmin(\.sh|\.bat)?\b|\b(startServer|stopServer|startManager)\.sh\b|"
            r"\bAdminConfig\.|\bAdminApp\."
        ),
        WEBSPHERE,
        APP,
    ),
    (re.compile(r"\b(standalone|domain|jboss-cli)\.(sh|bat)\b|\bJBOSS_HOME\b"), JBOSS, APP),
    (re.compile(r"\bcatalina\.(sh|bat)\b|\bCATALINA_(HOME|BASE)\b"), TOMCAT, APP),
    (re.compile(r"\basadmin\b"), GLASSFISH, APP),
    (re.compile(r"\bapachectl\b|\bhttpd\s+-k\b"), APACHE, WEB),
    (re.compile(r"\bnginx\s+-s\b"), NGINX, WEB),
]
# Java imports of vendor APIs.
VENDOR_API = [
    (re.compile(r"^\s*import\s+(weblogic\.[\w.*]+)", re.M), WEBLOGIC),
    (re.compile(r"^\s*import\s+(com\.ibm\.(websphere|ws|wsspi|ejs)\.[\w.*]+)", re.M), WEBSPHERE),
    (
        re.compile(
            r"^\s*import\s+(org\.jboss\.(ejb3|annotation|security|naming|system|mx|"
            r"as\.\w+|wsf)\b[\w.*]*)",
            re.M,
        ),
        JBOSS,
    ),
    (re.compile(r"^\s*import\s+(org\.apache\.catalina\.[\w.*]+)", re.M), TOMCAT),
]


def _version_tag(image: str) -> str | None:
    """``tomcat:9.0-jdk11`` -> ``9.0``; ``node:18-alpine`` -> ``18``; None for ``latest``."""
    if ":" not in image.rsplit("/", 1)[-1]:
        return None
    tag = image.rsplit(":", 1)[1]
    m = re.match(r"^v?(\d+(?:\.\d+){0,3})", tag)
    return m.group(1) if m else None


class ServersAnalyzer:
    name: ClassVar[str] = "servers"
    version: ClassVar[str] = "2"

    def supports(self, file: FileRecord) -> bool:
        name = PurePosixPath(file.path).name.lower()
        if file.category in ("binary", "doc", "test"):
            return False
        return (
            any(rx.match(name) for rx, _, _ in DESCRIPTORS)
            or name
            in (
                "config.xml",
                "server.xml",
                "context.xml",
                "standalone.xml",
                "domain.xml",
                "plugin-cfg.xml",
                "web.config",
                "workers.properties",
                ".htaccess",
                "dockerfile",
                "containerfile",
            )
            or name.startswith(("standalone-", "dockerfile.", "httpd", "nginx"))
            or name.endswith((".dockerfile", ".conf"))
            or (file.language == "java")
            or file.category == "script"
        )

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        facts = Facts()
        text = ctx.text or ""
        facts.coverage.lines = text.count("\n") + 1 if text else 0
        if not text:
            return facts
        path = file.path
        name = PurePosixPath(path).name.lower()
        lines = LineIndex(text)
        found = _Found(path, facts, lines)
        if file.language == "java":
            for rx, server in VENDOR_API:
                for m in rx.finditer(text):
                    found.add(server, None, APP, m.start())
                    found.finding(
                        "server-specific-api",
                        Severity.MEDIUM,
                        m.start(),
                        f"Uses the {server} API {m.group(1)}: [[server-api]]",
                    )
                    break
            return facts
        if file.category == "script" and not name.endswith(".conf"):
            for rx, server, cat in SCRIPT_SIGNS:
                hit = rx.search(text)
                if hit:
                    found.add(server, None, cat, hit.start())
            return facts
        if (
            name in ("dockerfile", "containerfile")
            or name.startswith("dockerfile.")
            or (name.endswith(".dockerfile"))
        ):
            for m in _DOCKER_FROM.finditer(text):
                image = m.group(1).lower()
                base = image.split("@", 1)[0]
                repo = base.rsplit(":", 1)[0] if ":" in base.rsplit("/", 1)[-1] else base
                for rx, server, cat in IMAGES:
                    if rx.search(repo):
                        found.add(server, _version_tag(base), cat, m.start())
                        break
            return facts
        for rx, server, what in DESCRIPTORS:
            if rx.match(name):
                version = None
                for drx, dserver in _DTD_VERSION:
                    dm = drx.search(text)
                    if dm and dserver == server:
                        version = dm.group(1)
                found.add(server, version, APP, 0)
                if server == TOMCAT:
                    return facts  # Tomcat's own configuration, not an application descriptor
                old = name.endswith(".xmi")
                found.finding(
                    "server-specific-config",
                    Severity.LOW,
                    0,
                    f"{what} ({PurePosixPath(path).name}"
                    + (", XMI format of WebSphere 6.x/7" if old else "")
                    + f"): its settings (context root, JNDI names, data sources, security roles, "
                    f"class loading) are specific to {server} [[server-move]]",
                )
                return facts
        if name == "config.xml" and ("<domain" in text and "weblogic" in text.lower()):
            dv = _DOMAIN_VERSION.search(text)
            found.add(WEBLOGIC, dv.group(1) if dv else None, APP, dv.start() if dv else 0)
            found.config_finding(WEBLOGIC, "WebLogic domain configuration (config.xml)")
        elif name in ("standalone.xml", "domain.xml") or name.startswith("standalone-"):
            jm = _JBOSS_DOMAIN.search(text)
            if jm:
                found.add(JBOSS, None, APP, jm.start())
                found.config_finding(JBOSS, f"JBoss/WildFly server configuration ({name})")
        elif name == "server.xml":
            if _WAS_TRADITIONAL.search(text):
                found.add(WEBSPHERE, None, APP, 0)
                found.config_finding(WEBSPHERE, "WebSphere server configuration (server.xml)")
            elif _LIBERTY.search(text):
                hit = _LIBERTY.search(text)
                found.add(LIBERTY, None, APP, hit.start() if hit else 0)
                found.config_finding(LIBERTY, "WebSphere Liberty server configuration")
            elif _TOMCAT_SERVER.search(text):
                hit = _TOMCAT_SERVER.search(text)
                found.add(TOMCAT, None, APP, hit.start() if hit else 0)
                found.config_finding(TOMCAT, "Tomcat server configuration (server.xml)")
        elif name == "context.xml" and _TOMCAT_CONTEXT.search(text):
            found.add(TOMCAT, None, APP, text.find("<Context"))
            found.config_finding(TOMCAT, "Tomcat context (context.xml)")
        elif name == "plugin-cfg.xml" and _PLUGIN_CFG.search(text):
            found.add(IHS, None, WEB, 0)
            found.add(WEBSPHERE, None, APP, text.find("<ServerCluster"))
        elif name == "web.config" and _IIS.search(text):
            hit = _IIS.search(text)
            found.add(IIS, None, WEB, hit.start() if hit else 0)
        elif name == "workers.properties" and _MOD_JK.search(text):
            hit = _MOD_JK.search(text)
            found.add(APACHE, None, WEB, hit.start() if hit else 0)
            found.add(TOMCAT, None, APP, hit.start() if hit else 0)
        elif name.endswith(".conf") or name == ".htaccess" or name.startswith(("httpd", "nginx")):
            self._web_server(name, text, found)
        return facts

    def _web_server(self, name: str, text: str, found: _Found) -> None:
        ngx = _NGINX.search(text)
        if name.startswith("nginx") or (ngx and not _HTTPD.search(text)):
            if ngx:
                found.add(NGINX, None, WEB, ngx.start())
            return
        httpd = _HTTPD.search(text)
        if not httpd and name != ".htaccess" and not _MOD_JK.search(text):
            return
        ihs, ohs = _IHS.search(text), _OHS.search(text)
        if ihs:
            found.add(IHS, None, WEB, ihs.start())
            if re.search(r"WebSpherePluginConfig|mod_was_ap", text):
                found.add(WEBSPHERE, None, APP, ihs.start())
        elif ohs and "Oracle HTTP Server" in text:
            found.add(OHS, None, WEB, ohs.start())
        else:
            found.add(APACHE, None, WEB, httpd.start() if httpd else 0)
        if ohs:  # the WebLogic proxy plug-in sends requests to WebLogic
            found.add(WEBLOGIC, None, APP, ohs.start())
        jk = _MOD_JK.search(text)
        if jk:
            found.add(TOMCAT, None, APP, jk.start())


class _Found:
    def __init__(self, path: str, facts: Facts, lines: LineIndex) -> None:
        self.path = path
        self.facts = facts
        self.lines = lines

    def add(self, server: str, version: str | None, category: str, offset: int) -> None:
        line = self.lines.line(offset) if offset >= 0 else None
        self.facts.add("tech_stack", tech(server, version, category, self.path, line))

    def finding(self, rule: str, severity: Severity, offset: int, message: str) -> None:
        line = self.lines.line(offset)
        self.facts.add(
            "findings",
            Finding(
                id=make_id("finding", rule, self.path, line),
                rule_id=rule,
                severity=severity,
                category="migration",
                file=self.path,
                line=line,
                message=message,
                remediation="[[server-remediation]]",
                evidence=[Evidence(file=self.path, line=line)],
            ),
        )

    def config_finding(self, server: str, what: str) -> None:
        self.finding(
            "server-specific-config",
            Severity.LOW,
            0,
            f"{what}: data sources, JMS resources, security realms and tuning set here for "
            f"{server} [[server-move]]",
        )
