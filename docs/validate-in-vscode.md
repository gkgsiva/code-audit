# Validating legacy-audit from VS Code

Every phase ends with a section here: how to check, from VS Code on a Windows laptop (or Linux /
macOS), that what the phase built works. The repository ships `.vscode/` with tasks, debug
configurations and recommended extensions, so no typing of commands is needed.

## Once: open and set up

1. `git clone <your office repository URL>` and **File > Open Folder** on it (trust the
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

1. Run task `audit: 1. set up` again (installs `python-pptx`). Since 2026-10-12 the PPTX needs no
   Node.js; see *Corporate PowerPoint templates* at the end.
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

## Phase 7 follow-up: batches without scheduler inputs

Same on Windows and WSL2. On WSL2, if the browser does not open, run
`explorer.exe build/no-inputs/c-proc-batch/audit-output/latest/reports/functional/batch.html`
in the terminal.

1. Run `audit: 2. tests (pytest)`: everything passes.
2. Run `audit: 18. scan a sample without its scheduler inputs (Batch page)` and pick
   `c-proc-batch`. It copies the sample to `build/no-inputs/c-proc-batch` without its
   `audit-inputs` folder (no crontab, no CFT), scans it and opens the functional **Batch** page:
   - one card, **settlement** (the batch component), with a grey **no schedule found** pill and
     "The code says: Nightly settlement …" (the comment at the top of its ksh script);
   - click it: the diagram starts at "no schedule found", then the batch **settlement**, its
     script run_settlement.ksh, which runs settle_report.sql, settle_load and settle_send, and
     touches 4 tables; **What the code says about itself** quotes the comments of each file.
3. Open `build/no-inputs/c-proc-batch/audit-output/latest/reports/tech/batch-jobs.html`: the
   panel **Batch work with no scheduler definition** lists settlement. The **Batch jobs** tile
   on the overview says 1 ("0 scheduled, 1 with no schedule found"): one batch component, not
   one per script (since the component-classification change).
4. Run task 18 again with `spring-mvc-batch-app`: invoiceJob and PaymentListener (pill
   **on message**) are listed even without the Autosys files; run_invoice_job.sh is a step of
   the billing-batch batch.
5. **Copilot's batch reading**: run `audit: 4. scan a sample` with `spring-mvc-batch-app`, then
   `audit: 16. load demo functional enrichment (Billing sample)` and `audit: 17. build and open
   the functional document`. Open **Batch** > **BIL_NIGHTLY_BOX**: the AI box now lists what
   each program does, the controls found in the code and how to restart. With a real Copilot
   run, `/audit-enrich functional` writes these from the batch sources.

## Model-agnostic AI enrichment

Same on Windows and WSL2. Set environment variables in the terminal VS Code opens tasks in:
Windows PowerShell `setx NAME "value"` then restart VS Code; WSL2
`echo 'export NAME="value"' >> ~/.bashrc` then open a new terminal (or close and reopen the WSL
window).

1. Run `audit: 2. tests (pytest)`: everything passes, including `tests/test_ai.py` (it runs a
   fake OpenAI-compatible model locally, so no key is needed).
2. Run `audit: 14. check installed tools (doctor)`: the last row, **AI model**, says
   "Copilot in VS Code" (the default).
3. **Pick a model.** Either:
   - a cloud model: set `LEGACY_AUDIT_AI_PROVIDER=anthropic`, `LEGACY_AUDIT_AI_MODEL=<model>`
     and `ANTHROPIC_API_KEY` (or `openai` + `OPENAI_API_KEY`; Azure needs
     `LEGACY_AUDIT_AI_BASE_URL` and `AZURE_OPENAI_API_KEY`, with the deployment as the model);
   - or a local one: install Ollama, `ollama pull qwen2.5:14b`, start it with
     `OLLAMA_CONTEXT_LENGTH=32768`, and set `LEGACY_AUDIT_AI_PROVIDER=ollama`,
     `LEGACY_AUDIT_AI_MODEL=qwen2.5:14b`.
   Restart VS Code (Windows) or open a new WSL terminal so tasks see the variables.
4. Run `audit: 14` again: **AI model** shows the provider, model and address (or which key
   variable is missing). Then run `audit: 19. test the AI model settings (enrich --check)` with
   any sample: it prints "... answered correctly".
5. Run `audit: 4. scan a sample` with `spring-mvc-batch-app`, then
   `audit: 20. AI enrichment with the configured model (missing and stale stages)` with the same
   sample. Each stage prints "valid after N round(s)" (or the errors left). Then run
   `audit: 17. build and open the functional document`: the Batch page carries the model's
   stories, and each enrichment file's `meta.author` names the model.
6. **In the GUI**: run `audit: 13. start the GUI on a sample`, open **7. AI enrichment**: with a
   provider set, the panel "Run here with <model>" has **Run missing and stale stages** and
   **Run all stages**; progress shows in the top bar.
7. **Copilot with a pinned model**: in a sample's `audit.yaml` set
   `ai: {copilot_model: "Claude Sonnet 4.5 (copilot)"}` (keep `provider: copilot`), run
   `audit: 9. install Copilot files into a sample (init)`, and open
   `.github/agents/legacy-auditor.agent.md`: its header has the `model:` line.

## Corporate PowerPoint templates

Same on Windows and WSL2. To open a `.pptx` from WSL2, copy it to Windows first, for example
`cp samples/struts-oracle-app/audit-output/latest/reports/deck.pptx /mnt/c/Users/<you>/Desktop/`,
or use **Download** in the GUI.

1. Run `audit: 1. set up (venv + install)` (installs `python-pptx`, now required), then
   `audit: 2. tests (pytest)`: everything passes, including the three
   `test_pptx_is_built_strictly_on_the_chosen_template[blue|orange|purple]` tests.
2. Run `audit: 14. check installed tools (doctor)`: **PowerPoint templates** says ok with
   "Blue, Orange, Purple in …/templates". There is no pptxgenjs row any more.
3. Run `audit: 4. scan a sample` with `struts-oracle-app`, then
   `audit: 10. load demo enrichment and validate (Struts sample)` with an empty option.
4. Run `audit: 8. build the PowerPoint on a corporate template`, keep `struts-oracle-app` and pick
   `orange`. The panel prints `PowerPoint (Orange template) -> …/reports/deck.pptx`. Open it:
   - slide 1 is the template's orange cover with the application name; slide 2 **Contents** lists
     the 7 sections numbered 01–07; each section starts with a template chapter page (02, 03 …);
     the last slide is the template's **Thank you** page;
   - content slides have the template's title, footer (application · run), page number and RG
     logo; prose uses the template's heading style (blue capitals) and bullets;
   - charts, tables and boxes use the template's theme colours: click a bar and look at
     **Shape Fill**, the colour is one of the theme row, not a custom colour;
   - no "Lorem ipsum" or "Chapter title" sample text is left.
5. Run task 8 again with `blue` and `purple`: same slides on the other templates. Try a wrong
   name in a terminal, `legacy-audit report --type pptx --template green -c samples/struts-oracle-app/audit.yaml`:
   it stops with "PowerPoint template 'green' not found (available: Blue, Orange, Purple)".
6. **Default from audit.yaml**: in `samples/struts-oracle-app/audit.yaml` set
   `branding: {pptx_template: purple}` and run `audit: 11. build management deck and PPTX`: the
   terminal says Purple. Undo the change afterwards.
7. **In the GUI**: run `audit: 13. start the GUI on a sample`, open **8. Reports**. The
   **PowerPoint template** box shows the three template previews; the one from `audit.yaml` (or
   Blue) is selected. Click **Orange**, then **Build PowerPoint**: the toast says "PPTX written
   (Orange template)" and the table shows "PowerPoint (PPTX), Orange template" with **Download**.
   Reload the page: Orange is still selected.

## Component classification and batch count

Same on Windows and WSL2 (the tasks pick `.venv\Scripts\python` or `.venv/bin/python`). On
WSL2, open reports with `explorer.exe <file>` if the browser does not open by itself.

1. Run `audit: 2. tests (pytest)`: everything passes, including the new sample
   `legacy-layout-app` in `tests/test_samples.py`, `test_build_output_is_skipped_and_reported`
   and `test_one_batch_per_component_not_per_script`.
2. Run `audit: 21. show components and the batch count` and pick `legacy-layout-app` (an estate
   with no top-level build: an Eclipse Struts project with its compiled `bin/`, a batch of Java +
   ksh + properties, a Pro*C batch with its generated `.c` and `obj/`, a shared `config/`, a
   folder of deploy scripts). The terminal shows:
   - 5 components: **orders-web** `web` (config 5, screen 1, source 3: Java and JSP together),
     **billing-batch** `batch` (config 1, script 2, source 1: Java, ksh and properties
     together), **settlement-c** `batch` (found by `Makefile.aix`), **config** `config`,
     **scripts** `script`. There is no `apps`, `bin`, `src`, `ksh` or `properties` component;
   - **Left out as build output or generated (4)**: `apps/orders-web/bin` (Eclipse output
     folder), `apps/billing-batch/classes`, `settlement-c/obj`, `settlement-c/src/settle.c`
     (generated by Pro*C);
   - **Batch jobs: 2 = 0 scheduled + 2 with no schedule found (billing-batch, settlement-c)**:
     one per batch component, not one per ksh script.
3. Run `audit: 7. build and open the technical report` with `legacy-layout-app`:
   - **Overview**: the Batch jobs tile says 2 with "0 scheduled, 2 with no schedule found";
   - **Components**: new columns **Also holds** and **Made of**;
   - **Batch jobs**: the panel **Batch work with no scheduler definition** lists billing-batch
     and settlement-c; click billing-batch: the diagram goes from "no schedule found" to the
     batch, its script run_invoices.ksh, InvoiceRun, and the tables INVOICES and USAGE_RECORDS;
   - **Findings**: four `build-output-skipped` (info) rows say what was left out and why.
4. Run task 21 with `struts-oracle-app`: one component orders-web `web`, also holding `db` and
   `script` (its deploy.ksh stays a script of the web module, not a batch); Batch jobs 0.
   With `spring-mvc-batch-app`: Batch jobs 4 = 4 scheduled + 0 with no schedule found.
5. **In the GUI**: run `audit: 13. start the GUI on a sample` with `legacy-layout-app` and open
   **3. Components**: the same five components; under the table, the folders left out as build
   output. The **Type override** list now offers `config` and `online-mainframe`.
6. **On your own estate**: point `audit.yaml` at it and run `legacy-audit scan`. If a folder you
   need was left out as build output, set `inventory: {skip_build_output: false}` in
   `audit.yaml`; if a component is split or grouped wrongly, fix it on the GUI's Components
   screen or with `components:` in `audit.yaml`.

## Lines of code by language and consolidated integrations

Same on Windows and WSL2 (the tasks pick `.venv\Scripts\python` or `.venv/bin/python`). On
WSL2, open reports with `explorer.exe <file>` if the browser does not open by itself.

1. Run `audit: 2. tests (pytest)`: everything passes, including
   `tests/test_language.py`, `tests/test_loc.py`, `tests/test_encoding.py`,
   `test_merge_integrations_one_row_per_interface` and the `legacy-layout-app` sample.
2. Run `audit: 22. show lines of code by language and the integrations` and pick
   `legacy-layout-app`. The terminal shows:
   - **Lines of code by language (11)** with no `unknown` row: `sqlloader` (the SQL*Loader
     file `invoices.ctl`), `other (.tpl)` (a template no rule knows, named by its extension),
     and `backup` and `data` with 0 lines (`run_invoices.ksh.bak`, `rates.dat`). Total 23 files,
     144 code lines. The binary `settlement-c/data/rates.pak` (no NUL byte) and the `.class`
     files are not in the list at all;
   - **Integrations, one row per interface (2)**: `DB oracle://billdb:1521/BILL` and
     `SMTP smtp://mail.acme.local:25`, each with **2 usages**: two spellings of the same
     database and of the same mail server are one row each.
3. Run task 22 with `spring-mvc-batch-app` and `struts-oracle-app`: the same integrations as
   before, each once.
4. Run `audit: 7. build and open the technical report` with `legacy-layout-app`:
   - **Overview**: the chart **Code lines by language** shows `sqlloader` and no `unknown`
     (the two smallest languages, `make` and `other (.tpl)`, are grouped as `other`);
   - **Integrations**: two rows; the description says "One row per interface (type and
     target)"; a target that is both read and written shows direction `both`;
   - **Appendix > Counting rules**: the Integration and LOC rules describe the above.
