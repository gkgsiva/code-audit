"""Microsoft .NET and Windows desktop files that are not code: project files, NuGet package
lists, configuration, window layouts and ASP.NET pages (decision 134).

- **projects** (``.csproj``, ``.vbproj``, ``Directory.Build.props``): the language (C# or
  VB.NET, with ``LangVersion``), the runtime (.NET Framework 2.0 to 4.8.1, .NET Core, .NET 5+,
  .NET Standard), the application kind (``OutputType`` ``WinExe`` = desktop application,
  Windows Forms / WPF / ASP.NET / Worker service from the SDK, properties and references),
  NuGet ``PackageReference`` libraries, ``..\\packages\\<id>.<version>`` hint paths and
  project-to-project references;
- **NuGet lists**: ``packages.config``, ``Directory.Packages.props`` (central versions) and
  ``packages.lock.json`` (exact versions, direct and transitive);
- **solutions** (``.sln``): the Visual Studio version;
- **configuration** (``app.config``, ``web.config``, ``*.exe.config``, ``appsettings*.json``):
  connection strings as database integrations (credentials masked, a password is a finding)
  and WCF client endpoints as SOAP integrations;
- **layouts**: WPF / WinUI ``.xaml`` windows, pages and user controls and JavaFX ``.fxml``
  views as screens; the WPF ``App.xaml`` start-up window as an entry point;
- **ASP.NET pages**: Web Forms ``.aspx`` (``.ascx`` controls and ``.master`` pages are
  partials) with their ``SqlDataSource`` SQL, Razor ``.cshtml``/``.vbhtml`` views and Blazor
  ``.razor`` components as screens;
- **Windows Task Scheduler**: exported task definitions (``.xml``) and ``schtasks /create`` in
  batch and PowerShell scripts as batch jobs (scheduler ``windows-task``).

The code itself is read by the ``csharp`` and ``basic`` (VB6, VB.NET) analyzers; the helpers
for connection strings and ADO.NET providers below are shared with them.
"""

from __future__ import annotations

import json
import posixpath
import re
import xml.etree.ElementTree as ET
from typing import ClassVar

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import library, tech
from legacy_audit.analyzers.integrations import classify_url, integration
from legacy_audit.analyzers.sqltools import looks_like_sql, sql_fact
from legacy_audit.analyzers.textutil import LineIndex, attrs, iter_tag, line_of, parse_xml
from legacy_audit.analyzers.web import count_fields
from legacy_audit.model import (
    BatchJob,
    Category,
    EntryPoint,
    Evidence,
    FileRecord,
    Finding,
    Screen,
    Severity,
    make_id,
)

DOTNET = "dotnet"  # Library.language of NuGet packages

# ---- connection strings ---------------------------------------------------------------------

_CONN_PAIR = re.compile(r"(?:^|;)\s*([A-Za-z][\w ]*?)\s*=\s*(\{[^}]*\}|'[^']*'|\"[^\"]*\"|[^;]*)")
_SERVER_KEYS = ("data source", "server", "address", "addr", "network address", "host", "dsn",
                "dbq")  # fmt: skip
