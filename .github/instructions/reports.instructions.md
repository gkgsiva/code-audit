---
applyTo: "legacy_audit/report/**"
---

# Reports

- Reports only **display** facts. Read the run through `report/data.py` `RunData`; never
  recount or estimate a number here. Headline counts come from `audit-summary.json`
  (computed in `store/counts.py`). If a number is wrong, fix the analyzer, the linker or
  `store/counts.py` instead.
- Enrichment (AI) text comes from `deck_builder.load_enrichment(run_dir)`. Label it "(AI)";
  when a stage is missing show a placeholder, when it is stale or invalid show the warning
  the other pages show. Never take a number from AI text.
- Generated HTML opens from `file://` with no network: no CDN, no remote fonts; JS/CSS come
  from `report/assets/` (Chart.js and Mermaid are vendored in `assets/vendor/`).
- Respect the target profile: `rd.audit_only` (profile `none`) hides target, PostgreSQL grade,
  effort and plan content.
- PowerPoint (`pptx_build.py`, summary `to_deck`) uses only the corporate template's layouts
  and masters (`templates/*.potx`, `pptx_templates.py`).
- PDF (`pdf.py`) prints the HTML pages listed in `tech.NAV` / `functional.FN_NAV`; check that
  a new page prints well (wide tables, page breaks).
- Templates are Jinja2 in `tech_html/`, `summary_html/`, `mgmt_html/`; escape user data (the
  environment autoescapes; do not mark scanned text `|safe`).
- Tests: `tests/test_report.py`, `test_functional.py`, `test_mgmt.py`, `test_summary.py`,
  `test_pdf.py`, `test_batch_list.py`.
