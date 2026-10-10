# Validating legacy-audit from VS Code

Every phase ends with a section here: how to check, from VS Code on a Windows laptop (or Linux /
macOS), that what the phase built works. The repository ships `.vscode/` with tasks, debug
configurations and recommended extensions, so no typing of commands is needed.

## Once: open and set up

1. `git clone https://github.com/gkgsiva/code-audit` and **File > Open Folder** on it (trust the
   folder when asked).
2. Accept the recommended extensions (Python, Python Debugger, Ruff, SQLite Viewer).
3. **Terminal > Run Task... > `audit: 1. set up (venv + install)`** (Python 3.11+ must be on
   PATH as `python` on Windows, `python3` on Linux, macOS and WSL2). Then **Ctrl+Shift+P > Python: Select Interpreter** and pick `.venv`.
4. **Terminal > Run Task... > `audit: 2. tests (pytest)`**: every test passes. The **Testing**
   view (flask icon) shows the same tests; you can run or debug any single one there.
5. **Terminal > Run Task... > `audit: 3. lint + type check`**: no errors.

To scan one sample: **Run Task > `audit: 4. scan a sample`** and pick it. The run is written to
`samples/<sample>/audit-output/runs/<run-id>/` (`latest` points to it). To step through the
scanner with breakpoints, use **Run and Debug (Ctrl+Shift+D) > `legacy-audit: scan a sample`**.
To scan your own application, open a terminal (`.venv` active) and run
`legacy-audit init` then `legacy-audit scan` in its folder.

Every expected number for the samples is in `samples/<sample>/expected.yaml`; the test
`tests/test_samples.py` compares a fresh scan with it, so a green test run is the full check.
The steps below are for looking at the results yourself.

## Phase 1: foundations (inventory, runs, cache)

1. Run task `audit: 4. scan a sample` > `struts-oracle-app`.
2. Open `samples/struts-oracle-app/audit-output/latest/run.json`: input coverage (Autosys,
   MQ... "not provided" are gaps, not errors), timings, counting rules.
3. Open `latest/data/audit-summary.json`: `totals` (files, LOC) and `by_language` match
   `samples/struts-oracle-app/expected.yaml` > `inventory`.
4. Run the task again: the terminal says `analyzed: 0, from cache: N` — unchanged files are
   skipped. `legacy-audit diff previous latest -c samples/struts-oracle-app/audit.yaml` shows no
   change.
5. Open `latest/data/facts.sqlite` (SQLite Viewer) and browse the `inventory` table.

## Phase 2: analyzers (services, screens, SQL, integrations, jobs)

1. Run task `audit: 5. scan all samples`.
2. In each `samples/<sample>/audit-output/latest/data/audit-summary.json`, `counts` match
   `expected.yaml` > `counts` (services by kind, screens, SQL by type and source, integrations,
   batch jobs, libraries).
3. Open `data/services.csv`, `screens.csv`, `integrations.csv`, `batch_jobs.csv`: every row has
   `evidence` with a file and line; Ctrl+click a `file:line` in the terminal to jump to it.
4. `run.json` > `analyzers`: coverage % per analyzer; `unresolved_references` lists what is
   missing from the source (e.g. `archive_usage.sh` in `spring-mvc-batch-app`).

## Phase 3: rules, metrics, PostgreSQL grading, dependency security

1. Run task `audit: 5. scan all samples`.
2. `data/findings.csv`: findings by rule with severity and evidence; `audit-summary.json` >
   `findings_by_severity` and `pg_grades` match `expected.yaml` > `assessment`.
