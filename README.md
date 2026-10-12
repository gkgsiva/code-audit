# code-audit (legacy-audit)

A tool for auditing legacy applications before migrating them to **Java 25, Spring Boot 4.x, Angular 21, PostgreSQL and Google Cloud**.

It scans a codebase (Java/Struts/Spring, C/C++, Pro*C, COBOL, JCL, shell, Autosys JIL, IBM MQ, CFT), produces reproducible facts (JSON, CSV, SQLite, SBOM), lets analysts add AI interpretation with a GitHub Copilot agent in VS Code, and generates a technical HTML site, a management HTML deck and an editable PPTX. It can be re-run any number of times as new inputs arrive.

## Status

Phases 1 (foundations) and 2 (analyzers and linker) are done: inventory, components, input
coverage, run history, file cache, overrides and run diff, plus 15 analyzers (build, jars, config,
Java, Struts/JSP/web.xml, Spring, SQL, shell, C/C++, Pro*C, COBOL/CICS/BMS, JCL, Autosys, MQ,
CFT) whose facts are linked across files into services, screens, SQL, integrations, batch jobs,
libraries and code edges. Phase 3 adds metrics (cyclomatic complexity, duplication, dead-code
candidates, complexity bands), YAML rule packs and findings, PostgreSQL grading, dependency
security (Dependency-Check / OSV-Scanner, EOL, upgrade advice, CycloneDX SBOM) and an effort
estimate. Phase 4 adds the technical HTML report (offline, one folder per run). Phase 5 adds the
GitHub Copilot agent and prompt files (installed by `init`), validated enrichment with staleness
tracking, a read-only MCP server, the management HTML deck and the editable PPTX. Phase 6 adds
the local GUI (`legacy-audit serve`), `legacy-audit doctor`, parallel analysis (2M lines in
3 to 4 minutes on 4 CPUs) and hardening for interrupted runs, odd files and long Windows paths. See
`docs/progress.md` and `CHANGELOG.md`.

## Documents

- `docs/plan.md` — full specification
- `docs/decisions.md` — decisions and open questions
- `docs/usage-guide.html` — step-by-step usage guide for non-technical users (Windows, VS Code); open it in a browser
- `docs/user-guide.md` — an audit end to end with the GUI
- `docs/admin-guide.md` — install (pipx, offline), vulnerability database, branding, rule packs
- `docs/rules.md` — how to add, change or switch off rules
- `docs/performance.md` — scan times on a generated 2M-line estate
- `docs/copilot-agent.md` — the Copilot agent for analysts
- `docs/prompts/` — build prompts, one per phase
- `.github/copilot-instructions.md` — rules and conventions for developers and AI assistants

## Install

Python 3.11 or newer.

```
python -m venv .venv
.venv\Scripts\activate          # Windows  (Linux/macOS: source .venv/bin/activate)
pip install -e ".[dev]"
```

For analysts: `pipx install legacy_audit-<version>-py3-none-any.whl` (build it with
`python -m pip wheel . --no-deps -w dist`). Offline installs are in
[`docs/admin-guide.md`](docs/admin-guide.md).

## Usage

```
cd <legacy workspace>
legacy-audit serve                # local GUI on http://127.0.0.1:8765 (setup, inputs, run, results)
legacy-audit doctor               # which optional tools (Node, Dependency-Check, ...) are installed
legacy-audit init                 # audit.yaml, audit-overrides.yaml, audit-inputs/<kind>/, Copilot files
legacy-audit scan                 # new run under audit-output/runs/<run-id>/, latest -> newest
legacy-audit scan --full          # ignore the file cache and re-analyse everything
legacy-audit diff previous latest # what changed between two runs (ids, 'previous', 'latest')
legacy-audit report --open        # technical HTML report of the latest run, opened in a browser
legacy-audit report --run <id>    # report of an older run (default: latest)
legacy-audit report --type functional  # functional document: screen and batch flows, use cases, rules
legacy-audit report --type all    # technical site + functional document + management deck (HTML) + deck.pptx
legacy-audit validate             # check Copilot's enrichment against the scan; mark stale stages
legacy-audit enrich               # write the enrichment with the model in audit.yaml > ai (--check, --stale)
legacy-audit facts services -f kind=servlet   # query facts as JSON (what the agent reads)
legacy-audit mcp                  # read-only MCP server for Copilot (stdio)
legacy-audit purge-cache          # delete cloned Git sources (~/.legacy-audit/cache)
legacy-audit version
```

`--config/-c` points at another `audit.yaml` (default: `./audit.yaml`); `scan -o DIR` overrides
the output folder. Try it on a sample: `legacy-audit scan -c samples/struts-oracle-app/audit.yaml`
(also `spring-mvc-batch-app`, `c-proc-batch`, `cobol-cics-db2`).

**What a run writes** (`audit-output/runs/<run-id>/`):

- `run.json` – config hash, source folders and Git commit SHAs, tool version, timings, input
  coverage, per-analyzer coverage (files, unparsed regions, %), unresolved references,
  overrides applied, counting rules, warnings.
