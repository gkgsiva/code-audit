"""Visual Basic 6 and VB.NET, read line by line (no parser: decision 135).

Both dialects share the statement syntax this analyzer needs, so one reader serves both:

- **files**: VB6 forms (``.frm``), modules (``.bas``), classes (``.cls``), user controls
  (``.ctl``), user documents and property pages, VB6 projects (``.vbp``, ``.vbg``) and VB.NET
  source (``.vb``, ``.Designer.vb``);
- **screens**: each VB6 form and MDI form (its controls from the form header, input controls
  counted as fields, the caption as evidence), user controls and property pages as partials;
  VB.NET Windows Forms (``Inherits Form``, fields from the designer's controls) and user
  controls; ``frmX.Show``, ``Load frmX``, ``New FormX`` as navigation between screens;
- **database access**: ADO, DAO, RDO and ADO.NET calls (``Execute``, ``Open``,
  ``OpenRecordset``, ``CommandText``, ``New SqlCommand(...)``, data adapters), Dapper and Entity
  Framework raw SQL, data controls' ``RecordSource``; SQL built in code is rebuilt like the other
  languages (``&``/``+`` concatenation, ``sql = sql & ...``, ``&=``, ``StringBuilder``,
  ``String.Format``, ``IIf``; values glued in are ``?`` holes, branches make optional parts);
  stored procedures called through ``adCmdStoredProc`` / ``CommandType.StoredProcedure``;
  connection strings (credentials masked);
- **services and entry points**: VB.NET ASP.NET MVC / Web API controllers, ASMX web methods and
  WCF operations; ``Sub Main``, Windows services (``Inherits ServiceBase``), VB6 projects
  (``Type=Exe``: desktop application when it has forms, else a program);
- **dependencies**: a VB6 project's COM references and ActiveX controls as libraries
  (``source="com"``), the well-known ones also as technologies (ADO, DAO, MSXML, Crystal
  Reports, ...); every ActiveX control is a finding (it must be replaced in the target UI);
- **integrations**: URLs, connection strings, MSMQ and IBM MQ queues, Winsock hosts, files
  opened with ``Open ... For``, ``StreamReader``/``File.*``, SMTP servers;
- **tech stack**: Visual Basic 6 / VB.NET, ``Imports`` (Windows Forms, WPF, ASP.NET, WCF,
  Entity Framework, ADO.NET providers and their databases, ...).

Complexity units (``Sub``, ``Function``, ``Property``) are measured by ``metrics.units``
with the same line reader (:func:`logical_lines`).
"""

from __future__ import annotations

import posixpath
import re
from dataclasses import dataclass, field
from typing import ClassVar

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import library, tech
from legacy_audit.analyzers.dotnet import (
    DB_NAMESPACES,
    NAMESPACE_TECH,
    add_connection,
    connection_string,
)
from legacy_audit.analyzers.dynsql import Built, Tracker, choice, concat, empty, fill, hole, lit
from legacy_audit.analyzers.integrations import classify_url, integration
from legacy_audit.analyzers.sqltools import looks_like_sql, sql_fact
from legacy_audit.model import (
    Category,
    EntryPoint,
    Evidence,
    FileRecord,
    Finding,
    Screen,
    Service,
    Severity,
    make_id,
)

VB6 = "vb6"
VBNET = "vbnet"

# ---- lines ------------------------------------------------------------------------------------

_STRING = re.compile(r'"(?:[^"]|"")*"c?', re.I)
_CONTINUES = re.compile(r"(\s_|[&+,(=]|\bAnd|\bOr|\bAndAlso|\bOrElse)$", re.I)


def strip_comment(line: str) -> str:
    """The code of one physical line: a ``'`` outside a string, or a leading ``REM``, starts
    a comment."""
    s = line.lstrip()
    if s[:4].lower() == "rem " or s.lower() == "rem":
        return ""
    in_str = False
    for i, ch in enumerate(line):
        if ch == '"':
            in_str = not in_str
        elif ch == "'" and not in_str:
            return line[:i]
    return line


def strip_comments_basic(text: str) -> str:
    """Comments blanked, line breaks kept (for duplication windows)."""
    return "\n".join(strip_comment(ln) for ln in text.split("\n"))


def logical_lines(text: str, start: int = 0) -> list[tuple[int, str]]:
    """(first line, code) per statement line: comments removed, ``_`` continuations and
    VB.NET implicit continuations (a line ending in ``&``, ``,``, ``(``, ``=`` or an operator)
    joined. ``start`` skips the first lines (a VB6 form header)."""
    out: list[tuple[int, str]] = []
    pending: tuple[int, str] | None = None
    for i, raw in enumerate(text.split("\n")[start:], start + 1):
        code = strip_comment(raw).rstrip().rstrip("\r")
        if pending is not None:
            first, acc = pending
            code = acc + " " + code.strip()
            pending = None
        else:
            first = i
        if not code.strip():
            continue
        stripped = code.rstrip()
        if _CONTINUES.search(stripped) and not stripped.lstrip().startswith("#"):
            pending = (first, stripped[:-1] if stripped.endswith("_") else stripped)
            continue
        out.append((first, code.strip()))
    if pending is not None:
        out.append((pending[0], pending[1].strip()))
    return out


def split_top(text: str, seps: str) -> list[tuple[str, str]]:
    """Split ``text`` at the characters of ``seps`` outside strings and brackets: a list of
    (separator before, piece)."""
    out: list[tuple[str, str]] = []
    depth, in_str, start, sep = 0, False, 0, ""
    for i, ch in enumerate(text):
        if ch == '"':
            in_str = not in_str
        elif in_str:
            continue
        elif ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif depth == 0 and ch in seps:
            if ch == "=" and (text[i - 1 : i] in ("<", ">", ":", "&", "+") or
                              text[i + 1 : i + 2] in ("=",)):  # fmt: skip
                continue
            out.append((sep, text[start:i]))
            sep, start = ch, i + 1
    out.append((sep, text[start:]))
    return out


def _balanced(text: str, start: int) -> tuple[str, int] | None:
    """Content of the bracket opening at ``text[start]`` and the index after its close."""
    depth, in_str = 0, False
    for i in range(start, len(text)):
        ch = text[i]
        if ch == '"':
            in_str = not in_str
        elif in_str:
            continue
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return text[start + 1 : i], i + 1
    return None


def _unquote(lit_text: str) -> str:
    body = lit_text[:-1] if lit_text.lower().endswith('"c') else lit_text
    return body[1:-1].replace('""', '"')


def _args(text: str) -> list[str]:
    """Arguments of a call: ``(a, b)`` or the VB6 statement form ``a, b``."""
    t = text.strip()
    if t.startswith("("):
        b = _balanced(t, 0)
        if b is not None and not t[b[1] :].strip():
            t = b[0]
    if not t:
        return []
    return [p.strip() for _, p in split_top(t, ",")]


# ---- statements -------------------------------------------------------------------------------