3. **Dynamic SQL** — run task `audit: 6. show dynamic SQL of the latest run` and pick a sample.
   Each statement built at run time is printed rebuilt, with how it was built, its optional
   parts, holes and variants, for example (`c-proc-batch`):

   ```
   src/settle_load.pc:59  [manual] DELETE (embedded)
       DELETE FROM ? WHERE run_date < ADD_MONTHS(SYSDATE, -13) AND status <> 'FAILED'
       built with: concatenation, conditional, prepare-from-variable, string-format; ...
       variant: DELETE FROM ? WHERE run_date < ADD_MONTHS(SYSDATE, -13)
   ```

   Ctrl+click the `file:line` and check the code against the rebuilt text: Java
   (`struts-oracle-app` `OrderDao.advancedSearch`, `spring-mvc-batch-app` `InvoiceDao`), Pro*C
   (`c-proc-batch` `purge_old`), COBOL (`cobol-cics-db2` `ORDRPT` `DETAIL-PARA`). `?` marks a
   piece only known at run time: a *value* (injection risk, rule `sql-concatenation`) or part of
   the statement itself (rule `sql-runtime-structure`, graded manual).
   `python scripts/show_sql.py samples/<sample> --grade manual` lists every manual statement.
4. `data/sql_statements.csv`: `pg_grade`, `reason` and `pg_text` (suggested PostgreSQL) per
   statement.
5. `data/libraries.csv`: CVEs (from `audit-inputs/security/` reports), EOL and the upgrade
   recommendation; `data/sbom.cdx.json` is the CycloneDX SBOM; `data/effort.json` the
   low/likely/high estimate, labelled "estimate — weights from config".
6. Change a weight: add `complexity: {weights: {loc: 1000}}` to a sample's `audit.yaml`, run the
   scan again: the terminal says `analyzed: 0`, yet the `complexity` bands in `services.csv` and `effort.json` change.
   Undo the edit afterwards.
7. Without Dependency-Check installed, `run.json` > `coverage` shows `dependency-check: missing`
   (a gap, not "no vulnerabilities").

## Phase 4: technical HTML report

1. Run task `audit: 4. scan a sample` (or `5. scan all samples`), then
   `audit: 7. build and open the technical report` with the same sample. The report opens in the
   browser from `samples/<sample>/audit-output/runs/<run-id>/reports/tech/index.html`
   (also reachable through `audit-output/latest/reports/tech/`).
2. **Overview**: the KPI tiles (services, screens, SQL, integrations, batch jobs, libraries,
   findings by severity, PostgreSQL grades, effort) equal the numbers in
   `latest/data/audit-summary.json` and `effort.json`. The badges under the title show which
   inputs were present or missing.
3. **Evidence**: on any catalogue (e.g. *SQL*), the *Where* link opens the source file; the
   **VS Code** link opens it in VS Code at the line (allow the browser to open `vscode://` links
   the first time).
4. **Catalogues**: type in the search box, pick a value in a filter (e.g. *PG grade* = manual),
   click a column header to sort, and page with *Previous/Next* (50 rows per page). The *CRUD
   matrix* shows which component creates, reads, updates or deletes each table.
5. **Architecture**: the component graph, the integration map and the batch chains are drawn
   from the scan (Mermaid, no network). Each component page lists its services, screens, SQL and
   findings.
6. **Theme**: the button at the right of the menu switches light/dark; charts and diagrams follow.
7. **Offline**: with the network off (or DevTools > Network), no request leaves the machine.
   *Appendix* shows the counting rules, the config with passwords masked, and the run log summary.
8. **Re-run**: run tasks 4 and 7 again. A new `runs/<run-id>/reports/tech/` appears, the older
   run's report is unchanged, and `latest` points to the new one.

## Phase 5: Copilot agent, enrichment, management deck and PPTX

1. Run task `audit: 1. set up` again (adds `python-pptx` for the tests), then
   `audit: 8. install the PPTX builder (npm, once)` (needs Node.js 18+).
2. **Copilot files**: run `audit: 9. install Copilot files into a sample (init)`. The sample now
   has `.github/agents/legacy-auditor.agent.md`, `.github/chatmodes/…`, `.github/prompts/audit-*.prompt.md`,
   `.github/copilot-instructions.md`, `.github/legacy-audit/schemas/` and `.vscode/mcp.json`.
   Edit `copilot-instructions.md` and run the task again: your edit stays and the file is not
   listed as updated. These files are ignored by git here and never scanned.
