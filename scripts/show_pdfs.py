"""Build the four reports of a sample and their PDF copies, and print what was written.

    python scripts/show_pdfs.py samples/struts-oracle-app [--open]

Scans the folder when it has no run yet, builds the technical report, functional document,
management deck and summary report, then prints them to PDF with Microsoft Edge, Chrome or
Chromium (``pdf:`` in audit.yaml). Prints the browser used and, per PDF, its pages and paper
size. ``--open`` opens the summary PDF.
"""

from __future__ import annotations

import os
import sys
import webbrowser
from pathlib import Path

from pypdf import PdfReader

from legacy_audit.config import load_config
from legacy_audit.report.functional import build_functional_report
from legacy_audit.report.mgmt import build_mgmt_report
from legacy_audit.report.pdf import REPORTS, build_pdfs
from legacy_audit.report.summary import build_summary_report
from legacy_audit.report.tech import build_tech_report
from legacy_audit.scan import run_scan
from legacy_audit.store.runs import RunNotFound, resolve_run


def main(argv: list[str]) -> int:
    args = [a for a in argv if not a.startswith("--")]
    if not args:
        print(__doc__)
        return 2
    folder = Path(args[0]).resolve()
    if not (folder / "audit.yaml").exists():
        print(f"{folder} has no audit.yaml.")
        return 2
    loaded = load_config(folder / "audit.yaml")
    try:
        run_dir = resolve_run(loaded.output_dir, "latest")
    except RunNotFound:
        print(f"No run yet in {loaded.output_dir}; scanning {folder.name}...")
        run_dir = run_scan(loaded).run_dir
    cfg, branding = loaded.config, loaded.config.branding
    print(f"Building the reports of run {run_dir.name}...")
    build_tech_report(run_dir, loaded.inputs_dir)
    build_functional_report(run_dir, loaded.inputs_dir)
    build_mgmt_report(run_dir, branding, loaded.path.parent)
    build_summary_report(run_dir, branding, loaded.path.parent)
    print("Printing the PDF copies (about a minute)...")
    res = build_pdfs(run_dir, cfg.pdf)
    if res.browser is not None:
        where = " (Windows browser, started from WSL)" if res.browser.windows_from_wsl else ""
        print(f"\nBrowser: {res.browser.label} at {res.browser.path}{where}")
    print()
    for key, path in res.files.items():
        reader = PdfReader(path)
        box = reader.pages[0].mediabox
        w, h = float(box.width) / 72 * 25.4, float(box.height) / 72 * 25.4
        print(f"  {REPORTS[key][2]:<20} {len(reader.pages):>4} pages  {w:.0f} x {h:.0f} mm  {path}")
    for msg in res.messages:
        print(f"  !! {msg}")
    if not res.files:
        return 1
    if "--open" in argv and "summary" in res.files:
        target = res.files["summary"]
        if sys.platform == "win32":
            os.startfile(target)  # type: ignore[attr-defined]
        else:
            webbrowser.open(target.resolve().as_uri())
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