_MODIFIERS = (
    r"(?:(?:Public|Private|Friend|Protected|Shared|Static|Overrides|Overridable|Overloads|"
    r"Shadows|NotOverridable|MustOverride|MustInherit|NotInheritable|Async|Iterator|Partial|"
    r"ReadOnly|WriteOnly|Default|Widening|Narrowing|Global)\s+)*"
)
_PROC = re.compile(
    rf"^{_MODIFIERS}(Sub|Function|Property|Operator)\s+(?:(Get|Let|Set)\s+)?\[?(\w+)\]?", re.I
)
_PROC_END = re.compile(r"^End\s+(Sub|Function|Property|Operator)\b", re.I)
PROC, PROC_END = _PROC, _PROC_END  # for the metrics units
_LAMBDA_OPEN = re.compile(r"\b(?:Async\s+)?(Sub|Function)\s*\([^()]*\)\s*(As\s+[\w.]+\s*)?$", re.I)
_TYPE = re.compile(rf"^{_MODIFIERS}(Class|Module|Structure|Interface|Namespace)\s+([\w.]+)", re.I)
_TYPE_END = re.compile(r"^End\s+(Class|Module|Structure|Interface|Namespace)\b", re.I)
_DECL = re.compile(r"^(?:(?:Dim|Private|Public|Protected|Friend|Static|Shared|Const|ReadOnly|"
                   r"Global|WithEvents)\s+)+(?!Sub\b|Function\b|Property\b|Declare\b|Event\b|"
                   r"Enum\b|Type\b|Class\b|Structure\b|Module\b|Interface\b|Delegate\b|Operator\b)"
                   r"(.+)$", re.I)  # fmt: skip
_ASSIGN = re.compile(r"^(?:(?:Let|Set)\s+)?([.\w]+(?:\([^()]*\))?(?:\.\w+)*)\s*(&=|\+=|=)\s*(.+)$",
                     re.I)  # fmt: skip
_ATTRIBUTE = re.compile(r"^<\s*([^<>]+?)\s*>\s*(.*)$")
_IF_LINE = re.compile(r"^(?:Else)?If\b(.*?)\bThen\b\s*(.*)$", re.I)
_DB_METHOD = re.compile(
    r"\b([\w.]+?)\.(Execute|Open|OpenRecordset|CreateQueryDef|ExecuteReader|ExecuteNonQuery|"
    r"ExecuteScalar|ExecuteXmlReader|ExecuteReaderAsync|ExecuteNonQueryAsync|ExecuteScalarAsync|"
    r"Query|QueryFirst|QueryFirstOrDefault|QuerySingle|QuerySingleOrDefault|QueryMultiple|"
    r"QueryAsync|QueryFirstOrDefaultAsync|ExecuteAsync|SqlQuery|ExecuteSqlCommand|"
    r"ExecuteSqlRaw|ExecuteSqlRawAsync|FromSqlRaw|ExecuteSqlInterpolated|FromSqlInterpolated|"
    r"OpenResultset)\b\s*(?:\(\s*Of\s+[\w.]+\s*\))?",
    re.I,
)
_NEW_COMMAND = re.compile(
    r"\bNew\s+(?:[\w.]+\.)?(Sql|OleDb|Odbc|Oracle|MySql|Npgsql|DB2|iDB2|SQLite|Sqlite|Ase)"
    r"(Command|DataAdapter)\s*\(",
    re.I,
)
_NEW_BUILDER = re.compile(r"^(?:New\s+)?(?:System\.Text\.)?StringBuilder\b\s*(\(.*\))?$", re.I)
_BUILDER_CALL = re.compile(r"\.(Append|AppendLine|AppendFormat|Insert)\s*\(", re.I)
_NEWLINES = re.compile(
    r"^(vbCrLf|vbNewLine|vbCr|vbLf|vbTab|Environment\.NewLine|ControlChars\.(CrLf|NewLine|Cr|Lf|"
    r"Tab)|Constants\.vbCrLf)$",
    re.I,
)
_CHR = re.compile(r"^ChrW?\$?\s*\(\s*(\d+)\s*\)$", re.I)
_FORMAT_HOLE = re.compile(r"\{(\d+)(?:[,:][^}]*)?\}")
_NAME = re.compile(r"^(?:Me\.)?([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)$")
_SHOW = re.compile(r"\b([A-Za-z_]\w*)\.Show(?:Dialog)?\b|^Load\s+([A-Za-z_]\w*)\s*$|"
                   r"\bNew\s+([A-Za-z_]\w*)\s*(?:\(\s*\))?\s*(?:\.Show)", re.I)  # fmt: skip
_NEW_CLASS = re.compile(r"\bNew\s+([A-Za-z_][\w.]*)", re.I)
_SP_MARK = re.compile(r"adCmdStoredProc|CommandType\.StoredProcedure|\b4\s*$", re.I)
_PROC_NAME = re.compile(r"^[A-Za-z_][\w$#]*(\.[A-Za-z_][\w$#]*){0,2}$")
_URL = re.compile(r"\b(?:https?|ftps?|sftp|ldaps?|smtp)://[^\s\"'<>()\\]+", re.I)
_OPEN_FILE = re.compile(r"^Open\s+(.+?)\s+For\s+(Input|Output|Append|Binary|Random)\b", re.I)
_DOTNET_FILE = re.compile(
    r"\b(?:(File)\.(ReadAllText|ReadAllLines|ReadAllBytes|ReadLines|OpenRead|OpenText|"
    r"WriteAllText|WriteAllLines|WriteAllBytes|AppendAllText|OpenWrite|Create|AppendText)|"
    r"New\s+(?:System\.IO\.)?(StreamReader|StreamWriter|FileStream))\s*\(",
    re.I,
)
_MSMQ = re.compile(
    r"\bNew\s+(?:System\.Messaging\.)?MessageQueue\s*\(|\.PathName\s*=|\.FormatName\s*=", re.I
)
_MQ_ACCESS = re.compile(r"\.AccessQueue\s*\(?", re.I)
_WINSOCK = re.compile(r"\.(?:RemoteHost\s*=|Connect\s*\(?)", re.I)
_SMTP = re.compile(r"\bNew\s+(?:System\.Net\.Mail\.)?SmtpClient\s*\(|\.Host\s*=|smtpserver",
                   re.I)  # fmt: skip
_INPUT_CONTROLS = re.compile(
    r"^(TextBox|ComboBox|CheckBox|OptionButton|RadioButton|ListBox|CheckedListBox|MaskEdBox|"
    r"MaskedTextBox|DTPicker|DateTimePicker|MonthView|MonthCalendar|RichTextBox|DataCombo|"
    r"DataList|DBCombo|DBList|UpDown|NumericUpDown|Slider|TrackBar|ImageCombo|DataGrid|"
    r"DataGridView|MSFlexGrid|MSHFlexGrid|DBGrid|TDBGrid|SSDBGrid|vsFlexGrid|fpSpread|"
    r"ListView|FileListBox|DriveListBox|DirListBox)$",
    re.I,
)
_VB6_EXTENSIONS = (".frm", ".ctl", ".dob", ".pag", ".dsr", ".cls", ".bas")
# Base classes (Inherits) -> what the class is.
_BASES = {
    "form": "form",
    "usercontrol": "usercontrol",
    "servicebase": "windows-service",
    "backgroundservice": "background-service",
    "controller": "mvc",
    "apicontroller": "api",
    "controllerbase": "api",
    "webservice": "asmx",
    "dbcontext": "dbcontext",
    "page": "aspx",
}
_HTTP_ATTR = re.compile(r"^Http(Get|Post|Put|Delete|Patch)$", re.I)
_COND_BLOCKS = {"if", "select", "for", "do", "while", "catch"}


def _vb6_header_end(lines: list[str]) -> int:
    """Index of the first code line of a VB6 form/class/control file (after the designer
    header and the ``Attribute`` lines); 0 when there is no header."""
    last = -1
    for i, ln in enumerate(lines[:4000]):
        s = ln.strip()
        if s.lower().startswith("attribute vb_"):
            last = i
        elif last >= 0 and s and not s.lower().startswith("attribute "):
            break
    return last + 1 if last >= 0 else 0


@dataclass
class _Control:
    type: str
    name: str
    line: int
    props: dict[str, tuple[str, int]] = field(default_factory=dict)


