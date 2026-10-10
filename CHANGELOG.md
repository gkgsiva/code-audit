# Changelog

All notable changes to legacy-audit. Dates are when a phase was merged.

## 0.1.0 (unreleased)

The first complete version: phases 1 to 7 of `docs/plan.md`.

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
