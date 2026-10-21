"""Build the summary report's Dependency vulnerabilities section and print it.

    python scripts/show_dependency_vulns.py samples/spring-mvc-batch-app [--both] [--open]

Scans the folder (or, with ``--both``, a copy of it in ``build/vulns/<sample>/`` with the demo
Dependency-Check report ``tests/fixtures/security/billing-dependency-check-report.json`` added
next to its OSV-Scanner report; the sample itself is not touched), builds the summary report
and prints section 5: which scanner was used, the headline figures and the vulnerabilities,
worst first, with the scanner(s) that found each one. ``--open`` opens the page at section 5.
"""

from __future__ import annotations

import json
import shutil
import sys
import webbrowser
from pathlib import Path

from legacy_audit.config import load_config
from legacy_audit.report.summary import build_summary_report
from legacy_audit.scan import run_scan

ROOT = Path(__file__).resolve().parents[1]
DEMO_DC = ROOT / "tests" / "fixtures" / "security" / "billing-dependency-check-report.json"


def main(argv: list[str]) -> int:
    args = [a for a in argv if not a.startswith("--")]
    if not args:
        print(__doc__)
        return 2
    folder = Path(args[0]).resolve()
    if not (folder / "audit.yaml").exists():
        print(f"{folder} has no audit.yaml.")
        return 2
    if "--both" in argv:
        copy = ROOT / "build" / "vulns" / folder.name
        if copy.exists():
            shutil.rmtree(copy)
        shutil.copytree(folder, copy, ignore=shutil.ignore_patterns("audit-output"))
        (copy / "audit-inputs" / "security").mkdir(parents=True, exist_ok=True)
        shutil.copy(DEMO_DC, copy / "audit-inputs" / "security")
        print(f"Copied {folder.name} to {copy} and added the demo Dependency-Check report")
        folder = copy
    res = build_summary_report(run_scan(load_config(folder / "audit.yaml")).run_dir)
    data = json.loads((res.page.parent / "summary.json").read_text(encoding="utf-8"))
    sec = next(s for s in data["sections"] if s["key"] == "security")
    tables = {t["key"]: t for t in sec["tables"]}
    print(f"\n5. {sec['title']}\n")
    if sec["alert"]:
        print(f"  !! {sec['alert']}\n")
    for line in sec["intro"]:
        if line != sec["alert"]:
            print(f"  {line}")
    print("\n  " + " | ".join(f"{label}: {value}" for label, value in sec["kpis"]))
    print("\n  Scanners:")
    for r in tables["scanners"]["rows"]:
        print(f"    {r[0]:<17} {r[1]}" + (f", database {r[2]}" if r[2] else ""))
    rows = tables["vulnerabilities"]["rows"]
    print(f"\n  Vulnerabilities ({len(rows)}), worst first:")
    print(f"    {'Id':<20} {'Severity':<9} {'CVSS':>5}  {'Library':<40} {'Found by'}")
    for r in rows:
        print(f"    {r[0]:<20} {r[1]:<9} {r[2]!s:>5}  {(r[3] + ' ' + r[4])[:40]:<40} {r[6]}")
    if not rows:
        print(f"    {tables['vulnerabilities']['empty']}")
    print(f"\nSummary report: {res.page}\n{res.message}")
    if "--open" in argv:
        webbrowser.open(res.page.as_uri() + "#security")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