def parse_form_header(lines: list[str]) -> tuple[_Control | None, list[_Control]]:
    """The form (or user control) and its controls from a VB6 designer header."""
    stack: list[_Control] = []
    controls: list[_Control] = []
    root: _Control | None = None
    in_property = 0
    for i, raw in enumerate(lines, 1):
        s = raw.strip()
        if s.lower().startswith("attribute vb_"):
            break
        if s.startswith("BeginProperty"):
            in_property += 1
            continue
        if s.startswith("EndProperty"):
            in_property = max(in_property - 1, 0)
            continue
        if in_property:
            continue
        m = re.match(r"^Begin\s+([\w.]+)\s+(\w+)", s)
        if m:
            c = _Control(m.group(1), m.group(2), i)
            if root is None:
                root = c
            else:
                controls.append(c)
            stack.append(c)
            continue
        if s == "End" and stack:
            stack.pop()
            continue
        kv = re.match(r"^(\w+)\s*=\s*(.*)$", s)
        if kv and stack:
            value = kv.group(2).strip()
            if value.startswith('"'):
                m2 = _STRING.match(value)
                value = _unquote(m2.group(0)) if m2 else value.strip('"')
            stack[-1].props[kv.group(1).lower()] = (value, i)
    return root, controls


class BasicAnalyzer:
    name: ClassVar[str] = "basic"
    version: ClassVar[str] = "1"

    def supports(self, file: FileRecord) -> bool:
        return file.language in (VB6, VBNET, "vb6-project") and file.category not in (
            Category.TEST,
            Category.GENERATED,
            Category.BINARY,
        )

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        facts = Facts()
        text = ctx.text or ""
        facts.coverage.lines = text.count("\n") + 1
        if file.language == "vb6-project":
            _vbp(file.path, text, facts)
        else:
            _Reader(file, ctx, facts, text).run()
        return facts


# ---- VB6 projects -----------------------------------------------------------------------------

# COM references and ActiveX controls -> (technology, category).
_COM_TECH: list[tuple[re.Pattern[str], str, str]] = [
    (re.compile(r"ActiveX Data Objects|msado\d*\.(dll|tlb)", re.I), "ADO", "library"),
    (re.compile(r"DAO \d|dao\d+\.dll|Data Access Objects", re.I), "DAO", "library"),
    (re.compile(r"Remote Data Object|msrdo20", re.I), "RDO", "library"),
    (re.compile(r"Microsoft XML|msxml\d*\.dll", re.I), "MSXML", "library"),
    (re.compile(r"SOAP Type Library|mssoap\d*\.dll", re.I), "Microsoft SOAP Toolkit", "library"),
    (re.compile(r"Crystal|crystl32\.ocx|craxdrt\.dll|crviewer\.dll", re.I), "Crystal Reports",
     "library"),
    (re.compile(r"Excel \d|Excel\.exe", re.I), "Microsoft Office automation (COM)", "library"),
    (re.compile(r"Word \d+\.\d+ Object|msword", re.I), "Microsoft Office automation (COM)",
     "library"),
    (re.compile(r"Outlook|msoutl", re.I), "Microsoft Office automation (COM)", "library"),
    (re.compile(r"Scripting Runtime|scrrun\.dll", re.I), "Windows Scripting Runtime", "library"),
    (re.compile(r"MQAX200|IBM MQSeries Automation|mqax", re.I), "IBM MQ client", "middleware"),
    (re.compile(r"Message Queue|mqoa\.dll", re.I), "MSMQ", "middleware"),
    (re.compile(r"MSWINSCK\.OCX|Winsock", re.I), "Winsock control", "library"),
    (re.compile(r"MSINET\.OCX|Internet Transfer", re.I), "Internet Transfer control", "library"),
    (re.compile(r"MSCOMM32\.OCX", re.I), "MSComm serial control", "library"),
    (re.compile(r"COMCTL32\.OCX|MSCOMCTL\.OCX|MSCOMCT2\.OCX|Common Controls", re.I),
     "Windows Common Controls (OCX)", "library"),
    (re.compile(r"MSFLXGRD\.OCX|MSHFLXGD\.OCX|FlexGrid", re.I), "MSFlexGrid", "library"),
    (re.compile(r"MSDATGRD\.OCX|MSADODC\.OCX|MSDATLST\.OCX|DBGRID32\.OCX", re.I),
     "Data-bound controls (OCX)", "library"),
    (re.compile(r"TABCTL32\.OCX|COMDLG32\.OCX|RICHTX32\.OCX|MSMASK32\.OCX", re.I),
     "Visual Basic controls (OCX)", "library"),
    (re.compile(r"ThreeD|THREED32\.OCX|Sheridan|SSDW3B32|TrueDB|TDBG|vsflex|Spread|fpspr",
                re.I), "Third-party ActiveX controls", "library"),
]  # fmt: skip
_STDOLE = re.compile(r"stdole2?\.tlb|OLE Automation", re.I)


def _com_tech(desc: str) -> tuple[str, str] | None:
    return next(((n, c) for rx, n, c in _COM_TECH if rx.search(desc)), None)


def _vbp(path: str, text: str, facts: Facts) -> None:
    """A VB6 project (``.vbp``) or project group (``.vbg``)."""
    lines = text.splitlines()
    kv: dict[str, tuple[str, int]] = {}
    forms = 0
    for i, raw in enumerate(lines, 1):
        if "=" not in raw:
            continue
        key, value = raw.split("=", 1)
        key, value = key.strip(), value.strip()
        lk = key.lower()
        if lk in ("form", "usercontrol", "userdocument", "propertypage"):
            forms += lk == "form"
        elif lk == "reference":
            # *\G{guid}#2.8#0#..\path\msado15.dll#Microsoft ActiveX Data Objects 2.8 Library
            parts = value.split("#")
            if len(parts) >= 5 and not _STDOLE.search(value):
                desc = parts[4].strip() or posixpath.basename(parts[3].replace("\\", "/"))
                _com(facts, path, i, desc, parts[1], "com-reference", parts[3])
        elif lk == "object":
            # {831FDD16-0C5C-11D2-A9FC-0000F8754DA1}#2.0#0; MSCOMCTL.OCX
            ver = value.split("#")[1] if "#" in value else None
            ocx = value.rsplit(";", 1)[-1].strip() if ";" in value else value
            _com(facts, path, i, ocx, ver, "activex-control", ocx)
            facts.add(
                "findings",
                Finding(
                    id=make_id("finding", "vb6-activex-control", path, ocx),
                    rule_id="vb6-activex-control",
                    severity=Severity.MEDIUM,
                    category="portability",
                    file=path,
                    line=i,
                    message=f"ActiveX control {ocx} is used by the forms; it has no web "
                    "equivalent and must be replaced in the new user interface",
                    remediation="List the screens using it and pick an Angular component (grid, "
                    "date picker, tree, chart) to replace it.",
                    evidence=[Evidence(file=path, line=i, snippet=raw.strip()[:120])],
                ),
            )
        kv.setdefault(lk, (value.strip('"'), i))
    if path.lower().endswith(".vbg"):
        facts.add("tech_stack", tech("Visual Basic 6", "6.0", "language", path, 1))
        return
    facts.add("tech_stack", tech("Visual Basic 6", "6.0", "language", path, 1))
    ptype = kv.get("type", ("Exe", 1))
    name = kv.get("name", (posixpath.basename(path).rsplit(".", 1)[0], 1))[0]
    startup = kv.get("startup", ("", 0))[0]
    if ptype[0].lower() in ("exe", "oleexe"):
        kind = "desktop-app" if forms or (startup and "sub main" not in startup.lower()) else "main"
        facts.add(
            "entry_points",
            EntryPoint(
                id=make_id("entry", path, name, kind),
                name=name,
                kind=kind,
                file=path,
                line=ptype[1],
                evidence=[Evidence(file=path, line=ptype[1],
                                   snippet=f"Type={ptype[0]} Startup={startup}"[:120])],  # fmt: skip
            ),
        )
        if startup and "sub main" not in startup.lower():
            facts.ref(
                kind="forwards",
                source=f"file:{path}",
                source_label=name,
                target=startup,
                target_kinds=["desktop-form"],
                file=path,
                line=kv["startup"][1],
                report_unresolved=False,
            )


