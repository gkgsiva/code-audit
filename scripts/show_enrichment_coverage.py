"""Show what the AI enrichment reads and writes for desktop, .NET, Angular, Node.js and server
applications, then load a small demo enrichment and build the summary report.

    python scripts/show_enrichment_coverage.py FOLDER... [--open]

    for example: samples/desktop-dotnet-app samples/angular-node-app

For each folder: scans it, then prints what a model gets for the stages that changed (the fact
files each stage reads; the code of each batch, including Hangfire, Windows Task Scheduler and
node-cron jobs; the code of each desktop window and ASP.NET page; the code of each web service
operation offered). Then copies the demo enrichment from ``tests/fixtures/enrichment/<sample>/``
(as Copilot or ``legacy-audit enrich`` would write it), validates it, builds the summary report
and prints the rows marked (AI): component kinds, what replaces each product of the tech stack
and each server, and the upgrade plan next to vulnerable libraries. ``--open`` opens the
reports. Writes to each folder's own ``audit-output/`` like ``legacy-audit scan``.
"""

from __future__ import annotations

import json
import shutil
import sys
import webbrowser
from pathlib import Path

from legacy_audit.ai import context
from legacy_audit.config import load_config
from legacy_audit.enrich.stages import BY_NAME
from legacy_audit.enrich.validate import validate_run
from legacy_audit.report.data import RunData
from legacy_audit.report.flows import batch_flows
from legacy_audit.report.summary import build_summary_report
from legacy_audit.scan import run_scan

ROOT = Path(__file__).resolve().parents[1]
CHANGED = ("architecture", "flows", "dependency_plan", "risks", "target_architecture",
           "component_docs")  # fmt: skip


def show(folder: Path) -> Path:
    run_dir = run_scan(load_config(folder / "audit.yaml")).run_dir
    rd = RunData.load(run_dir)
    print(f"\n=== {folder.name} ===")

    print("\nFact files each stage reads (stages that read more than before):\n")
    for name in CHANGED:
        print(f"  {name:<20} {', '.join(BY_NAME[name].inputs)}")

    print("\nBatches and the code the AI reads for each:\n")
    for f in batch_flows(rd):
        sched = f.schedule.get("scheduler") or "no scheduler"
        print(f"  {f.name:<28} ({sched})")
        for rel in f.files or ["(no source file found)"]:
            print(f"      {rel}")

    print("\nScreens whose own code the AI reads (desktop windows, ASP.NET pages):\n")
    for scr in sorted(rd.items("screens"), key=lambda s: (s["technology"], s["name"])):
        if scr.get("technology") in context.CODE_SCREENS:
            files = ", ".join(context._screen_files(rd, scr))
            print(f"  {scr['technology']:<9} {scr['name']:<22} {files}")

    print("\nWeb service operations whose code the AI reads:\n")
    for ws in rd.items("web_services"):
        if ws.get("side") == "offered":
            for op in ws.get("operations") or []:
                print(f"  {ws['name']:<22} {op['name']:<36} {op.get('file')}:{op.get('line')}")

    fixtures = ROOT / "tests" / "fixtures" / "enrichment" / folder.name
    if fixtures.is_dir():
        target = run_dir / "enrichment"
        target.mkdir(exist_ok=True)
        for src in fixtures.glob("*.json"):
            shutil.copyfile(src, target / src.name)
        rep = validate_run(run_dir)
        print(f"\nDemo enrichment: {', '.join(rep.checked)}")
        print("  validation: " + ("passed" if rep.ok else "FAILED"))
        for m in rep.messages:
            print(f"  {m}")
        classes = json.loads((target / "classification.json").read_text(encoding="utf-8"))
        print("\nComponent kinds (AI):\n")
        for c in classes["components"]:
            print(f"  {c['component']:<24} {c['kind']:<9} {c['capability']}")

    res = build_summary_report(run_dir)
    data = json.loads((res.page.parent / "summary.json").read_text(encoding="utf-8"))
    sections = {s["key"]: {t["key"]: t for t in s["tables"]} for s in data["sections"]}
    project = sections["project"]
    stack = project["tech-stack"]
    if stack["columns"][-1] == "Target (AI)":
        print("\nTechnology stack, what replaces it (AI):\n")
        for r in stack["rows"]:
            if r[-1]:
                print(f"  {r[0]:<22} {r[1] or '-':<8} -> {r[-1]}")
    ai_servers = [r for r in project["servers"]["rows"] if str(r[-1]).endswith("(AI)")]
    if ai_servers:
        print("\nServers and runtimes, target (AI):\n")
        for r in ai_servers:
            print(f"  {r[0]:<34} -> {r[-1]}")
    libs = sections["security"].get("vulnerable-libraries")
    if libs and libs["columns"][-1] == "Upgrade plan (AI)":
        print("\nVulnerable libraries, upgrade plan (AI):\n")
        for r in libs["rows"]:
            print(f"  {r[0]:<40} {r[-1]}")
        if not libs["rows"]:
            print(
                "  No vulnerability results for this run (no scanner ran). With OSV-Scanner or\n"
                "  Dependency-Check results, part 5 of the summary report shows the plan here."
            )
    print(f"\nSummary report: {res.page}\n{res.message}")
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
    pages = [show(folder) for folder in folders]
    if "--open" in argv:
        for page in pages:
            webbrowser.open(page.as_uri())
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
