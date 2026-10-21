"""Scan a sample (or any folder with an audit.yaml) and print its web services: the REST and
SOAP services the application offers and the ones it calls.

    python scripts/show_web_services.py samples/web-services-app [--open]

Prints one block per web service (offered or called, REST or SOAP, address, how it was found,
WSDL, operations with the code behind each, callers), then builds the technical and summary
reports; ``--open`` opens the technical report's Web services page and the summary report.
Writes to the folder's own ``audit-output/`` like ``legacy-audit scan``.
"""

from __future__ import annotations

import sys
import webbrowser
from pathlib import Path

from legacy_audit.config import load_config
from legacy_audit.report.summary import build_summary_report
from legacy_audit.report.tech import build_tech_report
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
    web = result.facts.get("web_services", [])
    offered = [w for w in web if w.side == "offered"]  # type: ignore[attr-defined]
    called = [w for w in web if w.side == "called"]  # type: ignore[attr-defined]
    for title, rows in (
        ("Web services the application OFFERS (other systems call it)", offered),
        ("Web services the application CALLS", called),
    ):
        print(f"\n{title}: {len(rows)}\n")
        for w in rows:
            print(f"  {w.style} {w.name}")  # type: ignore[attr-defined]
            print(f"      address:       {w.url or '(not known from the code)'}")  # type: ignore[attr-defined]
            print(f"      found through: {', '.join(w.frameworks)}")  # type: ignore[attr-defined]
            print(f"      module:        {w.component or '-'}")  # type: ignore[attr-defined]
            if w.wsdl:  # type: ignore[attr-defined]
                print(f"      WSDL:          {w.wsdl}")  # type: ignore[attr-defined]
            for o in w.operations:  # type: ignore[attr-defined]
                where = f"{o.file}:{o.line}" if o.file else ""
                print(f"      - {o.name:<36} {o.handler or ''}  {where}")
            for c in w.callers:  # type: ignore[attr-defined]
                print(f"      called from {c}")
            print(f"      {w.description}")  # type: ignore[attr-defined]
    tech = build_tech_report(result.run_dir)
    page = tech.parent / "web-services.html"
    res = build_summary_report(result.run_dir)
    print(f"\nTechnical report, Web services page: {page}")
    print(f"Summary report (section 3, Integrations): {res.page}\n{res.message}")
    if "--open" in argv:
        webbrowser.open(page.as_uri())
        webbrowser.open(res.page.as_uri())
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