def _com(
    facts: Facts, path: str, line: int, desc: str, version: str | None, scope: str, file: str
) -> None:
    ver = version if version and re.match(r"^\d+(\.\d+)*$", version) else None
    facts.add(
        "libraries",
        library(group=None, artifact=desc, version=ver, source="com", file=path, line=line,
                scope=scope, language=VB6),  # fmt: skip
    )
    found = _com_tech(desc + " " + file)
    if found:
        facts.add("tech_stack", tech(found[0], None, found[1], path, line))


# ---- code -------------------------------------------------------------------------------------


@dataclass
class _Proc:
    name: str
    line: int
    tracker: Tracker
    consumed: set[str] = field(default_factory=set)
    blocks: list[str] = field(default_factory=list)
    lambdas: int = 0
    attrs: list[str] = field(default_factory=list)
    shared: bool = False


@dataclass
class _Class:
    name: str
    kind: str  # class, module, interface, structure, namespace
    line: int
    base: str = ""
    attrs: list[str] = field(default_factory=list)
    form_fields: int = 0


class _Reader:
    def __init__(self, file: FileRecord, ctx: AnalyzerContext, facts: Facts, text: str) -> None:
        self.file = file
        self.path = file.path
        self.lang = file.language
        self.facts = facts
        self.text = text
        self.dialect = ctx.config.sql.dialect
        self.module = Tracker()
        self.module_consumed: set[str] = set()
        self.proc: _Proc | None = None
        self.classes: list[_Class] = []
        self.builders: set[str] = set()
        self.var_types: dict[str, str] = {}  # variable -> class it was created from
        self.pending_attrs: list[str] = []
        self.screen_id: str | None = None
        self.vb_name = posixpath.basename(self.path).rsplit(".", 1)[0]
        self.sql_seen: set[tuple[int, str]] = set()
        self.url_seen: set[tuple[int, str]] = set()
        self.stored_proc = bool(_SP_MARK.search(text)) or "storedprocedure" in text.lower()
        self.soap = bool(re.search(r"MSSOAPLib|SoapClient|\?wsdl|\.asmx", text, re.I))
        self.dotnet = self.lang == VBNET

    # ---- driving ------------------------------------------------------------------------------
    def run(self) -> None:
        lines = self.text.split("\n")
        start = 0
        low = self.path.lower()
        if self.lang == VB6 and low.endswith(_VB6_EXTENSIONS):
            start = _vb6_header_end(lines)
            m = re.search(r'^Attribute\s+VB_Name\s*=\s*"([^"]+)"', self.text, re.M | re.I)
            if m:
                self.vb_name = m.group(1)
            if low.endswith((".frm", ".ctl", ".dob", ".pag", ".dsr")):
                self._vb6_form(lines[:start])
            kind = {".bas": "vb-module", ".cls": "vb-class"}.get(low[-4:], "")
            if kind:
                self.facts.symbol(kind, self.vb_name, self.path, 1)
        if self.lang == VB6:
            self.facts.add("tech_stack", tech("Visual Basic 6", None, "language", self.path, None))
        else:
            self.facts.add("tech_stack", tech("VB.NET", None, "language", self.path, None))
        logical = logical_lines(self.text, start)
        for idx, (line, code) in enumerate(logical):
            nxt = logical[idx + 1][1] if idx + 1 < len(logical) else ""
            self._line(line, code, nxt)
        self._end_proc()
        self._leftovers(self.module, self.module_consumed)

    def _cond(self) -> bool:
        return bool(self.proc and any(b in _COND_BLOCKS for b in self.proc.blocks))

    def _tracker(self) -> Tracker:
        return self.proc.tracker if self.proc else self.module

    def _consumed(self) -> set[str]:
        return self.proc.consumed if self.proc else self.module_consumed

    def _consume(self, name: str, value: Built) -> None:
        self._consumed().add(name)
        if self.module.get(name) is value:
            self.module_consumed.add(name)  # a module constant used here

    # ---- one logical line ---------------------------------------------------------------------
    def _line(self, line: int, code: str, nxt: str) -> None:
        # attributes: <WebMethod()> Public Function ..., or on their own line
        while True:
            m = _ATTRIBUTE.match(code)
            if not m or code.startswith("<>"):
                break
            self.pending_attrs += [a.strip() for _, a in split_top(m.group(1), ",")]
            code = m.group(2).strip()
            if not code:
                return
        low = code.lower()
        if low.startswith("#"):  # #If, #Region, #Const
            return
        if re.match(r"^(Imports|Option)\b", code, re.I):
            self._imports(code, line)
            return
        t = _TYPE.match(code)
        if t and not low.startswith(("end ", "exit ")):
            kind = t.group(1).lower()
            if kind != "namespace":
                self.classes.append(_Class(t.group(2), kind, line, attrs=self.pending_attrs))
                self.facts.symbol(
                    "dotnet-module" if kind == "module" else "dotnet-class",
                    t.group(2), self.path, line,
                )  # fmt: skip
            self.pending_attrs = []
            return
        if _TYPE_END.match(code):
            if not low.startswith("end namespace") and self.classes:
                self._end_class(self.classes.pop())
            return
        m2 = re.match(r"^Inherits\s+([\w.]+)", code, re.I)
        if m2 and self.classes:
            self.classes[-1].base = m2.group(1)
            self._class_kind(self.classes[-1], line)
            return
        if self.proc is not None and _PROC_END.match(code):
            if self.proc.lambdas:
                self.proc.lambdas -= 1
            else:
                self._end_proc()
            return
        p = _PROC.match(code)
        if p and not (self.classes and self.classes[-1].kind == "interface"):
            if self._proc_start(p, code, line, nxt):
                return
        if self.proc is None and not self.dotnet and low.startswith(("dim ", "private ", "public ",
                                                                      "global ", "const ")):  # fmt: skip
            self._statement(code, line, cond=False)  # module-level constants
            return
        if self.proc is None:
            if _DECL.match(code):
                self._statement(code, line, cond=False)  # VB.NET fields and constants
            return
        if _LAMBDA_OPEN.search(code) and not _PROC.match(code):
            self.proc.lambdas += 1
        self._blocks(code, line)

    def _proc_start(self, p: re.Match[str], code: str, line: int, nxt: str) -> bool:
        kind = p.group(1).lower()
        if re.search(r"\bMustOverride\b|\bDeclare\b", code, re.I):
            return False
        if kind == "property" and self.dotnet and not p.group(2):
            # VB.NET: an auto-property has no body; a full one starts with Get/Set
            if not re.match(r"^(?:(?:Private|Protected|Friend|Public)\s+)?(Get|Set)\b", nxt, re.I):
                return False
        self._end_proc()
        self.proc = _Proc(
            name=p.group(3),
            line=line,
            tracker=_copy(self.module),
            attrs=self.pending_attrs,
            shared=bool(re.search(r"\bShared\b", code, re.I)),
        )
        self.pending_attrs = []
        self._proc_meaning(p.group(1), p.group(3), code, line)
        return True

    def _end_proc(self) -> None:
        if self.proc is None:
            return
        self._leftovers(self.proc.tracker, self.proc.consumed, inherited=self.module)
        self.proc = None

    def _blocks(self, code: str, line: int) -> None:
        assert self.proc is not None
        blocks = self.proc.blocks
        low = code.lower()
        m = _IF_LINE.match(code)
        if m:
            if low.startswith("elseif") or low.startswith("else if"):
                self._expr_uses(m.group(1), line)
                return
            rest = m.group(2).strip()
            self._expr_uses(m.group(1), line)
            if rest:  # single-line If: If x Then a = b Else c = d
                for part in re.split(r"\bElse\b", rest, flags=re.I):
                    for _, stmt in split_top(part, ":"):
                        if stmt.strip():
                            self._statement(stmt.strip(), line, cond=True)
                return
            blocks.append("if")
            return
        word = low.split(None, 1)[0] if low else ""
        if low.startswith("end if"):
            _pop(blocks, "if")
        elif low.startswith("select case"):
            blocks.append("select")
        elif low.startswith("end select"):
            _pop(blocks, "select")
        elif word == "for":
            blocks.append("for")
        elif word == "next":
            _pop(blocks, "for")
        elif word == "do":
            blocks.append("do")
        elif word == "loop":
            _pop(blocks, "do")
        elif word == "while":
            blocks.append("while")
        elif low in ("wend", "end while"):
            _pop(blocks, "while")
        elif word == "try":
            blocks.append("try")
        elif word in ("catch", "finally"):
            if blocks and blocks[-1] in ("try", "catch"):
                blocks[-1] = "catch"
        elif low.startswith("end try"):
            _pop(blocks, "catch") if "catch" in blocks else _pop(blocks, "try")
        elif word in ("with", "using", "synclock"):
            blocks.append(word)
            self._calls(code, line)
        elif low.startswith(("end with", "end using", "end synclock")):
            _pop(blocks, low.split()[1])
        elif word in ("else", "case"):
            return
        else:
            for _, stmt in split_top(code, ":"):
                if stmt.strip():
                    self._statement(stmt.strip(), line, cond=self._cond())

    # ---- statements ---------------------------------------------------------------------------
    def _statement(self, code: str, line: int, cond: bool) -> None:
        low = code.lower()
        if re.match(r"^(exit|end|on\s+error|resume|goto|return)\b", low) and not low.startswith(
            "return "
        ):
            return
        self._integrations(code, line)
        d = _DECL.match(code)
        if d:
            for _, part in split_top(d.group(1), ","):
                self._declarator(part.strip(), line, cond)
            return
        a = _ASSIGN.match(code)
        if a and not low.startswith(("if ", "call ", "return ")):
            self._assignment(a.group(1), a.group(2), a.group(3).strip(), line, cond)
            return
        if self._builder_calls(code, line, cond):
            return
        self._calls(code, line)

    def _declarator(self, part: str, line: int, cond: bool) -> None:
        m = re.match(r"^(\w+)\s*(?:\(\s*\))?\s*(?:As\s+(New\s+)?([\w.]+)\s*(\(.*\))?)?\s*(?:=\s*(.+))?$",
                     part, re.I)  # fmt: skip
        if not m:
            return
        name = m.group(1).lower()
        type_ = (m.group(3) or "").lower()
        if m.group(2) and type_.endswith("stringbuilder"):
            self.builders.add(name)
            args = _args(m.group(4) or "")
            first = self._eval(args[0], line, consume=True) if args else None
            self._tracker().assign(name, first or empty(line), line, cond, append=False)
            return
        if m.group(2) or (m.group(5) or "").lower().startswith("new "):
            cls = (m.group(3) if m.group(2) else m.group(5)[4:]).split("(")[0].strip()
            self.var_types[name] = cls.rsplit(".", 1)[-1]
        value = m.group(5)
        if m.group(2) and m.group(4):
            self._calls(f"New {m.group(3)}{m.group(4)}", line)
        if value is None:
            return
        nb = _NEW_BUILDER.match(value.strip())
        if nb:
            self.builders.add(name)
            args = _args(nb.group(1) or "")
            first = self._eval(args[0], line, consume=True) if args else None
            self._tracker().assign(name, first or empty(line), line, cond, append=False)
            return
        b = self._eval(value, line)
        self._calls(value, line, uses=b is None)
        if b is not None:
            self._tracker().assign(name, b, line, cond, append=False)

    def _assignment(self, target: str, op: str, value: str, line: int, cond: bool) -> None:
        tlow = target.lower().removeprefix("me.")
        if tlow.endswith((".commandtext", ".recordsource", ".sql")) or tlow in (
            "commandtext",
            "recordsource",
            ".commandtext",
        ):
            b = self._eval(value, line, consume=True)
            if b is not None:
                source = "ado" if self.lang == VB6 else "ado.net"
                self._emit(b, source, line, strict=False, proc_ok=True)
            return
        if tlow.endswith((".connectionstring", ".connect")):
            b = self._eval(value, line)
            if b is not None:
                add_connection(self.facts, b.text, self.path, line, "ado" if self.lang == VB6 else
                               "ado.net")  # fmt: skip
            return
        if not _NAME.match(target):
            self._calls(value, line)
            return
        name = tlow
        tr = self._tracker()
        self._calls(value, line, uses=self._eval(value, line) is None)
        if op in ("&=", "+="):
            b = self._eval(value, line)
            if b is not None and tr.get(name) is not None:
                tr.assign(name, b, line, cond, append=True)
            elif b is not None:
                tr.assign(name, b, line, cond, append=False)
            return
        nb = _NEW_BUILDER.match(value)
        if nb:
            self.builders.add(name)
            args = _args(nb.group(1) or "")
            first = self._eval(args[0], line, consume=True) if args else None
            tr.assign(name, first or empty(line), line, cond, append=False)
            return
        parts = split_top(value, "&+")
        head = _NAME.match(parts[0][1].strip())
        if len(parts) > 1 and head and head.group(1).lower() == name and tr.get(name) is not None:
            rest = self._eval_parts(parts[1:], line, before=tr.get(name).text)  # type: ignore[union-attr]
            if rest is not None:
                tr.assign(name, rest, line, cond, append=True)
            return
        b = self._eval(value, line)
        if b is not None:
            tr.assign(name, b, line, cond, append=False)
        elif tr.get(name) is not None and not cond:
            tr.current.pop(name, None)  # now holds something else

    def _builder_calls(self, code: str, line: int, cond: bool) -> bool:
        m = re.match(r"^(?:Me\.)?(\w+)\s*\.", code)
        if not m or m.group(1).lower() not in self.builders:
            return False
        name = m.group(1).lower()
        pos = m.end() - 1
        done = False
        while True:
            c = _BUILDER_CALL.match(code, pos)
            if c is None:
                break
            b = _balanced(code, c.end() - 1)
            if b is None:
                break
            args = _args("(" + b[0] + ")")
            verb = c.group(1).lower()
            value: Built | None = None
            if verb == "appendformat" and args:
                value = self._format(args, line)
            elif args:
                value = self._eval(args[0], line)
            if verb == "appendline":
                value = concat(value, lit(" ", line)) if value is not None else lit(" ", line)
            if value is not None and verb != "insert":
                value.how.add("string-builder")
                self._tracker().assign(name, value, line, cond, append=True)
            done = True
            pos = b[1]
        return done

    def _calls(self, code: str, line: int, uses: bool = True) -> None:
        """Database calls, and (``uses``) SQL text passed to any other procedure."""
        handled = False
        for m in _DB_METHOD.finditer(code):
            verb = m.group(2).lower()
            rest = code[m.end() :].strip()
            if rest.startswith("("):
                b = _balanced(rest, 0)
                args = _args("(" + b[0] + ")") if b else []
            elif rest and not rest.startswith((".", ")")):
                args = _args(rest)  # VB6: cn.Execute sql, , adCmdText
            else:
                args = []
            if not args:
                continue
            first = args[0]
            if verb == "open":
                b0 = self._eval(first, line)
                if b0 is not None and connection_string(b0.text):
                    if not _NAME.match(first):  # a variable is reported where it is set
                        add_connection(self.facts, b0.text, self.path, line, "ado")
                    continue
            b = self._eval(first, line, consume=True)
            if b is None:
                continue
            if verb in ("openrecordset", "createquerydef", "openresultset"):
                source = "dao" if verb != "openresultset" else "rdo"
            elif verb in ("sqlquery", "executesqlcommand", "executesqlraw", "fromsqlraw",
                          "executesqlinterpolated", "fromsqlinterpolated",
                          "executesqlrawasync"):  # fmt: skip
                source = "ef-raw"
            elif verb.startswith(("query", "execute")) and self.dotnet:
                source = "dapper"  # ADO.NET's Execute* take no SQL argument; Dapper's do
            else:
                source = "ado" if self.lang == VB6 else "ado.net"
            if verb == "createquerydef" and len(args) > 1:
                b = self._eval(args[1], line, consume=True) or b
            self._emit(b, source, line, strict=False, proc_ok=False)
            handled = True
        for m in _NEW_COMMAND.finditer(code):
            b0 = _balanced(code, m.end() - 1)
            args = _args("(" + b0[0] + ")") if b0 else []
            if args:
                b = self._eval(args[0], line, consume=True)
                if b is not None:
                    self._emit(b, "ado.net", line, strict=False, proc_ok=True)
                    handled = True
        for m in _SHOW.finditer(code):
            target = m.group(1) or m.group(2) or m.group(3)
            if target:
                target = self.var_types.get(target.lower(), target)
            if target and target.lower() not in ("me", "msgbox", "form", "messagebox"):
                self._navigate(target, line)
        for m in _NEW_CLASS.finditer(code):
            cls = m.group(1)
            if "." not in cls and cls.lower() not in ("stringbuilder", "object", "collection",
                                                      "with"):  # fmt: skip
                self.facts.ref(kind="uses", source=f"file:{self.path}", source_label=self.path,
                               target=cls, target_kinds=["vb-class", "dotnet-class"],
                               file=self.path, line=line, report_unresolved=False)  # fmt: skip
        if handled or not uses:
            return
        self._expr_uses(code, line)

    def _expr_uses(self, code: str, line: int) -> None:
        """SQL held in a variable or written in place and handed to a procedure:
        ``RunSql sql``, ``Call Exec(sql)``, ``Return "SELECT ..."``."""
        tr = self._tracker()
        for word in set(re.findall(r"(?<![.\w])([A-Za-z_]\w*)\b(?!\s*=[^=])", code)):
            name = word.lower()
            value = tr.get(name)
            if value is not None and looks_like_sql(value.text.strip(), strict=True):
                if name not in self._consumed():
                    self._consume(name, value)
                    self._emit(value, "vb-string", line, strict=True, proc_ok=False)
        for m in _STRING.finditer(code):
            s = _unquote(m.group(0))
            if looks_like_sql(s.strip(), strict=True):
                # the whole expression the literal belongs to, rebuilt
                piece = _expression_around(code, m.start())
                b = self._eval(piece, line) or lit(s, line)
                self._emit(b, "vb-string", line, strict=True, proc_ok=False)

    # ---- values -------------------------------------------------------------------------------
    def _eval(self, expr: str, line: int, consume: bool = False) -> Built | None:
        expr = expr.strip()
        if not expr:
            return None
        parts = split_top(expr, "&+")
        if len(parts) == 1:
            return self._atom(expr, line, consume)
        return self._eval_parts(parts, line, consume=consume)

    def _eval_parts(
        self, parts: list[tuple[str, str]], line: int, before: str = "", consume: bool = False
    ) -> Built | None:
        out = empty(line)
        known = False
        for _, piece in parts:
            v = self._atom(piece.strip(), line, consume)
            if v is None:
                v = hole(before + out.text, line)
            else:
                known = True
            out = concat(out, v)
        if not known:
            return None
        out.how.add("concatenation")
        return out

    def _atom(self, a: str, line: int, consume: bool) -> Built | None:
        if not a:
            return None
        if a.startswith('"') and _STRING.fullmatch(a):
            return lit(_unquote(a), line)
        if a.startswith("(") and a.endswith(")"):
            b = _balanced(a, 0)
            if b is not None and b[1] == len(a):
                return self._eval(b[0], line, consume)
        if _NEWLINES.match(a):
            return lit(" ", line)
        c = _CHR.match(a)
        if c:
            code = int(c.group(1))
            return lit({39: "'", 34: '"'}.get(code, " "), line) if code in (39, 34, 9, 10, 13,
                                                                             32) else None  # fmt: skip
        m = _NAME.match(a)
        if m:
            name = m.group(1).lower()
            value = self._tracker().get(name)
            if value is not None and consume:
                self._consume(name, value)
            return value
        tb = re.match(r"^(?:Me\.)?(\w+)\.ToString\s*\(\s*\)$", a, re.I)
        if tb and tb.group(1).lower() in self.builders:
            value = self._tracker().get(tb.group(1).lower())
            if value is not None and consume:
                self._consume(tb.group(1).lower(), value)
            return value
        f = re.match(r"^(?:String\.Format|Format\$?)\s*\(", a, re.I)
        if f and a.lower().startswith("string.format"):
            b = _balanced(a, f.end() - 1)
            if b is not None:
                return self._format(_args("(" + b[0] + ")"), line)
        i = re.match(r"^I?If\s*\(", a, re.I)
        if i:
            b = _balanced(a, i.end() - 1)
            args = _args("(" + b[0] + ")") if b else []
            if len(args) == 3:
                x, y = self._eval(args[1], line), self._eval(args[2], line)
                if x is not None and y is not None:
                    return choice(x, y)
                return x or y
        s = re.match(r"^String\.Concat\s*\(", a, re.I)
        if s:
            b = _balanced(a, s.end() - 1)
            if b is not None:
                return self._eval_parts([("&", p) for p in _args("(" + b[0] + ")")], line)
        return None

    def _format(self, args: list[str], line: int) -> Built | None:
        if not args:
            return None
        fmt = self._eval(args[0], line)
        if fmt is None:
            return None
        values = [self._eval(x, line) for x in args[1:]]
        out = empty(line)
        pos = 0
        for m in _FORMAT_HOLE.finditer(fmt.text):
            out = concat(out, lit(fmt.text[pos : m.start()], line))
            k = int(m.group(1))
            v = values[k] if k < len(values) else None
            out = concat(out, v if v is not None else hole(out.text, line))
            pos = m.end()
        out = concat(out, lit(fmt.text[pos:], line))
        out.how.add("string-format")
        return out

    # ---- results ------------------------------------------------------------------------------
    def _emit(self, b: Built, source: str, line: int, strict: bool, proc_ok: bool) -> None:
        text = b.text.strip()
        if proc_ok and self.stored_proc and _PROC_NAME.match(text) and not looks_like_sql(text):
            text = f"CALL {text}"
            b = Built(text, b.line, lines=set(b.lines))
        if not looks_like_sql(text, strict=strict):
            return
        key = (line, text)
        if key in self.sql_seen:
            return
        self.sql_seen.add(key)
        fact = sql_fact(file=self.path, line=line, text=text, source=source,
                        dialect=self.dialect, dynamic=b.dynamic)  # fmt: skip
        fill(fact, b)
        self.facts.add("sql_statements", fact)

    def _leftovers(self, tr: Tracker, consumed: set[str], inherited: Tracker | None = None) -> None:
        """SQL text kept in a variable that no call used here (returned, passed on)."""
        for name, b in tr.current.items():
            if name in consumed:
                continue
            if inherited is not None and inherited.get(name) is b:
                continue  # a module constant: reported with the module
            self._emit(b, "vb-string", b.line, strict=True, proc_ok=False)

    def _navigate(self, target: str, line: int) -> None:
        self.facts.ref(
            kind="forwards",
            source=f"screen:{self.screen_id}" if self.screen_id else f"file:{self.path}",
            source_label=self.vb_name,
            target=target,
            target_kinds=["desktop-form"],
            file=self.path,
            line=line,
            report_unresolved=False,
        )

    def _integrations(self, code: str, line: int) -> None:
        lits = [(_unquote(m.group(0)), m.start()) for m in _STRING.finditer(code)]
        for s, _ in lits:
            if "://" in s and (line, s) not in self.url_seen:
                self.url_seen.add((line, s))
                for u in _URL.finditer(s):
                    c = classify_url(u.group(0), soap_hint=self.soap)
                    if c:
                        self.facts.add("integrations", integration(
                            type=c[0], target=c[1], direction="outbound", file=self.path,
                            line=line))  # fmt: skip
            elif ";" in s and "=" in s and not re.search(r"\.(connectionstring|connect)\s*=",
                                                         code, re.I):  # fmt: skip
                add_connection(self.facts, s, self.path, line, "ado" if self.lang == VB6 else
                               "ado.net")  # fmt: skip
        f = _OPEN_FILE.match(code)
        if f:
            b = self._eval(f.group(1), line)
            if b is not None and b.text.strip("? "):
                self._file(b.text, "inbound" if f.group(2).lower() in ("input",) else
                           "outbound" if f.group(2).lower() in ("output", "append") else "both",
                           line, "VB Open")  # fmt: skip
        for m in _DOTNET_FILE.finditer(code):
            b0 = _balanced(code, m.end() - 1)
            args = _args("(" + b0[0] + ")") if b0 else []
            b = self._eval(args[0], line) if args else None
            if b is None or not b.text.strip("? "):
                continue
            what = (m.group(2) or m.group(3) or "").lower()
            out = what.startswith(("write", "append", "openwrite", "create", "streamwriter"))
            self._file(b.text, "outbound" if out else "inbound", line, m.group(0).rstrip("("))
        if _MSMQ.search(code):
            q = next((s for s, _ in lits if "\\" in s or s.lower().startswith(("formatname:",
                                                                               "direct="))), None)  # fmt: skip
            if q:
                self.facts.add("integrations", integration(
                    type="MQ", target=q, direction="unknown", file=self.path, line=line,
                    protocol="MSMQ"))  # fmt: skip
        if _MQ_ACCESS.search(code) and lits:
            self.facts.add("integrations", integration(
                type="MQ", target=lits[0][0], direction="unknown", file=self.path, line=line,
                protocol="IBM MQ (MQAX)"))  # fmt: skip
        if _WINSOCK.search(code) and lits and re.match(r"^[\w.-]+$", lits[0][0]):
            if re.search(r"winsock|sck|socket|tcp", code, re.I):
                self.facts.add("integrations", integration(
                    type="SOCKET", target=lits[0][0], direction="outbound", file=self.path,
                    line=line, protocol="Winsock"))  # fmt: skip
        if _SMTP.search(code) and lits and re.match(r"^[\w.-]+\.\w+$", lits[-1][0]):
            if re.search(r"smtp|mail", code, re.I):
                self.facts.add("integrations", integration(
                    type="SMTP", target=lits[-1][0], direction="outbound", file=self.path,
                    line=line))  # fmt: skip

    def _file(self, target: str, direction: str, line: int, how: str) -> None:
        self.facts.add("integrations", integration(
            type="FILE", target=target.strip(), direction=direction, file=self.path, line=line,
            protocol=how))  # fmt: skip

    def _imports(self, code: str, line: int) -> None:
        m = re.match(r"^Imports\s+(?:\w+\s*=\s*)?([\w.]+)", code, re.I)
        if not m:
            return
        ns = m.group(1)
        for prefix, name, category in NAMESPACE_TECH:
            if ns == prefix or ns.startswith(prefix + "."):
                self.facts.add("tech_stack", tech(name, None, category, self.path, line))
                break
        if ns in DB_NAMESPACES:
            self.facts.add("tech_stack", tech(DB_NAMESPACES[ns], None, "database", self.path,
                                              line))  # fmt: skip

    # ---- forms, classes and services ----------------------------------------------------------
    def _vb6_form(self, header: list[str]) -> None:
        root, controls = parse_form_header(header)
        if root is None:
            return
        rtype = root.type.split(".")[-1].lower()
        partial = rtype in ("usercontrol", "propertypage", "userdocument")
        sid = make_id("screen", self.path)
        self.screen_id = sid
        caption = root.props.get("caption", ("", 0))[0]
        fields = sum(1 for c in controls if _INPUT_CONTROLS.match(c.type.split(".")[-1]))
        self.facts.add(
            "screens",
            Screen(
                id=sid,
                name=self.vb_name,
                technology="vb6",
                file=self.path,
                fields_count=fields,
                partial=partial,
                evidence=[Evidence(file=self.path, line=root.line,
                                   snippet=f"{root.type} {root.name}" +
                                   (f": {caption}" if caption else ""))],  # fmt: skip
            ),
        )
        self.facts.symbol("desktop-form", self.vb_name, self.path, root.line, owner=sid)
        for c in controls:
            ctype = c.type.split(".")[-1].lower()
            rs = c.props.get("recordsource")
            if rs and looks_like_sql(rs[0].strip()):
                self._emit(lit(rs[0], rs[1]), "ado" if ctype == "adodc" else "dao", rs[1],
                           strict=False, proc_ok=False)  # fmt: skip
            cs = c.props.get("connectionstring") or c.props.get("connect")
            if cs:
                add_connection(self.facts, cs[0], self.path, cs[1], "ado")
            db = c.props.get("databasename")
            if db and db[0]:
                self.facts.add("integrations", integration(
                    type="DB", target=f"access://{posixpath.basename(db[0].replace(chr(92), '/'))}",
                    direction="outbound", file=self.path, line=db[1], protocol="dao"))  # fmt: skip
                self.facts.add("tech_stack", tech("Microsoft Access", None, "database", self.path,
                                                  db[1]))  # fmt: skip
            for key in ("remotehost", "url"):
                v = c.props.get(key)
                if v and v[0]:
                    if "://" in v[0]:
                        cu = classify_url(v[0])
                        if cu:
                            self.facts.add("integrations", integration(
                                type=cu[0], target=cu[1], direction="outbound", file=self.path,
                                line=v[1]))  # fmt: skip
                    elif ctype == "winsock":
                        self.facts.add("integrations", integration(
                            type="SOCKET", target=v[0], direction="outbound", file=self.path,
                            line=v[1], protocol="Winsock"))  # fmt: skip

    def _class_kind(self, cls: _Class, line: int) -> None:
        base = cls.base.rsplit(".", 1)[-1].lower()
        kind = _BASES.get(base)
        if kind in ("form", "usercontrol"):
            sid = make_id("screen", "winforms", posixpath.dirname(self.path), cls.name)
            self.screen_id = sid
            self.facts.add(
                "screens",
                Screen(
                    id=sid,
                    name=cls.name,
                    technology="winforms",
                    file=self.path,
                    fields_count=_designer_fields(self.text),
                    partial=kind == "usercontrol",
                    evidence=[Evidence(file=self.path, line=line, snippet=f"Inherits {cls.base}")],
                ),
            )
            self.facts.symbol("desktop-form", cls.name, self.path, line, owner=sid)
            self.facts.add("tech_stack", tech("Windows Forms", None, "framework", self.path, line))
        elif kind in ("windows-service", "background-service"):
            self.facts.add(
                "entry_points",
                EntryPoint(
                    id=make_id("entry", self.path, cls.name, "windows-service"),
                    name=cls.name,
                    kind="windows-service",
                    file=self.path,
                    line=line,
                    evidence=[Evidence(file=self.path, line=line, snippet=f"Inherits {cls.base}")],
                ),
            )
        elif kind == "dbcontext":
            for m in re.finditer(r"\bProperty\s+(\w+)\s+As\s+DbSet\s*\(\s*Of\s+([\w.]+)\s*\)",
                                 self.text, re.I):  # fmt: skip
                self.facts.symbol("jpa-entity", m.group(1), self.path,
                                  self.text.count("\n", 0, m.start()) + 1,
                                  data={"table": m.group(2).rsplit(".", 1)[-1].upper(),
                                        "entity": m.group(2).rsplit(".", 1)[-1], "orm": "ef"})  # fmt: skip

    def _end_class(self, cls: _Class) -> None:
        pass

    def _proc_meaning(self, what: str, name: str, code: str, line: int) -> None:
        """What a procedure stands for: an entry point, a web method, a controller action."""
        cls = self.classes[-1] if self.classes else None
        lname = name.lower()
        if lname == "main" and what.lower() == "sub":
            body = _proc_body(self.text, line)
            kind = "desktop-app" if re.search(r"Application\.Run\b|\.Show(Dialog)?\b|"
                                              r"\bfrm\w+\.Show", body, re.I) else "main"  # fmt: skip
            self.facts.add(
                "entry_points",
                EntryPoint(
                    id=make_id("entry", self.path, "Main", kind),
                    name=f"{cls.name if cls else self.vb_name}.Main",
                    kind=kind,
                    file=self.path,
                    line=line,
                    evidence=[Evidence(file=self.path, line=line, snippet=code[:120])],
                ),
            )
            return
        if cls is None or not re.match(r"^(Public\s|Friend\s|Function|Sub)", code, re.I) and \
                not self.proc.attrs:  # type: ignore[union-attr]  # fmt: skip
            return
        attrs = self.proc.attrs if self.proc else []
        names = [re.match(r"^([\w.]+)", a).group(1).rsplit(".", 1)[-1].lower()  # type: ignore[union-attr]
                 for a in attrs if re.match(r"^[\w.]+", a)]  # fmt: skip
        cls_attrs = [re.match(r"^([\w.]+)", a).group(1).rsplit(".", 1)[-1].lower()  # type: ignore[union-attr]
                     for a in cls.attrs if re.match(r"^[\w.]+", a)]  # fmt: skip
        base = _BASES.get(cls.base.rsplit(".", 1)[-1].lower(), "")
        handler = f"{cls.name}.{name}"
        if "webmethod" in names or "operationcontract" in names or "operationcontractattribute" in \
                names:  # fmt: skip
            how = "WCF" if "webmethod" not in names else "ASMX"
            url = f"{cls.name}.{'asmx' if how == 'ASMX' else 'svc'}/{name}"
            self._service(f"{how} {cls.name}.{name}", "soap-operation", handler, url, line)
            self.facts.add("tech_stack", tech("WCF" if how == "WCF" else
                                              "ASP.NET Web Services (ASMX)", None, "framework",
                                              self.path, line))  # fmt: skip
            return
        if base in ("mvc", "api") or "apicontroller" in cls_attrs:
            if not re.search(r"\bPublic\b", code, re.I) or re.search(r"\bShared\b", code, re.I):
                return
            verb = next((_HTTP_ATTR.match(n).group(1).upper() for n in names  # type: ignore[union-attr]
                         if _HTTP_ATTR.match(n)), None)  # fmt: skip
            route = _route(attrs)
            prefix = _route(cls.attrs, ("Route", "RoutePrefix"))
            ctrl = re.sub(r"Controller$", "", cls.name)
            if prefix is not None:
                prefix = prefix.replace("[controller]", ctrl)
            else:
                prefix = (f"api/{ctrl}" if base == "api" else ctrl)
            path = route if route is not None and route.startswith("/") else (
                f"/{prefix.strip('/')}" + (f"/{route}" if route else
                                           ("" if base == "api" else f"/{name}")))  # fmt: skip
            path = path.replace("[action]", name)
            kind = "rest-operation" if base == "api" or "apicontroller" in cls_attrs else "aspnet-mvc"
            self._service(f"{verb or ('GET' if kind == 'aspnet-mvc' else 'ANY')} {path}", kind,
                          handler, path, line)  # fmt: skip

    def _service(self, name: str, kind: str, handler: str, url: str, line: int) -> None:
        sid = make_id("service", kind, self.path, name)
        self.facts.add(
            "services",
            Service(
                id=sid,
                name=name,
                kind=kind,
                file=self.path,
                handler=handler,
                url=url,
                evidence=[Evidence(file=self.path, line=line)],
            ),
        )
        self.facts.symbol("service", handler, self.path, line, owner=sid)


