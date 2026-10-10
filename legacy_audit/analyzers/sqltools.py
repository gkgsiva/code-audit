"""SQL helpers shared by every analyzer that finds SQL: detection, normalization (for distinct
counting), statement type, tables (``sqlglot`` with a regex fallback) and vendor features."""

from __future__ import annotations

import hashlib
import logging
import re

import sqlglot
from sqlglot import exp

from legacy_audit.model import Evidence, SqlStatement, make_id

logging.getLogger("sqlglot").setLevel(logging.CRITICAL)

MAX_TEXT = 4000

# Statement shapes, not just a leading keyword, so UI text like "Select a customer" is not SQL.
_SQL_SHAPE = re.compile(
    r"""^\s*\(?\s*(
        SELECT\b[\s\S]+?\bFROM\b
      | INSERT\s+INTO\b
      | UPDATE\s+[\w.$#"]+(\s+\w+)?\s+SET\b
      | DELETE\s+(FROM\s+)?[\w.$#"]+
      | MERGE\s+INTO\b
      | WITH\s+\w+\s+AS\s*\(
      | CALL\s+[\w.$#]+
      | \{\s*(\?\s*=\s*)?CALL\b
      | EXEC(UTE)?\s+[\w.$#]+
      | (CREATE|ALTER|DROP|TRUNCATE)\s+(OR\s+REPLACE\s+)?
        (TABLE|VIEW|INDEX|SEQUENCE|PROCEDURE|FUNCTION|PACKAGE|TRIGGER|SYNONYM)\b
      | DECLARE\s+[\w-]+\s+CURSOR\b
    )""",
    re.IGNORECASE | re.VERBOSE,
)

_HQL_SHAPE = re.compile(
    r"^\s*(FROM\s+[A-Z]\w*|SELECT\s[\s\S]+?\sFROM\s+[A-Z]\w*|UPDATE\s+[A-Z]\w*\s|DELETE\s+(FROM\s+)?[A-Z]\w*)",
    re.IGNORECASE,
)

_STRING_LIT = re.compile(r"'(?:[^']|'')*'")
_NUMBER = re.compile(r"(?<![\w.$#:])\d+(\.\d+)?(?![\w.])")
_BIND = re.compile(
    r"(\?\d*|:[A-Za-z_][\w$#-]*(\s*:\s*[A-Za-z_][\w-]*)?|:\d+|#\{[^}]*\}|\$\{[^}]*\}|#\w+#)"
)
_WS = re.compile(r"\s+")
_COMMENTS = re.compile(r"--[^\n]*|/\*[\s\S]*?\*/")

VENDOR_FEATURES: list[tuple[str, re.Pattern[str]]] = [
    ("NVL", re.compile(r"\bNVL2?\s*\(", re.I)),
    ("DECODE", re.compile(r"\bDECODE\s*\(", re.I)),
    ("ROWNUM", re.compile(r"\bROWNUM\b", re.I)),
    ("CONNECT BY", re.compile(r"\bCONNECT\s+BY\b", re.I)),
    ("OUTER JOIN (+)", re.compile(r"\(\+\)")),
    ("SYSDATE", re.compile(r"\bSYSDATE\b", re.I)),
    ("DUAL", re.compile(r"\bFROM\s+DUAL\b", re.I)),
    ("TO_CHAR/TO_DATE", re.compile(r"\bTO_(CHAR|DATE|NUMBER)\s*\(", re.I)),
    ("ADD_MONTHS", re.compile(r"\bADD_MONTHS\s*\(", re.I)),
    ("SEQUENCE.NEXTVAL", re.compile(r"\.\s*NEXTVAL\b", re.I)),
    ("HINT", re.compile(r"/\*\+")),
    ("MERGE", re.compile(r"^\s*MERGE\b", re.I)),
    ("DB2 WITH UR", re.compile(r"\bWITH\s+(UR|CS|RS|RR)\s*$", re.I)),
    ("DB2 FETCH FIRST", re.compile(r"\bFETCH\s+FIRST\b", re.I)),
    ("CURRENT DATE/TIMESTAMP", re.compile(r"\bCURRENT\s+(DATE|TIMESTAMP)\b", re.I)),
    ("TOP", re.compile(r"^\s*SELECT\s+TOP\s+\d", re.I)),
]