- `data/` – one JSON (source of truth) and CSV file per entity, `facts.sqlite` with one table per
  entity (services, screens, SQL statements, DB objects, integrations, batch jobs, libraries,
  tech stack, entry points, dependencies, findings...), and `audit-summary.json` (headline
  counts, totals and top-N lists, under 30 KB). Phase 3 adds `findings`, `metrics`, PostgreSQL
  grades on `sql_statements`, CVEs/EOL/recommendations on `libraries`, `sbom.cdx.json`
  (CycloneDX 1.5), `effort.json` ("estimate — weights from config") and `target-mapping.json`.
- `security/` – reports written by Dependency-Check / OSV-Scanner when the scan runs them.
- `logs/scan.log`.
- `enrichment/` – Copilot's interpretation, one JSON file per stage (schemas in
  `schemas/enrichment/`), and `status.json` (present / valid / stale per stage). Carried into each
  new run; stages whose facts changed are marked stale.
- `reports/mgmt/` and `reports/deck.pptx` – written by `legacy-audit report --type mgmt|pptx|all`:
  `deck.json` (the slides as data), an offline HTML deck (arrow keys, `N` for notes, print to PDF)
  and an editable PowerPoint with native charts. The PPTX needs Node.js 18+ and `npm install` once
  in `pptx/`; without them it is skipped with a message. Slides whose enrichment is missing show
  a placeholder.
- `reports/tech/` – written by `legacy-audit report`: `index.html` (overview), one page per
  component, architecture diagrams, catalogues (services, screens, SQL, DB objects, CRUD matrix,
  integrations, batch jobs, libraries, findings) with search, filters, sorting and paging,
  PostgreSQL readiness, security, findings and an appendix (counting rules, masked config,
  glossary). It works offline from disk (Chart.js and Mermaid are vendored); every number links
  to its source file, and a **VS Code** link opens the file at the line.

**Rules, weights and security tools** are set in `audit.yaml`: `complexity` (weights, scales,
band thresholds), `effort.weights` (person-days per item and level), `rules` (packs, `disabled`,
`severity`, a team `packs_dir`), `security` (Dependency-Check / OSV-Scanner paths, data folder,
offline). Changing them takes effect on the next run without re-analysing files. The rule packs
are in `rules/`, each documented in its header comment.

Vulnerability data: put Dependency-Check or OSV-Scanner JSON reports in `audit-inputs/security/`
(e.g. from CI), or install the tools with a local database; the scan runs them offline. Without
either, `run.json` shows the check as a coverage gap.

**SQL built at run time** is rebuilt into the statement the code sends: Java concatenation,
`StringBuilder` chains, `String.format` and ternaries; C/Pro*C `strcpy`/`strcat`/`sprintf` into a
buffer that `EXEC SQL PREPARE ... FROM :buf`, ODBC, OCI and other client calls execute; COBOL
`MOVE`/`STRING` into a host variable for `PREPARE`/`EXECUTE IMMEDIATE`; MyBatis/iBATIS dynamic
tags. `sql_statements` shows how each was built, its optional parts, `?` holes (a value =
injection risk, or part of the statement = manual) and variants; `python scripts/show_sql.py
<app folder> --dynamic` prints them for the latest run.

Re-runs only re-analyse new or changed files; totals, metrics, rules and effort are always
recomputed. Supplementary inputs (Autosys JIL, MQSC, CFT exports, DDL, crontab, server config,
vulnerability reports) go in
`audit-inputs/<kind>/` whenever they arrive; a missing folder is reported as a coverage gap.
Corrections go in `audit-overrides.yaml` and are re-applied on every run.

The scanner never writes inside the source tree and makes no network calls, except cloning a
Git source when `source.type: git` is configured (token read from the environment variable
named in `token_env`, never stored).

**Copilot enrichment**: open the workspace in VS Code, pick the **legacy-auditor** agent in
Copilot Chat and run `/audit-scan`, `/audit-enrich all`, `/audit-report`. The agent only
interprets: numbers are copied from the scan as figures, claims cite `file:line`, and
`legacy-audit validate` rejects anything else. See [`docs/copilot-agent.md`](docs/copilot-agent.md).

**Any other model**: set the `ai:` section of `audit.yaml` (`openai` for any OpenAI-compatible
endpoint, `azure-openai`, `anthropic` or `ollama`, plus `model`; the API key comes from an
environment variable) and run `legacy-audit enrich` (or the GUI's AI enrichment screen). The same
validator checks the result.

## Validating from VS Code

Open the folder in VS Code and use **Terminal > Run Task...**: `audit: 1. set up`, `2. tests`,
`3. lint + type check`, `4. scan a sample`, `5. scan all samples`, `6. show dynamic SQL`,
`7. build and open the technical report`, `8`–`12` for phase 5 (PPTX builder, Copilot files, demo
enrichment, decks, facts), `13`–`15` for phase 6 (GUI, doctor, performance test), `16`–`17` for phase 7 (demo functional
enrichment, functional document), `18` (scan without scheduler inputs), `19`–`20` (AI model check and
enrichment with any model); **Run and
Debug** has `legacy-audit: scan a sample` for breakpoints. What to check after each phase is in
[`docs/validate-in-vscode.md`](docs/validate-in-vscode.md).

## Development

```
ruff check . && ruff format --check .
mypy legacy_audit
pytest
python -m legacy_audit.model.schemas   # regenerate schemas/facts/ after changing a model
```
