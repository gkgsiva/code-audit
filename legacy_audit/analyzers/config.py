"""Configuration files (properties, XML, YAML, INI, JSON): JDBC URLs, hosts and URLs,
queue names and CFT partners as integrations; credentials are reported masked, never copied.
"""

from __future__ import annotations

import re
from typing import ClassVar

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import tech
from legacy_audit.analyzers.integrations import (
    JDBC_RE,
    URL_RE,
    classify_url,
    integration,
    parse_jdbc,
)
from legacy_audit.model import Evidence, FileRecord, Finding, Severity, make_id

SECRET_KEY = re.compile(r"(passw(or)?d|pwd|passphrase|secret|api[_-]?key|token|credential)", re.I)
# Message/label bundles (ApplicationResources.properties, messages_fr.properties): their
# "password" keys are UI labels, not credentials.
_MESSAGE_BUNDLE = re.compile(
    r"(?i)(^|[_.-])(messages?|\w*resources?|labels?|i18n|strings?|text)(_[a-z]{2}(_[a-z]{2})?)?"
    r"\.properties$"
)
_KV = re.compile(r"^\s*([\w.\-\[\]/]+)\s*[=:]\s*(.*?)\s*$")
_XML_PROP = re.compile(
    r"<(?:property|entry|add|param|init-param|context-param|env-entry)\b[^>]*?"
    r"(?:name|key)\s*=\s*\"([^\"]+)\"[^>]*?value\s*=\s*\"([^\"]*)\"",
    re.I,
)
_XML_PARAM = re.compile(
    r"<param-name>\s*([^<]+?)\s*</param-name>\s*<param-value>\s*([^<]*?)\s*</param-value>", re.I
)
_HOST_KEY = re.compile(r"(^|[._-])(host|hostname|server)$", re.I)
_PORT_KEY = re.compile(r"(^|[._-])port$", re.I)
_QUEUE_KEY = re.compile(r"queue", re.I)
OWNED_INPUTS = ("audit-inputs/autosys/", "audit-inputs/mq/", "audit-inputs/cft/",
                "audit-inputs/crontab/", "audit-inputs/ddl/")  # fmt: skip
_NOT_QUEUE_KEY = re.compile(r"manager|mgr|factory|depth|size|timeout|count|max|min", re.I)
_QUEUE_VALUE = re.compile(r"^[A-Z0-9][A-Z0-9._]{2,47}$")
_CFT_KEY = re.compile(r"cft", re.I)
_PLACEHOLDER = re.compile(r"^\s*(\$\{.*\}|@\w+@|%\w+%|<[^>]+>)?\s*$")

DIRECTION_HINTS = (
    (re.compile(r"(request|req|out|send|put|publish|producer|dest|target|to)\b", re.I), "producer"),
    (
        re.compile(r"(reply|resp|in|receive|recv|get|listen|consumer|source|from)\b", re.I),
        "consumer",
    ),
)


def queue_role(key: str) -> str | None:
    words = re.sub(r"[._-]", " ", key)
    for rx, role in DIRECTION_HINTS:
        if rx.search(words):
            return role
    return None


def credential_finding(file: str, line: int, key: str) -> Finding:
    return Finding(
        id=make_id("finding", "config-credential", file, line),
        rule_id="config-credential",
        severity=Severity.HIGH,
        category="security",
        file=file,
        line=line,
        message=f"Credential '{key}' is set in configuration (value masked)",
        evidence=[Evidence(file=file, line=line, snippet=f"{key}=****")],
    )


def scan_value_for_endpoints(
    value: str, file: str, line: int, facts: Facts, key: str | None = None
) -> None:
    for m in JDBC_RE.finditer(value):
        product, target = parse_jdbc(m.group(0))
        facts.add(
            "integrations",
            integration(
                type="DB",
                target=target,
                direction="outbound",
                file=file,
                line=line,
                protocol="jdbc",
            ),
        )
        facts.add("tech_stack", tech(product, None, "database", file, line))
    for m in URL_RE.finditer(value):
        if m.group(0).lower().startswith("jdbc"):
            continue
        c = classify_url(m.group(0))
        if c:
            # The key lets a client written as ${payment.url} find its address (decision 133).
            details = {"key": key} if key and c[0] in ("HTTP", "SOAP") else None
            facts.add(
                "integrations",
                integration(type=c[0], target=c[1], direction="outbound", file=file, line=line,
                            details=details),
            )  # fmt: skip


