"""Scan a sample (or any folder with an audit.yaml) and print its Angular, AngularJS and
Node.js parts and its application and web servers.

    python scripts/show_web_stack.py samples/angular-node-app [--open]

Prints the components with their type (``frontend`` for Angular and AngularJS, ``api`` for
Express and NestJS), the screens (routed pages; app shells and embedded components are
partials), the API routes with their full path, which front-end file calls which route, the
scheduled jobs (node-cron, NestJS @Cron), and the servers and runtimes with versions, end of
life and the files they were found in. Then builds the summary report; ``--open`` opens it.
Writes to the folder's own ``audit-output/`` like ``legacy-audit scan``.
"""

from __future__ import annotations

import sys
import webbrowser
from pathlib import Path

from legacy_audit.config import load_config
from legacy_audit.report.data import RunData
from legacy_audit.report.servers import server_rows
from legacy_audit.report.summary import build_summary_report
from legacy_audit.scan import run_scan


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    cfg_path = Path(argv[0]).resolve() / "audit.yaml"
    if not cfg_path.exists():
        print(f"{cfg_path.parent} has no audit.yaml.")
        return 2
    result = run_scan(load_config(cfg_path))
    facts = result.facts
    names: dict[str, str] = {}
    for kind in ("services", "screens", "batch_jobs"):
        for f in facts.get(kind, []):
            names[f.id] = f.name  # type: ignore[attr-defined]
    rd = RunData.load(result.run_dir)
    for rec in rd.items("inventory"):
        names[rec["id"]] = rec["path"]

    print("\nComponents:\n")
    for c in facts["components"]:
        print(f"  {c.name:<20} {c.type!s:<10} also: {', '.join(c.roles)}")  # type: ignore[attr-defined]

    print("\nScreens (routed pages; partials are parts of a page):\n")
    for s in sorted(facts["screens"], key=lambda s: (s.partial, s.name)):  # type: ignore[attr-defined]
        print(f"  {s.technology:<10} {s.name}{'  (partial)' if s.partial else ''}")  # type: ignore[attr-defined]

    print("\nAPI routes and jobs:\n")
    for s in sorted(facts["services"], key=lambda s: (s.kind, s.name)):  # type: ignore[attr-defined]
        print(f"  {s.kind:<11} {s.name}")  # type: ignore[attr-defined]

    print("\nFront end calls to the API:\n")
    for e in facts["dependencies"]:
        if e.kind == "calls":  # type: ignore[attr-defined]
            src, dst = names.get(e.source, e.source), names.get(e.target, e.target)  # type: ignore[attr-defined]
            print(f"  {src}  ->  {dst}{'' if e.resolved else '  (not found)'}")  # type: ignore[attr-defined]

    print("\nScheduled jobs:\n")
    for j in facts["batch_jobs"]:
        print(f"  {j.scheduler:<10} {j.name:<20} {j.schedule or ''}")  # type: ignore[attr-defined]

    print("\nApplication servers, web servers and runtimes:\n")
    for r in server_rows(rd):
        version, eol = r["version"] or "-", r["eol"] or "-"
        print(f"  {r['kind']:<19} {r['name']:<34} {version:<18} end of life: {eol}")
        for f in r["files"]:
            print(f"  {'':<19} found in {f}")

    res = build_summary_report(result.run_dir)
    print(f"\nSummary report: {res.page}\n{res.message}")
    if "--open" in argv:
        webbrowser.open(res.page.as_uri())
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