3. **Enrichment without Copilot**: run `audit: 4. scan a sample` with `struts-oracle-app`, then
   `audit: 10. load demo enrichment and validate` with an empty option. All stage files show
   `valid` (12 in phase 5, 14 since phase 7). Run it again with `--break`: the terminal shows
   `figure 'Services' says 12 but the scan says 4 (counts.services.total)` and exits with an
   error. Run it once more without `--break` to restore.
4. **Stale stages**: run `audit: 4. scan a sample` again. Open
   `samples/struts-oracle-app/audit-output/latest/enrichment/status.json`: the enrichment was
   carried into the new run and `stale` is empty, since nothing changed. (The automated test adds
   Autosys input to `spring-mvc-batch-app` and checks that flows, risks, plan and narratives go
   stale while classification does not.)
5. **Decks**: run `audit: 11. build management deck and PPTX`. The technical report opens; it has
   a new **Analysis** page with the enrichment, its stage states and evidence links. Then open
   `audit-output/latest/reports/mgmt/index.html`: arrow keys move between the slides (about 40 since phase 7), `N` shows
   speaker notes, the browser's Print saves a PDF with one slide per page. Open
   `audit-output/latest/reports/deck.pptx` in PowerPoint: click a chart and choose
   **Edit Data**; the charts are native charts and the tables are native tables.
6. **Placeholders**: build the decks for another sample (no enrichment). Slides that need
   enrichment show "Not enriched yet … run /audit-enrich <stage> in Copilot".
7. **MCP / facts**: run `audit: 12. query facts as Copilot does`; it prints the JSON rows that the
   MCP tool `query_facts` returns. With Copilot: open the sample folder as a workspace, start the
   `legacy-audit` server from `.vscode/mcp.json`, pick the **legacy-auditor** agent and ask
   "which services use the ORDERS table?". See `docs/copilot-agent.md`.

## Phase 6: GUI and hardening

1. Run task `audit: 1. set up` again (adds FastAPI and uvicorn), then
   `audit: 14. check installed tools (doctor)`. The table lists Python and the Python packages as
   required and `ok`, and every other tool as optional, with what it is needed for. Rename your
   Node folder or run on a machine without it: Node.js shows `missing` and the command still ends
   with "Ready to scan".
2. **GUI from a clean folder**: create an empty folder, copy one sample's code into it (for
   example `samples/struts-oracle-app/orders-web` as `src`), and in a terminal there run
   `<repo>\.venv\Scripts\legacy-audit serve` (Windows) or `<repo>/.venv/bin/legacy-audit serve`.
   The browser opens on **Project setup**:
   - Click **Create audit.yaml**, set *Folders* to `src` and **Save audit.yaml**. Open
     `audit.yaml` in VS Code: the comments are still there.
   - On **Run**, click **Re-run**. The bar moves through inventory, analysis, linking, rules and
     writing facts, and the run appears in the table.
   - Open **Dashboard** (tiles and four charts), then **Reports** > **Build technical report** >
     **Open**.

   No CLI command was needed apart from starting the GUI.
3. **Late input**: copy `samples/spring-mvc-batch-app/audit-inputs/autosys/billing.jil` to your
   Desktop, then run `audit: 13. start the GUI on a sample` with `spring-mvc-batch-app`.
   - On **Inputs**, **Remove** `billing.jil`, then **Re-run** on **Run**.
   - Back on **Inputs**, **Add file** with the copy from your Desktop, then **Re-run** again.
   - **Dashboard**: *Batch jobs* goes up and the Autosys badge is green.
   - **Compare runs**: the default previous-to-latest comparison lists the added batch jobs and
     dependencies.
   - **AI enrichment** lists the stages as missing (13 since phase 7) with `/audit-enrich all` to copy. With
     enrichment present, the new JIL marks flows, risks, plan and narratives `stale`; the test
     `test_inputs_upload_rerun_marks_enrichment_stale` in `tests/test_gui.py` checks exactly this
     sequence through the GUI's API.
