"""Embedded SQL (``EXEC SQL ... ;`` in Pro*C, ``EXEC SQL ... END-EXEC`` in COBOL / Pro*COBOL).

Each block becomes a ``SqlStatement`` with source ``embedded``; cursor declarations count as the
SELECT they declare; ``OPEN/FETCH/CLOSE``, ``WHENEVER``, ``CONNECT`` and transaction control are
not statements. ``PREPARE``/``EXECUTE IMMEDIATE`` from a host variable are rebuilt from the code
that fills the variable when the caller can (see ``resolve``), else recorded as ``DYNAMIC``.
Callers get the block spans back so they can blank them before parsing the host language.
"""

from __future__ import annotations

import re
from collections.abc import Callable
from dataclasses import dataclass, field

from legacy_audit.analyzers.dynsql import Built, fill
from legacy_audit.analyzers.sql import embedded_dml
from legacy_audit.analyzers.sqltools import looks_like_sql, sql_fact, statement_type
from legacy_audit.analyzers.textutil import LineIndex
from legacy_audit.model import SqlStatement

_C_BLOCK = re.compile(
    r"\bEXEC\s+SQL\b(\s+EXECUTE\s+(?:BEGIN|DECLARE)\b[\s\S]*?END-EXEC|(?:'[^']*'|\"[^\"]*\"|[^;'\"])*)\s*;",
    re.I,
)
_COBOL_BLOCK = re.compile(r"\bEXEC\s+SQL\b([\s\S]*?)\bEND-EXEC\b", re.I)
_NOT_STATEMENTS = re.compile(
    r"^\s*(OPEN|FETCH|CLOSE|WHENEVER|CONNECT|COMMIT|ROLLBACK|SAVEPOINT|BEGIN\s+DECLARE|"
    r"END\s+DECLARE|DECLARE\s+[\w-]+\s+(STATEMENT|TABLE)|VAR\b|TYPE\b|ENABLE|CONTEXT|ALLOCATE|"
    r"FREE|DESCRIBE|SET\b|GET\s+DIAGNOSTICS|EXECUTE\s+[\w-]+\s*(USING|$))",
    re.I,
)
_INCLUDE = re.compile(r"^\s*INCLUDE\s+['\"]?([\w.$#-]+)['\"]?", re.I)
_DYNAMIC = re.compile(r"^\s*(PREPARE\s+[\w-]+\s+FROM|EXECUTE\s+IMMEDIATE)\s+(.*)$", re.I | re.S)
_PLSQL_BLOCK = re.compile(
    r"^\s*EXECUTE\s+(DECLARE|BEGIN)\b([\s\S]*?)(END\s*;?\s*END-EXEC|END\s*;?)?\s*$", re.I
)
_PLSQL_CALL = re.compile(r"(?:\bBEGIN|;)\s*([A-Za-z][\w$#]*(?:\.[A-Za-z][\w$#]*)?)\s*\(", re.I)
_PLSQL_WORDS = {
    "IF",
    "WHILE",
    "FOR",
    "SELECT",
    "INSERT",
    "UPDATE",
    "DELETE",
    "MERGE",
    "RETURN",
    "RAISE",
    "OPEN",
    "FETCH",
    "CLOSE",
    "EXIT",
    "NULL",
    "DBMS_OUTPUT.PUT_LINE",
}
_DML_TYPES = {"SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "CALL", "WITH"}


@dataclass
class EmbeddedResult:
    statements: list[SqlStatement] = field(default_factory=list)
    includes: list[tuple[int, str]] = field(default_factory=list)  # (line, member)
    spans: list[tuple[int, int]] = field(default_factory=list)  # character spans of blocks
    unparsed: list[tuple[int, int]] = field(default_factory=list)  # line ranges


def extract(
    text: str,
    *,
    file: str,
    dialect: str | None,
    cobol: bool,
    resolve: Callable[[str, int], Built | None] | None = None,
) -> EmbeddedResult:
    """EXEC SQL blocks of C (``;``) or COBOL (``END-EXEC``) text. COBOL callers pass the code
    area only (see ``cobol.source_lines``), so sequence and identification columns are gone.
    ``resolve(":host-var", line)`` rebuilds the text a dynamic statement prepares."""
    res = EmbeddedResult()
    index = LineIndex(text)
    for m in (_COBOL_BLOCK if cobol else _C_BLOCK).finditer(text):
        res.spans.append((m.start(), m.end()))
        line = index.line(m.start())
        body = m.group(1).strip()
        inc = _INCLUDE.match(body)
        if inc:
            member = inc.group(1)
            if member.upper() not in ("SQLCA", "SQLDA", "ORACA", "SQLCA.H", "SQLDA.H", "ORACA.H"):
                res.includes.append((line, member))
            continue
        if not body or _NOT_STATEMENTS.match(body):
            continue
        plsql = _PLSQL_BLOCK.match(body)
        if plsql:
            for call in _PLSQL_CALL.finditer(body):
                if call.group(1).upper() not in _PLSQL_WORDS:
                    res.statements.append(
                        sql_fact(
                            file=file,
                            line=line + body.count("\n", 0, call.start(1)),
                            text=f"CALL {call.group(1)}",
                            source="embedded",
                            dialect=dialect,
                        )
                    )
            for off, dml in embedded_dml(body):
                res.statements.append(
                    sql_fact(
                        file=file,
                        line=line + body.count("\n", 0, off),
                        text=dml,
                        source="embedded",
                        dialect=dialect,
                    )
                )
            continue
        dyn = _DYNAMIC.match(body)
        if dyn:
            arg = dyn.group(2).strip()
            built = resolve(arg.split()[0], line) if resolve and arg.startswith(":") else None
            if built is not None and looks_like_sql(built.text):
                fact = sql_fact(
                    file=file,
                    line=line,
                    text=built.text,
                    source="embedded",
                    dialect=dialect,
                    dynamic=built.dynamic,
                )
                fact.built_with = ["prepare-from-variable"]
                res.statements.append(fill(fact, built))
            elif arg.startswith(":"):
                fact = sql_fact(
                    file=file,
                    line=line,
                    text=body,
                    source="embedded",
                    dialect=dialect,
                    dynamic=True,
                )
                res.statements.append(
                    fact.model_copy(
                        update={
                            "type": "DYNAMIC",
                            "structural_holes": 1,
                            "built_with": ["prepare-from-variable"],
                        }
                    )
                )
            elif arg.startswith(("'", '"')):
                res.statements.append(
                    sql_fact(
                        file=file,
                        line=line,
                        text=arg.strip("'\""),
                        source="embedded",
                        dialect=dialect,
                    )
                )
            continue
        cursor = re.match(
            r"^\s*DECLARE\s+[\w-]+\s+CURSOR\s+(?:WITH\s+HOLD\s+)?FOR\s+([\s\S]+)$", body, re.I
        )
        stmt = body
        if cursor:
            target = cursor.group(1).strip()
            if re.match(r"^[\w-]+$", target):  # cursor over a prepared statement
                continue
        if statement_type(stmt) not in _DML_TYPES:
            res.unparsed.append((line, line + body.count("\n")))
            continue
        fact = sql_fact(file=file, line=line, text=stmt, source="embedded", dialect=dialect)
        if not fact.parsed:
            res.unparsed.append((line, line + body.count("\n")))
        res.statements.append(fact)
    return res


def block_spans(text: str, *, cobol: bool) -> list[tuple[int, int]]:
    """Character spans of the ``EXEC SQL`` blocks (the same spans :func:`extract` returns)."""
    return [m.span() for m in (_COBOL_BLOCK if cobol else _C_BLOCK).finditer(text)]


def blank_spans(text: str, spans: list[tuple[int, int]]) -> str:
    """Replace each span with spaces, keeping newlines so line numbers stay right."""
    if not spans:
        return text
    chars = list(text)
    for start, end in spans:
        for i in range(start, end):
            if chars[i] != "\n":
                chars[i] = " "
    return "".join(chars)