5. **On your own estate**: run `legacy-audit scan` (or task 22 with a folder that has an
   `audit.yaml`: `python scripts/show_loc_integrations.py <folder>`, `python3` on WSL2). The
   first scan after this update re-reads every file once. Files listed under **Not recognised,
   named by extension** tell us which extension rules to add; send the list to the team.

## Integration purposes and the consolidated batch list

Same on Windows and WSL2. On WSL2 use `python3` where a step says `python`.

1. Run `audit: 2. tests (pytest)`: everything passes, including `tests/test_batch_list.py`.
2. Run `audit: 22. show lines of code by language and the integrations` with
   `legacy-layout-app`. Under **Integrations** each row now has a purpose and a sentence:
   - `HTTP https://acme.okta.com/oauth2/default`: **login / authentication**, "Login /
     authentication with Okta (OAuth 2.0 / OpenID Connect) at acme.okta.com";
   - `HTTP https://pricing.acme.local/api/v2/prices`: **HTTP API**, "Calls the HTTPS endpoint
     /api/v2/prices on pricing.acme.local";
   - `FTP files.acme.local:21`: **file transfer**; DB, SMTP likewise.
3. Run `audit: 23. show all batches with what they do and when they run`:
   - `legacy-layout-app` (no JIL): **billing-batch** "Monthly invoicing batch..." / "Monthly,
     says a comment in the code" with the file and line; **settlement-c** "Daily settlement
     run." / "Daily, says a comment in the code";
   - `spring-mvc-batch-app` (with JIL): BIL_NIGHTLY_BOX "Billing nightly cycle" (scheduler) /
     "Runs every weekday (Mon to Fri) at 01:30." (autosys); PaymentListener "On each message
     received";
   - `cobol-cics-db2`: ORDNIGHT "No schedule found (Autosys JIL, crontab not provided)."