_CALL = re.compile(r"^\s*\{?\s*(\?\s*=\s*)?(CALL|EXEC(UTE)?)\s+([\w.$#]+)", re.I)
_CURSOR = re.compile(r"^\s*DECLARE\s+[\w-]+\s+CURSOR\s+(WITH\s+HOLD\s+)?FOR\s+", re.I)
_SELECT_INTO = re.compile(r"\bINTO\s+[^()]*?(?=\bFROM\b)", re.I | re.S)
_DB2_SPECIAL_REGISTER = re.compile(r"\bCURRENT\s+(DATE|TIMESTAMP|TIME)\b", re.I)
_DB2_ISOLATION = re.compile(r"\bWITH\s+(UR|CS|RS|RR)\s*$", re.I)
_REGEX_TABLES = re.compile(
    r"\b(?:FROM|JOIN|INTO|UPDATE|TABLE|MERGE\s+INTO|USING)\s+([A-Za-z_][\w$#]*(?:\.[A-Za-z_][\w$#]*)?)",
    re.I,
)
_NOT_TABLES = {"SELECT", "DUAL", "SET", "VALUES", "WHERE", "TABLE", "LATERAL", "UNNEST", "THE"}


# Free-standing strings ("Select a customer from the list") must also carry SQL syntax.
_SQL_SYNTAX = re.compile(r"[*,=?()<>]|\b(WHERE|JOIN|GROUP\s+BY|ORDER\s+BY|UNION)\b", re.I)
_UPPER_SELECT = re.compile(r"^\s*\(?\s*SELECT\b[\s\S]+?\bFROM\b")


def looks_like_sql(text: str, *, strict: bool = False) -> bool:
    """Statement shape; ``strict`` (strings with no SQL call site) also wants upper-case
    SELECT/FROM or SQL punctuation, so UI captions are not counted."""
    m = _SQL_SHAPE.match(text)
    if not m:
        return False
    if strict and m.group(1)[:6].upper() == "SELECT":
        return bool(_UPPER_SELECT.match(text) or _SQL_SYNTAX.search(text))
    return True


def looks_like_hql(text: str) -> bool:
    return bool(_HQL_SHAPE.match(text))


def strip_comments(sql: str) -> str:
    return _COMMENTS.sub(" ", sql)


def normalize(sql: str) -> str:
    """Whitespace collapsed, literals and bind variables replaced by ``?``, upper-cased."""
    s = strip_comments(sql)
    s = _STRING_LIT.sub("?", s)
    s = _BIND.sub("?", s)
    s = _NUMBER.sub("?", s)
    s = _WS.sub(" ", s).strip().rstrip(";").strip()
    return s.upper()


def fingerprint(sql: str) -> str:
    return hashlib.sha256(normalize(sql).encode("utf-8")).hexdigest()[:16]


def statement_type(sql: str) -> str:
    s = strip_comments(sql).lstrip(" \t\r\n({")
    if _CALL.match(sql):
        return "CALL"
    if _CURSOR.match(s):
        return "SELECT"
    word = re.match(r"[A-Za-z]+", s)
    if not word:
        return "UNKNOWN"
    w = word.group(0).upper()
    if w == "WITH":
        return "SELECT"
    if w in ("CREATE", "ALTER", "DROP", "TRUNCATE", "GRANT", "REVOKE", "COMMENT"):
        return "DDL"
    if w == "FROM":
        return "SELECT"  # HQL
    return w


def vendor_features(sql: str) -> list[str]:
    return [name for name, pat in VENDOR_FEATURES if pat.search(sql)]


def _sqlglot_dialect(dialect: str | None) -> str | None:
    if dialect in (None, "db2", "generic", "sybase", "informix"):
        return None
    return {"sqlserver": "tsql", "postgresql": "postgres"}.get(dialect, dialect)


