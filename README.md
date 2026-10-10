# code-audit (legacy-audit)

A tool for auditing legacy applications before migrating them to **Java 25, Spring Boot 4.x, Angular 21, PostgreSQL and Google Cloud**.

It scans a codebase (Java/Struts/Spring, C/C++, Pro*C, COBOL, JCL, shell, Autosys JIL, IBM MQ, CFT), produces reproducible facts (JSON, CSV, SQLite, SBOM), lets analysts add AI interpretation with a GitHub Copilot agent in VS Code, and generates a technical HTML site, a management HTML deck and an editable PPTX. It can be re-run any number of times as new inputs arrive.

## Status

Phases 1 (foundations) and 2 (analyzers and linker) are done: inventory, components, input
coverage, run history, file cache, overrides and run diff, plus 15 analyzers (build, jars, config,
Java, Struts/JSP/web.xml, Spring, SQL, shell, C/C++, Pro*C, COBOL/CICS/BMS, JCL, Autosys, MQ,
CFT) whose facts are linked across files into services, screens, SQL, integrations, batch jobs,
libraries and code edges. Rules, metrics and dependency security come in phase 3. See
`docs/progress.md`.

## Documents

- `docs/plan.md` — full specification
- `docs/decisions.md` — decisions and open questions
- `docs/prompts/` — build prompts, one per phase
- `CLAUDE.md` — rules and conventions for Claude Code

## Install

Python 3.11 or newer.

```
python -m venv .venv
.venv\Scripts\activate          # Windows  (Linux/macOS: source .venv/bin/activate)
pip install -e ".[dev]"
```

## Usage

```
cd <legacy workspace>
legacy-audit init                 # audit.yaml, audit-overrides.yaml, audit-inputs/<kind>/
legacy-audit scan                 # new run under audit-output/runs/<run-id>/, latest -> newest
legacy-audit scan --full          # ignore the file cache and re-analyse everything
legacy-audit diff previous latest # what changed between two runs (ids, 'previous', 'latest')
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
  counts, totals and top-N lists, under 30 KB).
- `logs/scan.log`.

Re-runs only re-analyse new or changed files; totals are always recomputed. Supplementary
inputs (Autosys JIL, MQSC, CFT exports, DDL, crontab, server config) go in
`audit-inputs/<kind>/` whenever they arrive; a missing folder is reported as a coverage gap.
Corrections go in `audit-overrides.yaml` and are re-applied on every run.

The scanner never writes inside the source tree and makes no network calls, except cloning a
Git source when `source.type: git` is configured (token read from the environment variable
named in `token_env`, never stored).

## Development

```
ruff check . && ruff format --check .
mypy legacy_audit
pytest
python -m legacy_audit.model.schemas   # regenerate schemas/facts/ after changing a model
```