4. Run `audit: 7. build and open the technical report` with `legacy-layout-app`:
   - **Integrations**: new columns **Purpose** (filter it on `login / authentication`) and
     **Description**;
   - **Batch jobs**: **All batches** first, with **Download this list as CSV** (opens in
     Excel), then **Scheduler job details**.
5. Run `audit: 17. build and open the functional document`: the **Batch** page opens with
   **All batches at a glance**; **Interfaces** shows the scan's description where Copilot has
   not written one yet.
6. **With Copilot** (optional): run the enrichment (`/audit-enrich`, or task 20). Stage 12
   now asks for an interface story for every HTTP, SOAP, FTP, SFTP, SCP and CFT row and a
   short `purpose` and `when` per batch; rebuild the report and the Description, What it
   does and When it runs columns show Copilot's words marked "AI (Copilot)" / "(AI)".


## Summary report (HTML and PowerPoint)

Same on Windows and WSL2. On WSL2 use `python3` where a step says `python`.

1. Run `audit: 2. tests (pytest)`: everything passes, including `tests/test_summary.py`.
2. Run `audit: 4. scan a sample` with `legacy-layout-app`, then
   `audit: 24. build and open the summary report (HTML and PowerPoint)` with the same sample.
   The terminal prints `Summary report -> ...reports/summary/index.html` and
   `PowerPoint written (Blue template).` The page opens in the browser (on WSL2 open the
   printed path from Windows Explorer if it does not).
3. On the page, check the four numbered sections in the bar at the top:
   - **1 Project**: a short description, tiles (148 lines of code, 5 modules), charts of lines
     by language, lines by module and modules by complexity, then **Technology stack** and
     **Modules** (description, technology, complexity, lines of code);
   - **2 Database and queries**: 3 SQL statements on 4 tables, the PostgreSQL readiness
     ring, statements by type, most used tables, and the tables with their CRUD;
   - **3 Integrations**: 5 systems, with Okta as **login / authentication**;
   - **4 Batches**: billing-batch and settlement-c with what they do and when they run.
   Each table has **Download CSV** (opens in Excel). Long tables have a filter box.
4. Click **PowerPoint (PPTX)** at the top right: `summary.pptx` downloads. Open it in
   PowerPoint: the corporate cover, contents, one chapter slide per section, "at a glance"
   and figures slides with native charts, table slides, and the closing slide.
5. Other template: run in the terminal
   `python -m legacy_audit report --type summary --template purple -c samples/legacy-layout-app/audit.yaml`
   (Windows: `.venv\Scripts\python`, WSL2: `.venv/bin/python`): the message says Purple.
6. From the GUI: run `audit: 13. start the GUI on a sample`, open the run, click **Build
   summary report**, then open it. The page now also shows a template list and **Rebuild
   PowerPoint**; pick Orange, click it, and the page reloads with "Built on the Orange
   template".
7. With Copilot (optional): after the enrichment, rebuild; the project description, module
   descriptions, integration stories and batch purposes show Copilot's words marked AI.
8. Without Copilot, see the AI text with the demo analysis: run `audit: 4. scan a sample`
   with `struts-oracle-app`, then `audit: 10. load demo enrichment and validate (Struts
   sample)` (pick the empty option, not `--break`), then task 24 with `struts-oracle-app`. In **2 Database and queries** the intro ends with "Migration approach
   (AI): ...", **Tables** has the columns **Business data (AI)** (ORDERS = Order) and
   **Migration (AI)** (ORDERS = auto), and **Migration plan per object (AI)** lists
   PKG_ORDER_PRICING as rewrite. For batches: task 4 with `spring-mvc-batch-app`, task 16 (Billing
   demo analysis), then task 24 with `spring-mvc-batch-app`: BIL_NIGHTLY_BOX's *What it does* ends with "(AI)".


## Dependency vulnerabilities in the summary report

Same on Windows and WSL2. On WSL2 use `python3` where a step says `python`, and `.venv/bin/python`
where Windows uses `.venv\Scripts\python`.

1. Run `audit: 2. tests (pytest)`: everything passes, including the new tests at the end of
   `tests/test_summary.py`.
2. **Both scanners**: run `audit: 25. dependency vulnerabilities from both scanners (Billing
   demo)`. It copies the Billing sample to `build/vulns/`, adds a demo Dependency-Check report
   next to the sample's OSV-Scanner report, and prints part 5: 5 vulnerabilities in 4 of 10
   libraries (3 critical, 2 high), "both: 2, Dependency-Check only: 2, OSV-Scanner only: 1",
   then the list worst first with **Found by** (CVE-2019-17571 in log4j and CVE-2020-26945 in
   mybatis are *both*; CVE-2020-25638 in hibernate-core is *OSV-Scanner* only). The page opens
   at **5 Dependency vulnerabilities** (on WSL2, if it does not open, open the printed path
   from Windows Explorer).
3. On the page, check: tiles (10 libraries, 4 vulnerable, 5 vulnerabilities, 3 critical,
   2 high, 4 fix known, 2 found by both scanners); four charts (by severity, which scanner
   found them, most vulnerable libraries, by module); **Top vulnerabilities**, **Vulnerable
   libraries** (with the suggested fix) and **Vulnerability checks** (both scanners "report
   imported", Dependency-Check database 2026-10-15). Each has **Download CSV**.
4. Click **PowerPoint (PPTX)**: after the four earlier parts comes chapter 05 "Dependency
   vulnerabilities" with "at a glance" (tiles, summary, severity chart), "figures" (scanner and
   library charts), and table slides for the top vulnerabilities, the vulnerable libraries and
   the checks, all on the corporate template.
5. **One scanner**: run `audit: 4. scan a sample` with `struts-oracle-app`, then task 24 with
   the same sample. Part 5 says "Checked with Dependency-Check" and "Not used: OSV-Scanner
   (not installed)" (or OSV-Scanner's own result if it is installed), and every row is found
   by Dependency-Check.
6. **No scanner**: in a terminal run
   `python scripts/scan_without_inputs.py samples/struts-oracle-app` (Windows:
   `.venv\Scripts\python`, WSL2: `.venv/bin/python`), then
   `python -m legacy_audit report --type summary --open -c build/no-inputs/struts-oracle-app/audit.yaml`.
   If neither scanner is installed, part 5 shows a yellow box "No vulnerability check was
   done ... This does not mean they are safe.", the tiles say "not done", and the tables say
   "Not checked" instead of "None found". (If you have the scanners installed, the scan runs
   them and you see their real results instead.)
