# audit-reports

Package: `com.legacyaudit.reports` (plain Java, no Spring). It builds the four HTML reports and
their PDF copies from a run folder. The command line (`legacy-audit report`) and the web backend
(the Reports page) call the same classes.

| Report | Class | Written to `<run>/reports/` | Built from |
| --- | --- | --- | --- |
| Technical report | `TechReport` | `tech/` (index and 15 pages, `components/`, `flows/`, `data/*.js`, CSV) | facts, enrichment (Analysis page) |
| Functional document | `FunctionalReport` | `functional/` (11 pages, `flows/`) | facts and the `functional` enrichment stage |
| Management deck | `MgmtReport` | `mgmt/index.html` (slider) and `mgmt/deck.json` | facts and every enrichment stage |
| Summary report | `SummaryReport` | `summary/index.html`, `summary.json`, `data/*.csv` | facts, enrichment notes |
| PDF copies | `PdfReports` | `pdf/summary.pdf`, `management.pdf`, `functional.pdf`, `technical.pdf` | the HTML above, printed by Edge or Chrome |

Each `build(runDir, ...)` writes the report into the run and, when `audit-output/latest` is a
plain copy of the same run, into `latest` too. `buildInto(...)` writes into any folder (the
tests use it).

## Same output as the Python tool

The code is a line-by-line port of `legacy_audit/report/*.py` on `office-main`, and the HTML
templates are the Python templates, unchanged (`src/main/resources/com/legacyaudit/reports/`:
`tech_html`, `mgmt_html`, `summary_html`, `assets`, `mgmt_assets`). The only template change: the
summary page has no PowerPoint button (the PowerPoint is not carried over, decision 225).
`ReportParityTest` checks every file of the four reports against what Python wrote for the same
run (see [testing.md](testing.md)).

To make that possible, a few helpers behave like Python:

| Class | What it gives |
| --- | --- |
| `jinja.*` | A small Jinja2: the template syntax the reports use (`if`, `for` with `loop`, `macro`, `set`, `include`, filters such as `tojson`, `selectattr`, `round`, `join`), autoescape, `trim_blocks`, `lstrip_blocks`, undefined values printing as nothing. `Environment` loads a template folder from the class path; `global(...)` adds functions such as `fmt` and `icon` |
| `RunData` | The run loaded once: `run.json`, `audit-summary.json`, entity files, effort, target, folder roots; ids to labels, evidence links (relative link and `vscode://` link), Python's `quote` and `relpath` |
| `D`, `Counter`, `Json`, `Fmt` | Python dict access (`d.get(k) or ""`), `collections.Counter` with stable `most_common`, JSON read and written as Python does, numbers formatted as `f"{v:,}"` / `f"{v:,.1f}"` |
| `Visuals`, `Palette` | Chart data, gauges, heat levels, risk matrix, Gantt bars; the colour tokens |
| `Diagrams`, `Flows` | Mermaid diagrams (components, integrations, batch chains, layers, today to target); screen and batch flows, schedules, the week grid, CRUD matrix |
| `Catalogues`, `Batches`, `Servers`, `Comments` | The searchable catalogue tables, the batch list, the application server table, code comments with secrets masked |

Enrichment files are read through `engine.enrich.Status` (`loadEnrichment`, `compute`), which
also tells which stages are missing or out of date; slides and pages show a placeholder or a
warning for those.

## PDF (`PdfReports`)

1. `findBrowser`: `pdf.browser_path` from `audit.yaml`, else Edge, Chrome or Chromium in the
   usual places for Windows, macOS, Linux, a Playwright install (`PLAYWRIGHT_BROWSERS_PATH`) and,
   on WSL2, the Windows Edge/Chrome under `/mnt/c` (paths are then passed as `\\wsl.localhost`).
   No browser: the PDFs are skipped with a message, never an error.
2. `Printer.printPage`: writes a temporary copy `_pdf-<page>.html` with the paper size and
   `window.LEGACY_AUDIT_PDF` (rows per table, fit wide tables) and runs the browser headless
   with the network off. On Linux it retries without the browser sandbox when the sandbox
   cannot start.
3. Summary and deck are one page each. The technical and functional reports are printed page by
   page (detail pages after their main page, up to 4 at a time), then joined with **PDFBox**
   behind a cover with the contents; bookmarks are added, links between pages become jumps
   inside the PDF, and "page i of n" is stamped on each page.

## Management deck

`MgmtReport.deck(...)` returns the slides as data (`deck.json`): sections, slides, blocks
(`kpis`, `bullets`, `text`, `chart`, `table`, `diagram`, `gauges`, `heatmap`, `capmap`, `layers`,
`flow`, `gantt`, `placeholder`). `mgmt_html/deck.html` turns them into the slider; `branding`
from `audit.yaml` gives the colour, font and logo.

## Adding to a report

1. Change the Python reference first only if the Python tool also needs it; otherwise change the
   template and the Java class together.
2. Run `ReportParityTest` (task 12). A deliberate change makes it fail: update the fixtures in
   `src/test/resources/report-parity/` (README there) so they show the new output.