_DB_KEYS = ("initial catalog", "database", "dbname")
_SECRET_KEYS = ("password", "pwd")
# OLE DB providers, ODBC drivers and ADO.NET providers -> database product.
_PROVIDERS: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"sqloledb|sqlncli|msoledbsql|sql server|sqlclient", re.I), "SQL Server"),
    (re.compile(r"oraoledb|msdaora|oracle", re.I), "Oracle"),
    (re.compile(r"jet\.oledb|ace\.oledb|access driver|\.mdb\b|\.accdb\b", re.I), "Microsoft Access"),
    (re.compile(r"ibmdadb2|db2oledb|db2|iseries|as400|ibmda400", re.I), "DB2"),
    (re.compile(r"mysql|mariadb", re.I), "MySQL"),
    (re.compile(r"postgres|npgsql|pgoledb", re.I), "PostgreSQL"),
    (re.compile(r"sqlite", re.I), "SQLite"),
    (re.compile(r"sybase|ase", re.I), "Sybase"),
    (re.compile(r"informix", re.I), "Informix"),
    (re.compile(r"teradata", re.I), "Teradata"),
]
_SCHEMES = {
    "SQL Server": "sqlserver",
    "Oracle": "oracle",
    "Microsoft Access": "access",
    "DB2": "db2",
    "MySQL": "mysql",
    "PostgreSQL": "postgresql",
    "SQLite": "sqlite",
    "Sybase": "sybase",
    "Informix": "informix",
    "Teradata": "teradata",
}
# ADO.NET provider namespaces (C# ``using``, VB.NET ``Imports``) -> database product.
DB_NAMESPACES: dict[str, str] = {
    "System.Data.SqlClient": "SQL Server",
    "Microsoft.Data.SqlClient": "SQL Server",
    "System.Data.OracleClient": "Oracle",
    "Oracle.ManagedDataAccess.Client": "Oracle",
    "Oracle.DataAccess.Client": "Oracle",
    "Npgsql": "PostgreSQL",
    "MySql.Data.MySqlClient": "MySQL",
    "MySqlConnector": "MySQL",
    "IBM.Data.DB2": "DB2",
    "IBM.Data.Db2": "DB2",
    "IBM.Data.DB2.iSeries": "DB2",
    "System.Data.SQLite": "SQLite",
    "Microsoft.Data.Sqlite": "SQLite",
    "Sybase.Data.AseClient": "Sybase",
    "Teradata.Client.Provider": "Teradata",
}
# Namespaces -> (technology, category): what a C# or VB.NET file is built on.
NAMESPACE_TECH: list[tuple[str, str, str]] = [
    ("System.Windows.Forms", "Windows Forms", "framework"),
    ("System.Windows.Controls", "WPF", "framework"),
    ("System.Windows.Markup", "WPF", "framework"),
    ("Microsoft.UI.Xaml", "WinUI", "framework"),
    ("System.Web.UI", "ASP.NET Web Forms", "framework"),
    ("System.Web.Mvc", "ASP.NET MVC", "framework"),
    ("System.Web.Http", "ASP.NET Web API", "framework"),
    ("System.Web.Services", "ASP.NET Web Services (ASMX)", "framework"),
    ("Microsoft.AspNetCore", "ASP.NET Core", "framework"),
    ("System.ServiceModel", "WCF", "framework"),
    ("CoreWCF", "CoreWCF", "framework"),
    ("System.Runtime.Remoting", ".NET Remoting", "middleware"),
    ("System.Data.Entity", "Entity Framework", "framework"),
    ("Microsoft.EntityFrameworkCore", "Entity Framework Core", "framework"),
    ("Dapper", "Dapper", "library"),
    ("NHibernate", "NHibernate", "framework"),
    ("System.Data.SqlClient", "ADO.NET", "library"),
    ("Microsoft.Data.SqlClient", "ADO.NET", "library"),
    ("System.Data.OleDb", "ADO.NET", "library"),
    ("System.Data.Odbc", "ADO.NET", "library"),
    ("Oracle.ManagedDataAccess", "ADO.NET", "library"),
    ("Oracle.DataAccess", "ADO.NET", "library"),
    ("System.Messaging", "MSMQ", "middleware"),
    ("IBM.WMQ", "IBM MQ client", "middleware"),
    ("IBM.XMS", "IBM MQ client", "middleware"),
    ("System.ServiceProcess", "Windows Service", "runtime"),
    ("Quartz", "Quartz.NET", "scheduler"),
    ("Hangfire", "Hangfire", "scheduler"),
    ("log4net", "log4net", "library"),
    ("NLog", "NLog", "library"),
    ("Serilog", "Serilog", "library"),
    ("CrystalDecisions", "Crystal Reports", "library"),
    ("Microsoft.Reporting", "SQL Server Reporting Services (ReportViewer)", "library"),
    ("Microsoft.Office.Interop", "Microsoft Office automation (Interop)", "library"),
    ("System.DirectoryServices", "Active Directory (System.DirectoryServices)", "auth"),
]

_APP_KINDS = {
    "Microsoft.NET.Sdk.Web": "ASP.NET Core",
    "Microsoft.NET.Sdk.WindowsDesktop": None,  # Windows Forms or WPF, from the properties
    "Microsoft.NET.Sdk.Worker": "Worker service",
    "Microsoft.NET.Sdk.Razor": "ASP.NET Core",
    "Microsoft.NET.Sdk.BlazorWebAssembly": "Blazor",
}
# Old-style project type GUIDs (ProjectTypeGuids).
_TYPE_GUIDS = {
    "349c5851-65df-11da-9384-00065b846f21": "ASP.NET Web Forms",  # web application
    "e3e379df-f4c6-4180-9b81-6769533abe47": "ASP.NET MVC",
    "e53f8fea-eae0-44a6-8774-ffd645390401": "ASP.NET MVC",
    "f85e285d-a4e0-4152-9332-ab1d724d3325": "ASP.NET MVC",
    "60dc8134-eba5-43b8-bcc9-bb4bc16c2548": "WPF",
    "3d9ad99f-2412-4246-b90b-4eaa41c64699": "WCF",
}
_REFERENCE_TECH = {
    "system.windows.forms": "Windows Forms",
    "presentationframework": "WPF",
    "system.web": "ASP.NET",
    "system.web.mvc": "ASP.NET MVC",
    "system.servicemodel": "WCF",
    "system.serviceprocess": "Windows Service",
    "system.messaging": "MSMQ",
    "system.data.entity": "Entity Framework",
}
_TECH_CATEGORY = {
    "Windows Forms": "framework",
    "WPF": "framework",
    "ASP.NET": "framework",
    "ASP.NET Web Forms": "framework",
    "ASP.NET MVC": "framework",
    "ASP.NET Core": "framework",
    "Blazor": "framework",
    "WCF": "framework",
    "Worker service": "runtime",
    "Windows Service": "runtime",
    "MSMQ": "middleware",
    "Entity Framework": "framework",
}
_HINT_PACKAGE = re.compile(r"packages[\\/]+([A-Za-z_][\w.-]*?)\.(\d+(?:\.\d+){1,3}(?:-[\w.]+)?)[\\/]")


