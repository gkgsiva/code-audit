"""Scan one or more samples (or any folders with an audit.yaml) and print their desktop and
.NET parts: VB6, VB.NET, C# and Java Swing / JavaFX.

    python scripts/show_desktop_stack.py samples/desktop-dotnet-app samples/java-swing-app [--open]

For each folder, prints the components with their type (``desktop`` for VB6, Windows Forms,
WPF and Swing programs), the screens by technology (user controls, panels, master pages and
layouts are partials) and which screen opens which, the SQL the code builds with its source
(ADO, DAO, ADO.NET, Dapper, Entity Framework...), the scheduled tasks and Windows services,
the NuGet packages and VB6 COM/ActiveX references, and the runtimes with their end of life.
Then builds the summary report; ``--open`` opens it. Writes to each folder's own
``audit-output/`` like ``legacy-audit scan``.
"""

from __future__ import annotations

import sys
import webbrowser
from pathlib import Path

from legacy_audit.config import load_config
from legacy_audit.report.data import RunData
from legacy_audit.report.summary import build_summary_report
from legacy_audit.scan import run_scan

DESKTOP_TECH = {"vb6", "winforms", "wpf", "swing", "awt", "javafx", "aspx", "razor"}


def show(folder: Path) -> Path:
    result = run_scan(load_config(folder / "audit.yaml"))
    facts = result.facts
    print(f"\n=== {folder.name} ===")

    print("\nComponents:\n")
    for c in facts["components"]:
        print(f"  {c.name:<24} {c.type!s:<9} also: {', '.join(c.roles)}")  # type: ignore[attr-defined]

    names = {s.id: s.name for s in facts["screens"]}  # type: ignore[attr-defined]
    screens = set(names)
    for rec in RunData.load(result.run_dir).items("inventory"):
        names[rec["id"]] = rec["path"]
    print("\nScreens (partials are parts of a screen):\n")
    for s in sorted(facts["screens"], key=lambda s: (s.technology, s.partial, s.name)):  # type: ignore[attr-defined]
        if s.technology not in DESKTOP_TECH:  # type: ignore[attr-defined]
            continue
        part = "  (partial)" if s.partial else ""  # type: ignore[attr-defined]
        print(f"  {s.technology:<9} {s.name:<22} {s.fields_count} fields{part}")  # type: ignore[attr-defined]
    for e in facts["dependencies"]:
        if e.kind == "forwards" and e.target in screens:  # type: ignore[attr-defined]
            src = names.get(e.source, e.source)  # type: ignore[attr-defined]
            print(f"  opens: {src} -> {names[e.target]}")  # type: ignore[attr-defined]

    print("\nSQL built in the code:\n")
    for q in sorted(facts["sql_statements"], key=lambda q: (q.file, q.line)):  # type: ignore[attr-defined]
        text = q.text if len(q.text) <= 90 else q.text[:89] + "…"  # type: ignore[attr-defined]
        print(f"  {q.source:<17} {text}")  # type: ignore[attr-defined]

    print("\nBatch work (scheduled tasks, Hangfire, Windows services):\n")
    for j in facts["batch_jobs"]:
        print(f"  {j.scheduler:<12} {j.name:<20} {j.schedule or 'no schedule found'}")  # type: ignore[attr-defined]
    for ep in facts["entry_points"]:
        if ep.kind == "windows-service":  # type: ignore[attr-defined]
            print(f"  {'service':<12} {ep.name:<20} runs all the time (Windows service)")  # type: ignore[attr-defined]

    print("\nNuGet packages and VB6 COM / ActiveX references:\n")
    for lib in sorted(facts["libraries"], key=lambda x: (x.source, x.artifact)):  # type: ignore[attr-defined]
        if lib.source in ("nuget", "com"):  # type: ignore[attr-defined]
            scope = f"  ({lib.scope})" if lib.scope else ""  # type: ignore[attr-defined]
            print(f"  {lib.source:<6} {lib.artifact:<45} {lib.version or '-'}{scope}")  # type: ignore[attr-defined]

    print("\nLanguages and runtimes:\n")
    for t in facts["tech_stack"]:
        if t.category in ("language", "runtime"):  # type: ignore[attr-defined]
            eol = f"end of life {t.eol_date}" if t.eol else "supported"  # type: ignore[attr-defined]
            if t.eol is None:  # type: ignore[attr-defined]
                eol = "-"
            print(f"  {t.name:<22} {t.version or '-':<8} {eol}")  # type: ignore[attr-defined]

    res = build_summary_report(result.run_dir)
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
