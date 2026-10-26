"""``audit-summary.json``: totals and top-N lists, kept under 30 KB for the agent's context."""

from __future__ import annotations

import json
from collections import defaultdict
from typing import Any

from legacy_audit.inventory.inputs import coverage_line
from legacy_audit.model import (
    ENTITIES,
    Component,
    DbObject,
    FileRecord,
    Finding,
    InputCoverage,
    Integration,
    Library,
    Metric,
    RunInfo,
    Service,
    TechItem,
    WebService,
)
from legacy_audit.store.counts import counts, unscheduled_batch_entries
from legacy_audit.store.writer import FactSet

MAX_BYTES = 30_000


def _by(records: list[FileRecord], key: str) -> dict[str, dict[str, int]]:
    out: dict[str, dict[str, int]] = defaultdict(
        lambda: {"files": 0, "loc_code": 0, "loc_comment": 0, "loc_blank": 0}
    )
    for r in records:
        row = out[str(getattr(r, key))]
        row["files"] += 1
        row["loc_code"] += r.loc_code
        row["loc_comment"] += r.loc_comment
        row["loc_blank"] += r.loc_blank
    return {k: out[k] for k in sorted(out)}


SEVERITY_RANK = {"critical": 0, "high": 1, "medium": 2, "low": 3, "info": 4, "unknown": 5}


def build_summary(
    run: RunInfo, facts: FactSet, top_n: int = 15, effort: dict[str, Any] | None = None
) -> dict[str, Any]:
    files = [f for f in facts.get("inventory", []) if isinstance(f, FileRecord)]
    source = [f for f in files if f.origin == "source"]
    comps = [c for c in facts.get("components", []) if isinstance(c, Component)]
    coverage = [c for c in facts.get("input_coverage", []) if isinstance(c, InputCoverage)]
    services = [s for s in facts.get("services", []) if isinstance(s, Service)]
    integrations = [i for i in facts.get("integrations", []) if isinstance(i, Integration)]
    tables = [
        o
        for o in facts.get("db_objects", [])
        if isinstance(o, DbObject) and o.object_type == "table"
    ]
    tech = [t for t in facts.get("tech_stack", []) if isinstance(t, TechItem)]
    findings = [f for f in facts.get("findings", []) if isinstance(f, Finding)]
    libs = [lib for lib in facts.get("libraries", []) if isinstance(lib, Library)]
    metrics = [m for m in facts.get("metrics", []) if isinstance(m, Metric)]
    headline = counts(facts)
    entries = unscheduled_batch_entries(facts)

    def build(n: int) -> dict[str, Any]:
        return {
            "run": {
                "run_id": run.run_id,
                "application": run.application,
                "tool_version": run.tool_version,
                "started_at": run.started_at,
                "sources": [s.model_dump(mode="json") for s in run.sources],
            },
            "target": run.target,
            "totals": {
                "files": len(source),
                "input_files": len(files) - len(source),
                "loc_code": sum(f.loc_code for f in source),
                "loc_comment": sum(f.loc_comment for f in source),
                "loc_blank": sum(f.loc_blank for f in source),
                "components": len(comps),
                **{e: len(facts.get(e, [])) for e in ENTITIES if e not in ("inventory",)},
            },
            "counts": headline,
            "unscheduled_batches": sorted(entries),
            "unscheduled_batch_entries": entries,
            "tech_stack": [
                {"name": t.name, "version": t.version, "category": t.category, "eol": t.eol}
                for t in tech[: n * 4]
            ],
            "by_language": _by(source, "language"),
            "by_category": _by(source, "category"),
            "components": [
                {"name": c.name, "type": c.type, "files": c.files, "loc_code": c.loc_code}
                for c in sorted(comps, key=lambda c: (-c.loc_code, c.name))[:n]
            ],
            "largest_files": [
                {"path": f.path, "language": f.language, "loc_code": f.loc_code}
                for f in sorted(source, key=lambda f: (-f.loc_code, f.path))[:n]
            ],
            "top_services": [
                {"name": s.name, "kind": s.kind, "component": s.component, "tables": len(s.tables)}
                for s in sorted(services, key=lambda s: (-len(s.tables), s.name))[:n]
            ],
            "top_tables": [
                {"name": t.name, "references": len(t.referenced_by), "crud": sorted(t.crud)}
                for t in sorted(tables, key=lambda t: (-len(t.referenced_by), t.name))[:n]
            ],
            "integrations": [
                {
                    "type": i.type,
                    "purpose": i.purpose,
                    "target": i.target,
                    "direction": i.direction,
                    "usages": i.usages,
                    "endpoints": i.endpoints,
                }
                for i in sorted(integrations, key=lambda i: (-i.usages, i.type, i.target))[:n]
            ],
            "web_services": [
                {
                    "name": w.name,
                    "style": w.style,
                    "side": w.side,
                    "url": w.url,
                    "operations": len(w.operations),
                    "component": w.component,
                }
                for w in sorted(
                    (w for w in facts.get("web_services", []) if isinstance(w, WebService)),
                    key=lambda w: (w.side != "offered", -len(w.operations), w.name),
                )[:n]
            ],
            "top_findings": [
                {
                    "rule": f.rule_id,
                    "severity": f.severity,
                    "file": f.file,
                    "line": f.line,
                    "message": f.message[:200],
                }
                for f in findings[:n]
            ],
            "risky_libraries": [
                {
                    "name": f"{lib.group}:{lib.artifact}" if lib.group else lib.artifact,
                    "version": lib.version,
                    "max_severity": lib.max_severity,
                    "cves": len(lib.cves),
                    "eol": lib.eol,
                    "recommendation": lib.recommendation.action if lib.recommendation else None,
                    "target": lib.recommendation.target if lib.recommendation else None,
                }
                for lib in sorted(
                    (lib for lib in libs if lib.cves or lib.eol),
                    key=lambda x: (
                        SEVERITY_RANK.get(x.max_severity or "unknown", 5),
                        -len(x.cves),
                        x.artifact,
                    ),
                )[:n]
            ],
            "component_metrics": [
                {
                    "component": m.component,
                    "complexity": m.complexity,
                    "complexity_avg": m.complexity_avg,
                    "duplication_pct": m.duplication_pct,
                    "test_ratio": m.test_ratio,
                    "dead_code_candidates": m.dead_code_candidates,
                }
                for m in metrics[: n * 2]
            ],
            "effort": {k: effort[k] for k in ("label", "unit", "low", "likely", "high")}
            if effort
            else None,
            "vulnerability_sources": [s.model_dump(mode="json") for s in run.vulnerability_sources],
            "coverage": {c.kind: coverage_line(c) for c in coverage},
            "analyzer_coverage": {
                name: {"files": a.files, "coverage_pct": a.coverage_pct}
                for name, a in run.analyzers.items()
            },
            "unresolved_references": run.unresolved_references,
            "counting_rules": run.counting_rules,
        }

    n = top_n
    summary = build(n)
    while len(json.dumps(summary)) > MAX_BYTES and n > 1:
        n //= 2
        summary = build(n)
    if len(json.dumps(summary)) > MAX_BYTES:
        summary["by_language"] = dict(list(summary["by_language"].items())[:20])
    return summary