def connection_string(text: str) -> tuple[str | None, str, bool] | None:
    """(database product, target, has a password) of an ADO / ADO.NET / ODBC connection
    string, or None when the text is not one. The target never carries credentials."""
    pairs = {}
    for m in _CONN_PAIR.finditer(text.strip()):
        pairs[m.group(1).strip().lower()] = m.group(2).strip().strip("{}'\"")
    server = next((pairs[k] for k in _SERVER_KEYS if pairs.get(k)), None)
    if server is None or len(pairs) < 2:
        return None
    if not any(k in pairs for k in ("provider", "driver", "user id", "uid", "integrated security",
                                    "trusted_connection", "initial catalog", "database",
                                    "password", "pwd", "port", "persist security info",
                                    "dsn", "dbq")):  # fmt: skip
        return None
    hint = " ".join(pairs.get(k, "") for k in ("provider", "driver", "dbq"))
    if pairs.get("data source", "").lower().endswith((".mdb", ".accdb")):
        hint += " .mdb"
    product = next((p for rx, p in _PROVIDERS if rx.search(hint)), None)
    if product is None and "host" in pairs:
        product = "PostgreSQL"  # Npgsql: Host=...;Database=...
    if product is None and ("initial catalog" in pairs or "server" in pairs) and not pairs.get(
        "dsn"
    ):
        product = "SQL Server"  # the ADO.NET SqlConnection form
    database = next((pairs[k] for k in _DB_KEYS if pairs.get(k)), None)
    if pairs.get("dsn") and not pairs.get("data source"):
        target = f"odbc://{pairs['dsn']}"
    else:
        scheme = _SCHEMES.get(product or "", "db")
        host = server.split(",")[0].strip()
        if product == "Microsoft Access":
            host = posixpath.basename(host.replace("\\", "/"))
        target = f"{scheme}://{host}" + (f"/{database}" if database else "")
    secret = any(pairs.get(k) for k in _SECRET_KEYS)
    return product, target, secret


def add_connection(
    facts: Facts, text: str, path: str, line: int | None, protocol: str, provider: str = ""
) -> bool:
    """A connection string found in code or configuration: a DB integration, the database as a
    technology and, when it holds a password, a credential finding. True when it was one."""
    found = connection_string(text)
    if found is None:
        return False
    product, target, secret = found
    if provider:
        product = next((p for rx, p in _PROVIDERS if rx.search(provider)), product)
        if product and target.startswith("db://"):
            target = f"{_SCHEMES.get(product, 'db')}://" + target[5:]
    facts.add(
        "integrations",
        integration(
            type="DB", target=target, direction="outbound", file=path, line=line, protocol=protocol
        ),
    )
    if product:
        facts.add("tech_stack", tech(product, None, "database", path, line))
    if secret:
        facts.add(
            "findings",
            Finding(
                id=make_id("finding", "connection-string-password", path, line),
                rule_id="connection-string-password",
                severity=Severity.HIGH,
                category="security",
                file=path,
                line=line,
                message=f"Password written in a connection string to {target} (value masked)",
                remediation="Use Windows authentication or read the password from a secret store "
                "(Secret Manager on GCP); rotate it.",
                evidence=[Evidence(file=path, line=line, snippet="Password=****")],
            ),
        )
    return True


def framework_version(tfm: str) -> tuple[str, str] | None:
    """(runtime, version) of a target framework moniker or ``TargetFrameworkVersion``:
    ``net48``/``v4.8`` -> .NET Framework 4.8, ``netcoreapp3.1`` -> .NET Core 3.1,
    ``net8.0-windows`` -> .NET 8.0, ``netstandard2.0`` -> .NET Standard 2.0."""
    t = tfm.strip().lower()
    m = re.match(r"^v?(\d)\.(\d+)(?:\.(\d+))?$", t)
    if m:  # TargetFrameworkVersion v4.7.2
        return ".NET Framework", ".".join(g for g in m.groups() if g)
    m = re.match(r"^net(\d)(\d)(\d)?$", t)
    if m:  # net472, net48, net35
        return ".NET Framework", ".".join(g for g in m.groups() if g)
    m = re.match(r"^netcoreapp(\d+\.\d+)", t)
    if m:
        return ".NET Core", m.group(1)
    m = re.match(r"^netstandard(\d+\.\d+)", t)
    if m:
        return ".NET Standard", m.group(1)
    m = re.match(r"^net(\d+\.\d+)", t)
    if m:
        return ".NET", m.group(1)
    return None


