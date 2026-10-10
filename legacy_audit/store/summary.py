"""``audit-summary.json``: totals and top-N lists, kept under 30 KB for the agent's context."""

from __future__ import annotations

import json
from collections import defaultdict
from typing import Any

from legacy_audit.inventory.inputs import coverage_line
from legacy_audit.model import ENTITIES, Component, FileRecord, InputCoverage, RunInfo
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


def build_summary(run: RunInfo, facts: FactSet, top_n: int = 15) -> dict[str, Any]:
    files = [f for f in facts.get("inventory", []) if isinstance(f, FileRecord)]
    source = [f for f in files if f.origin == "source"]
    comps = [c for c in facts.get("components", []) if isinstance(c, Component)]
    coverage = [c for c in facts.get("input_coverage", []) if isinstance(c, InputCoverage)]

    def build(n: int) -> dict[str, Any]:
        return {
            "run": {
                "run_id": run.run_id,
                "application": run.application,
                "tool_version": run.tool_version,
                "started_at": run.started_at,
                "sources": [s.model_dump(mode="json") for s in run.sources],
            },
            "totals": {
                "files": len(source),
                "input_files": len(files) - len(source),
                "loc_code": sum(f.loc_code for f in source),
                "loc_comment": sum(f.loc_comment for f in source),
                "loc_blank": sum(f.loc_blank for f in source),
                "components": len(comps),
                **{e: len(facts.get(e, [])) for e in ENTITIES if e not in ("inventory",)},
            },
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
            "coverage": {c.kind: coverage_line(c) for c in coverage},
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
