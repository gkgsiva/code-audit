"""Building ``Integration`` facts. One integration is one unique (type, target, direction);
MQ/JMS queues are one integration per queue, their direction derived from producers and
consumers when everything is merged."""

from __future__ import annotations

import re
from urllib.parse import urlsplit

from legacy_audit.model import Evidence, Integration, make_id

# Queues: direction is derived after merging (producers / consumers / definitions).
DIRECTIONLESS = {"MQ", "JMS"}

# Hosts of specification documents, XML namespaces and schema locations: never integrations.
IGNORED_HOSTS = re.compile(
    r"(^|\.)(java\.sun\.com|jakarta\.apache\.org|struts\.apache\.org|www\.w3\.org|w3\.org|"
    r"xmlns\.jcp\.org|xmlns\.oracle\.com|www\.springframework\.org|springframework\.org|"
    r"maven\.apache\.org|logging\.apache\.org|mybatis\.org|ibatis\.apache\.org|"
    r"hibernate\.org|hibernate\.sourceforge\.net|www\.hibernate\.org|jboss\.org|"
    r"www\.oracle\.com|java\.oracle\.com|schemas\.xmlsoap\.org|schemas\.microsoft\.com|"
    r"www\.apache\.org|apache\.org|tiles\.apache\.org|jcp\.org|example\.com|example\.org|"
    r"localhost|127\.0\.0\.1|purl\.org|www\.opensymphony\.com|dtd\.\w+)$",
    re.I,
)

URL_RE = re.compile(
    r"\b(https?|ftps?|sftp|ldaps?|smtp|jdbc:[a-z0-9]+(?::[a-z0-9]+)?)://?[^\s\"'<>()\\]+", re.I
)
JDBC_RE = re.compile(r"\bjdbc:([a-z0-9]+)(?::([a-z0-9]+))?:[^\s\"'<>\\]+", re.I)

_DB_NAMES = {
    "oracle": "Oracle",
    "db2": "DB2",
    "as400": "DB2",
    "sqlserver": "SQL Server",
    "jtds": "SQL Server",
    "microsoft": "SQL Server",
    "mysql": "MySQL",
    "mariadb": "MariaDB",
    "postgresql": "PostgreSQL",
    "sybase": "Sybase",
    "informix-sqli": "Informix",
    "informix": "Informix",
    "h2": "H2",
    "hsqldb": "HSQLDB",
    "derby": "Derby",
    "teradata": "Teradata",
}


def integration(
    *,
    type: str,
    target: str,
    direction: str,
    file: str,
    line: int | None,
    role: str | None = None,
    protocol: str | None = None,
    details: dict[str, str] | None = None,
) -> Integration:
    """One usage of an integration. ``role`` is 'producer' or 'consumer' (queues)."""
    key = (type, target) if type in DIRECTIONLESS else (type, target, direction)
    return Integration(
        id=make_id("integration", *key),
        type=type,
        target=target,
        direction=direction,
        protocol=protocol,
        usages=1,
        source_files=[file],
        producers=[file] if role == "producer" else [],
        consumers=[file] if role == "consumer" else [],
        details=details or {},
        evidence=[Evidence(file=file, line=line)],
    )


def mask_userinfo(url: str) -> str:
    """Drop user:password@ from a URL and user/password@ from an Oracle thin URL."""
    url = re.sub(r"(://)[^/@\s]+@", r"\1", url)
    return re.sub(r"(jdbc:oracle:[a-z]+:)[^@/\s]+/[^@\s]+@", r"\1@", url, flags=re.I)


def parse_jdbc(url: str) -> tuple[str, str]:
    """(database product, target) for a JDBC URL; target has no credentials."""
    m = JDBC_RE.match(url)
    vendor = (m.group(1) if m else "unknown").lower()
    product = _DB_NAMES.get(vendor, vendor)
    clean = mask_userinfo(url)
    target = re.sub(r"^jdbc:", "", clean, flags=re.I)
    target = re.sub(r"[;?].*$", "", target)
    host = re.search(r"@(?://)?([\w.-]+)(?::(\d+))?[:/]?([\w.$-]+)?", target)
    if host:
        target = f"{vendor}://{host.group(1)}" + (f":{host.group(2)}" if host.group(2) else "")
        if host.group(3):
            target += f"/{host.group(3)}"
    else:
        hp = re.search(r"//([\w.-]+)(?::(\d+))?(?:/([\w.$-]+))?", target)
        if hp:
            target = f"{vendor}://{hp.group(1)}" + (f":{hp.group(2)}" if hp.group(2) else "")
            if hp.group(3):
                target += f"/{hp.group(3)}"
    return product, target


def classify_url(url: str, soap_hint: bool = False) -> tuple[str, str] | None:
    """(type, target) for a URL found in code or config; None for spec/namespace URLs."""
    url = mask_userinfo(url.rstrip(".,;"))
    if url.lower().startswith("jdbc:"):
        _, target = parse_jdbc(url)
        return "DB", target
    parts = urlsplit(url)
    host = parts.hostname or ""
    if not host or IGNORED_HOSTS.search(host) or "${" in host or "$" in host:
        return None
    scheme = parts.scheme.lower()
    port = f":{parts.port}" if parts.port else ""
    path = parts.path.rstrip("/")
    if scheme in ("http", "https"):
        soap = soap_hint or "/services/" in path.lower() or "wsdl" in (parts.query or "").lower()
        return ("SOAP" if soap else "HTTP"), f"{scheme}://{host}{port}{path}"
    kind = {
        "ftp": "FTP",
        "ftps": "FTP",
        "sftp": "SFTP",
        "ldap": "LDAP",
        "ldaps": "LDAP",
        "smtp": "SMTP",
    }.get(scheme)
    if kind is None:
        return None
    return kind, f"{scheme}://{host}{port}"
