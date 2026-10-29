# Progress

Update this file at the end of every phase, and whenever a session stops mid-phase.

| Phase | Status | Notes |
| --- | --- | --- |
| 0 – Planning | Done | `docs/plan.md`, `docs/decisions.md`, prompts written 2026-10-10 |
| 1 – Foundations | Done | Package, config, local + git sources, inventory, components, coverage, runs, file cache, overrides, diff, summary, CLI, fact model + schemas, `struts-oracle-app` inventory sample (2026-10-10) |
| 2 – Core analyzers | Done | 15 analyzers (build, deps, config, java, web, spring, sql, shell, c_cpp, proc, cobol/BMS, jcl, autosys, mq, cft), linker (edges, merges, Maven versions, screen/partial and COBOL sub-program rules, service reach, component classification), headline counts, analyzer coverage in `run.json`, 3 new samples with `expected.yaml`, re-run and 200k LOC stress tests (2026-10-10) |
| 3 – Rules, metrics, dependency security | Done | Code units and cyclomatic complexity, duplication, fan-in/out, dead-code candidates, test ratio, complexity bands; rule engine with 4 YAML packs (19 risk rules, PostgreSQL constructs, dependency rules, EOL) and config switches; PostgreSQL grading with sqlglot; Dependency-Check/OSV import or run, EOL, upgrade advice, CycloneDX SBOM, optional jdeps/jdeprscan; effort model; 4 samples extended (2026-10-10). Added after review: dynamic SQL rebuilt in Java, C/ODBC/OCI, Pro*C and COBOL with holes, variants and grading; VS Code tasks and `docs/validate-in-vscode.md` (2026-10-10) |
| 4 – Technical HTML report | Done | `legacy-audit report --type tech [--open]`: static offline site per run (overview, component pages, architecture/integration/batch Mermaid diagrams, 8 catalogues with search/filter/sort/paging, CRUD matrix, PostgreSQL readiness, dependency security + SBOM download, findings, appendix), evidence links to files and VS Code, light/dark; vendored Chart.js and Mermaid (2026-10-10) |
| 5 – Copilot agent, enrichment, management outputs | Done | `init` installs the legacy-auditor agent, instructions, 4 prompt files, enrichment schemas and `.vscode/mcp.json` (keeps user edits); 12 enrichment schemas; `legacy-audit validate` (schema, references, figures vs scan, evidence, prose counts) with stamping, staleness and carry-over; read-only MCP server and `facts` command; `deck.json`, HTML management deck (26 slides) and editable PPTX with native charts; Analysis page in the technical report (2026-10-10) |
| 6 – GUI and hardening | Done | `legacy-audit serve` (local FastAPI GUI, 9 screens, background jobs with live progress and cancel), `legacy-audit doctor`; files analysed and SQL graded in parallel processes with a grade cache; scan lock, cleanup of interrupted runs, findings for too-large/unreadable files, long Windows paths; faster linking; user, admin and rules guides, performance results, changelog (2026-10-10) |
| 7 – Functional documentation and graphics | Done | Enrichment stages 12 (functional) and 13 (component documentation); screen and batch flows drawn from the scan; functional document (`report --type functional`); colours, gauges, radar, bubble, heat maps, Gantt and coloured flows in the technical report; section dividers, gauges, capability map, flow slides, batch calendar, layers, heat maps and roadmap in the HTML deck and native in the PPTX (2026-10-10) |

## Phase 3 result

Acceptance criteria (from `docs/prompts/phase-3-rules-metrics-security.md`), all checked by tests:

1. Each sample's PostgreSQL grades, finding counts by rule and vulnerable-library counts (from the
   recorded reports in `audit-inputs/security/`) match `expected.yaml` (`tests/test_samples.py`).
2. Dependency-Check missing from PATH gives a coverage row `dependency-check: missing` and no crash
   (`tests/test_security.py::test_missing_dependency_check_is_a_coverage_gap`).
3. Changing a weight re-bands and re-prices on re-run with 0 files re-analysed
   (`tests/test_metrics.py::test_changing_weights_rebands_without_reanalysis`); disabling or
   re-grading a rule works the same way (`tests/test_rules.py`).
4. `sbom.cdx.json` validates against the vendored CycloneDX 1.5 schema (`tests/test_security.py`).

Added after the first review (Siva, 2026-10-10): SQL built at run time is rebuilt in Java
(builders, `+=`, `String.format`, ternaries), C/C++ (`strcpy`/`strcat`/`sprintf` buffers; ODBC,
DB2 CLI, OCI, libpq, MySQL, SQLite calls), Pro*C (`PREPARE`/`EXECUTE IMMEDIATE` from a buffer) and
COBOL (`VALUE`/`MOVE`/`STRING` into a host variable), with fragments, optional parts, value and
structural holes and variants (`tests/analyzers/test_dynamic_sql.py`, decisions 55–60). Rules
`sql-concatenation` (value holes) and `sql-runtime-structure` (structural holes); grading per
decision 58. VS Code validation steps are in `docs/validate-in-vscode.md`, with tasks in
`.vscode/` (decision 61).

