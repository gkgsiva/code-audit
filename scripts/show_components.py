"""Scan a sample and print how its files were grouped into components.

    python scripts/show_components.py samples/legacy-layout-app

Prints the Maven / Gradle module tree (parents and aggregators are groups, not components),
one line per component (type, the other kinds of work it holds, what it is made of, its
folder), the folders and files left out as build output, and the batch count split into
scheduled jobs and batches with no schedule found: one per entry point (top-level script,
program or Java main) of a batch component. Writes to the sample's
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
    grouped = [c for c in comps if c.build_group]  # type: ignore[attr-defined]
    if grouped:
        print("\nMaven / Gradle modules (a parent or aggregator pom is a group, not a component):")
        shown: set[tuple[str, ...]] = set()
        for c in sorted(grouped, key=lambda c: (c.build_group, c.path)):  # type: ignore[attr-defined]
            chain = tuple(c.build_group)  # type: ignore[attr-defined]
            for n in range(1, len(chain) + 1):
                if chain[:n] not in shown:
                    shown.add(chain[:n])
                    print(f"  {'  ' * (n - 1)}{chain[n - 1]} (group)")
            pack = f" [{c.packaging}]" if c.packaging else ""  # type: ignore[attr-defined]
            print(f"  {'  ' * len(chain)}{c.name}{pack} -> {c.type}")  # type: ignore[attr-defined]
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
    for comp, items in (summary.get("unscheduled_batch_entries") or {}).items():
        if not items:
            print(f"  {comp}: 1 batch (no entry script or program found)")
            continue
        print(f"  {comp}: {len(items)} batch(es)")
        for i in items:
            print(f"    - {i['name']} ({i['kind']}, {i['file']})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
