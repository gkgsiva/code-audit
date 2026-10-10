# Phase 4 — Technical HTML documentation

Goal: `legacy-audit report --type tech` builds a static, self-contained HTML site from the latest run that architects and tech leads can browse offline.

## Requirements

- Jinja2 templates in `legacy_audit/report/tech_html/`; output to `audit-output/runs/<id>/reports/tech/` (and `latest`). Opens from disk (`file://`), no server, **no CDN**: vendor Chart.js, Mermaid, DataTables (or a small vanilla table filter), and a CSS file. Light and dark mode via `prefers-color-scheme`.
- Templates only display facts; any number shown must exist in the data files.
- Header on every page: application, run id, date, source SHA(s), input coverage badges (e.g. "Autosys: not provided", "Vulnerability DB: 2026-10-01").

## Pages

1. **Overview** – KPI tiles, tech stack with versions and EOL flags, LOC by language chart, components table.
2. **Components** – one page each: files, LOC, entry points, services, screens, SQL, integrations, libraries, dependencies in/out, findings.
3. **Architecture & flows** – component dependency diagram generated from code edges (Mermaid), integration map, batch chains (Autosys/JCL/Spring Batch) as Mermaid flowcharts; when enrichment exists (phase 5), show the agent's architecture and flow diagrams instead, labelled as AI-derived with evidence links.
4. **Catalogues** – services, screens, SQL statements, DB objects with CRUD matrix, integrations, batch jobs, libraries: sortable, filterable, searchable tables; each row links to its evidence (relative link to the source file when local, `vscode://file/...` link, and file:line text).
5. **PostgreSQL readiness** – grade breakdown chart, top vendor constructs, procedure list, statements needing manual work.
6. **Dependency security** – severity chart, top 20 risky libraries, full upgrade table (current → recommended, action, reason), licence flags, SBOM download.
7. **Findings** – by severity/category, filterable.
8. **Enrichment sections** (rendered only when present, phase 5): risks, assumptions, target mapping, migration plan.
9. **Appendix** – counting rules, run configuration (secrets removed), coverage per analyzer, scan log summary, glossary.

## Charts

Follow a consistent palette and accessible contrast; label units; no 3D/pie for more than five categories (use bars).

## Acceptance criteria

- Report builds for every sample; HTML validates (basic check) and contains no `http(s)://` script or stylesheet references (test).
- Every KPI on the overview equals the corresponding value in `audit-summary.json` (test parses the HTML).
- Large catalogues (≥ 20k rows, synthetic) stay usable: paginate or lazy-load from a JSON file next to the page.
- Re-running `report` after a new scan updates only from `latest`; older runs' reports stay intact.
