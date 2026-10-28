"""Compare two runs: counts and added / removed / changed records per entity."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from legacy_audit.model import ENTITIES
from legacy_audit.store.writer import read_facts

_LABEL_KEYS = ("path", "name", "kind", "target", "rule_id", "artifact", "file", "id")


def label(record: dict[str, Any]) -> str:
    for k in _LABEL_KEYS:
        if record.get(k):
            return str(record[k])
    return str(record.get("id"))


def diff_runs(run_a: Path, run_b: Path) -> dict[str, Any]:
    fa, fb = read_facts(run_a / "data"), read_facts(run_b / "data")
    entities: dict[str, Any] = {}
    for entity in ENTITIES:
        a = {r["id"]: r for r in fa[entity]}
        b = {r["id"]: r for r in fb[entity]}
        added = sorted(set(b) - set(a))
        removed = sorted(set(a) - set(b))
        changed = []
        for i in sorted(set(a) & set(b)):
            if a[i] != b[i]:
                fields = sorted(k for k in set(a[i]) | set(b[i]) if a[i].get(k) != b[i].get(k))
                changed.append(
                    {
                        "id": i,
                        "label": label(b[i]),
                        "fields": {f: [a[i].get(f), b[i].get(f)] for f in fields},
                    }
                )
        entities[entity] = {
            "count_a": len(a),
            "count_b": len(b),
            "added": [{"id": i, "label": label(b[i])} for i in added],
            "removed": [{"id": i, "label": label(a[i])} for i in removed],
            "changed": changed,
        }
    info_a = json.loads((run_a / "run.json").read_text(encoding="utf-8"))
    info_b = json.loads((run_b / "run.json").read_text(encoding="utf-8"))
    return {
        "run_a": info_a["run_id"],
        "run_b": info_b["run_id"],
        "sources_a": info_a.get("sources", []),
        "sources_b": info_b.get("sources", []),
        "entities": entities,
    }
