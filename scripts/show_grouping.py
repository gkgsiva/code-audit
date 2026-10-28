"""Scan a sample and print how its components and integrations were grouped (decisions 176-177).

    python scripts/show_grouping.py samples/maven-multimodule-app

Components: one per deployable when a pom.xml or Gradle build is there (a library module goes
into the war, ear or batch jar that uses it), otherwise part folders of one technology are one
component (COBOL + BMS + copybooks, JCL + PROCLIB, the batches of one batch folder, shared
configuration and scripts). Prints each component with the folders it is made of, and how many
components the old one-per-module-and-folder rule would give (``inventory.component_grouping:
module``).

Integrations: one row per external service (all the endpoints called on one host, http or
https, REST or SOAP; the queues and channels of one queue manager; the files of one server or
folder). Prints each row with what it groups and the callers. Writes to the sample's own
``audit-output/`` like ``legacy-audit scan``.
"""

from __future__ import annotations

import sys
from pathlib import Path

from legacy_audit.config import load_config
from legacy_audit.inventory.components import detect_components, disk_reader
from legacy_audit.scan import resolve_sources, run_scan


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    cfg_path = Path(argv[0]).resolve() / "audit.yaml"
    if not cfg_path.exists():
        print(f"{cfg_path.parent} is not a sample (no audit.yaml).")
        return 2
    loaded = load_config(cfg_path)
    result = run_scan(loaded)
    comps = result.facts["components"]
    records = [r for r in result.facts["inventory"] if r.origin == "source"]  # type: ignore[attr-defined]
    roots = resolve_sources(loaded)
    before, _ = detect_components(
        records,  # type: ignore[arg-type]
        [r.name for r in roots],
        loaded.config.components,
        disk_reader({r.name: r.path for r in roots}),
        "module",
    )
    print(f"\nComponents: {len(comps)} (one per module and folder would give {len(before)})\n")
    print(f"  {'Component':<28} {'Type':<17} {'Files':>5}  Made of")
    for c in comps:
        parts = ", ".join(p or "(root)" for p in c.parts) or (c.path or "(root)")  # type: ignore[attr-defined]
        print(f"  {c.name:<28} {c.type:<17} {c.files:>5}  {parts}")  # type: ignore[attr-defined]

    integ = result.facts["integrations"]
    print(f"\nIntegrations: {len(integ)}, one row per external service\n")
    for it in sorted(integ, key=lambda i: (i.type, i.target)):  # type: ignore[attr-defined]
        print(f"  {it.type:<9} {it.target}  [{it.direction}, {it.usages} usage(s)]")  # type: ignore[attr-defined]
        for e in it.endpoints:  # type: ignore[attr-defined]
            print(f"      - {e}")
        if it.components:  # type: ignore[attr-defined]
            print(f"      called from: {', '.join(it.components)}")  # type: ignore[attr-defined]
    called = [w for w in result.facts.get("web_services", []) if w.side == "called"]  # type: ignore[attr-defined]
    if called:
        print(f"\nWeb services called: {len(called)} (one per host for REST)")
        for w in called:
            print(f"  {w.style:<5} {w.name}  {w.url or ''}  ({len(w.operations)} operation(s))")  # type: ignore[attr-defined]
    print(f"\nRun written to {result.run_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