7. **On a real estate, quickest: OSV-Scanner alone in online mode** (one scanner is enough; no
   database to download; needs internet to osv.dev during the scan):
   - Windows: download only `osv-scanner_windows_amd64.exe` from
     github.com/google/osv-scanner/releases, put it in `C:\Tools\osv-scanner\` and rename it
     `osv-scanner.exe`. Check: `C:\Tools\osv-scanner\osv-scanner.exe --version` starts with
     `osv-scanner version: 2.`
   - WSL2: `mkdir -p ~/tools`, then
     `curl -L -o ~/tools/osv-scanner https://github.com/google/osv-scanner/releases/latest/download/osv-scanner_linux_amd64`
     and `chmod +x ~/tools/osv-scanner`. Check: `~/tools/osv-scanner --version`.
   - In the application's `audit.yaml` (GUI: **1. Project setup > Advanced: edit audit.yaml as
     text**), under `security:` set
     `dependency_check: enabled: false` and, under `osv_scanner:`, `enabled: true`,
     `path: "C:/Tools/osv-scanner/osv-scanner.exe"` (WSL2: `"/home/<you>/tools/osv-scanner"`,
     full path). `offline: false` is already there in a new `audit.yaml` (the default); an older
     `audit.yaml` may still say `offline: true`, change it.
   - **Tools on this computer** shows OSV-Scanner **ok**. Scan, then task 24 (or **Build summary
     report** in the GUI): part 5 says "Checked with OSV-Scanner", "Not used: Dependency-Check
     (turned off in audit.yaml)", and the **Vulnerability checks** table shows OSV-Scanner
     "ran during the scan".
8. **Optional, later**: add Dependency-Check (get a free NVD API key first, so its one-time
   database download takes 10 to 30 minutes instead of hours; usage guide, Vulnerability
   checks, Option 3), or put a JSON report from your security team in `audit-inputs/security/`
   (any file name; the kind is detected from the content).

## Angular, AngularJS, Node.js and application servers

Same on Windows and WSL2. On WSL2 use `python3` where a step says `python`, and `.venv/bin/python`
where Windows uses `.venv\Scripts\python`. If the new parser is missing, run
`.venv\Scripts\python -m pip install -e .` (WSL2: `.venv/bin/python -m pip install -e .`) once.

1. Run `audit: 2. tests (pytest)`: everything passes, including
   `tests/analyzers/test_typescript.py`, `test_servers.py`, `test_npm_deps.py` and the
   `angular-node-app` sample check.
2. Run `audit: 26. Angular, Node.js and servers (Angular and Node sample)`. The terminal shows:
   - **Components**: `orders-web` and `legacy-portal` are `frontend`, `orders-api` and
     `billing-api` are `api` (also `batch`), `deploy` is `config`.
   - **Screens**: 3 Angular pages (order list, order detail, reports) and 2 AngularJS views
     (accounts, account detail); the app shells, the header and the account summary are
     partials.
   - **API routes**: `GET /api/orders`, `POST /api/orders`, `GET /api/orders/:id`,
     `DELETE /api/orders/:id`, `GET /api/customers/:id`, `GET /health` (Express) and three
     `/api/invoices` routes (NestJS).
   - **Front end calls**: `order.service.ts` calls four of those routes.
   - **Scheduled jobs**: `purge-old-orders` (0 2 * * *) and `month-end-close` (30 1 1 * *).
   - **Servers**: Tomcat, WebSphere, Liberty, JBoss / WildFly 4.2, WebLogic 8.1 and 12.2.1.4.0,
     Apache HTTP Server, Nginx 1.25 and Node.js 18 and 20.11, each with the files it was found in.
   Then the summary report opens (on WSL2, if it does not open, open the printed path from
   Windows Explorer).
3. In the summary report, part **1 Project**: the tile **App and web servers** says 7 and the
   table **Application and web servers** lists them with version, end of life (WebLogic 8.1,
   JBoss 4.2 and Node.js 18 are end of life), modules, files and the target. **Download CSV**
   gives `servers.csv`. Part **2** lists the Prisma, TypeORM and raw SQL statements, part
   **3** the payments, geocoding and Okta calls and the two databases, part **4** the two
   Node.js jobs with their schedule.
4. Click **PowerPoint (PPTX)**: the Project chapter has a table slide "Application and web
   servers".
5. Run `audit: 4. scan a sample` with `angular-node-app`, then `audit: 7. build and open the
   technical report` with the same sample: in the technical report, **Overview** has a section **Application and web servers** with links to
   each file (and **VS Code** links); **Findings** lists `server-specific-config` for each
   descriptor and `build-output-skipped` for `chart.min.js`. Then `audit: 11. build management
   deck and PPTX`: the deck has an "Application and web servers" slide after the technology
   stack.
6. **On your own Angular or Node application**: point `audit.yaml` at the folder that holds
   `package.json` (the GUI's **1. Project setup**), scan, and check that `node_modules/` and
   `dist/` do not appear in the lines of code and that each routed page is a screen.

## Web services (REST and SOAP)

Same on Windows and WSL2. On WSL2 use `.venv/bin/python` where Windows uses
`.venv\Scripts\python`. The demo works on a copy of the sample, so the repository stays clean.

1. Run `audit: 2. tests (pytest)`: everything passes, including
   `tests/analyzers/test_webservices.py` and the `web-services-app` sample check.
2. Run `audit: 27. Web services, REST and SOAP (web services sample)`. The terminal shows two
   lists:
   - **Offers (other systems call it): 5**: REST `CustomerController` (`/api/customers`,
     Spring MVC) and `OrderResource` (`/rest/orders`, JAX-RS); SOAP `Calculator`
     (`/Calculator.jws`, Axis), `OrderStatusService` (`/ws/OrderStatus`, JAX-WS + CXF, with its
     WSDL) and `QuoteService` (`/services/QuoteService`, Axis). Each has 2 operations with the
     file and line.
   - **Calls: 5**: REST `payment-service` (Feign, address taken from
     `application.properties`), `stock.acme.local` (RestTemplate), `rates.acme.local` (libcurl
     in C); SOAP `CrmService` (generated JAX-WS client, operations from `crm.wsdl`) and
     `RateService` (gSOAP in C).
   Then the technical report's **Web services** page and the summary report open (on WSL2,
   if they do not open, open the printed paths from Windows Explorer).
3. On the **Web services** page: the column filters **Offered / called** and **REST / SOAP**
   narrow the table, the **Evidence** column links to each file (with **VS Code** links), the
   operations table lists every operation with its handler, and **Download CSV** gives
   `web-services.csv`.
4. In the summary report, part **3 Integrations**: tiles **Web services offered** (5) and
   **Web services called** (5), a chart by style, and the **Web services** table. Click
   **PowerPoint (PPTX)**: the Integrations chapter has a "Web services" slide on the Blue
   template.
5. Optional, with Copilot: run the enrichment (task 10) on the sample; after the functional
   stage the table gains a **Suggested migration** column, its text marked (AI).
6. **On your own application**: scan it as usual and open the **Web services** page. Check
   that each REST controller, SOAP endpoint and WSDL you know is listed once, and that the
   called services match the systems in **Integrations**.

## Desktop and .NET applications (VB6, VB.NET, C#, Java Swing)

