"""SQL scripts, PL/SQL packages and DDL (``.sql``, ``.pks``/``.pkb``..., DDL exports in
``audit-inputs/ddl``). DML becomes ``SqlStatement``s; tables, views, sequences and PL/SQL units
become ``DbObject``s. DML inside PL/SQL bodies is extracted too (source ``plsql``)."""

from __future__ import annotations

import re
from typing import ClassVar

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.sqltools import split_statements, sql_fact, statement_type
from legacy_audit.model import DbObject, Evidence, FileRecord, make_id

_PLSQL_UNIT = re.compile(
    r"^\s*CREATE\s+(?:OR\s+REPLACE\s+)?(?:EDITIONABLE\s+|NONEDITIONABLE\s+)?"
    r"(PACKAGE\s+BODY|PACKAGE|PROCEDURE|FUNCTION|TRIGGER|TYPE\s+BODY|TYPE)\s+"
    r"(\"?[\w$#]+\"?(?:\.\"?[\w$#]+\"?)?)",
    re.I,
)
_DDL_OBJECT = re.compile(
    r"^\s*CREATE\s+(?:OR\s+REPLACE\s+)?(?:GLOBAL\s+TEMPORARY\s+|UNIQUE\s+|BITMAP\s+|"
    r"FORCE\s+|NOFORCE\s+|MATERIALIZED\s+|PUBLIC\s+)*"
    r"(TABLE|VIEW|INDEX|SEQUENCE|SYNONYM)\s+(\"?[\w$#]+\"?(?:\.\"?[\w$#]+\"?)?)",
    re.I,
)
_MEMBER = re.compile(r"\b(PROCEDURE|FUNCTION)\s+([\w$#]+)", re.I)
_DML = re.compile(r"\b(SELECT|INSERT|UPDATE|DELETE|MERGE)\b", re.I)
_STATEMENT_STARTERS = {";", "BEGIN", "THEN", "ELSE", "LOOP", "(", "IS", "AS", "DECLARE", "", "IN"}


def blank_strings_and_comments(text: str) -> str:
    """Same length as ``text`` with string literals and comments replaced by spaces."""

    def blank(m: re.Match[str]) -> str:
        return re.sub(r"[^\n]", " ", m.group(0))

    return re.sub(r"'(?:[^']|'')*'|--[^\n]*|/\*[\s\S]*?\*/", blank, text)


def embedded_dml(body: str) -> list[tuple[int, str]]:
    """(offset, statement) for DML statements inside a PL/SQL block."""
    clean = blank_strings_and_comments(body)
    out: list[tuple[int, str]] = []
    covered = -1
    for m in _DML.finditer(clean):
        if m.start() < covered:
            continue
        before = clean[: m.start()].rstrip()
        prev = re.search(r"([\w]+|[;(])$", before)
        prev_tok = prev.group(1).upper() if prev else ""
        if prev_tok not in _STATEMENT_STARTERS:
            continue
        depth = 0
        end = len(clean)
        for i in range(m.end(), len(clean)):
            ch = clean[i]
            if ch == "(":
                depth += 1
            elif ch == ")":
                if depth == 0:  # closes the enclosing cursor/sub-query parenthesis
                    end = i
                    break
                depth -= 1
            elif ch == ";" and depth == 0:
                end = i
                break
        stmt = body[m.start() : end].strip()
        if (
            prev_tok == "("
            and stmt.upper().startswith("SELECT")
            and not re.search(r"\bFROM\b", stmt, re.I)
        ):
            continue
        out.append((m.start(), stmt))
        covered = end
    return out


class SqlAnalyzer:
    name: ClassVar[str] = "sql"
    version: ClassVar[str] = "1"

    def supports(self, file: FileRecord) -> bool:
        return file.language in ("sql", "plsql")

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        facts = Facts()
        text = ctx.text or ""
        facts.coverage.lines = text.count("\n") + 1
        dialect = ctx.config.sql.dialect
        path = file.path
        for start, stmt in split_statements(text):
            unit = _PLSQL_UNIT.match(stmt)
            if unit:
                self._plsql_unit(path, start, stmt, unit, facts, dialect)
                continue
            ddl = _DDL_OBJECT.match(stmt)
            if ddl:
                otype = ddl.group(1).lower()
                name = ddl.group(2).replace('"', "").upper()
                self._db_object(facts, name, otype, path, start)
                continue
            stype = statement_type(stmt)
            if stype in (
                "DDL",
                "GRANT",
                "REVOKE",
                "COMMENT",
                "COMMIT",
                "ROLLBACK",
                "SET",
                "UNKNOWN",
            ):
                continue
            if stype in ("BEGIN", "DECLARE"):
                for off, dml in embedded_dml(stmt):
                    line = start + stmt.count("\n", 0, off)
                    facts.add(
                        "sql_statements",
                        sql_fact(file=path, line=line, text=dml, source="plsql", dialect=dialect),
                    )
                continue
            fact = sql_fact(file=path, line=start, text=stmt, source="sql-file", dialect=dialect)
            if not fact.parsed:
                facts.coverage.gap(start, start + stmt.count("\n"), "SQL not parsed by sqlglot")
            facts.add("sql_statements", fact)
        return facts

    def _db_object(
        self,
        facts: Facts,
        name: str,
        otype: str,
        path: str,
        line: int,
        members: list[str] | None = None,
    ) -> None:
        oid = make_id("db_object", otype.split()[0], name)
        facts.add(
            "db_objects",
            DbObject(
                id=oid,
                name=name,
                object_type=otype,
                defined_in=path,
                members=members or [],
                evidence=[Evidence(file=path, line=line)],
            ),
        )
        facts.symbol("db-" + otype.split()[0], name, path, line, owner=oid)

    def _plsql_unit(
        self, path: str, start: int, stmt: str, unit: re.Match[str], facts: Facts, dialect: str
    ) -> None:
        kind = unit.group(1).upper().split()[0].lower()  # package / procedure / function ...
        name = unit.group(2).replace('"', "").upper()
        members = []
        if kind == "package":
            members = sorted(
                {m.group(2).upper() for m in _MEMBER.finditer(blank_strings_and_comments(stmt))}
            )
        self._db_object(facts, name, kind, path, start, members)
        for member in members:
            facts.symbol(
                "db-procedure",
                f"{name}.{member}",
                path,
                start,
                owner=make_id("db_object", kind, name),
            )
        body_start = re.search(r"\b(IS|AS)\b", stmt, re.I)
        body = stmt[body_start.end() :] if body_start else stmt
        offset = body_start.end() if body_start else 0
        for off, dml in embedded_dml(body):
            line = start + stmt.count("\n", 0, offset + off)
            facts.add(
                "sql_statements",
                sql_fact(file=path, line=line, text=dml, source="plsql", dialect=dialect),
            )