def _xml_line(text: str, needle: str) -> int | None:
    i = text.find(needle)
    return text.count("\n", 0, i) + 1 if i >= 0 else None


# ---- the analyzer -----------------------------------------------------------------------------

_CONFIG_NAME = re.compile(r"^(app|web)(\.[\w-]+)?\.config$|\.(exe|dll)\.config$", re.I)
_APPSETTINGS = re.compile(r"^appsettings(\.[\w-]+)?\.json$", re.I)
_TASK_NS = "schemas.microsoft.com/windows/2004/02/mit/task"
_SCHTASKS = re.compile(r"\bschtasks(?:\.exe)?\s+/create\b(.*)$", re.I | re.M)
_ASP_INPUT = re.compile(
    r"<asp:(TextBox|DropDownList|CheckBox|CheckBoxList|RadioButton|RadioButtonList|ListBox|"
    r"FileUpload|Calendar)\b",
    re.I,
)
_RAZOR_INPUT = re.compile(
    r"@Html\.(TextBox|TextArea|DropDownList|CheckBox|RadioButton|Password|Editor|ListBox)"
    r"(For)?\s*\(|<(input|select|textarea)\b[^>]*\basp-for\s*=",
    re.I,
)
_XAML_INPUT = re.compile(
    r"<(?:\w+:)?(TextBox|PasswordBox|ComboBox|CheckBox|RadioButton|DatePicker|ListBox|Slider|"
    r"RichTextBox|DataGrid|TextField|TextArea|ChoiceBox|DateTimePicker|NumberBox|TableView|"
    r"ListView|Spinner)\b"
)
_ASP_SQL = re.compile(
    r"\b(Select|Insert|Update|Delete)Command\s*=\s*\"([^\"]+)\"", re.I
)
_ASP_NAV = re.compile(
    r"\b(?:NavigateUrl|PostBackUrl|href|action)\s*=\s*\"~?/?([^\"?#<%]+?\.aspx)", re.I
)
_PAGE_DIRECTIVE = re.compile(r"<%@\s*(Page|Control|Master)\b([^%]*)%>", re.I)


class DotnetAnalyzer:
    name: ClassVar[str] = "dotnet"
    version: ClassVar[str] = "1"

    def supports(self, file: FileRecord) -> bool:
        if file.category in (Category.BINARY, Category.TEST):
            return False
        low = file.path.lower()
        name = low.rsplit("/", 1)[-1]
        return (
            file.language in ("msbuild", "xaml", "fxml", "aspx", "razor")
            or name in ("packages.config", "packages.lock.json")
            or bool(_CONFIG_NAME.search(name) or _APPSETTINGS.match(name))
            or (file.language == "xml" and "/obj/" not in low)
            or file.language in ("batch", "powershell")
        )

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        facts = Facts()
        text = ctx.text or ""
        facts.coverage.lines = text.count("\n") + 1
        path = file.path
        name = path.rsplit("/", 1)[-1].lower()
        if file.language in ("batch", "powershell"):
            _schtasks(path, text, facts)
        elif name.endswith((".csproj", ".vbproj", ".props", ".targets")):
            _project(path, name, text, facts)
        elif name.endswith(".sln"):
            _solution(path, text, facts)
        elif name == "packages.config":
            _packages_config(path, text, facts)
        elif name == "packages.lock.json":
            _nuget_lock(path, text, facts)
        elif _CONFIG_NAME.search(name):
            _config(path, text, facts)
        elif _APPSETTINGS.match(name):
            _appsettings(path, text, facts)
        elif file.language in ("xaml", "fxml"):
            _layout(path, file.language, text, facts)
        elif file.language == "aspx":
            _aspx(path, text, facts, ctx.config.sql.dialect)
        elif file.language == "razor":
            _razor(path, text, facts)
        elif file.language == "xml" and _TASK_NS in text[:4000]:
            _task_xml(path, text, facts)
        return facts


def _parse(path: str, text: str, facts: Facts) -> ET.Element | None:
    try:
        return parse_xml(text)
    except ET.ParseError as err:
        facts.coverage.gap(1, text.count("\n") + 1, f"XML parse error: {err}")
        return None