Same on Windows and WSL2. On WSL2 use `python3` where a step says `python`, and `.venv/bin/python`
where Windows uses `.venv\Scripts\python`. This adds a new parser for C#: if it is missing, run
`.venv\Scripts\python -m pip install -e .` (WSL2: `.venv/bin/python -m pip install -e .`) once.

1. Run `audit: 2. tests (pytest)`: everything passes, including
   `tests/analyzers/test_dotnet.py`, `test_basic.py`, `test_csharp.py` and the
   `desktop-dotnet-app` and `java-swing-app` sample checks.
2. Run `audit: 28. Desktop and .NET apps (VB6, VB.NET, C#, Java Swing samples)`. The terminal
   shows, for the .NET sample:
   - **Components**: `billing-desk-vb6` (VB6), `orders-winforms-vbnet` (VB.NET) and
     `inventory-wpf-cs` (C# WPF) are `desktop`; `billing-api-cs` (ASP.NET Core) and
     `legacy-webforms` (ASP.NET Web Forms) are `web`; `nightly-export-cs` and
     `invoice-mailer-svc` (a Windows service) are `batch`.
   - **Screens**: the VB6 forms `frmMain` and `frmCustomer`, the Windows Forms `MainForm` and
     `OrderDetailForm`, the WPF windows `MainWindow` and `StockDialog`, two ASP.NET pages and
     one Razor view; user controls, the master page and the layout are partials. The
     **opens** lines show which screen opens which (for example `frmMain -> frmCustomer`).
   - **SQL built in the code**: 20 statements with where they come from (`ado`, `dao`,
     `ado.net`, `dapper`, `entity-framework`...). The searches built piece by piece show the
     whole statement with `?` where a value is filled in at run time.
   - **Batch work**: `NightlyExport` (daily at 01:30) and `WeeklyPurge` (weekly on Sunday at
     03:00) from Windows Task Scheduler, `close-invoices` (daily at 02:00) from Hangfire, and
     the `MailerService` Windows service.
   - **NuGet packages and VB6 references**: Dapper, Entity Framework Core, Hangfire,
     log4net... and the VB6 COM references and ActiveX controls (`.OCX`).
   - **Languages and runtimes**: Visual Basic 6, .NET Framework 4.0, 4.5.2 and 4.6.1 and
     .NET 6 are end of life.
   For the Swing sample: one `desktop` component `teller-desk` with the screens `MainFrame`,
   `CustomerDialog`, `Rates` (JavaFX) and the partial `AccountPanel`, and the customer search
   SQL. Then both summary reports open (on WSL2, if they do not open, open the printed paths
   from Windows Explorer).
3. In the .NET summary report, part **1 Project**: **Modules** lists the seven modules with
   their type (desktop, web, batch); **Technology stack** shows the end-of-life rows. Part
   **2** lists the tables (CUSTOMER, INVOICE, STOCK_ITEM...) and the five databases
   connected (SQL Server, Oracle and an Access file). Part **3** shows the ledger HTTP calls,
   the pricing SOAP service, the file shares, the MSMQ queue and the mail server. Part **4**
   lists the scheduled tasks and the Windows service.
4. Run `audit: 4. scan a sample` with `desktop-dotnet-app`, then `audit: 7. build and open the
   technical report` with the same sample. **Screens** lists the desktop windows by
   technology; **Findings** shows the passwords found in connection strings (values masked),
   the ActiveX controls, Windows API calls (`Declare ... Lib`), `CreateObject`, `On Error Resume
   Next` and Windows drive and share paths.
5. **On your own VB6 or .NET application**: point `audit.yaml` at the folder that holds the
   `.vbp`, `.sln`, `.csproj` or `.vbproj` files (the GUI's **1. Project setup**), scan, and
   check that `bin/`, `obj/` and `packages/` do not appear in the lines of code and that each
   form or window is a screen. For dependency vulnerabilities, OSV-Scanner reads
   `packages.lock.json` on its own (recent versions also read `.csproj` and `packages.config`).

## AI enrichment for desktop, .NET, Angular, Node.js and servers

Same on Windows and WSL2. On WSL2 use `python3` where a step says `python`, and `.venv/bin/python`
where Windows uses `.venv\Scripts\python`. No new package to install.

1. Run `audit: 2. tests (pytest)`: everything passes, including
   `tests/test_enrich_new_stacks.py`.
2. Run `audit: 29. AI enrichment for desktop, .NET, Angular and servers (demo)`. It scans the
   two samples and prints, for each, what the AI is given and what it writes back:
   - **Fact files each stage reads**: `architecture` and `flows` now read `entry_points` and
     `web_services`; `dependency_plan` and `risks` read `tech_stack`.
   - **Batches and the code the AI reads for each**: `close-invoices` (Hangfire) shows
     `billing-api-cs/Jobs/InvoiceJobs.cs`, `NightlyExport` (Windows Task Scheduler) shows
     `nightly-export-cs/Program.cs`, and the Node.js jobs show their `.ts` files. Before this
     change these jobs had no file at all.
   - **Screens whose own code the AI reads**: the VB6 forms, the Windows Forms code files
     (`MainForm.vb`, not the designer file), the WPF code-behind (`.xaml.cs`) and the ASP.NET
     pages.
   - **Web service operations whose code the AI reads**: each REST and SOAP operation with its
     file and line.
   - **Demo enrichment ... validation: passed**, then **Component kinds (AI)**: the VB6,
     WinForms and WPF programs are `desktop`, `billing-api-cs` is `api`; in the Angular sample
     the two front ends are `frontend` and the Node.js services `api`.
   - **Technology stack, what replaces it (AI)**: Visual Basic 6, .NET Framework, Windows Forms
     and WPF with their target; for the Angular sample WebLogic, WebSphere, Nginx, Node.js and
     AngularJS.
   - **Servers and runtimes, target (AI)** (Angular sample).
   Then both summary reports open (on WSL2, if they do not open, open the printed paths from
   Windows Explorer).
3. In the .NET summary report, part **1 Project**: the **Technology stack** table has a last
   column **Target (AI)**. Part **4 Batches**: `close-invoices`, `NightlyExport` and
   `invoice-mailer-svc` have a "What it does" text marked (AI), and there is no
   "nightly-export-cs: not started by any job" row any more.
4. In the Angular summary report, part **1 Project**: the **Application and web servers**
   table shows the AI's target, marked (AI), for WebLogic, WebSphere, Nginx and Node.js; Tomcat,
   Liberty, JBoss and Apache keep the default target from `rules/target-mapping.yaml`.
5. Run `audit: 4. scan a sample` with `angular-node-app`, then `audit: 7. build and open the
   technical report` with the same sample: on **Overview**, the servers table's **Target**
   column shows the AI's target with a "Today:" line under it.
6. Vulnerabilities: part **5** shows an **Upgrade plan (AI)** column next to each vulnerable
   library once the `dependency_plan` stage is written and a scanner has run. The demo samples
   have no scanner results, so check it on the Billing sample: run task 25, then ask Copilot
   `/audit-enrich dependency_plan` (or run `legacy-audit enrich --stage dependency_plan` with a
   model set in `audit.yaml`), validate, and build the summary report again.
7. **On your own application**: after scanning, run `/audit-enrich stale` in Copilot (or task
   20 with another model). Stages written before this change (architecture, flows, dependency
   plan, risks, target architecture, component docs) show as stale once, because they now read
   more facts. When they are redone, check that each desktop window and each Hangfire, Windows
   task or node-cron job has a story that says what it does, and that each server and runtime
   has a target marked (AI).

## Line numbers in files with old line endings or page breaks

Same on Windows and WSL2.

1. Run `audit: 2. tests (pytest)`: everything passes, including `tests/test_line_numbers.py`.
2. **On the application where `legacy-audit validate` said "line N is outside '<file>'"**: pull,
   scan again (the first scan after the update re-reads every file, so it takes as long as a
   first scan), then run `legacy-audit validate` or ask Copilot to carry on with
   `/audit-enrich stale`. The error is gone, and the evidence link of that web service or
   integration opens on the line that holds the address.
3. Optional: if the file is part of a third-party product kept in the source tree (a tool's
   examples or documentation), add its folder to `exclude:` in `audit.yaml` (for example
   `- "files/logiciel/**"`) so its addresses are not reported as integrations.

## Batch count with no scheduler input (Copilot)

Same on Windows and WSL2 (`python3` on WSL2).

1. Run `audit: 2. tests (pytest)`: everything passes, including
   `tests/test_mcp.py::test_batch_jobs_query_names_the_unscheduled_batches`.
2. Run `audit: 4. scan a sample` with `legacy-layout-app`, then in the terminal, from
   `samples/legacy-layout-app`: `legacy-audit facts batch_jobs` (Windows:
   `..\..\.venv\Scripts\legacy-audit facts batch_jobs`). `rows` is empty, and under it a `note`
   explains that the summary's total (2) counts the batch components `billing-batch` and
   `settlement-c`, listed with their `component:` ids.
3. **In your audit folder**: after pulling, run `legacy-audit init` once (without `--force`; it
   keeps `audit.yaml`) so Copilot gets the updated agent rules, then `/audit-enrich stale`.
   Copilot documents each unscheduled batch with its component id instead of stopping.

## Migration target: Spring Boot, WildFly on GKE, or audit only

Same on Windows and WSL2. On WSL2 use `python3` where a step says `python`, and `.venv/bin/python`
where Windows uses `.venv\Scripts\python`. No new package to install.

1. Run `audit: 2. tests (pytest)`: everything passes, including `tests/test_targets.py`.
2. Run `audit: 30. Migration targets: Spring Boot, WildFly on GKE, audit only (demo)`. It scans
   `struts-oracle-app` and `angular-node-app` three times each, once per target, without
   touching their `audit.yaml` (the runs go to `audit-output/targets/<target>/`). For each target
   it prints:
   - the target, for example **WildFly 41 (Jakarta EE 11, Java 25) on GKE, database on
     PostgreSQL**;
   - **AI stages to write**: 14 of 14, or 9 of 14 for `none` (no service mapping, data
     migration, dependency plan, target architecture or plan);
   - **PostgreSQL grades** and **Effort estimate**: present for the two targets, "no target" for
     `none`;
   - **Servers and runtimes, what each becomes** (Angular sample): a Spring Boot jar on GKE or
     Cloud Run for the default, a WildFly 41 container on GKE for `wildfly-gke`, "(no target)"
     for `none`;
   - **Library advice**: for the Struts sample, Struts 1 goes to Spring MVC by default; with
     WildFly it says Struts 7, or the Eclipse Transformer to keep the Struts 1 code; Oracle's
     driver goes to PostgreSQL for both targets and to a supported Oracle driver for `none`;
   - one **server finding** and what to do about it, worded for the target.
   Then the six summary reports open (on WSL2, if they do not open, open the printed paths from
   Windows Explorer).
3. In each summary report, part **1 Project** says "Migration target: ..." and the header shows
   the target. For `none`: the servers table has no **Target** column, part **2 Database** has no
   PostgreSQL chart, tile or Auto/Review/Manual columns.
4. **On your own application**: open `audit.yaml` (or the GUI's **Settings** page, field
   **Migration target**) and set
   ```yaml
   target:
     profile: wildfly-gke        # or spring-boot-angular (default), or none
   ```
   Scan again (task 3 or the GUI's **Scan** button; files are not re-read, only the advice
   changes), then build the reports. The technical report's header and **Appendix** name the
   target; with `none` the **PostgreSQL** page is gone and the management deck has no target or
   plan slides.
5. **Copilot**: run `legacy-audit init` once in the audit folder (without `--force`) so Copilot
   gets the new agent rules, then `/audit-enrich stale`. After a change of target only the
   stages that suggest something are stale (service mapping, data migration, dependency plan,
   risks, target architecture, plan). With `wildfly-gke`, `legacy-audit validate` warns if the
   AI still writes Spring Boot, Cloud Run or Angular 21 in those stages. With `none`, Copilot
   skips the five target stages.

## Maven modules and batch folders

The new sample `samples/maven-multimodule-app` is a Maven parent with modules (one only in a
profile), a nested parent of two batch modules started by ksh scripts, a shared `config/`, and
an `ops/batch/` folder of three plain-folder batches sharing `common/`. Steps are the same on
Windows and WSL2: VS Code picks the right command (WSL2 uses `.venv/bin/python`, set up with
`python3`).

1. Pull the branch, then run `audit: 1. set up (venv + install)` once (nothing new to install)
   and `audit: 2. tests (pytest)`: everything passes, including `tests/test_components.py`,
   `tests/test_samples.py::test_sample_counts[maven-multimodule-app]` and
   `tests/test_functional.py::test_one_batch_per_entry_point`.
2. Run **`audit: 31. show Maven modules and batches (multi-module sample)`**. Check in the
   terminal:
   - 6 components since the grouping (section "Components and integrations grouped" below;
     12 with `inventory.component_grouping: module`); `acme-parent` and `acme-batches` are
     not among them (they are groups);
   - the module tree shows `acme-parent (group)` with `acme-core [jar]`, `acme-ear [ear]`,
     `acme-tools [jar]`, `acme-web [war] -> web`, and `acme-batches (group)` with
     `billing-batch` and `report-batch`;
   - `ops/batch` is split into `archive`, `eod`, `purge` (batch) and `batch` (type script: the
     shared `common/functions.ksh` and `env.properties`);
   - `Batch jobs: 6 = 0 scheduled + 6 with no schedule found`, listing `scripts` (2:
     `run_billing.ksh`, `run_report.ksh`), `eod` (2), `purge` (1) and `archive` (1).
     `billing-batch` and `report-batch` are not counted: the scripts start them.
3. Run `audit: 7. build and open the technical report` and pick `maven-multimodule-app`. On
   **Components**, the "Maven group" column shows `acme-parent > acme-batches` for the batch
   modules and the packaging (`jar`, `war`, `ear`); on **Batch** there are six "no schedule
   found" flows, `eod: run_eod.ksh` and `eod: run_eod_report.ksh` among them.
4. Optional, the GUI: run `audit: 13. start the GUI on a sample` with `maven-multimodule-app`
   and open **Components**: the Maven group column is there before any scan.
5. **In your own audit folder**: pull, run `legacy-audit scan` (components are always
   recomputed, nothing to clear), then `legacy-audit init` once (keeps `audit.yaml`) so
   Copilot gets the updated batch rules, and `/audit-enrich stale`: component names of Maven
   parents disappear and batches with several entry points are documented one by one.

## PDF copies of the reports

Needs Microsoft Edge or Google Chrome (Edge is on every Windows laptop). After pulling, run
`pip install -e .` once (task `audit: 1.` does it): the new package `pypdf` joins the pages.

1. Run `audit: 2. tests (pytest)`: everything passes, including `tests/test_pdf.py`. Its last
   test prints real PDFs with the browser it finds (skipped, not failed, when there is none).
2. Run `audit: 32. PDF copies of the reports (sample app)` and pick `struts-oracle-app`. The
   terminal names the browser (on Windows: `Microsoft Edge at C:\Program Files (x86)\...`)
   and lists four PDFs with their pages and paper size: summary, technical and functional on
   A4 (210 x 297 mm), the management deck on wide 16:9 pages. The summary PDF opens.
3. Check the PDFs (in File Explorer, `audit-output\latest\reports\pdf\`):
   - `summary.pdf`: page 1 is a cover with **Contents**, then the five parts with their charts
     drawn, no buttons or search boxes,
     "Page 2 of N" at the bottom right.
   - `summary.pdf` and `technical.pdf`: click a line of the **Contents** on page 1; the PDF
     jumps to that page. In the technical PDF, click a component name on the Components page: it
     jumps to that component's page.
   - `technical.pdf`: page 1 is a cover with **Contents** and page numbers; the bookmarks panel
     lists Overview, Components (with each component under it), Architecture, Flows (with each
     flow), ... Appendix; diagrams fit the page width.
   - `management.pdf`: one slide per page, no slider controls.
4. In the app (`audit: 3.` or `legacy-audit serve`), **8. Reports > Build PDFs**: when it is
   done, the four *(PDF)* lines have an **Open** button that shows the PDF in the browser.
5. Run `legacy-audit doctor`: the line **Browser for PDFs** names the browser that will be used.
6. No browser: set `pdf: browser_path: C:\nowhere\msedge.exe` in the sample's `audit.yaml`
   and run `legacy-audit report --type all`: every other report is built, and a yellow line
   says "PDF skipped: the browser in pdf.browser_path was not found ...". Undo the change.

**WSL2** (`python3`, tasks run the Linux commands): step 2 uses a Chrome or Chromium installed
in Ubuntu when there is one, otherwise the Windows Edge (`/mnt/c/Program Files (x86)/Microsoft/
Edge/Application/msedge.exe`, "started from WSL"). If the Windows Edge writes no PDF, install
Chromium in Ubuntu (`sudo apt install chromium-browser`) or set `pdf.browser_path` to a Linux
browser, and run the task again. Open the PDFs from the app (step 4) or copy them to Windows:
`cp samples/struts-oracle-app/audit-output/latest/reports/pdf/*.pdf /mnt/c/Users/<you>/Desktop/`.

## Copilot agent and prompts show in Copilot Chat

Files installed by an older `legacy-audit init` had a header that current VS Code builds could
not read, so the **legacy-auditor** agent and the `/audit-*` prompts were missing (decision 173).

1. Run `audit: 2. tests (pytest)`: everything passes, including
   `test_front_matter_is_valid_yaml` in `tests/test_copilot.py`.
2. In your audit folder (the one with `audit.yaml`), open a terminal and run
   `legacy-audit init` (Windows: in the activated venv; WSL2: the same command). It prints
   `updated .github/agents/legacy-auditor.agent.md` and `updated .github/prompts/...`.
3. Open `.github/prompts/audit-enrich.prompt.md`: the header reads
   `description: "Enrich the scan facts: all stages, stale stages, or one stage"` (in quotes)
   and shows no red or yellow mark.
4. Open that same audit folder in VS Code (File > Open Folder; on WSL2 `code .` from the
   folder), then Ctrl+Shift+P > **Developer: Reload Window**.
5. In Copilot Chat pick **Agent**, type `/audit`: `/audit-scan`, `/audit-enrich`,
   `/audit-report` and `/audit-qa` are listed. The agent list has **legacy-auditor**.

## Components and integrations grouped

Components are grouped per deployable when there is a `pom.xml` (a library module goes into the
war, ear or batch jar that uses it) and by technology otherwise; integrations are one row per
external service (decisions 176 and 177). Steps are the same on Windows and WSL2: VS Code picks
the right command (WSL2 uses `.venv/bin/python`, set up with `python3`).

1. Pull the branch, then run `audit: 2. tests (pytest)`: everything passes, including
   `tests/test_components.py` (the `grouping` tests) and `tests/test_samples.py`.
2. Run **`audit: 33. show component and integration grouping (sample app)`** and pick
   `maven-multimodule-app`. Check in the terminal:
   - `Components: 6 (one per module and folder would give 12)`;
   - `acme-web` is made of `acme-web, acme-core` (acme-web's pom depends on acme-core);
     `acme-tools` stays on its own (nothing uses it);
   - `acme-parent-scripts-config` is made of `config, ops/batch, ops/batch/archive,
     ops/batch/eod, ops/batch/purge, scripts`.
3. Run task 33 again with `cobol-cics-db2`: 3 components, `cobol-online` (bms, cobol,
   copybook), `jcl-jobs` (jcl, proclib) and `ddl`. With `legacy-layout-app`: 4 components,
   `config` and `scripts` are one `shared-scripts-config`.
4. Run task 33 with `spring-mvc-batch-app`: under Integrations, `MQ-QMGR
   QMBILL@mqbill01.acme.local:1414 [both, 7 usage(s)]` lists the queues `BILLING.INVOICE.PAID`
   and `PAYMENTS.IN` and the channels `BILLING.SVRCONN` and `QMBILL.QMCRM` (5 rows before).
   With `web-services-app`: `HTTP https://stock.acme.local` lists `/api/v2/items` and
   `/api/v2/reservations`. With `desktop-dotnet-app`: `https://ledger.acme.local` holds the
   http and https calls, `//fileserver` its two files, and "Web services called" has one
   `ledger.acme.local` row.
5. Run `audit: 24. build and open the summary report (HTML and PowerPoint)` with `spring-mvc-batch-app`: the Integrations table
   has an "Endpoints / queues / files" column. Run `audit: 7. build and open the technical
   report` with `maven-multimodule-app`: the Components page has a "Folders" column and the
   `acme-web` page says "grouped from 2 folders".
6. The old lists are one setting away: in a sample's `audit.yaml` set
   `inventory: component_grouping: module` and `integrations: group_by_service: false`, run
   task 33 again (12 components, one row per address), then undo the change.
7. **In your own audit folder**: pull, run `legacy-audit init` once (the Copilot prompt now
   explains grouped components and services), `legacy-audit scan`, then `/audit-enrich stale`.
   Enrichment written before still shows: a story written for one endpoint or queue is used
   for the row it now belongs to; component pages of folders that were grouped need the rerun.

## A failing step does not stop the scan

The scan no longer fails with `UNIQUE constraint failed: web_services.id` (two SOAP services
called on one host), and a step that goes wrong now gives a warning instead of a failed run
(decision 178). Steps are the same on Windows and WSL2.

1. Pull the branch, then run `audit: 2. tests (pytest)`: everything passes.
2. Run **`audit: 34. scan error handling tests`**. Three tests pass: two SOAP services on one
   host keep their own ids, a failing step leaves a warning and the run completes, and a
   duplicate id is left out with a warning.
3. **In your own audit folder** (the one that failed): run `legacy-audit scan` again, or
   **Run scan** in the GUI. The run finishes; the Web services page lists each SOAP service.
4. If a step still goes wrong, the run finishes anyway and the warnings list (terminal, or the
   run page in the GUI) has a line starting with the step's name and "could not be built".
   Send that line and the run log (`audit-output/runs/<run-id>/logs/`).

## Hosted site (Docker, login, admin page)

The tool now also runs as one shared web site: log in, open or create a project under the
audits folder, scan, follow the scan on Status, read Logs, and set everything on the admin page
(decisions 180 to 186). Steps are the same on Windows and WSL2 unless a step says otherwise.

1. Pull the branch, run `audit: 1. set up` once (new files only, no new packages), then
   `audit: 2. tests (pytest)`: everything passes. **`audit: 38. hosted site tests`** runs the
   hosted ones alone (13 tests: settings, login, defaults and locks, queue and time left, roles,
   report switches, status and logs, an audits folder that cannot be written, the dashboard numbers, Git clone clean-up).
2. **Without Docker first**: run **`audit: 37. run the hosted site without Docker (samples)`**.
   The terminal says `legacy-audit hosted site for ...audits-hosted at http://127.0.0.1:8765/`
   and a yellow "Test login" line. Open http://127.0.0.1:8765 (on WSL2, in your Windows
   browser).
3. Log in as **siva**. On **Projects** fill in **Create a project**: name `Orders`, then in the
   folder box click `samples`, `struts-oracle-app`, `orders-web`. Click **Create project**: the
   project opens on **3. Components** with **← All projects** and `siva (owner)` at the top.
4. **4. Run > Re-run**. Open http://127.0.0.1:8765/#status in a second tab: the scan shows
   `running` with its step, a progress bar and the time left, then moves to **Recently
   finished** with `run <id>`. Run **Re-run** again: the time left now says "(last run)".
5. **Logs**: pick `Orders: run ... (scan.log)`, choose **Warnings and errors**: one orange
   "Vulnerability check not done" line. **Download** saves the file.
6. **8. Reports > Build all**: the reports open from the table. There is no Open run folder
   button (the files are on the server).
7. **Log out**, log in as **admin**. **Admin** is now in the menu:
   - **Dashboard** opens first: the tiles show 1 project and the scans you ran, **Scans per
     day** has a green bar for today, **Busiest projects** shows Orders. Under **Server**,
     memory, CPU and disk have values, and the memory and CPU charts grow a point every 10
     seconds. Switch **Theme**: the charts redraw in the other colours. **Show as a table**
     under a chart lists its numbers; **Download CSV** saves `legacy-audit-usage-...csv`.
   - **Reports**: untick **Technical report (HTML)** and **Save**. Log in as siva again: on
     8. Reports the technical report and its PDF row are gone, and its build button too.
   - **Project defaults**: set **Migration target** to **None (audit only)**, tick **lock**,
     **Save**. In the Orders project, **1. Project setup** still shows the project's own file;
     the next scan and reports use target none (no target columns).
   - **Users and roles**: siva is listed with role owner; change a role, add a user, untick
     sign-up and check a new name can no longer log in.
   - **Limits**: set **Jobs at once** to 1, start a scan on two projects quickly: on Status the
     second shows `queued, place 1` and "after the jobs ahead", then runs.
   - **Activity** lists the logins, the project creation, scans and settings changes;
     **Health** shows disk space, space per project and the tool checks.
   - **Limits** shows **Keep Git clones (days)** 14 and **Keep this much disk free (GB)** 5.
     **Health** ends with **Git clones** ("No clones yet." until a Git project is scanned).
   - **Git project** (only if you have an https Git repository you may clone): create a project
     with **A Git repository**, its address and branch `main`, and **Re-run**. **Health > Git
     clones** shows one row with the branch and the commit. On **4. Run**, type another branch
     in **Branch or tag** and **Re-run**: the Runs table shows both branches with their commits,
     and Health two clones. **Delete** on one row removes it.
8. Stop task 37 (trash can on the terminal). Its projects are in `audits-hosted/` (not committed).
9. **With Docker** (Docker Desktop on Windows, or Docker Engine in WSL2): copy
   `docker/env.example` to `.env`, set `LEGACY_AUDIT_SESSION_SECRET`, `AUDITS_DIR` (create the
   folder first, e.g. `C:/legacy-audit/audits`) and `SOURCES_DIR` (a code folder, e.g.
   `C:/code`; forward slashes). On WSL2 with Docker Engine also set `AUDIT_UID` and `AUDIT_GID`
   to the output of `id -u` and `id -g`. Run **`audit: 36. run the hosted site (Docker)`**:
   after the build, `docker compose ps` shows the container `healthy`. Open
   http://localhost:8765, log in as **admin**, and repeat steps 3 to 7 (the folders start at
   `/sources`). **Admin > Health** shows Chromium, Mermaid CLI and OSV-Scanner found.
10. **Data outside the container**: open your `AUDITS_DIR` folder in Explorer: it has a folder for the
    Orders project and `.legacy-audit`. Run `docker compose down`, then task 36 again:
    the project is still on **Projects**.
11. **At the office**, if the build stops: run **`audit: 39. build the Docker image and save
    build.log`**. The end of the output shows a box "legacy-audit build stopped: ..." with what
    failed. Follow [docs/hosting.md, Building at the office](hosting.md#6-building-at-the-office):
    `BUILD_PROXY` and `RUN_PROXY` in `.env`, the company certificate in `docker/certs/`, the
    company mirrors, or `WITH_SYSTEM_PACKAGES=false` to get running now. Send `build.log` if it
    still fails.

## Development history, one line per pull request

1. Open `docs/history.md` (**Ctrl+Shift+V** for the preview): one dated line for every pull
   request from #1, plus the plan and the presentation templates.
2. To see the same lines as commits, open **Terminal > New Terminal** (on WSL2 the Ubuntu
   terminal in `~/code-audit`) and run:

   ```bash
   git fetch origin office-history
   git log --oneline origin/office-history
   ```

   It prints one commit per line of `docs/history.md`, newest first. The last commit there has
   exactly the same files as `office-main` (`git diff origin/office-history origin/office-main`
   prints nothing once this change is merged).