class ConfigAnalyzer:
    name: ClassVar[str] = "config"
    version: ClassVar[str] = "2"  # 2: configuration key on HTTP endpoints, namespaces skipped

    def supports(self, file: FileRecord) -> bool:
        if file.path.lower().startswith(OWNED_INPUTS):
            return False  # scheduler, MQ and CFT exports have their own analyzers
        return file.language in ("properties", "yaml", "ini", "xml", "json") and file.category in (
            "config",
            "source",
            "other",
        )

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        facts = Facts()
        text = ctx.text or ""
        facts.coverage.lines = text.count("\n") + 1
        pairs: list[tuple[int, str, str]] = []
        if file.language == "xml":
            for m in list(_XML_PROP.finditer(text)) + list(_XML_PARAM.finditer(text)):
                pairs.append((text.count("\n", 0, m.start()) + 1, m.group(1), m.group(2)))
            # Endpoints in any attribute or text (namespaces and DTD hosts are ignored).
            for no, line in enumerate(text.splitlines(), 1):
                if (
                    "<!DOCTYPE" in line
                    or "xmlns" in line
                    or "schemaLocation" in line
                    or (
                        "amespace" in line  # targetNamespace, namespaceUri: names, not endpoints
                    )
                ):
                    continue
                if "jdbc:" in line or "://" in line:
                    scan_value_for_endpoints(line, file.path, no, facts)
        else:
            section = ""
            for no, line in enumerate(text.splitlines(), 1):
                s = line.strip()
                if not s or s.startswith(("#", "!", ";", "//")):
                    continue
                if file.language == "ini" and s.startswith("[") and s.endswith("]"):
                    section = s[1:-1] + "."
                    continue
                if file.language == "yaml":
                    ym = re.match(r"^(\s*)([\w.\-]+)\s*:\s*(.*?)\s*$", line)
                    if ym:
                        pairs.append((no, ym.group(2), ym.group(3).strip("'\"")))
                    continue
                if file.language == "json":
                    jm = re.match(r'^\s*"([^"]+)"\s*:\s*"([^"]*)"', line)
                    if jm:
                        pairs.append((no, jm.group(1), jm.group(2)))
                    continue
                kv = _KV.match(line)
                if kv:
                    pairs.append((no, section + kv.group(1), kv.group(2)))
                else:
                    facts.coverage.gap(no, no, "not a key/value line")
            for no, key, value in pairs:
                scan_value_for_endpoints(value, file.path, no, facts, key)

        hosts: dict[str, tuple[int, str]] = {}
        ports: dict[str, str] = {}
        bundle = bool(_MESSAGE_BUNDLE.search(file.path.rsplit("/", 1)[-1]))
        # Spring bean files are read by the spring analyzer, which knows what each bean is.
        spring_xml = file.language == "xml" and bool(re.search(r"<(\w+:)?beans\b", text))
        for no, key, value in pairs:
            if (
                SECRET_KEY.search(key)
                and value
                and not _PLACEHOLDER.match(value)
                and not bundle
                and not re.search(r"\s", value)  # UI text such as "Password is required"
            ):
                facts.add("findings", credential_finding(file.path, no, key))
                continue
            prefix = re.sub(r"[._-]?(host|hostname|server|port)$", "", key, flags=re.I)
            if spring_xml:
                continue
            if _HOST_KEY.search(key) and value and not _PLACEHOLDER.match(value):
                hosts[prefix] = (no, value)
            elif _PORT_KEY.search(key) and value.isdigit():
                ports[prefix] = value
            elif (
                _QUEUE_KEY.search(key)
                and not _NOT_QUEUE_KEY.search(key)
                and _QUEUE_VALUE.match(value)
            ):
                facts.add(
                    "integrations",
                    integration(
                        type="MQ",
                        target=value,
                        direction="unknown",
                        file=file.path,
                        line=no,
                        role=queue_role(key),
                        details={"key": key},
                    ),
                )
            elif _CFT_KEY.search(key) and re.search(r"part(ner)?", key, re.I) and value:
                facts.add(
                    "integrations",
                    integration(
                        type="CFT",
                        target=value,
                        direction="outbound",
                        file=file.path,
                        line=no,
                        details={"partner": value},
                    ),
                )
        for prefix, (no, host) in hosts.items():
            if "://" in host or not re.match(r"^[\w.-]+$", host):
                continue
            kind = _host_kind(prefix)
            target = f"{host}:{ports[prefix]}" if prefix in ports else host
            facts.add(
                "integrations",
                integration(
                    type=kind,
                    target=target,
                    direction="outbound",
                    file=file.path,
                    line=no,
                    details={"key": prefix},
                ),
            )
        return facts


def _host_kind(prefix: str) -> str:
    p = prefix.lower()
    for word, kind in (
        ("smtp", "SMTP"),
        ("mail", "SMTP"),
        ("sftp", "SFTP"),
        ("ftp", "FTP"),
        ("ldap", "LDAP"),
        ("mq", "MQ-QMGR"),
        ("cft", "CFT"),
        ("db", "DB"),
        ("jdbc", "DB"),
        ("http", "HTTP"),
        ("ws", "HTTP"),
        ("api", "HTTP"),
    ):
        if word in p:
            return kind
    return "SOCKET"