def parse_tables(sql: str, dialect: str | None) -> tuple[list[str], list[str], bool]:
    """Return (tables, procedures, parsed_ok)."""
    m = _CALL.match(sql)
    if m:
        return [], [m.group(4).upper()], True
    s = strip_comments(sql)
    s = _CURSOR.sub("", s)
    s = _BIND.sub("?", s)
    s = _SELECT_INTO.sub(" ", s) if statement_type(s) == "SELECT" else s
    s = _DB2_ISOLATION.sub("", s.strip().rstrip(";"))
    s = _DB2_SPECIAL_REGISTER.sub(lambda m: "CURRENT_" + m.group(1).upper(), s)
    try:
        tree = sqlglot.parse_one(s, read=_sqlglot_dialect(dialect))
        if tree is None or isinstance(tree, exp.Command):
            raise ValueError("not parsed")
        ctes = {c.alias_or_name.upper() for c in tree.find_all(exp.CTE)}
        tables = sorted(
            {
                ".".join(p for p in (t.db, t.name) if p).upper()
                for t in tree.find_all(exp.Table)
                if t.name and t.name.upper() not in ctes
            }
            - _NOT_TABLES
        )
        return tables, [], True
    except Exception:
        tables = sorted({m.group(1).upper() for m in _REGEX_TABLES.finditer(s)} - _NOT_TABLES)
        return tables, [], False


def sql_fact(
    *,
    file: str,
    line: int,
    text: str,
    source: str,
    dialect: str | None,
    dynamic: bool = False,
    hql: bool = False,
) -> SqlStatement:
    stype = statement_type(text)
    procs: list[str]
    if hql:
        tables, procs, ok = _hql_entities(text), [], True
    else:
        tables, procs, ok = parse_tables(text, dialect)
    fp = fingerprint(text)
    return SqlStatement(
        id=make_id("sql", file, line, fp),
        file=file,
        line=line,
        type=stype,
        source=source,
        text=text.strip()[:MAX_TEXT],
        tables=tables,
        procedures=procs,
        dialect="hql" if hql else dialect,
        dynamic=dynamic or "${" in text,
        parsed=ok,
        fingerprint=fp,
        normalized=normalize(text)[:MAX_TEXT],
        vendor_features=[] if hql else vendor_features(text),
        evidence=[Evidence(file=file, line=line, snippet=_WS.sub(" ", text.strip())[:120])],
    )


_HQL_ENTITY = re.compile(r"\b(?:FROM|JOIN|UPDATE|INTO)\s+([A-Z]\w*(?:\.\w+)*)", re.I)


def _hql_entities(text: str) -> list[str]:
    return sorted({m.group(1) for m in _HQL_ENTITY.finditer(text)} - {"SELECT"})


def split_statements(text: str) -> list[tuple[int, str]]:
    """Split a SQL script into (1-based start line, statement). Handles ``;``, a lone ``/``
    after PL/SQL blocks, quotes and comments. Lines starting with SQL*Plus commands are skipped.
    """
    out: list[tuple[int, str]] = []
    buf: list[str] = []
    start = 0
    in_plsql = False
    plsql_start = re.compile(
        r"^\s*(CREATE\s+(OR\s+REPLACE\s+)?(PACKAGE|PROCEDURE|FUNCTION|TRIGGER|TYPE)\b|DECLARE\b|BEGIN\b)",
        re.I,
    )
    sqlplus = re.compile(
        r"^\s*(SET|SPOOL|PROMPT|WHENEVER|EXIT|QUIT|COLUMN|COL|TTITLE|BTITLE|BREAK|DEFINE|UNDEFINE|@@?|START|SHOW|CONNECT|REM(ARK)?)\b",
        re.I,
    )
    in_comment = False
    for no, raw in enumerate(text.splitlines(), 1):
        line = raw
        stripped = line.strip()
        if not buf:
            if in_comment:
                if "*/" in stripped:
                    in_comment = False
                continue
            if not stripped or stripped.startswith("--"):
                continue
            if stripped.startswith("/*") and "*/" not in stripped:
                in_comment = True
                continue
            if stripped.startswith("/*") and stripped.endswith("*/"):
                continue
            if stripped == "/" or sqlplus.match(line):
                continue
            start = no
            in_plsql = bool(plsql_start.match(line))
        if in_plsql:
            if stripped == "/":
                out.append((start, "\n".join(buf)))
                buf = []
                continue
            buf.append(line)
            continue
        buf.append(line)
        code = _STRING_LIT.sub("''", re.sub(r"--.*$", "", line))
        if code.rstrip().endswith(";"):
            stmt = "\n".join(buf).rstrip().rstrip(";")
            out.append((start, stmt))
            buf = []
    if buf and "\n".join(buf).strip():
        out.append((start, "\n".join(buf).rstrip().rstrip(";")))
    return out