New outputs in `data/`: `findings.*`, `metrics.*` (bands, duplication, test ratio, dead code,
fan-in/out), graded `sql_statements.*` (`pg_grade`, `reason`, `pg_text`), `libraries.*` (CVEs,
EOL, recommendation), `sbom.cdx.json`, `effort.json`, `target-mapping.json`; `run.json` gains
`vulnerability_sources` (status, database date); `audit-summary.json` gains phase 3 counts, top
findings, risky libraries, component metrics and the effort range.

## Phase 4 result

Acceptance criteria (from `docs/prompts/phase-4-tech-report.md`), all checked by `tests/test_report.py`:

1. The report builds for every sample; every page passes a basic HTML check (doctype, every
   element closed in order) and has no `http(s)://` script, stylesheet or image reference; every
   local reference exists (`test_report_builds_valid_offline_html`).
2. Every KPI tile on the overview equals its value in `audit-summary.json`
   (`test_overview_kpis_equal_the_summary`).
3. A synthetic 20k-row SQL catalogue stays out of the page (rows paged in by `report.js` from
   `data/sql.js`; 25k rows load in about 0.5 s in Chromium) (`test_large_catalogue_stays_out_of_the_page`).
4. A new scan plus `report` builds from `latest` into the new run; the older run's report is
   untouched; a `latest` copy (no symlinks) gets its own build with working links
   (`test_rerun_keeps_older_reports`, `test_latest_copy_gets_its_own_build`).

Checked by eye in Chromium (light and dark): overview, components, architecture, SQL, security,
findings, appendix pages of the samples; no console errors and no network requests.
How to validate in VS Code: `docs/validate-in-vscode.md` § Phase 4.

## Phase 5 result

Acceptance criteria (from `docs/prompts/phase-5-agent-enrichment-mgmt.md`), all checked by tests:

1. `init` on a sample installs the Copilot files; re-running `init` keeps user edits and writes a
   `.new` copy when the template changed (`tests/test_copilot.py`).
2. Hand-written enrichment for `struts-oracle-app` (`tests/fixtures/enrichment/`) validates; a
   wrong count, missing or out-of-range evidence, an unknown id or component fails with a message
   naming the field (`tests/test_enrich.py`).
3. Adding Autosys input and re-scanning `spring-mvc-batch-app` marks flows, risks, plan and
   narratives stale but not classification; an unchanged re-scan marks nothing; enrichment
   written into a copied `latest` survives (`tests/test_enrich.py`).
4. `report --type mgmt` and `--type pptx` build for the sample with fixtures; the PPTX opens with
   python-pptx, has one slide per `deck.json` slide, native bar, doughnut and line charts, native
   tables and no chart pictures; the HTML deck is offline and its KPIs equal the summary for every
   sample; missing enrichment gives placeholders (`tests/test_mgmt.py`).
5. The MCP server answers each tool in-process and over real stdio (`tests/test_mcp.py`).

Checked by eye: HTML deck in Chromium (no console errors, no network); the PPTX rendered through
LibreOffice. The Copilot files were not run inside VS Code from the build environment (see
`docs/copilot-agent.md` § Versions). How to validate in VS Code: `docs/validate-in-vscode.md`
§ Phase 5.

## Phase 6 result

Acceptance criteria (from `docs/prompts/phase-6-gui-hardening.md`):

1. From a clean environment with only Python, the GUI's API inits a workspace, scans, shows the
   dashboard and builds the technical report (`tests/test_gui.py::test_clean_workspace_init_scan_dashboard_report`).
   Also checked by hand: the built wheel installed into a fresh venv, `legacy-audit serve` driven
   end to end over HTTP on a copy of the C/Pro*C sample.
2. Adding a JIL from Inputs and re-running updates the dashboard (batch jobs up, Autosys present)
   and marks flows, risks, plan and narratives stale; compare runs lists the new jobs
   (`test_inputs_upload_rerun_marks_enrichment_stale`).
3. `doctor` reports Node and Dependency-Check as missing, optional, with what each adds, and still
   says the machine can scan (`tests/test_hardening.py::test_doctor_without_node_or_dependency_check`).
4. Performance on a generated 2M-line estate meets the 15-minute target; see
   [performance.md](performance.md).

