# Changelog

All notable changes to legacy-audit. Dates are when a phase was merged.

## 0.1.0 (unreleased)

The first complete version: phases 1 to 6 of `docs/plan.md`.

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
