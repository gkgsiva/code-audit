"""Scan a sample (or any folder with an audit.yaml) once per migration target and print what
changes: the target, the AI stages needed, PostgreSQL grades, effort, what each server becomes
and the advice for each library at risk.

    python scripts/show_targets.py FOLDER... [--open]

    for example: samples/struts-oracle-app samples/angular-node-app

The three targets (``audit.yaml > target.profile``) are ``spring-boot-angular`` (the default),
``wildfly-gke`` (the code moves as it is onto WildFly on GKE, only the database moves to
PostgreSQL) and ``none`` (audit only: nothing suggested). The folder's own audit.yaml is not
changed: each target is scanned into ``audit-output/targets/<target>/`` with the summary report
built there. ``--open`` opens the three summary reports.
"""

from __future__ import annotations

import json
import sys
import webbrowser
from pathlib import Path

from legacy_audit.config import load_config
from legacy_audit.enrich.stages import STAGES
from legacy_audit.enrich.status import active_stages
from legacy_audit.report.data import RunData
from legacy_audit.report.servers import server_rows
from legacy_audit.report.summary import build_summary_report
from legacy_audit.scan import run_scan
from legacy_audit.target import PROFILES

LIBRARIES = 6  # library rows shown per target


def show(folder: Path, profile: str) -> Path:
    loaded = load_config(folder / "audit.yaml")
    loaded.config.target.profile = profile  # type: ignore[assignment]
    run_dir = run_scan(loaded, output_dir=folder / "audit-output" / "targets" / profile).run_dir
    rd = RunData.load(run_dir)
    print(f"\n--- {profile}: {rd.target['label']} ---")
    skipped = [s.name for s in STAGES if s not in active_stages(run_dir)]
    print(
        f"  AI stages to write: {len(STAGES) - len(skipped)} of {len(STAGES)}"
        + (f" (not needed: {', '.join(skipped)})" if skipped else "")
    )
    grades = rd.summary.get("counts", {}).get("pg_grades") or {}
    print(
        "  PostgreSQL grades: "
        + (", ".join(f"{k} {v}" for k, v in sorted(grades.items())) or "not graded (no target)")
    )
    effort = rd.effort
    print(
        "  Effort estimate:   "
        + (
            f"{effort['likely']} person-days likely ({effort['low']} to {effort['high']})"
            if effort
            else "none (no target)"
        )
    )
    servers = server_rows(rd)
    if servers:
        print("  Servers and runtimes, what each becomes:")
        for r in servers:
            print(f"    {r['name']:<34} -> {r['target'] or '(no target)'}")
    libs = [
        lib
        for lib in rd.items("libraries")
        if (lib.get("recommendation") or {}).get("action") not in (None, "keep")
    ]
    if libs:
        print("  Library advice:")
        for lib in libs[:LIBRARIES]:
            rec = lib["recommendation"]
            target = f": {rec['target']}" if rec.get("target") else ""
            print(f"    {lib['artifact']:<24} {rec['action']}{target}")
        if len(libs) > LIBRARIES:
            print(f"    ... and {len(libs) - LIBRARIES} more")
    server_findings = [f for f in rd.items("findings") if f["rule_id"].startswith("server-")]
    if server_findings:
        f = server_findings[0]
        print(f"  Server finding, e.g.: {f['message']}")
        print(f"      what to do: {f.get('remediation')}")
    mapping = rd.target_mapping
    print(f"  Target mapping rules: {len(mapping)}")
    res = build_summary_report(run_dir)
    data = json.loads((res.page.parent / "summary.json").read_text(encoding="utf-8"))
    said = [t for t in data["sections"][0]["intro"] if t.startswith("Migration target")]
    print(f"  Summary report, project section: {said[0] if said else '(no target line)'}")
    print(f"  Summary report: {res.page}")
    return res.page


def main(argv: list[str]) -> int:
    folders = [Path(a).resolve() for a in argv if not a.startswith("--")]
    if not folders:
        print(__doc__)
        return 2
    for folder in folders:
        if not (folder / "audit.yaml").exists():
            print(f"{folder} has no audit.yaml.")
            return 2
    pages = []
    for folder in folders:
        print(f"\n=== {folder.name} ===")
        pages += [show(folder, profile) for profile in PROFILES]
    if "--open" in argv:
        for page in pages:
            webbrowser.open(page.as_uri())
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