def _project(path: str, name: str, text: str, facts: Facts) -> None:
    root = _parse(path, text, facts)
    if root is None:
        return
    props: dict[str, tuple[str, int | None]] = {}
    for el in root.iter():
        if el.text and el.text.strip() and not list(el):
            props.setdefault(el.tag, (el.text.strip(), line_of(el)))
    is_project = name.endswith(("proj",))
    lang = "VB.NET" if name.endswith(".vbproj") else "C#"
    if is_project:
        version = props.get("LangVersion", (None, None))[0]
        if version and not re.match(r"^\d", version):
            version = None  # latest, preview, default
        facts.add("tech_stack", tech(lang, version, "language", path, 1))
    frameworks: list[tuple[str, int | None]] = []
    for key in ("TargetFramework", "TargetFrameworks", "TargetFrameworkVersion"):
        if key in props:
            value, line = props[key]
            frameworks += [(v, line) for v in value.split(";") if v.strip()]
    for tfm, line in frameworks:
        rv = framework_version(tfm)
        if rv:
            facts.add("tech_stack", tech(rv[0], rv[1], "runtime", path, line))
    kinds: dict[str, int | None] = {}
    sdk = root.get("Sdk") or ""
    if sdk in _APP_KINDS and _APP_KINDS[sdk]:
        kinds[str(_APP_KINDS[sdk])] = 1
    for key, label in (("UseWindowsForms", "Windows Forms"), ("UseWPF", "WPF"),
                       ("UseMaui", ".NET MAUI")):  # fmt: skip
        if props.get(key, ("",))[0].lower() == "true":
            kinds[label] = props[key][1]
    guids = props.get("ProjectTypeGuids", ("", None))
    for g in re.findall(r"[0-9a-fA-F-]{36}", guids[0]):
        if g.lower() in _TYPE_GUIDS:
            kinds[_TYPE_GUIDS[g.lower()]] = guids[1]
    my_type = props.get("MyType", ("", None))[0].lower()  # VB.NET application framework
    if my_type == "windowsforms":
        kinds["Windows Forms"] = props["MyType"][1]
    for el in iter_tag(root, "Reference"):
        inc = (el.get("Include") or "").split(",")[0].strip().lower()
        if inc in _REFERENCE_TECH:
            kinds.setdefault(_REFERENCE_TECH[inc], line_of(el))
        hint = next((c.text for c in el if c.tag == "HintPath" and c.text), None)
        hm = _HINT_PACKAGE.search(hint or "")
        if hm:
            _nuget(facts, hm.group(1), hm.group(2), path, line_of(el), declared=True)
    if "ASP.NET Web Forms" in kinds or "ASP.NET MVC" in kinds:
        kinds.pop("ASP.NET", None)
    for label, line in kinds.items():
        facts.add("tech_stack", tech(label, None, _TECH_CATEGORY.get(label, "framework"), path,
                                     line))  # fmt: skip
    for el in iter_tag(root, "PackageReference", "PackageVersion"):
        pkg = el.get("Include") or el.get("Update")
        if not pkg:
            continue
        ver = el.get("Version") or el.get("VersionOverride")
        if ver is None:
            ver = next((c.text for c in el if c.tag == "Version" and c.text), None)
        _nuget(facts, pkg, _nuget_version(ver), path, line_of(el), declared=True)
    for el in iter_tag(root, "ProjectReference"):
        inc = (el.get("Include") or "").replace("\\", "/")
        if inc:
            target = posixpath.normpath(posixpath.join(posixpath.dirname(path), inc))
            facts.ref(
                kind="depends-on",
                source=f"file:{path}",
                source_label=path,
                target=target,
                target_kinds=["path"],
                file=path,
                line=line_of(el),
            )
    if not is_project:
        return
    out = props.get("OutputType", ("Library" if sdk else "", None))
    assembly = props.get("AssemblyName", (posixpath.basename(path).rsplit(".", 1)[0], None))[0]
    kind = None
    if out[0].lower() == "winexe" or (
        out[0].lower() == "exe" and kinds.keys() & {"Windows Forms", "WPF", "WinUI"}
    ):
        kind = "desktop-app"
    elif "Worker service" in kinds or re.search(
        r"Microsoft\.Extensions\.Hosting\.WindowsServices", text
    ):
        kind = "windows-service"
    if kind:
        facts.add(
            "entry_points",
            EntryPoint(
                id=make_id("entry", path, assembly, kind),
                name=assembly,
                kind=kind,
                file=path,
                line=out[1],
                evidence=[Evidence(file=path, line=out[1], snippet=f"OutputType {out[0]}")],
            ),
        )


