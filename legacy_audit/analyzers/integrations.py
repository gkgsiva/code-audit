"""Building ``Integration`` facts. Each usage is recorded per (type, target, direction); MQ/JMS
queues per queue, their direction derived from producers and consumers when everything is
merged. The merge then keeps one integration per interface (``consolidation_key``)."""

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


# ---- one row per interface ---------------------------------------------------------------------

# Types whose target is a host (and port): ftp://h, h:21 and FTP h all name one server.
_HOST_TYPES = {"FTP", "SFTP", "SCP", "SMTP", "LDAP", "SOCKET"}
_DEFAULT_PORTS = {
    "http": "80",
    "https": "443",
    "ftp": "21",
    "ftps": "990",
    "sftp": "22",
    "scp": "22",
    "smtp": "25",
    "ldap": "389",
    "ldaps": "636",
}
_TYPE_SCHEME = {"FTP": "ftp", "SFTP": "sftp", "SCP": "scp", "SMTP": "smtp", "LDAP": "ldap"}
_QUEUE_URI = re.compile(r"^queue://[^/]*/", re.I)


def _host_port(type: str, target: str) -> tuple[str, str] | None:
    """(display, key) for a host target, the key without a default port; None when the target
    is not a host (``mail (mailx)``)."""
    scheme = ""
    rest = target.strip()
    if "://" in rest:
        parts = urlsplit(rest)
        scheme = parts.scheme.lower()
        host = (parts.hostname or "").lower()
        try:
            port = str(parts.port) if parts.port else ""
        except ValueError:
            return None
    else:
        rest = rest.rsplit("@", 1)[-1]  # user@host
        host, _, port = rest.partition(":")
        host = host.lower()
        if port and not port.isdigit():
            port = port if type == "SOCKET" else ""  # host:/path (scp) keeps the host only
    if not host or not re.match(r"^[\w.*?-]+$", host):
        return None
    shown = f"{host}:{port}" if port else host
    if port and port == _DEFAULT_PORTS.get(scheme or _TYPE_SCHEME.get(type, ""), ""):
        port = ""
    return (f"{scheme}://{shown}" if scheme else shown), f"{host}:{port}" if port else host


def consolidation_key(type: str, target: str) -> tuple[str, str, str]:
    """(family, key, display target): integrations with the same family and key are one
    interface, whatever direction, spelling or default port each usage was written with."""
    if type in ("HTTP", "SOAP"):
        parts = urlsplit(target)
        scheme = parts.scheme.lower()
        host = (parts.hostname or "").lower()
        try:
            port = str(parts.port) if parts.port else ""
        except ValueError:
            port = ""
        if not host:
            return "HTTP", target, target
        path = parts.path.rstrip("/")
        shown = f"{scheme}://{host}{':' + port if port else ''}{path}"
        if port == _DEFAULT_PORTS.get(scheme):
            port = ""
        return "HTTP", f"{scheme}://{host}{':' + port if port else ''}{path}", shown
    if type in _HOST_TYPES:
        hp = _host_port(type, target)
        if hp:
            return type, hp[1], hp[0]
        return type, target.strip().lower(), target.strip()
    if type == "DB":
        shown = re.sub(r"^(\w+://)([^:/]+)", lambda m: m.group(1) + m.group(2).lower(), target)
        return type, target.lower(), shown
    if type == "MQ-QMGR":
        return type, target.split("@", 1)[0].strip(), target
    if type in ("MQ", "JMS", "MQ-CHANNEL"):
        name = _QUEUE_URI.sub("", target.strip()).split("?", 1)[0]
        return type, name, name
    if type == "FILE":
        path = target.replace("\\", "/")
        return type, path, path
    return type, target, target


def merge_directions(directions: set[str]) -> str:
    """inbound + outbound is both; a concrete direction wins over defined and unknown."""
    concrete = directions - {"defined", "unknown"}
    if "both" in concrete or {"inbound", "outbound"} <= concrete:
        return "both"
    if concrete:
        return concrete.pop()
    return "defined" if "defined" in directions else "unknown"


# ---- what each interface is for ----------------------------------------------------------------

# Identity providers: a call to one is a login / authentication interface, whatever its type.
_IDENTITY = [
    (re.compile(r"(^|\.)okta(preview|-emea)?\.com$|(^|\.)okta\.", re.I), "Okta"),
    (re.compile(r"(^|\.)auth0\.com$", re.I), "Auth0"),
    (re.compile(r"^login\.(microsoftonline|windows)\.(com|net)$|^sts\.windows\.net$", re.I),
     "Microsoft Entra ID"),
    (re.compile(r"(^|\.)(pingidentity|pingone)\.(com|eu|asia)$", re.I), "PingIdentity"),
    (re.compile(r"(^|\.)(onelogin|forgerock)\.(com|io)$", re.I), "Identity provider"),
    (re.compile(r"(^|[.-])(keycloak|sso|idp|adfs|login|auth|oauth|cas|iam)[.-]", re.I),
     "Identity provider"),
]  # fmt: skip
_IDENTITY_PATH = re.compile(
    r"/(oauth2?|openid-connect|\.well-known/openid-configuration|saml2?|sso|adfs|"
    r"auth/realms|realms/[^/]+/protocol|cas/login|idp|authorize|token)(/|$)",
    re.I,
)

