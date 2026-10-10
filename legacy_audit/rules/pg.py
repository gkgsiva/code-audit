"""PostgreSQL grading of SQL statements and stored objects (rules in ``pg-rules.yaml``)."""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

import sqlglot
from sqlglot.errors import ErrorLevel

from legacy_audit.analyzers.sqltools import MAX_TEXT, prepare, sqlglot_dialect, strip_comments
from legacy_audit.model import DbObject, PgGrade, SqlStatement

MANUAL_OBJECTS = ("package", "procedure", "function", "trigger")


@dataclass(frozen=True)
class Construct:
    id: str
    pattern: re.Pattern[str]
    grade: PgGrade
    reason: str
    valid_in_pg: bool = False


def constructs(data: dict[str, Any]) -> list[Construct]:
    seen: dict[str, Construct] = {}
    for c in data.get("constructs", []):
        if c["id"] not in seen:  # team entries come first and win
            seen[c["id"]] = Construct(
                id=c["id"],
                pattern=re.compile(c["regex"], re.I | re.M),
                grade=PgGrade(c.get("grade", "review")),
                reason=c["reason"],
                valid_in_pg=bool(c.get("valid_in_pg", False)),
            )
    return list(seen.values())


def _transpile(text: str, dialect: str | None) -> str:
    out = sqlglot.transpile(
        prepare(text),
        read=sqlglot_dialect(dialect),
        write="postgres",
        unsupported_level=ErrorLevel.RAISE,
    )
    if len(out) != 1 or not out[0].strip():
        raise ValueError("not one statement")
    # sqlglot writes psycopg placeholders; the target is Java, so keep JDBC "?".
    return re.sub(r"%s\b", "?", out[0])[:MAX_TEXT]


_ORDER = {PgGrade.AUTO: 0, PgGrade.REVIEW: 1, PgGrade.MANUAL: 2}


def _worst(a: PgGrade, b: PgGrade) -> PgGrade:
    return a if _ORDER[PgGrade(a)] >= _ORDER[PgGrade(b)] else b


def _grade_text(
    text: str, dialect: str | None, rules: list[Construct]
) -> tuple[PgGrade, list[str], str | None]:
    found = [c for c in rules if c.pattern.search(strip_comments(text))]
    reasons = [c.reason for c in found]
    if any(c.grade == PgGrade.MANUAL for c in found):
        return PgGrade.MANUAL, reasons, None
    try:
        pg_text: str | None = _transpile(text, dialect)
    except Exception as err:
        if found:  # the flagged constructs explain it: a reviewer converts them by hand
            return PgGrade.REVIEW, [*reasons, "not converted automatically"], None
        return PgGrade.MANUAL, [f"does not transpile to PostgreSQL ({type(err).__name__})"], None
    if pg_text and any(not c.valid_in_pg and c.pattern.search(pg_text) for c in found):
        pg_text = None
        reasons.append("not converted automatically")
    return (PgGrade.REVIEW if found else PgGrade.AUTO), reasons, pg_text


def grade(st: SqlStatement, rules: list[Construct]) -> tuple[PgGrade, str | None, str | None]:
    """(grade, reason, suggested PostgreSQL text).

    Dynamic SQL rebuilt from Java (or MyBatis/iBATIS tags) is graded on its full text and on
    each variant, and is at least *review*; it is *manual* only when part of the statement
    itself (a column, table or clause) is known only at run time."""
    if st.source == "spring-data":
        return PgGrade.AUTO, "derived query; the ORM generates the SQL", None
    text = strip_comments(st.text)
    found = [c for c in rules if c.pattern.search(text)]
    reasons = [c.reason for c in found]
    if st.dialect == "hql":
        g = PgGrade.REVIEW if found else PgGrade.AUTO
        return g, "; ".join(reasons) or None, None
    if st.type == "DYNAMIC" or st.structural_holes:
        where = f" ({st.structural_holes} place(s))" if st.structural_holes else ""
        why = f"part of the statement is only known at run time{where}"
        return PgGrade.MANUAL, "; ".join([why, *reasons]), None
    if not st.parsed:
        return PgGrade.MANUAL, "; ".join(["statement could not be parsed", *reasons]), None
    g, reasons, pg_text = _grade_text(st.text, st.dialect, rules)
    if st.dynamic:
        for variant in st.variants:
            gv, rv, _ = _grade_text(variant, st.dialect, rules)
            g = _worst(g, gv)
            reasons += rv
        g = _worst(g, PgGrade.REVIEW)
        parts = f"{st.fragments} fragment(s)"
        if st.optional_fragments:
            parts += f", {st.optional_fragments} optional"
        if st.value_holes:
            parts += f", {st.value_holes} concatenated value(s)"
        why = f"dynamic SQL rebuilt from {parts}"
        if st.variants:
            why += f"; {len(st.variants) + 1} variants, check each"
        reasons.insert(0, why)
    return g, "; ".join(dict.fromkeys(reasons)) or None, pg_text


def grade_all(
    statements: list[SqlStatement], objects: list[DbObject], data: dict[str, Any]
) -> None:
    """Grade in place. Identical statements are graded once."""
    rules = constructs(data)
    memo: dict[tuple[Any, ...], tuple[PgGrade, str | None, str | None]] = {}
    for st in statements:
        key = (st.text, st.dialect, st.source, st.dynamic, st.parsed, st.type,
               st.structural_holes, st.fragments, st.optional_fragments,
               tuple(st.variants))  # fmt: skip
        result = memo.get(key)
        if result is None:
            result = memo[key] = grade(st, rules)
        st.pg_grade, st.reason, st.pg_text = result
    for obj in objects:
        if obj.object_type in MANUAL_OBJECTS:
            obj.pg_grade = PgGrade.MANUAL
            obj.reason = f"stored {obj.object_type}: move the logic to Java or rewrite in PL/pgSQL"