def _route(attrs: list[str], names: tuple[str, ...] = ("Route", "HttpGet", "HttpPost", "HttpPut",
                                                         "HttpDelete", "HttpPatch")) -> str | None:  # fmt: skip
    for a in attrs:
        m = re.match(r"^(?:[\w.]+\.)?(\w+?)(?:Attribute)?\s*\(\s*\"([^\"]*)\"", a)
        if m and m.group(1) in names:
            return m.group(2)
    return None


def _copy(tr: Tracker) -> Tracker:
    out = Tracker()
    out.current = dict(tr.current)
    out.history = {k: list(v) for k, v in tr.history.items()}
    return out


def _pop(blocks: list[str], kind: str) -> None:
    for i in range(len(blocks) - 1, -1, -1):
        if blocks[i] == kind:
            del blocks[i:]
            return


def _proc_body(text: str, line: int) -> str:
    lines = text.split("\n")[line:]
    out = []
    for ln in lines:
        if _PROC_END.match(ln.strip()):
            break
        out.append(ln)
    return "\n".join(out)


def _designer_fields(text: str) -> int:
    """Input controls created by a Windows Forms designer (``Me.txtName = New TextBox()``)."""
    n = 0
    for m in re.finditer(r"=\s*New\s+(?:System\.Windows\.Forms\.)?(\w+)\s*\(", text):
        if _INPUT_CONTROLS.match(m.group(1)):
            n += 1
    return n


def _expression_around(code: str, pos: int) -> str:
    """The comma-separated argument (or right-hand side) that holds the character at ``pos``."""
    start = 0
    depth, in_str = 0, False
    for i, ch in enumerate(code[:pos]):
        if ch == '"':
            in_str = not in_str
        elif in_str:
            continue
        elif ch == "(":
            depth += 1
            start = i + 1
        elif ch == ")":
            depth -= 1
        elif ch in ",=" or (ch == " " and code[:i].strip().lower() in ("return",)):
            start = i + 1
    end = len(code)
    depth, in_str = 0, False
    for i in range(pos, len(code)):
        ch = code[i]
        if ch == '"':
            in_str = not in_str
        elif in_str:
            continue
        elif ch == "(":
            depth += 1
        elif ch == ")":
            if depth == 0:
                end = i
                break
            depth -= 1
        elif ch == "," and depth == 0:
            end = i
            break
    return code[start:end].strip()