Hardening tests (`tests/test_hardening.py`): ordered parallel map and cancel, parallel scan equals
serial, cancel removes the unfinished run, interrupted runs are cleaned up, one scan at a time,
too-large and unreadable files, symlink loop, read-only source tree, `\\?\` paths and paths over
260 characters (the last one runs on Windows CI only). GUI safety (`tests/test_gui.py`): foreign
hosts and cross-site writes refused, path traversal refused, secrets masked in the source viewer
and data pack.

Checked by eye in Chromium: all 9 screens, light and dark and at phone width, with no console
errors and no requests leaving `127.0.0.1`. How to validate in VS Code:
`docs/validate-in-vscode.md` § Phase 6.

## Phase 7 result

Asked for by Siva: functional documentation for scanned applications, a functional flow for each
batch and for each screen (with its services and tables), a more colourful technical document and
more graphics in the deck and PPTX.

Acceptance criteria (from `docs/prompts/phase-7-functional-docs-graphics.md`), all in
`tests/test_functional.py` unless noted:

1. Screen and batch flows match the facts: `orderDetail.jsp` is loaded by `/orderDetail`, opens
   `/orderSearch` and reads ORDERS; `BIL_NIGHTLY_BOX` runs BIL_USAGE_WAIT, BIL_INVOICE and
   BIL_ARCHIVE in order at 01:30 on weekdays, retries BIL_INVOICE once and archives whatever
   happens; `invoiceJob` is started by BIL_INVOICE through `run_invoice_job.sh`.
2. The functional document builds offline and is valid HTML on all four samples, with placeholders
   naming `/audit-enrich functional` when there is no enrichment.
3. Stage 12 and 13 fixtures validate (14 files, `tests/test_enrich.py`); a wrong screen id fails.
4. The PPTX holds native charts (one doughnut per gauge) and native tables (heat maps included),
   no chart pictures (`tests/test_mgmt.py`).

Checked by eye in Chromium (light and dark) and in LibreOffice for the PPTX, with no console errors
and no network requests. How to validate in VS Code: `docs/validate-in-vscode.md` § Phase 7.

## Next step

All seven phases are done. Next: validate on a real estate and on the team's Windows laptops (record
laptop timings in `docs/performance.md`), calibrate the complexity and effort weights, and run the
Copilot agent in VS Code once.

## Known gaps

- Struts 2, JSF and Spring Web Flow are recognised (tech stack, actions/beans) but with fewer
  facts than Struts 1 and Spring MVC; Gradle is regex-only.
- Spring Data derived queries are recorded by method name; their SQL is not derived.
- C/C++: calls through function pointers and macros are not followed; `#ifdef` branches are all
  read. COBOL: `CALL` through a variable is resolved only from a `VALUE` or `MOVE` literal.
- Shell: commands built from variables (`$BIN/prog`) are linked when a file with that name exists,
  never reported as unresolved (decision 34); `eval` and functions are not followed.
- Service reach stops at 4 hops (decision 33).
- LOC approximations (see decision 20): comment markers inside strings, Java in JSP scriptlets.
- Re-including a file inside a default-excluded folder needs the whole folder re-included
  (`files.include: ["build/"]`), as in `.gitignore`.
- `purge-cache` removes the whole Git clone cache; there is no per-repo purge yet.
- Python 3.11 and Windows are covered by CI only (local runs were Linux, Python 3.13).
- Performance was measured on the 4-CPU build container with a generated estate
  (`docs/performance.md`); laptop timings and a real estate are still to be measured. A 2M-line
  run writes about 2.6 GB of output, most of it fact files in three formats.
- GUI: one analyst per laptop; no login, no multi-user locking beyond one scan at a time. The
  pre-scan on the Components screen uses file names only, so its type can differ from the
  scan's (the latest scan's type is shown when there is one).
- Vulnerabilities come only from Dependency-Check / OSV-Scanner results (imported or run); the
  tool does not download or sync a database itself. Transitive dependencies that are not in the
  inventory are counted as unmatched report entries (logged), not listed.
- EOL and upgrade targets are rule data (`rules/eol.yaml`, `rules/dependency-upgrades.yaml`) and
  need a periodic review; `verify: true` marks targets to confirm.
- Complexity and effort weights are first defaults, not calibrated on a real estate yet.
- Dead-code candidates do not see reflection, Spring XML beans by class name in non-scanned files,
  or callers outside the scanned estate; the agent confirms them.
- Undeclared jars without a group get no package URL, so OSV results (by package) cannot match
  them; Dependency-Check matches them by SHA-1 or file name.
- Dynamic SQL is followed within one file and in source order: text filled in another file, by a
  called function's return value, through pointers/aliases, `memcpy`, COBOL `UNSTRING`/`INSPECT`,
  or a C++ `std::string` is a hole (or a `DYNAMIC` statement); at most 5 variants are kept.
- Report: diagrams are capped at 60 nodes (largest components, most used integrations) with a
  note; the full lists are in the catalogues. Licence flags rely on licences in POMs/manifests,
  which most scanned libraries do not carry. Enrichment sections arrive in phase 5.
- Copilot: the agent and prompt files follow the documented VS Code 1.105–1.106 formats and are
  tested for shape only; the first real run in VS Code may need the `tools:` list adjusting.
- Enrichment staleness compares whole fact files per kind, so any change in a kind (even in
  another component) marks the stages that read it stale.
- Flows: a service's tables come from the SQL in the files it reaches, so a DAO shared by several
  screens shows all its tables on each of them (decision 97). Flows of very large boxes are
  readable in HTML (Mermaid) but crowded on a PPTX slide; the deck shows the 3 largest screens and
  batches only.
- PPTX: diagrams are pictures only when Mermaid CLI is installed; long tables are cut to 10 rows
  per slide. (The corporate template is applied since 2026-10-12, see below.)

## Phase 7 follow-up: batches without scheduler inputs (2026-10-12)

