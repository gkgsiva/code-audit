"""Scan a sample (or any folder with an audit.yaml) and print its consolidated batch list.

    python scripts/show_batches.py samples/legacy-layout-app

One line per batch, scheduled or not: what it does and when it runs, each with where it came
from (the Copilot analysis, the scheduler, a comment or setting in the code, or the scan). The
same list is on the technical report's Batch jobs page and in its ``data/batches.csv``. Writes
to the folder's own ``audit-output/`` like ``legacy-audit scan``.
"""

from __future__ import annotations

import sys
from pathlib import Path

from legacy_audit.config import load_config
from legacy_audit.report import flows
from legacy_audit.report.batches import batch_list
from legacy_audit.report.data import RunData
from legacy_audit.report.deck_builder import load_enrichment
from legacy_audit.scan import run_scan


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    cfg_path = Path(argv[0]).resolve() / "audit.yaml"
    if not cfg_path.exists():
        print(f"{cfg_path.parent} has no audit.yaml.")
        return 2
    run_dir = run_scan(load_config(cfg_path)).run_dir
    rd = RunData.load(run_dir)
    bflows = flows.batch_flows(rd)
    said = {}
    for f in bflows:
        c = flows.code_comments(rd, f)
        if c:
            said[f.id] = c[0]["lines"][0]
    fn = load_enrichment(run_dir).get("functional") or {}
    stories = {b["job_id"]: b for b in fn.get("batches", []) or []}
    rows = batch_list(rd, bflows, said, stories)
    print(f"\nAll batches ({len(rows)}):\n")
    for r in rows:
        where = f" [{r['when_file']}:{r['when_line']}]" if r.get("when_file") else ""
        print(f"  {r['name']}  ({r['kind']}, {r['component'] or 'no component'})")
        print(f"    What it does : {r['what']}  ({r['what_from']})")
        print(f"    When it runs : {r['when']}  ({r['when_from']}){where}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
