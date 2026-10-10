"""Effort model: person-days [low, likely, high] from the weights in ``effort.weights``.

Every item counted elsewhere is priced by its level: services, screens (not partials) and
batch jobs by complexity band; distinct SQL statements by PostgreSQL grade; stored code by
grade; integrations each; libraries by recommended action. The result is labelled
"estimate — weights from config": it is arithmetic on counts, not a forecast.
"""

from __future__ import annotations

from collections import Counter
from typing import Any

from legacy_audit.config import AuditConfig
from legacy_audit.model import (
    BatchJob,
    DbObject,
    EffortEstimate,
    EffortLine,
    Integration,
    Library,
    Screen,
    Service,
    SqlStatement,
)
from legacy_audit.rules.pg import MANUAL_OBJECTS

DATABASE = "(database)"
SHARED = "(shared)"


def _level(band: str | None) -> str:
    return (band or "Simple").lower().replace(" ", "_")


def estimate(
    cfg: AuditConfig, entities: dict[str, list[Any]], component_of: dict[str, str | None]
) -> EffortEstimate:
    """``component_of`` maps a file path to its component (libraries list files)."""
    weights = cfg.effort.weights
    counts: Counter[tuple[str, str, str]] = Counter()  # (component, item, level)

    for s in entities.get("services", []):
        if isinstance(s, Service) and s.kind != "batch-job":
            counts[(s.component or SHARED, "service", _level(s.complexity))] += 1
    for sc in entities.get("screens", []):
        if isinstance(sc, Screen) and not sc.partial:
            counts[(sc.component or SHARED, "screen", _level(sc.complexity))] += 1
    for j in entities.get("batch_jobs", []):
        if isinstance(j, BatchJob) and j.kind != "box":
            counts[(j.component or SHARED, "batch_job", _level(j.complexity))] += 1
    seen_sql: set[tuple[str, str]] = set()
    for st in entities.get("sql_statements", []):
        if not isinstance(st, SqlStatement) or not st.pg_grade:
            continue
        comp = st.component or SHARED
        if (comp, st.text) in seen_sql:  # the same statement converts once
            continue
        seen_sql.add((comp, st.text))
        counts[(comp, "sql", str(st.pg_grade))] += 1
    for o in entities.get("db_objects", []):
        if isinstance(o, DbObject) and o.object_type in MANUAL_OBJECTS and o.pg_grade:
            counts[(DATABASE, "stored_procedure", str(o.pg_grade))] += 1
    for i in entities.get("integrations", []):
        if isinstance(i, Integration):
            comp = i.components[0] if len(i.components) == 1 else SHARED
            counts[(comp, "integration", "each")] += 1
    for lib in entities.get("libraries", []):
        if isinstance(lib, Library) and lib.recommendation:
            comps = sorted({c for f in lib.where_used if (c := component_of.get(f))})
            comp = comps[0] if len(comps) == 1 else SHARED
            counts[(comp, "library", lib.recommendation.action)] += 1

    out = EffortEstimate(weights=weights)
    for (comp, item, level), n in sorted(counts.items()):
        triple = weights.get(item, {}).get(level)
        if triple is None:
            continue  # a level without a weight is not priced
        low, likely, high = (round(n * w, 2) for w in triple)
        out.lines.append(
            EffortLine(
                component=comp, item=item, level=level, count=n, low=low, likely=likely, high=high
            )
        )
        per = out.by_component.setdefault(comp, {"low": 0.0, "likely": 0.0, "high": 0.0})
        per["low"] += low
        per["likely"] += likely
        per["high"] += high
    for per in out.by_component.values():
        for k in per:
            per[k] = round(per[k], 2)
    out.low = round(sum(line.low for line in out.lines), 2)
    out.likely = round(sum(line.likely for line in out.lines), 2)
    out.high = round(sum(line.high for line in out.lines), 2)
    return out