def _nuget_version(spec: str | None) -> str | None:
    """``1.2.3``, ``[1.2.3]``, ``[1.2,2.0)`` -> the lowest version named."""
    if not spec:
        return None
    m = re.search(r"\d+(?:\.\d+){0,3}(?:-[\w.]+)?", spec)
    return m.group(0) if m else None


def _nuget(
    facts: Facts,
    pkg: str,
    version: str | None,
    path: str,
    line: int | None,
    declared: bool,
    scope: str | None = None,
) -> None:
    facts.add(
        "libraries",
        library(
            group=None,
            artifact=pkg,
            version=version,
            source="nuget",
            file=path,
            line=line,
            declared=declared,
            scope=scope,
            language=DOTNET,
        ),
    )


def _packages_config(path: str, text: str, facts: Facts) -> None:
    root = _parse(path, text, facts)
    if root is None:
        return
    for el in iter_tag(root, "package"):
        a = attrs(el)
        if a.get("id"):
            scope = "development" if a.get("developmentDependency") == "true" else None
            _nuget(facts, a["id"], a.get("version"), path, line_of(el), True, scope)
        tfm = a.get("targetFramework")
        rv = framework_version(tfm) if tfm else None
        if rv:
            facts.add("tech_stack", tech(rv[0], rv[1], "runtime", path, line_of(el)))


def _nuget_lock(path: str, text: str, facts: Facts) -> None:
    try:
        data = json.loads(text)
    except json.JSONDecodeError as err:
        facts.coverage.gap(1, facts.coverage.lines, f"JSON parse error: {err}")
        return
    deps = data.get("dependencies") if isinstance(data, dict) else None
    if not isinstance(deps, dict):
        return
    seen: set[tuple[str, str]] = set()
    for packages in deps.values():
        if not isinstance(packages, dict):
            continue
        for pkg, info in packages.items():
            if not isinstance(info, dict) or info.get("type") == "Project":
                continue
            ver = info.get("resolved")
            if not isinstance(ver, str) or (pkg, ver) in seen:
                continue
            seen.add((pkg, ver))
            direct = info.get("type") == "Direct"
            _nuget(facts, pkg, ver, path, None, direct, None if direct else "transitive")


def _solution(path: str, text: str, facts: Facts) -> None:
    m = re.search(r"^VisualStudioVersion\s*=\s*(\d+)(?:\.(\d+))?", text, re.M)
    fmt = re.search(r"Format Version (\d+\.\d+)", text)
    if m:
        version = f"{m.group(1)}.{m.group(2) or 0}"
    elif fmt:
        version = {"8.00": "2003", "9.00": "2005", "10.00": "2008", "11.00": "2010"}.get(
            fmt.group(1)
        )
    else:
        version = None
    line = _xml_line(text, "VisualStudioVersion") or 1
    facts.add("tech_stack", tech("Visual Studio solution", version, "build", path, line))


def _config(path: str, text: str, facts: Facts) -> None:
    root = _parse(path, text, facts)
    if root is None:
        return
    for el in iter_tag(root, "connectionStrings"):
        for add in el:
            a = attrs(add)
            cs = a.get("connectionString")
            if add.tag == "add" and cs:
                add_connection(
                    facts, cs, path, line_of(add), "ado.net", a.get("providerName") or ""
                )
    for client in iter_tag(root, "client"):
        for ep in client:
            a = attrs(ep)
            address = a.get("address") or ""
            binding = a.get("binding") or ""
            if ep.tag != "endpoint" or not address:
                continue
            details = {"binding": binding, "contract": a.get("contract") or ""}
            if address.lower().startswith("net.tcp://"):
                host = re.match(r"net\.tcp://([^/]+)", address, re.I)
                if host:
                    facts.add(
                        "integrations",
                        integration(type="SOCKET", target=host.group(1), direction="outbound",
                                    file=path, line=line_of(ep), protocol="net.tcp (WCF)",
                                    details=details),  # fmt: skip
                    )
                continue
            c = classify_url(address, soap_hint="http" in binding.lower() and "web" not in binding)
            if c:
                facts.add(
                    "integrations",
                    integration(type=c[0], target=c[1], direction="outbound", file=path,
                                line=line_of(ep), protocol="WCF", details=details),  # fmt: skip
                )


def _appsettings(path: str, text: str, facts: Facts) -> None:
    try:
        data = json.loads(re.sub(r"^\s*//.*$", "", text, flags=re.M))
    except json.JSONDecodeError:
        return
    cs = data.get("ConnectionStrings") if isinstance(data, dict) else None
    if isinstance(cs, dict):
        for key, value in cs.items():
            if isinstance(value, str):
                add_connection(facts, value, path, _xml_line(text, f'"{key}"'), "ado.net")