Asked for by Siva: the Components page showed batch components, but the Batch tab said "no
batch" when the JIL/JCL files were missing; the AI enrichment should read the batch files and
their comments to explain each batch.

- Done: batch scripts, programs and listeners that no scheduler fact starts get their own flow
  ("no schedule found" or "on message") on the functional Batch page, the technical Flows and
  Batch jobs pages and the deck (decision 102). Each batch page shows "What the code says about
  itself": header comments copied from its source files, credentials masked (decision 103).
  Stage 12 now reads the batch sources and comments and fills `programs`, `controls` and
  `restart` (decision 104). New VS Code task 18 scans a sample without its `audit-inputs`.
- Known gaps: which scripts and programs are "top level" is a heuristic (nothing calls them);
  a script started only from an unscanned scheduler can still show up beside the job that runs
  it once the export arrives (it then becomes a step and its own card disappears). Comments are
  read only when the source tree is on disk at report time.

## Model-agnostic AI enrichment (2026-10-12)

Asked for by Siva: "make it model agnostic so that I can use any model for AI enrichment".

- Done: `legacy-audit enrich` (and the GUI's AI enrichment screen) writes the enrichment with any
  model set in `audit.yaml > ai`: OpenAI-compatible endpoints (OpenAI, Gemini, gateways, LM
  Studio, vLLM), Azure OpenAI, Anthropic and Ollama; `--check` tests the settings, `doctor` shows
  the model. Copilot stays the default and `ai.copilot_model` pins its model. VS Code tasks 19
  and 20. Decisions 105–109.
- Tested with a local fake OpenAI-compatible endpoint (request shapes for every provider, a
  repair round, the CLI and the GUI). Not yet run against real cloud models from this
  environment (no keys here): the first real run should start with `enrich --check`, then one
  stage (`-s overview`).
- Known gaps: a model without tools sees a packed, possibly cut, view of the facts (a note says
  how many rows were left out), so on a large estate Copilot (which queries the facts itself) or
  a large-context model gives better results; small local models may need several repair rounds
  or a smaller `context_chars`. Per-component looping for large estates is not done yet.

## Corporate PowerPoint templates (2026-10-12)

Asked for by Siva: use the three corporate templates added in `templates/`, let people choose one on
the reports page, and keep the PPT strictly on those templates.

- Done: the PPTX is built by Python (`python-pptx`) on the chosen `.potx`: cover, contents,
  chapter pages and the closing page are the template's own layouts, content slides use its
  `TEXT`/`TEXT_DOUBLE` layouts, and every chart, table and shape uses its theme colours and font.
  Node and `pptxgenjs` are no longer needed (Node only for Mermaid pictures). The reports page has
  a template picker with previews (remembered per browser); `report --template blue|orange|purple`;
  `branding.pptx_template` sets the default. VS Code task 8 now builds the PPTX on a chosen
  template. Decisions 110–112.
- Checked: tests build the Struts deck on all three templates (template theme and layouts kept,
  no sample text left, every deck title present, native charts and tables); the slides were
  rendered and reviewed.
- Known gaps: text fitting is estimated (PowerPoint does not shrink text it was not asked to), so a
  very long bullet list can still run past a box; the font is the template's embedded
  Renault Group font, which LibreOffice previews with a substitute.

## Component classification and batch count (2026-10-12)

Asked for by Siva: Eclipse `bin/` folders were scanned, Struts web and batch components were
split by language folder, there was no configuration component, each ksh script was taken as a
batch, and the batch count showed 0 without scheduler inputs.

- Done: build output left out and reported (decision 113, `inventory.skip_build_output`);
  layout-based components with project files (`.project`, `Makefile.*`, ...), part folders and
  containers (decision 114); `config` component type, `Component.roles` and
  `Component.composition` (decision 115), shown as "Also holds" / "Made of" in the technical
  report and in the GUI type list; one batch per batch component, counted in
  `counts.batch_jobs` (`scheduled` + `unscheduled`) and drawn as one "no schedule found" flow
  with its scripts and programs as steps (decision 116). New sample `legacy-layout-app`, VS Code
  task 21, `component_roles`/`unscheduled_batches` in every sample's `expected.yaml`.
- Known gaps: the part-folder and batch-folder name lists are English/Java/Unix centred; a
  mainframe library laid out by type (`cobol/`, `jcl/`, `copybook/` at the top) still gives one
  component per library, as before. Wrong groupings are fixed with `components:` in
  `audit.yaml` or on the GUI's Components screen.
- Next: run task 21 and a scan on a real estate and adjust the name lists if a folder is
  grouped wrongly.

## Lines of code and consolidated integrations (2026-10-20)

Asked for by Siva: the integrations list showed the same system several times, and the lines of
code by language had many "unknown" entries, including binaries such as `.class` files.

- Done: no language is "unknown" any more: about 50 more extensions recognised with their
  comment syntax, anything else named `other (.ext)` (decision 118); one integration per
  interface, directions merged into `both` and spellings of one target folded together
  (decision 119); binaries left out by content as well as by extension (decision 120); backup
  copies and data files listed with no lines (decision 121). `legacy-layout-app` gained a
  SQL*Loader file, a template, a backup, a data file, a binary without NUL bytes and two
  spellings of its SMTP host and database. VS Code task 22 prints lines of code by language and
  the integrations. Inventory version 3: the first scan after upgrading re-reads every file.
- Known gaps: integration ids of merged rows change, so an AI enrichment written before this
  change shows its interface stories as stale until stage 12 is re-run. Two DB URLs to one host
  with and without a database name stay two rows. MQ names are compared case-sensitively (as MQ
  does).
- Next: run task 22 on a real estate; any `other (.ext)` it lists is a candidate for a new
  extension rule in `legacy_audit/inventory/language.py`.

## Integration purposes and consolidated batch list (2026-10-20)

Asked for by Siva in the same thread: Okta integrations should read as login /
authentication, HTTP/HTTPS/FTP integrations need a description (enrichable by Copilot), and
the batches need one consolidated list with a short description and the schedule, also when
no JIL is given.

- Done: `Integration.purpose` and `description` (decision 122), Purpose and Description
  columns in the Integrations catalogue, scan description on the functional Interfaces page;
  `report/batches.py` consolidated batch list with schedule hints from the code (decision
  123), first on the Batch jobs page with `data/batches.csv`, and on the functional Batch
  page; stage 12 prompt asks for interface stories and short batch `purpose`/`when`. VS Code
  task 23 (`scripts/show_batches.py`); `legacy-layout-app` gained an Okta issuer, a REST URL
  and an FTP host.
- Known gaps: identity providers on a custom domain with no okta/sso/auth word in the host,
  path or key are not recognised; schedule hints take the first match only.
- Next: run tasks 22 and 23 on a real estate and check the purposes and hints.


## Summary report (2026-10-20)

Asked for by Siva in the same thread: a report in four sections (project with technology
stack and modules, database and queries, integrations, batches), each a consolidated summary
with graphics and data, as HTML with a button to get a PowerPoint. Siva chose to build the
PowerPoint on the corporate template rather than in the browser with pptxgenjs.

- Done: `report/summary.py` and `report/summary_html/summary.html` write
  `reports/summary/` (index.html, summary.json, deck.json, data/*.csv, summary.pptx);
  `report --type summary` and `--type all`, GUI **Build summary report** and the page's
  **Rebuild PowerPoint** with a template choice when opened from the GUI (decision 124).
  Tests in `tests/test_summary.py`; VS Code task 24.
- Known gaps: module descriptions come from the Copilot component docs when present, else a
  scan sentence listing the module's contents; table slides show the first 10 rows (the page
  and CSVs have all of them).
- Next: build it on a real estate and check the slide layouts with the team.
- Follow-up (same day): the Database section now uses the Copilot analysis (business data
  names, migration approach per object, overall strategy) and Copilot's batch text is marked
  AI (decision 125).
- Follow-up (2026-10-21, thread "Dependency vulnerabilities in summary report"): a fifth
  section, Dependency vulnerabilities, merges Dependency-Check and OSV-Scanner results with
  the scanner(s) per vulnerability, as HTML and slides; says plainly when no scanner ran
  (decision 126). Demo with both scanners: `tests/fixtures/security/billing-dependency-check-report.json`,
  `scripts/show_dependency_vulns.py`, VS Code task 25. Known gap: report entries that match
  no library in the inventory (transitive dependencies) are logged but not listed.
  Next: run both scanners on a real estate and compare the "found by" split.
- Follow-up (2026-10-21): the guides now recommend OSV-Scanner alone with `offline: false` as
  the quick start (no database download) and `osv_scanner.offline` now defaults to `false`;
  Dependency-Check is optional (decision 127).

## Angular, AngularJS, Node.js/TypeScript and application servers (2026-10-21)

Thread "Angular, Node and TypeScript support". Siva asked for Angular, Node.js and TypeScript
coverage across the whole audit, then for AngularJS and for application and web servers
(WebSphere, WebLogic, Tomcat, JBoss, WildFly...) in both the detailed and the summary reports.

- Done: analyzers `typescript` and `servers`, npm lock files in `deps`, minified-file skip,
  component types `frontend` and `api`, SPA screen rule, front end to API `calls` edges,
  TypeScript complexity units, EOL and target rules, servers in the technical report
  (Overview), summary report (Project section) and deck; Copilot agent told about them
  (decisions 128 to 132). Sample `samples/angular-node-app` with `expected.yaml`; unit tests in
  `tests/analyzers/test_typescript.py`, `test_servers.py`, `test_npm_deps.py` and new cases in
  `test_linker.py`, `test_metrics.py`, `test_walker.py`. VS Code task 26.
- Known gaps: a service's reach is per file, so a route defined next to `app.listen` reaches
  everything the server file imports; ORM calls are kept as readable text, not parsed SQL;
  server product versions come only from DTDs, `domain-version` and image tags (Tomcat's
  `server.xml` has none); React and Vue are named in the tech stack but their pages are not
  counted as screens.
- Next: run on a real Angular/Node estate and check the screen and route counts with the team.

## Web services, REST and SOAP (2026-10-21)

Thread "Web, REST and SOAP services". Siva asked for web services, REST services and SOAP
services. Plan agreed first (offered and called services, WSDL, reports, PPTX, AI hints).

- Done: entity `web_services` (decisions 133 to 137), analyzer `webservices` (WSDL, Axis,
  Axis2, CXF, sun-jaxws, servlets), REST/SOAP detection and client calls in the Java,
  TypeScript, C, COBOL, Spring and config analyzers, linker `linker/websvc.py`, counts,
  technical report page and CSV, summary report Integrations section and slides, Copilot and
  `enrich` story per service. Sample `samples/web-services-app` (5 offered, 5 called,
  18 operations); unit tests in `tests/analyzers/test_webservices.py`; every sample's
  `expected.yaml` lists its web services. VS Code task 27 (`scripts/show_web_services.py`).
- .NET services (ASP.NET Web API and Core, ASMX, WCF) from the .NET analyzers are in the
  list too (decision 138).
- Known gaps: JSR-109 `webservices.xml` is not read; JAX-RS annotations on an interface are not carried
  to the implementing class; operations of a SOAP client stub are known only from its WSDL or
  Axis `setOperationName`; the "own API" rule relies on a host label matching a module name;
  Node `soap.listen` services have no operations (they are in the WSDL object at run time).
- Next: run on a real estate and check the offered/called lists with the team.

## Desktop and .NET: VB6, VB.NET, C#, Java Swing (2026-10-21)

Thread "Desktop and .NET language support". Siva asked for VB forms, VB.NET, C#, Java Swing and
VB6 support across the audit.

- Done: new component type `desktop`; languages and lines of code for VB6, VB.NET, C#,
  MSBuild, XAML, FXML, ASP.NET and Razor; .NET build output and NuGet `packages/` skipped;
  analyzers `dotnet` (project files, NuGet, `app.config`/`web.config`/`appsettings.json`,
  XAML/FXML, ASPX, Razor, Windows Task Scheduler), `basic` (VB6 and VB.NET) and `csharp`
  (tree-sitter); Swing/AWT/JavaFX in the Java analyzer; screens and navigation for every
  desktop technology; dynamic SQL rebuilt in VB and C# (ADO, DAO, ADO.NET, Dapper, Entity
  Framework); Windows services, scheduled tasks, Hangfire and Quartz.NET as batch; NuGet
  package URLs for OSV-Scanner; VB6 COM/OCX references; end-of-life dates for VB6 and .NET;
  Windows risk rules; complexity units for C# and VB; target mapping; Copilot agent told about
  desktop apps (decisions 141 to 152). Samples `samples/desktop-dotnet-app` and
  `samples/java-swing-app` with `expected.yaml`; unit tests in `tests/analyzers/test_dotnet.py`,
  `test_basic.py`, `test_csharp.py` and new cases in `test_java.py`, `test_metrics.py`,
  `test_walker.py`, `test_language.py`. VS Code task 28 (`scripts/show_desktop_stack.py`).
- Known gaps: .NET web services are reported as services (REST, SOAP) but are wired into the
  web services list only once the web services work is merged; VB code is read with patterns,
  so unusual line layouts can hide a statement; Entity Framework LINQ queries are readable
  text, not parsed SQL; forms built entirely in code (no designer) show 0 fields; .NET 8 and 9
  reach end of life on 2026-11-10 and must be marked then; Crystal Reports `.rpt` files are
  binary and not read.
- Next: run on a real VB6/.NET estate and check the screen, SQL and batch counts with the team.

## AI enrichment for desktop, .NET, Angular, Node.js, servers and web services (2026-10-21)

Thread "Enrichment for new languages and services". Siva asked for the enrichment to cover what
was added the same day (Angular/Node.js and servers, desktop and .NET, web services, dependency
vulnerabilities).

- Done: scheduled jobs that the scan could not link to a file (Hangfire, Quartz.NET, node-cron,
  Windows Task Scheduler) now show the code they run on their flow page and to the AI;
  `legacy-audit enrich` packs the code of desktop windows, ASP.NET pages and offered web service
  operations; six stages read more fact files (`entry_points`, `web_services`, `tech_stack`);
  classification kinds `desktop`, `frontend`, `api`; `target_architecture.platform_changes`
  shown as **Target (AI)** in the summary's technology stack and in the servers tables;
  `dependency_plan` shown as **Upgrade plan (AI)** next to vulnerable libraries; the
  `/audit-enrich` prompt and the agent rules updated; product versions ("Angular 21
  components") no longer flagged as counts (decisions 153 to 158). Demo enrichment for
  `desktop-dotnet-app` and `angular-node-app` in `tests/fixtures/enrichment/`; tests in
  `tests/test_enrich_new_stacks.py`; VS Code task 29 (`scripts/show_enrichment_coverage.py`).
- Known gaps: not yet run with Copilot or a cloud model on these samples (the demo enrichment
  is hand-written); the screen code shown to an API model is the first 120 lines of each
  window, so long forms are cut; Angular components and AngularJS controllers are not given as
  code (their logic reaches the AI through routes, services and `calls` edges); the deck and
  PPTX show the AI's server targets but not the technology stack's.
- Next: run `/audit-enrich all` on the two samples in VS Code and compare with the demo.

## Line numbers in files with old line endings or page breaks (2026-10-22)

Siva's Copilot enrichment stopped at the architecture stage: a web service fact cited a line past
the end of an XML example (`files/logiciel/xep27/examples/xmlspec/xpath.xml`).

- Done: one rule for line breaks (`\n`, `\r\n`, lone `\r`) in the scan, the validator and the
  MCP evidence reader (decision 159); `INVENTORY_VERSION` 5; `tests/test_line_numbers.py`.
- Known gaps: files under a third-party product folder are still scanned unless excluded in
  `audit.yaml`.
- Next: Siva rescans and resumes `/audit-enrich stale`.

## Batch count with no scheduler input, for Copilot (2026-10-22)

Copilot stopped before the functional stages: `batch_jobs.json` was empty while
`counts.batch_jobs.total` was 4 (batch components without a scheduler export, decision 116).

- Done: `facts batch_jobs` / MCP `query_facts` explain the difference and list the unscheduled
  batches with their component ids; agent rules and `/audit-enrich` updated (decision 160).
- Next: Siva runs `legacy-audit init` in the audit folder to refresh the agent files.

## Migration target: Spring Boot, WildFly on GKE, or audit only (2026-10-22)

Siva: the target is not always Spring Boot / Angular; some applications are lifted onto WildFly
for GKE (code kept, only the database to PostgreSQL, WildFly 41 on Java 25), and some audits
have no target.

- Done: `target.profile` in `audit.yaml` (`spring-boot-angular` default, `wildfly-gke`,
  `none`), `data/target.json`; per-target rule tables (`profiles` / `by_target` in
  `target-mapping.yaml`, `eol.yaml`, `dependency-upgrades.yaml`) and `[[phrase]]` wording in
  findings; audit only drops PostgreSQL grades, effort, the target mapping and the five target
  stages; reports, deck and PPTX follow the target or leave target parts out; agent rules,
  enrich prompts and the validator follow it; GUI picker; `tests/test_targets.py`, VS Code task
  30 (`scripts/show_targets.py`), decisions 161-165.
- Known gaps: one target per application (no per-component target); the effort weights are
  the same for a lift onto WildFly as for a rewrite (adjust `effort` in `audit.yaml`); a WildFly
  move of a Spring application keeps Spring but the eol advice still names the supported
  Spring release lines, not a WildFly-specific path.
- Next: Siva sets the target per application, rescans, runs `legacy-audit init` once and
  `/audit-enrich stale`.

## Maven modules and batch folders (2026-10-23)

Siva: multi-module Maven projects, Maven parent projects and several batches under one folder
were not processed correctly; when a pom.xml is there, Maven should decide.

- Done: Maven parents / aggregators (packaging `pom`) and Gradle roots are groups, each module a
  component, with `build_group`, `artifact`, `packaging` on the component (decision 166); a
  batch folder of several batches is split, its shared part never a batch (decision 167); one
  batch per entry point when no scheduler covers it, Maven batch modules started by a script
  count with that script (decision 168, Siva's choice). New sample `maven-multimodule-app`,
  `spring-mvc-batch-app` loses its "billing" parent component, VS Code task 31
  (`scripts/show_components.py` now prints the module tree and the batch entry points).
- Known gaps: a module listed in a pom but missing from the source is not reported as a gap;
  Gradle `projectDir` is read only in the simple `file('x')` form; `ear` modules do not list
  the modules they bundle; batch folders inside an application folder (next to `src/`) are not
  split (their entry points still count one by one).
- Next: Siva runs it on a real multi-module estate and checks the module tree and the count.

## PDF copies of the reports (2026-10-23)

Siva asked for PDF versions of the reports and confirmed all four, on A4.

- Done: `legacy-audit report --type pdf` (and `--type all`) writes `reports/pdf/summary.pdf`,
  `management.pdf`, `functional.pdf` and `technical.pdf`, printed by Edge, Chrome or Chromium
  in headless mode, offline; the long reports get a cover with contents, bookmarks and page
  numbers (`legacy_audit/report/pdf.py`, `pypdf`). `pdf:` section in `audit.yaml`, GUI
  **Build PDFs** button and PDF links, `doctor` browser check, VS Code task 32
  (`scripts/show_pdfs.py`), `tests/test_pdf.py`, decisions 169-172.
- Known gaps: printing a Windows Edge from WSL2 (paths as `\\wsl.localhost\...`) is covered by
  tests of the command but not yet tried on a real laptop; a Linux Chrome/Chromium inside WSL2
  is the fallback. The editable PowerPoint is not converted to PDF (the management deck PDF
  holds the same slides).
- Next: Siva runs task 32 on Windows and on WSL2.

## Copilot files not showing in VS Code (2026-10-23)

- Done: the agent, chat mode and prompt headers installed by `init` quote every value
  (decision 173). Unquoted descriptions holding ": " were invalid YAML, and current VS Code
  dropped the files, so `/audit-*` and the **legacy-auditor** agent were missing.
  `tests/test_copilot.py::test_front_matter_is_valid_yaml`; troubleshooting steps in
  `docs/copilot-agent.md`; validate section "Copilot agent and prompts show in Copilot Chat".
- Next: Siva runs `legacy-audit init` in each audit folder, reloads VS Code and checks `/audit`.

## Components and integrations grouped (2026-10-26)

Siva: the components list should be consolidated by pom when there is one, otherwise by
technology or framework; integrations calling the same external service should be one entry.
He chose "per deployable" on the card and asked for MQ queues to be grouped by queue manager.

- Done: `legacy_audit/inventory/grouping.py` (decision 176): a Maven/Gradle library module goes
  into the one war, ear or runnable/batch jar that uses it (pom `<dependencies>`, Gradle
  `project(':x')`); the folders beside the modules are `<group>-scripts-config`; without a build
  file, part folders group by technology (`cobol-online`, `jcl-jobs`, one per batch folder,
  `shared-scripts-config`), and config/scripts beside a single application join it.
  `Component.parts`, `inventory.component_grouping: auto | module`. Integrations (decision 177):
  one row per host (http/https, REST/SOAP), per queue manager (queues and channels, from the
  MQSC header, file name or the components' connection), per file server or folder;
  `Integration.endpoints` and `grouped_ids` (old enrichment still finds its row),
  `integrations.group_by_service`. Called REST web services are one per host too. Summary
  report (Endpoints column, slides), technical report (Folders column, component page,
  catalogue), deck, GUI picker (ticks a grouped component's folders together), Copilot prompt.
  Samples: maven-multimodule-app 12 -> 6 components (acme-web now depends on acme-core),
  cobol-cics-db2 6 -> 3, legacy-layout-app 5 -> 4; spring-mvc-batch-app 9 -> 5 integrations,
  desktop-dotnet-app 13 -> 11, web-services-app 7 -> 6, c-proc-batch 6 (queue under QMSETL).
  VS Code task 33 (`scripts/show_grouping.py`), validate section "Components and integrations
  grouped".
- Known gaps: .NET project references (`<ProjectReference>`) and Ant/Make builds do not fold
  libraries into their application yet; two Struts or Spring web apps in sibling folders stay
  two components (they are two deployables); audit-overrides.yaml entries naming a folder
  component that is now grouped (e.g. `config`) need the new name; component docs written by
  Copilot for folders now grouped need `/audit-enrich stale`.
- Next: Siva runs task 33 on the samples and a real estate and checks the lists.

## A failing step does not stop the scan (2026-10-28)

Siva's scans on WSL2 failed with `UNIQUE constraint failed: web_services.id`.

- Done (decision 178): two SOAP services called on one host no longer share an id (bug from
  decision 177's one-REST-service-per-host rule); the web services list and integration
  grouping steps, when they fail, log the error and add a run warning instead of failing the
  run; duplicate ids are left out with a warning before the facts are written. Tests in
  `tests/test_linker.py`, VS Code task 34, validate section "A failing step does not stop the scan".
- Next: Siva reruns the scan that failed.

## Hosted site: Docker, login, projects, admin, status and logs (2026-10-29)

Siva asked for a containerized, hosted version where any project owner can open or create an
audit project under a top-level audits folder, with an admin page, a dummy login (Okta later),
a logs page and a status page with time left.

- Done (decisions 180-186): `legacy-audit host` and `legacy_audit/hosted/` (settings from
  `LEGACY_AUDIT_*`, test login with roles, users/projects/activity files, admin defaults merged
  into every project with locks, folder confinement, shared queue with time left, status, logs
  and admin pages with 10 tabs); the GUI app is served per project under `/p/<id>/` (relative
  URLs, hosted hooks, report switches, summary sections); `Dockerfile`, `docker-compose.yml`,
  `.dockerignore`, `docker/env.example`, `docker/certs/`. Docs: `docs/hosting.md`, user guide
  "On the hosted site", README, admin guide, `.github/copilot-instructions.md`. Tests
  `tests/test_hosted.py`; VS Code tasks 36 (Docker), 37 (no Docker, samples), 38 (tests);
  validate section "Hosted site (Docker, login, admin page)".
- Checked here: full test suite; the pages in Chromium (login, projects, create with the folder
  picker, run, status with a queued and a running scan, logs, reports, every admin tab, phone
  width); the image built and ran (healthy, login, create, scan, reports) with the apt step
  left out, because this build machine cannot reach the Debian package servers. The full image
  with Chromium, Node and OSV-Scanner is not built here yet.
- Known gaps: Okta (placeholder only); no Kubernetes/GKE manifest; one container (the queue and
  sessions are in memory, jobs finished before a restart are not on Status); no upload of a
  source ZIP (code comes from a mounted folder or Git); the status time left for a first full
  scan comes from the current step's pace only.
- Next: Siva builds the full image on a laptop (task 36), tries it with two users, then the Okta step.
