# Changelog

All notable changes to legacy-audit. Dates are when a phase was merged.

## 0.1.0 (unreleased)

The first complete version: phases 1 to 7 of `docs/plan.md`.

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