def _layout(path: str, language: str, text: str, facts: Facts) -> None:
    """A WPF/WinUI or JavaFX layout: windows and pages are screens, user controls partials."""
    root = _parse(path, text, facts)
    if root is None:
        return
    tag = root.tag
    a = attrs(root)
    cls = a.get("Class") or a.get("controller") or ""
    stem = posixpath.basename(path).rsplit(".", 1)[0]
    if language == "xaml":
        if tag == "Application":
            start = a.get("StartupUri")
            facts.add(
                "entry_points",
                EntryPoint(
                    id=make_id("entry", path, cls or stem, "desktop-app"),
                    name=cls or stem,
                    kind="desktop-app",
                    file=path,
                    line=line_of(root),
                    evidence=[Evidence(file=path, line=line_of(root), snippet="WPF Application")],
                ),
            )
            if start:
                facts.ref(
                    kind="forwards",
                    source=f"file:{path}",
                    source_label=cls or stem,
                    target=posixpath.normpath(posixpath.join(posixpath.dirname(path), start)),
                    target_kinds=["path"],
                    file=path,
                    line=line_of(root),
                )
            facts.add("tech_stack", tech("WPF", None, "framework", path, line_of(root)))
            return
        if tag not in ("Window", "Page", "UserControl", "NavigationWindow", "RibbonWindow") and not (
            tag.endswith("Window")
        ):
            return  # resource dictionaries, styles
        technology = "wpf"
        partial = tag == "UserControl"
        facts.add("tech_stack", tech("WPF", None, "framework", path, line_of(root)))
    else:
        if not cls:
            return  # an included fragment without its own controller
        technology = "javafx"
        partial = False
        facts.add("tech_stack", tech("JavaFX", None, "framework", path, line_of(root)))
    # a JavaFX view is named after its file (Rates.fxml), its controller is a separate class
    name = cls.rsplit(".", 1)[-1] if cls and language == "xaml" else stem
    sid = make_id("screen", path)
    facts.add(
        "screens",
        Screen(
            id=sid,
            name=name,
            technology=technology,
            file=path,
            fields_count=len(_XAML_INPUT.findall(text)),
            partial=partial,
            evidence=[Evidence(file=path, line=line_of(root), snippet=f"<{tag}> {cls}".strip())],
        ),
    )
    facts.symbol("desktop-form", name, path, line_of(root), owner=sid)
    if cls and language == "fxml":
        facts.symbol("desktop-form", cls, path, line_of(root), owner=sid)


def _aspx(path: str, text: str, facts: Facts, dialect: str | None) -> None:
    low = path.lower()
    d = _PAGE_DIRECTIVE.search(text)
    kind = d.group(1).lower() if d else ("control" if low.endswith(".ascx") else "page")
    if low.endswith(".master"):
        kind = "master"
    directive = d.group(2) if d else ""
    inherits = re.search(r"\bInherits\s*=\s*\"([^\"]+)\"", directive, re.I)
    stem = posixpath.basename(path)
    sid = make_id("screen", path)
    idx = LineIndex(text)
    facts.add(
        "screens",
        Screen(
            id=sid,
            name=stem,
            technology="aspx",
            file=path,
            fields_count=len(_ASP_INPUT.findall(text)) + count_fields(text),
            partial=kind != "page",
            evidence=[Evidence(file=path, line=idx.line(d.start()) if d else 1,
                               snippet=(d.group(0)[:120] if d else None))],  # fmt: skip
        ),
    )
    if inherits:
        facts.symbol("aspx-page", inherits.group(1).rsplit(".", 1)[-1], path, None, owner=sid)
    facts.add("tech_stack", tech("ASP.NET Web Forms", None, "framework", path, None))
    for m in _ASP_SQL.finditer(text):
        sql = m.group(2).replace("&#39;", "'").replace("&quot;", '"')
        if looks_like_sql(sql):
            facts.add(
                "sql_statements",
                sql_fact(file=path, line=idx.line(m.start()), text=sql, source="aspx-datasource",
                         dialect=dialect),  # fmt: skip
            )
    for m in _ASP_NAV.finditer(text):
        target = m.group(1)
        base = posixpath.dirname(path) if not text[m.start() : m.end()].count("~/") else ""
        facts.ref(
            kind="links",
            source=f"screen:{sid}",
            source_label=stem,
            target=posixpath.normpath(posixpath.join(base, target)) if base else target,
            target_kinds=["path"],
            file=path,
            line=idx.line(m.start()),
            report_unresolved=False,
        )


