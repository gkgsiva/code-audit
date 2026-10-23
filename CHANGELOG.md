# Changelog

All notable changes to legacy-audit. Dates are when a phase was merged.

## 0.1.0 (unreleased)

The first complete version: phases 1 to 7 of `docs/plan.md`.

### PDF copies of the reports (2026-10-23)

- `legacy-audit report --type pdf` (and `--type all`, and **Build PDFs** in the app) saves the
  summary report, management deck, functional document and technical report as PDFs in
  `reports/pdf/`, printed by Microsoft Edge, Chrome or Chromium in the background, offline.
- A4 by default (`pdf.paper: Letter`); the technical and functional PDFs are one file each,
  with a cover and contents, bookmarks for every page and "page i of n"; long tables are cut
  at `pdf.max_table_rows` with a note. No browser: the PDFs are skipped with a message.
- New dependency `pypdf`; `legacy-audit doctor` shows the browser; VS Code task 32
  (`scripts/show_pdfs.py`).
- Wide tables print whole: no more columns cut at the page edge; very wide tables (the
  technical report's SQL lists) move to landscape pages.

### Web services, REST and SOAP (2026-10-21)

- One list of **web services**: the ones the application offers (Spring REST controllers,
  JAX-RS, JAX-WS, Spring-WS, Apache Axis 1 and Axis2, CXF, Jersey/RESTEasy servlets, Node
  `soap.listen`) and the ones it calls (RestTemplate, WebClient, RestClient, Feign, Retrofit,
  JAX-RS client, Java and Apache HttpClient, OkHttp, HttpURLConnection, JAX-WS and Axis stubs,
  WebServiceTemplate, CXF clients, Angular HttpClient, Node `soap`, libcurl and gSOAP in C,
  CICS `INVOKE SERVICE` and `WEB` in COBOL, ASP.NET Web API, ASMX and WCF in .NET), each with REST or SOAP, its address, its
  operations (HTTP method and path, or SOAP operation), module, files and lines.
- WSDL files are read (services, ports, operations, address) and attached to the service that
  implements or calls them.
- Shown in the technical report (new **Web services** page and CSV, module pages), the summary
  report (Integrations section: tiles, chart and table) and the PowerPoint; Copilot and
  `legacy-audit enrich` can add a purpose and a migration hint per service, marked AI.
- XML namespaces (for example a SOAP `QName` namespace) are no longer taken for integrations.
- New sample `samples/web-services-app`; VS Code task 27 (`scripts/show_web_services.py`).

### Desktop and .NET: VB6, VB.NET, C# and Java Swing (2026-10-21)

- VB6, VB.NET and C# programs and Java Swing/JavaFX desktop programs are audited like the rest
  (new component type `desktop`): lines of code, screens (VB6 forms, Windows Forms, WPF
  windows, Swing frames and dialogs, JavaFX views, ASP.NET pages and Razor views) with their
  input fields and which screen opens which, SQL built in the code (ADO, DAO, ADO.NET, Dapper,
  Entity Framework), database connections in code and configuration, file shares, MSMQ, mail,
  HTTP and SOAP calls, ASP.NET controllers and ASMX/WCF services, Windows services, Windows
  Task Scheduler, Hangfire and Quartz.NET jobs, and complexity.
- NuGet packages (for OSV-Scanner) and VB6 COM references and ActiveX controls are listed as
  libraries; Visual Basic 6 and old .NET Framework and .NET versions are marked end of life.
- New findings: passwords in connection strings (masked), ActiveX controls, Windows API calls,
  `CreateObject`, the Windows registry, `On Error Resume Next` and Windows drive and share
  paths.
- `.NET` build output (`bin/`, `obj/`, generated files) and NuGet `packages/` are skipped.
- New samples `samples/desktop-dotnet-app` and `samples/java-swing-app`; VS Code task 28
  (`scripts/show_desktop_stack.py`).

### Angular, AngularJS, Node.js/TypeScript and application servers (2026-10-21)

- Angular and AngularJS front ends (component type `frontend`) and Node.js APIs with Express
  or NestJS (type `api`) are audited like the Java parts: lines of code, screens (routed
  pages), API routes, which page calls which route, SQL and ORM calls, database connections,
  outbound HTTP calls, node-cron and NestJS scheduled jobs, complexity, npm libraries from
  `package.json` and lock files (for OSV-Scanner) and the Node.js version.
- Minified and bundled JavaScript is skipped and reported, like build output.
- Application and web servers (WebLogic, WebSphere, Liberty, JBoss/WildFly, Tomcat, Apache,
  IHS, Nginx, IIS...) are found in descriptors, server configuration, Dockerfiles and scripts,
  with versions and end of life, and listed in the technical report, the summary report and
  the management deck.
- New sample `samples/angular-node-app`; VS Code task 26 (`scripts/show_web_stack.py`).

### Dependency vulnerabilities in the summary report (2026-10-21)

- The summary report has a fifth part, **Dependency vulnerabilities**: tiles, charts (by
  severity, by scanner, most vulnerable libraries, by module), the top vulnerabilities worst
  first, the vulnerable libraries with the suggested fix, and the status of each scanner;
  the same as slides in the PowerPoint.
- OWASP Dependency-Check and OSV-Scanner results are one list; each vulnerability says which
  scanner found it (or both). Without any scanner results the part says plainly that the
  check was not done.
- VS Code task 25 shows it on the Billing sample with both scanners
  (`scripts/show_dependency_vulns.py`).

### Summary report (2026-10-20)

- New `legacy-audit report --type summary` (also in `--type all` and a GUI button): one page
  in four parts (project and modules, database and queries, integrations, batches) with
  key numbers, charts, tables and a CSV per table.
- Its **PowerPoint (PPTX)** button downloads `summary.pptx`, built on the corporate template;
  from the GUI the page can rebuild it with another template.
- VS Code task 24 builds and opens it.
- After the Copilot analysis, the Database section adds business data names, a migration
  approach per table and procedure and the overall migration approach, and Copilot's batch
  words are marked AI.

### Integration purposes and one batch list (2026-10-20)

- Every integration has a purpose and a one-sentence description; Okta and other identity
  providers are "login / authentication". Copilot's interface stories replace the
  description once written.
- The Batch jobs page starts with **All batches**: each batch once with what it does and when
  it runs (from the scheduler, Copilot, or what the code states when no JIL/crontab is given),
  also as `data/batches.csv`; the functional Batch page has the same list.
- VS Code task 23 prints the batch list (`scripts/show_batches.py`).

### Lines of code and consolidated integrations (2026-10-20)

- No language is "unknown": about 50 more extensions are recognised (SQL*Loader `.ctl`, DB2
  and Informix embedded SQL, more PL/SQL, awk, sed, Tcl, PowerShell, VBScript, Kotlin, Scala,
  SQLJ, C#, ...); any other text extension shows as `other (.ext)`.
- Binary files are left out of the lines of code by content too (NUL byte or mostly control
  bytes), whatever their extension; backup copies (`.bak`, `.old`, `~`, `.ksh_old`, dated
  copies) and data files (`.dat`, `.csv`) are listed with no lines.
- Integrations are one row per interface: directions merge into `both`, and host case, default
  ports, trailing slashes, `queue:///` and `QM@host` spellings no longer give duplicates.
- VS Code task 22 prints lines of code by language and the integrations
  (`scripts/show_loc_integrations.py`).

### Component classification and batch count (2026-10-12)

- Build output is no longer scanned: Eclipse `bin/` and other `bin/`, `classes/`, `obj/`,
  `Debug/`, `Release/` folders holding compiled files, Eclipse output folders from
  `.classpath`, and `.c` files generated from Pro*C (`.cob` from Pro*COBOL, `.java` from SQLJ).
  Each is reported as a `build-output-skipped` finding; `inventory.skip_build_output: false`
  turns it off. A Unix `bin/` of ksh scripts is still scanned.
- Components follow the application layout when there is no build file: `src`, `WebContent`,
  `java`, `ksh`, `properties`, `conf`, ... are parts of the component around them (a Struts
  module keeps Java and JSP together, a batch its Java, ksh and properties); folders that only
  hold applications are containers; Eclipse `.project`, `Makefile.*`, `CMakeLists.txt` and
  Visual Studio projects mark components.
- New component type `config`; components list every kind of work they hold (`roles`, shown as
  "Also holds") and their files by category (`composition`, "Made of").
- The batch count includes batch components no scheduler definition covers: one per component,
  however many ksh scripts it has (they are its steps). The Batch pages show one "no schedule
  found" batch per such component instead of one per script.
- New sample `legacy-layout-app`; VS Code task 21 shows components and the batch count.

### Corporate PowerPoint templates (2026-10-12)

- The PPTX is built on the corporate templates in `templates/` (Renault Group Blue, Orange and
  Purple): the template's cover, contents, chapter and closing pages, its content layouts, theme
  colours and font on every slide. Pick one on the GUI's reports page (with previews), with
  `legacy-audit report --template`, or with `branding.pptx_template`.
- The PPTX no longer needs Node.js or `npm install`: it is built in Python (`python-pptx`). Node
  is only for diagram pictures (Mermaid CLI). `doctor` lists the templates found.

### Model-agnostic AI enrichment (2026-10-12)

- `legacy-audit enrich`: write the enrichment with any model (OpenAI-compatible endpoints such as
  OpenAI, Gemini or a company gateway, Azure OpenAI, Anthropic, Ollama), set in the new `ai:`
  section of `audit.yaml` or `LEGACY_AUDIT_AI_*` variables; `--check` tests the settings. The
  validator's errors are sent back to the model to fix.
- GUI: **Run missing and stale stages** / **Run all stages** on the AI enrichment screen when a
  model other than Copilot is set; `doctor` shows the model.
- Copilot stays the default; `ai.copilot_model` pins its model in the agent file.
- VS Code tasks 19 (test the model settings) and 20 (enrichment with the configured model).

### Phase 7 follow-up: batches without scheduler inputs (2026-10-12)

- The Batch pages list every batch script, program and listener even when no Autosys JIL, JCL
  or crontab was provided, marked "no schedule found", with its steps, tables and files.
- "What the code says": each batch shows the header comments of its source files (credentials
  masked).
- Copilot stage 12 reads the batch sources and comments; batch stories gain what each program
  does, the controls in the code and how to restart.
- VS Code task 18: scan a sample without its scheduler inputs.

### Phase 7: functional documentation and graphics (2026-10-10)

- Functional document (`legacy-audit report --type functional`, also in `all`, the GUI and VS
  Code task 17): capability map, users and use cases, business flows, a flow for every screen
  (services and tables with C/R/U/D) and every batch (trigger, ordered steps, tables and files,
  failure handling, week calendar and timeline), business rules, data, interfaces, glossary.
- Screen and batch flows are drawn from the scan; Copilot's new stages 12 (functional) and 13
  (component documentation) add the explanations, checked by `legacy-audit validate`.
- New facts: services list the SQL they reach; Autosys jobs keep their description and failure
  settings (alarms, retries, notifications).
- Technical report: coloured theme and icons, readiness gauges, doughnut, stacked, radar and
  bubble charts, Flows and Data model pages, layered and today-to-target diagrams, risk heat map,
  roadmap Gantt, "what this component does".
- Management deck and PPTX: coloured section dividers, gauges, capability map, screen and batch
  flow slides, batch calendar, layers, CRUD and risk heat maps, today-to-target flow and roadmap,
  all native (editable) in PowerPoint.

### Phase 6: GUI and hardening (2026-10-10)

- `legacy-audit serve`: local web GUI on `127.0.0.1` with nine screens: project setup, inputs,
  components, run, dashboard, explorer, AI enrichment status, reports and compare runs. It runs
  scans in the background with live progress and cancel, and documents its API under `/api/docs`.
- `legacy-audit doctor`: lists required and optional tools (Python, Node, pptxgenjs, Mermaid
  CLI, Dependency-Check, OSV-Scanner, JDK tools, Git) and what each one adds.
- Files are analysed and SQL is graded in parallel processes (`inventory.workers`). SQL grades
  are cached between runs. A 2M-line estate scans in 3 to 4 minutes on 4 CPUs (see
  `docs/performance.md`).
- Robustness:
  - one scan at a time per output folder;
  - unfinished runs are cleaned up and an interrupted scan resumes from the cache;
  - too-large and unreadable files become coverage findings;
  - Windows paths longer than 260 characters work;
  - symlink loops and read-only source trees are handled.
- `audit.yaml` edits from the GUI keep comments. The component picker writes
  `audit-overrides.yaml`.
- Docs: user guide, admin guide, rules guide, performance results.

### Phase 5: Copilot agent, enrichment, management deck and PPTX

- Copilot custom agent, prompt files and MCP registration installed by `init`.
- Enrichment schemas, `legacy-audit validate` (figures checked against the scan, evidence
  required) and staleness after re-scans.
- Read-only MCP server and `legacy-audit facts`.
- Management HTML deck (26 slides), editable PPTX (pptxgenjs) and an Analysis page in the
  technical report.

### Phase 4: technical HTML report

- Offline static site:
  - overview, components, services, screens, SQL, data, integrations, batch, dependencies,
    findings, metrics and coverage;
  - vendored Chart.js and Mermaid;
  - evidence links to VS Code.

### Phase 3: rules, metrics and security

- Code metrics (complexity, duplication, dead-code candidates) and effort estimate.
- YAML rule packs and PostgreSQL grading with sqlglot. SQL built at run time is rebuilt in Java,
  C, Pro*C and COBOL.
- Dependency-Check and OSV-Scanner import or run, end-of-life data, upgrade advice and a
  CycloneDX SBOM.

### Phase 2: analyzers

- Build, dependency, config, Java, web, Spring, SQL, shell, C/C++, Pro*C, COBOL/BMS, JCL, Autosys,
  MQ and CFT analyzers.
- Cross-file linker and three more sample applications.

### Phase 1: foundations

- Package, CLI (`init`, `scan`, `diff`, `purge-cache`), configuration and inventory.
- Encoding and language detection, components, file-hash cache, run snapshots with `latest`.
- Optional Git sources and JSON/CSV/SQLite output.
