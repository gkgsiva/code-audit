"""Scan a sample (or any folder with an audit.yaml) and print its lines of code by language and
its integrations.

    python scripts/show_loc_integrations.py samples/legacy-layout-app

Prints the lines of code per language (no "unknown": a text extension no rule knows shows as
"other (.ext)", with its files listed so a rule can be added), the backup copies and data files
listed with no lines, and the integrations, one row per interface with its direction and
usages. Writes to the folder's own ``audit-output/`` like ``legacy-audit scan``.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from legacy_audit.config import load_config
from legacy_audit.scan import run_scan

SHOW_FILES = 10


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    cfg_path = Path(argv[0]).resolve() / "audit.yaml"
    if not cfg_path.exists():
        print(f"{cfg_path.parent} has no audit.yaml.")
        return 2
    result = run_scan(load_config(cfg_path))
    summary = json.loads((result.run_dir / "data" / "audit-summary.json").read_text("utf-8"))
    langs = summary["by_language"]
    print(f"\nLines of code by language ({len(langs)}):\n")
    print(f"  {'Language':<24} {'Files':>6} {'Code':>9} {'Comment':>9} {'Blank':>8}")
    for name, v in sorted(langs.items(), key=lambda kv: (-kv[1]["loc_code"], kv[0])):
        print(
            f"  {name:<24} {v['files']:>6} {v['loc_code']:>9} {v['loc_comment']:>9} "
            f"{v['loc_blank']:>8}"
        )
    t = summary["totals"]
    print(f"  {'Total':<24} {t['files']:>6} {t['loc_code']:>9} {t['loc_comment']:>9} "
          f"{t['loc_blank']:>8}")  # fmt: skip
    files = [f for f in result.facts["inventory"] if f.origin == "source"]  # type: ignore[attr-defined]
    for label, test in (
        ("Not recognised, named by extension", lambda lang: lang.startswith("other (")),
        ("Backup copies (listed, no lines counted)", lambda lang: lang == "backup"),
        ("Data files (listed, no lines counted)", lambda lang: lang == "data"),
    ):
        paths = sorted(f.path for f in files if test(f.language))  # type: ignore[attr-defined]
        if paths:
            print(f"\n{label} ({len(paths)}):")
            for p in paths[:SHOW_FILES]:
                print(f"  {p}")
            if len(paths) > SHOW_FILES:
                print(f"  ... and {len(paths) - SHOW_FILES} more (see inventory.csv)")
    integ = result.facts["integrations"]
    print(f"\nIntegrations, one row per interface ({len(integ)}):\n")
    print(f"  {'Type':<12} {'Direction':<10} {'Usages':>6}  Target")
    for i in sorted(integ, key=lambda i: (i.type, i.target)):  # type: ignore[attr-defined]
        print(f"  {i.type:<12} {i.direction:<10} {i.usages:>6}  {i.target}")  # type: ignore[attr-defined]
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