4. **Explorer**: pick *sql statements*, click a row. The source opens on the right with the line
   highlighted, and **Open in VS Code** jumps to it. Pick *components* and click one to drill down
   into its services, SQL and jobs.
5. **Components**: untick a component, **Save choices**, **Re-run**. It disappears from the
   dashboard, and `audit-overrides.yaml` has its folder under `files.exclude`. Tick it again
   afterwards.
6. **Cancel**: start a **Full rescan** and press **Cancel**. The run table keeps the previous run
   as latest, and no half-written run folder is left in `audit-output/runs/`.
7. **Performance**: run `audit: 15. performance test` with `500000`. It prints the full scan and
   re-run times and the per-stage timings. Compare them with `docs/performance.md`; please send
   the 2,000,000 result from a team laptop so the laptop column can be filled in.
8. **Security checks** are automated: `tests/test_gui.py` covers foreign Host headers,
   cross-site writes, path traversal on evidence, reports, run ids and input file names, and
   checks that the sample's database password appears in no output file or data pack.

## Phase 7: functional document, screen and batch flows, graphics

Works the same on Windows and on WSL2 (the tasks pick `.venv\Scripts\python` or
`.venv/bin/python` themselves). On WSL2, open the HTML files from the Windows Explorer
(`\\wsl$\...`) or with `explorer.exe <file>` if the browser does not open by itself.

1. Run `audit: 2. tests (pytest)`: everything passes, including `tests/test_functional.py`.
2. **Batch flows without Copilot**: run `audit: 4. scan a sample` with `spring-mvc-batch-app`,
   then `audit: 17. build and open the functional document` with the same sample. Open
   **Batch**:
   - the week calendar shows BIL_NIGHTLY_BOX at 01:30 Monday to Friday, and invoiceJob as
     "Started by BIL_INVOICE (via run_invoice_job.sh)";
   - click **BIL_NIGHTLY_BOX**: the diagram shows the schedule and the usage file starting the
     box, the three steps in order (the archive runs on "success or failure"), the tables
     INVOICES and INVOICE_AUDIT (C) and USAGE_RECORDS (R), the MQ queue and the e-mail; *If it
     fails* says BIL_INVOICE raises an alarm and is retried once.
   The purpose and business impact boxes say "Not enriched yet … /audit-enrich functional".
3. **The words from Copilot**: run `audit: 16. load demo functional enrichment (Billing sample)`
   (it stands in for Copilot and prints `valid`), then task 17 again. The same pages now carry the
   capability map, use cases, rules and the story of each batch next to the scan's diagram.
4. **Screen flows**: run task 4 and task 17 with `struts-oracle-app`, open **Screens** and click
   `orderDetail.jsp`: it comes from orderSearch.jsp, is loaded by `/orderDetail`, opens
   `/orderSearch`, reads ORDERS, CUSTOMERS and USERS, and calls the pricing SOAP service.
5. **Technical report**: run `audit: 7. build and open the technical report`. The overview has
   coloured tiles, readiness gauges and charts; the new **Flows** and **Data model** pages hold
   every screen and batch flow and the CRUD heat map; each component page has a radar and, with
   enrichment (task 10), a "What this component does" box.
6. **Deck and PPTX**: run `audit: 11. build management deck and PPTX` with
   `spring-mvc-batch-app`. In the HTML deck each section starts with a coloured slide; look for
   *Migration readiness* (gauges), *Batch flow: BIL_NIGHTLY_BOX*, *Batch calendar* and *Who
   changes which data*. Open `audit-output/latest/reports/deck.pptx` in PowerPoint: the gauges are
   doughnut charts (**Edit Data** works), the calendar and heat maps are tables, and the flow
   boxes and arrows can be moved and recoloured.
7. **Dark mode**: the theme button in each report switches light/dark; colours stay readable and
   every value is still printed as text.
