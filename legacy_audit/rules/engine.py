"""Turns rule packs into findings: fact rules over collections, text rules from cached hits.

Every run re-evaluates the rules (they are cheap), so switching a rule off or changing its
severity in ``audit.yaml`` takes effect without re-analysing files. The same switches apply to
findings that analyzers and the linker raise themselves (``config-credential``,
``unresolved-reference``, ``analyzer-error``).
"""

from __future__ import annotations

import re
import string
from collections.abc import Callable, Iterable, Mapping
from typing import Any

from legacy_audit.config import AuditConfig
from legacy_audit.model import Evidence, Finding, Severity, make_id
from legacy_audit.rules.packs import SEVERITIES, Rule

MAX_EVIDENCE = 5
_MISSING = object()


def _get(item: Mapping[str, Any], path: str) -> Any:
    value: Any = item
    for part in path.split("."):
        if isinstance(value, Mapping) and part in value:
            value = value[part]
        else:
            return _MISSING
    return value


def _number(v: Any) -> float | None:
    if isinstance(v, bool):
        return None
    if isinstance(v, int | float):
        return float(v)
    return None


def _check(value: Any, cond: Any) -> bool:
    if not isinstance(cond, Mapping):
        return value is not _MISSING and value == cond
    for op, arg in cond.items():
        present = value is not _MISSING and value is not None
        if op == "exists":
            ok = present == bool(arg)
        elif op == "empty":
            ok = (not present or value in ("", [], {})) == bool(arg)
        elif op == "eq":
            ok = present and value == arg
        elif op == "ne":
            ok = not present or value != arg
        elif op == "in":
            ok = present and value in arg
        elif op == "not_in":
            ok = not present or value not in arg
        elif op == "contains":
            ok = present and isinstance(value, list | str) and arg in value
        elif op == "regex":
            ok = present and re.search(str(arg), str(value)) is not None
        elif op in ("gt", "gte", "lt", "lte"):
            n, a = _number(value), _number(arg)
            ok = (
                n is not None
                and a is not None
                and {"gt": n > a, "gte": n >= a, "lt": n < a, "lte": n <= a}[op]
            )
        else:
            raise ValueError(f"unknown condition {op!r}")
        if not ok:
            return False
    return True


def matches(item: Mapping[str, Any], where: Mapping[str, Any]) -> bool:
    return all(_check(_get(item, field), cond) for field, cond in where.items())


class _Fields(string.Formatter):
    """``{field}`` and ``{a.b}`` over a dict; unknown fields render as ``?``."""

    def get_field(self, field_name: str, args: Any, kwargs: Any) -> Any:
        value = _get(kwargs, field_name.replace("[", ".").replace("]", ""))
        return ("?" if value is _MISSING or value is None else value), field_name


_FORMAT = _Fields()


def render(template: str, item: Mapping[str, Any]) -> str:
    def text(v: Any) -> Any:
        return ", ".join(map(str, v)) if isinstance(v, list) else v

    return _FORMAT.vformat(template, (), {k: text(v) for k, v in item.items()})


def _location(item: Mapping[str, Any]) -> tuple[str | None, int | None, list[Evidence]]:
    ev = [Evidence.model_validate(e) for e in item.get("evidence") or []][:MAX_EVIDENCE]
    file = item.get("file") if isinstance(item.get("file"), str) else None
    line = item.get("line") if isinstance(item.get("line"), int) else None
    if file is None and ev:
        file, line = ev[0].file, ev[0].line
    if not ev and file:
        ev = [Evidence(file=file, line=line)]
    return file, line, ev


def finding(rule: Rule, item: Mapping[str, Any], key: str) -> Finding:
    file, line, ev = _location(item)
    severity = rule.severity
    if rule.severity_field:
        value = str(item.get(rule.severity_field) or "").lower()
        severity = value if value in SEVERITIES else severity
    return Finding(
        id=make_id("finding", rule.id, key),
        rule_id=rule.id,
        severity=Severity(severity),
        category=rule.category,
        file=file,
        line=line,
        message=render(rule.message, item),
        remediation=rule.remediation,
        evidence=ev,
    )


def evaluate(
    rules: Iterable[Rule],
    collection: Callable[[str], list[dict[str, Any]]],
    hits: Iterable[tuple[str, str, int, str]],
) -> list[Finding]:
    """Findings for fact rules (``collection(name)`` gives the items) and for text hits
    ``(file, rule id, line, text)``."""
    rules = list(rules)
    out: list[Finding] = []
    for rule in rules:
        if rule.match is None:
            continue
        for item in collection(rule.match.entity):
            if matches(item, rule.match.where):
                key = str(item.get("id") or (item.get("file"), item.get("line"), item.get("name")))
                out.append(finding(rule, item, key))
    by_id = {r.id: r for r in rules if r.pattern is not None}
    for file, rule_id, line, text in hits:
        hit_rule = by_id.get(rule_id)
        if hit_rule is not None:
            item = {"file": file, "line": line, "match": text}
            out.append(finding(hit_rule, item, f"{file}:{line}:{text}"))
    return out


def apply_config(findings: Iterable[Finding], cfg: AuditConfig) -> list[Finding]:
    """Drop disabled rules, apply severity changes, de-duplicate by id, sort."""
    disabled = set(cfg.rules.disabled)
    by_id: dict[str, Finding] = {}
    for f in findings:
        if f.rule_id in disabled:
            continue
        sev = cfg.rules.severity.get(f.rule_id)
        if sev:
            f = f.model_copy(update={"severity": sev})
        by_id.setdefault(f.id, f)
    order = {s: i for i, s in enumerate(("critical", "high", "medium", "low", "info"))}
    return sorted(
        by_id.values(),
        key=lambda f: (order.get(str(f.severity), 9), f.rule_id, f.file or "", f.line or 0),
    )
