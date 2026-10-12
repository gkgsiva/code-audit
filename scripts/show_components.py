"""Scan a sample and print how its files were grouped into components.

    python scripts/show_components.py samples/legacy-layout-app

Prints one line per component (type, the other kinds of work it holds, what it is made of,
its folder), the folders and files left out as build output, and the batch count split into
scheduled jobs and batch components no scheduler definition covers. Writes to the sample's
own ``audit-output/`` like ``legacy-audit scan``.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from legacy_audit.config import load_config
from legacy_audit.scan import run_scan


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    cfg_path = Path(argv[0]).resolve() / "audit.yaml"
    if not cfg_path.exists():
        print(f"{cfg_path.parent} is not a sample (no audit.yaml).")
        return 2
    result = run_scan(load_config(cfg_path))
    summary = json.loads((result.run_dir / "data" / "audit-summary.json").read_text("utf-8"))
    comps = result.facts["components"]
    print(f"\n{len(comps)} component(s):\n")
    print(f"  {'Component':<22} {'Type':<17} {'Also holds':<22} {'Made of':<44} Folder")
    for c in comps:
        also = ", ".join(r for r in c.roles if r != c.type) or "-"  # type: ignore[attr-defined]
        made = ", ".join(f"{k} {v}" for k, v in c.composition.items())  # type: ignore[attr-defined]
        folder = c.path or "(root)"  # type: ignore[attr-defined]
        print(f"  {c.name:<22} {c.type:<17} {also:<22} {made:<44} {folder}")  # type: ignore[attr-defined]
    left = [f for f in result.facts.get("findings", []) if f.rule_id == "build-output-skipped"]  # type: ignore[attr-defined]
    print(f"\nLeft out as build output or generated ({len(left)}):")
    for f in left:
        print(f"  {f.file}: {f.message.split(';')[0].removeprefix('Left out of the scan: ')}")  # type: ignore[attr-defined]
    jobs = summary["counts"]["batch_jobs"]
    print(
        f"\nBatch jobs: {jobs['total']} = {jobs['scheduled']} scheduled + "
        f"{jobs['unscheduled']} with no schedule found "
        f"({', '.join(summary['unscheduled_batches']) or 'none'})"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