PURPOSES = {
    "HTTP": "HTTP API",
    "SOAP": "web service",
    "FTP": "file transfer",
    "SFTP": "file transfer",
    "SCP": "file transfer",
    "CFT": "file transfer",
    "MQ": "messaging",
    "JMS": "messaging",
    "MQ-CHANNEL": "messaging",
    "MQ-QMGR": "messaging",
    "DB": "database",
    "SMTP": "email",
    "LDAP": "directory",
    "SOCKET": "socket",
    "FILE": "file",
}
LOGIN = "login / authentication"

_VERB = {
    ("outbound", "HTTP"): "Calls",
    ("inbound", "HTTP"): "Is called on",
    ("both", "HTTP"): "Calls and is called on",
    ("outbound", "file transfer"): "Sends files to",
    ("inbound", "file transfer"): "Receives files from",
    ("both", "file transfer"): "Sends and receives files with",
    ("outbound", "messaging"): "Puts messages on",
    ("inbound", "messaging"): "Reads messages from",
    ("both", "messaging"): "Puts and reads messages on",
    ("outbound", "file"): "Writes",
    ("inbound", "file"): "Reads",
    ("both", "file"): "Reads and writes",
}


def _host_and_path(target: str) -> tuple[str, str]:
    if "://" in target:
        parts = urlsplit(target)
        return (parts.hostname or "").lower(), parts.path
    return target.split(":", 1)[0].lower(), ""


def identity_provider(it: Integration) -> str | None:
    """The identity provider an integration calls (Okta, Entra ID, ...), or None."""
    if it.type not in ("HTTP", "SOAP", "LDAP"):
        return None
    host, path = _host_and_path(it.target)
    for rx, name in _IDENTITY:
        if host and rx.search(host):
            return name
    keys = " ".join(it.details.values()).lower()
    if "okta" in keys:
        return "Okta"
    if it.type != "LDAP" and _IDENTITY_PATH.search(path):
        return "Identity provider"
    return None


def describe(it: Integration) -> tuple[str, str]:
    """(purpose, one-sentence description) of a consolidated integration, from its facts only."""
    idp = identity_provider(it)
    host, path = _host_and_path(it.target)
    where = f"{it.usages} place{'s' if it.usages != 1 else ''} in the code"
    if it.usages == 0:
        where = "defined in the inputs, not used by the code"
    if idp:
        what = "OAuth 2.0 / OpenID Connect" if "/oauth" in path.lower() else "single sign-on"
        if it.type == "LDAP":
            what = "LDAP"
        return LOGIN, f"Login / authentication with {idp} ({what}) at {host or it.target}; {where}."
    purpose = PURPOSES.get(it.type, it.type.lower())
    d = it.direction if it.direction in ("outbound", "inbound", "both") else ""
    if it.type in ("HTTP", "SOAP"):
        scheme = "HTTPS" if it.target.lower().startswith("https:") else "HTTP"
        name = path.rstrip("/").rsplit("/", 1)[-1] if path.strip("/") else ""
        thing = (
            f"the {name} web service" if it.type == "SOAP" and name
            else "a SOAP web service" if it.type == "SOAP"
            else f"the {scheme} endpoint {path}" if path.strip("/")
            else f"an {scheme} service"
        )  # fmt: skip
        verb = _VERB.get((d or "outbound", "HTTP"), "Calls")
        tail = "" if scheme in thing else f" ({scheme})"
        on = " at" if verb.startswith("Is") else " on"
        return purpose, f"{verb} {thing}{on} {host}{tail}; {where}."
    if purpose == "file transfer":
        proto = it.type if it.type != "CFT" else "CFT"
        # A host found in configuration says where, not which way the files go.
        verb = "Transfers files with" if "key" in it.details else _VERB.get((d, purpose), "")
        verb = verb or "Transfers files with"
        extra = ""
        if it.type == "CFT":
            if it.details.get("idf"):
                extra = f", flow {it.details['idf']}"
            if it.details.get("host"):
                extra += f", partner host {it.details['host']}"
        return purpose, f"{verb} {it.target} by {proto}{extra}; {where}."
    if purpose == "messaging":
        if it.type == "MQ-QMGR":
            return purpose, f"Connects to the MQ queue manager {it.target}; {where}."
        if it.type == "MQ-CHANNEL":
            return purpose, f"Uses the MQ channel {it.target}; {where}."
        verb = _VERB.get((d, purpose), "Uses")
        return purpose, f"{verb} the queue {it.target}; {where}."
    if purpose == "database":
        return purpose, f"Connects to the database {it.target}; {where}."
    if purpose == "email":
        return purpose, f"Sends email through {it.target}; {where}."
    if purpose == "directory":
        return purpose, f"Looks up users or groups in the directory {it.target}; {where}."
    if purpose == "socket":
        verb = "Listens on" if d == "inbound" else "Opens a socket to"
        return purpose, f"{verb} {it.target}; {where}."
    if purpose == "file":
        verb = _VERB.get((d, purpose), "Uses")
        return purpose, f"{verb} the file {it.target}; {where}."
    return purpose, f"{it.type} {it.target}; {where}."