def _razor(path: str, text: str, facts: Facts) -> None:
    low = path.lower()
    stem = posixpath.basename(path)
    page = re.search(r"^\s*@page\b", text, re.M)
    if low.endswith(".razor"):
        partial = not page  # a Blazor component without a route
        what = "Blazor"
    else:
        partial = stem.startswith("_") or "/shared/" in low or "/components/" in low
        what = "ASP.NET Core" if page else "ASP.NET MVC"
    sid = make_id("screen", path)
    facts.add(
        "screens",
        Screen(
            id=sid,
            name=stem,
            technology="razor",
            file=path,
            fields_count=len(_RAZOR_INPUT.findall(text)) + count_fields(text),
            partial=partial,
            evidence=[Evidence(file=path, line=1)],
        ),
    )
    facts.add("tech_stack", tech(what, None, "framework", path, None))


# ---- Windows Task Scheduler -----------------------------------------------------------------

_DAYS = ("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")


def _task_xml(path: str, text: str, facts: Facts) -> None:
    root = _parse(path, text, facts)
    if root is None or root.tag != "Task":
        return
    uri = next((e.text for e in root.iter("URI") if e.text), None)
    desc = next((e.text for e in root.iter("Description") if e.text), None)
    name = (uri or posixpath.basename(path).rsplit(".", 1)[0]).strip("\\/").split("\\")[-1]
    when: list[str] = []
    for trig in root.iter():
        if not trig.tag.endswith("Trigger"):
            continue
        start = next((e.text for e in trig.iter("StartBoundary") if e.text), "")
        at = start.split("T", 1)[1][:5] if "T" in start else ""
        if trig.tag == "CalendarTrigger":
            days = [d for d in _DAYS if next(trig.iter(d), None) is not None]
            interval = next((e.text for e in trig.iter("DaysInterval") if e.text), None)
            if next(trig.iter("ScheduleByWeek"), None) is not None:
                when.append(f"weekly on {', '.join(days) or '?'} at {at}".strip())
            elif next(trig.iter("ScheduleByMonth"), None) is not None:
                when.append(f"monthly at {at}")
            else:
                every = f"every {interval} days" if interval and interval != "1" else "daily"
                when.append(f"{every} at {at}".strip())
        elif trig.tag == "TimeTrigger":
            when.append(f"once at {start}")
        elif trig.tag == "BootTrigger":
            when.append("at system start-up")
        elif trig.tag == "LogonTrigger":
            when.append("at log-on")
        else:
            when.append(trig.tag.replace("Trigger", " trigger").lower())
        rep = next((e.text for e in trig.iter("Interval") if e.text), None)
        if rep:
            when[-1] += f", repeated every {rep}"
    cmd = next((e.text for e in root.iter("Command") if e.text), None)
    args = next((e.text for e in root.iter("Arguments") if e.text), None)
    command = " ".join(x for x in (cmd, args) if x) or None
    _task(facts, path, line_of(root), name, "; ".join(when) or None, command, desc)


def _schtasks(path: str, text: str, facts: Facts) -> None:
    idx = LineIndex(text)
    for m in _SCHTASKS.finditer(text):
        opts = dict(
            (k.lower(), v.strip('"'))
            for k, v in re.findall(r"/(\w+)\s+(\"[^\"]*\"|\S+)", m.group(1))
        )
        name = opts.get("tn")
        if not name:
            continue
        sc = opts.get("sc", "").lower()
        when = {"daily": "daily", "weekly": "weekly", "monthly": "monthly", "once": "once",
                "minute": "every minute", "hourly": "hourly", "onstart": "at system start-up",
                "onlogon": "at log-on", "onidle": "when idle"}.get(sc, sc)  # fmt: skip
        if opts.get("mo") and sc in ("daily", "weekly", "monthly", "minute", "hourly"):
            when = f"{when} (every {opts['mo']})"
        if opts.get("d"):
            when += f" on {opts['d']}"
        if opts.get("st"):
            when += f" at {opts['st']}"
        _task(facts, path, idx.line(m.start()), name.split("\\")[-1], when or None,
              opts.get("tr"), None)  # fmt: skip


def _program(command: str) -> str:
    """The program a command line runs, without its folder: ``"C:\\x\\a b.exe" /q`` -> ``a b.exe``."""
    exe = command.split('"')[1] if command.startswith('"') else command.split()[0]
    return exe.replace("\\", "/").rsplit("/", 1)[-1]


def _task(
    facts: Facts,
    path: str,
    line: int | None,
    name: str,
    schedule: str | None,
    command: str | None,
    description: str | None,
) -> None:
    jid = make_id("batch_job", "windows-task", name)
    facts.add(
        "batch_jobs",
        BatchJob(
            id=jid,
            name=name,
            scheduler="windows-task",
            description=description or "Windows Task Scheduler task",
            schedule=schedule,
            command=command,
            programs=[_program(command)] if command else [],
            evidence=[Evidence(file=path, line=line, snippet=(command or "")[:120] or None)],
        ),
    )
    facts.symbol("windows-task", name, path, line, owner=jid)
