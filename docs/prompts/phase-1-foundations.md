# Phase 1 — Foundations

Goal: a runnable `legacy-audit` package that inventories a codebase, stores facts, keeps run history and supports re-runs. No language analysis yet beyond detection and LOC.

## Build

1. **Packaging** – `pyproject.toml` (hatchling), console script `legacy-audit`, dev extras (pytest, ruff, mypy). `README.md` with install and usage.
2. **Config** – `audit.yaml` loaded into pydantic models; `audit.example.yaml` fully commented. Sections: `application`, `source` (`type: local|git`, paths or repos), `include`/`exclude` globs, `components` (optional manual list), `inputs_dir` (default `audit-inputs/`), `output_dir` (default `audit-output/`), `target` (java 25, spring_boot 4.1, angular 21, database postgresql, cloud gcp), `counting` and `complexity` weights, `rules` enabled, `branding`. Unknown keys → clear error.
3. **Source handling** – `local`: one or more paths. `git` (optional): shallow clone into `~/.legacy-audit/cache/<host>/<owner>/<repo>@<ref>`, fetch on re-run, record commit SHA. Token from env var named in config (e.g. `token_env: GIT_TOKEN`) or git credential helper; never logged; stripped from the remote URL after clone. `purge-cache` command.
4. **Inventory** – walker honouring include/exclude and `.gitignore`-style defaults (skip `.git`, `node_modules`, `target/`, `build/`, binaries unless asked). Per file: path (relative, POSIX style), size, SHA-256, language (extension + content sniffing: COBOL fixed format, `EXEC SQL`, JCL `//` cards, JIL `insert_job`, Pro*C), category (source/config/build/sql/screen/job/script/binary/test/generated/doc), encoding (utf-8, cp1252, Shift-JIS, EBCDIC heuristics via `charset-normalizer`), LOC code/comment/blank per language comment syntax.
5. **Components** – default component detection from folder structure and build files (each Maven module / Ant project / top folder with sources), overridable in config.
6. **Supplementary inputs** – detect `audit-inputs/{autosys,mq,cft,ddl,crontab,server-config}`; produce `InputCoverage` (present / missing / file count). Missing is not an error.
7. **Store and runs** – run id `YYYYMMDD-HHMMSS-<short hash>`; folder `audit-output/runs/<id>/data/`; `latest` pointer (copy or pointer file on Windows, symlink elsewhere). Write JSON (source of truth), CSV per entity, SQLite (`facts.sqlite`) with one table per entity. `run.json` with config hash, source SHA(s), tool version, timings, coverage.
8. **Hash cache** – per-file analysis results cached by (file SHA, analyzer version); re-run re-analyzes only changed/new files, drops deleted ones, always recomputes aggregates.
9. **Overrides** – `audit-overrides.yaml` (component type overrides, include/exclude, renames) applied after analysis every run; recorded in `run.json`.
10. **Diff** – `legacy-audit diff <runA> <runB>` → counts and added/removed records per entity, JSON + console table.
11. **Summary** – `audit-summary.json` (totals, top-N lists, coverage) kept under 30 KB.
12. **CLI** – `init` (writes starter `audit.yaml`, `audit-overrides.yaml`, `audit-inputs/` folders; Copilot files come in phase 5), `scan`, `diff`, `purge-cache`, `version`. `scan --full` ignores cache. Rich console progress; exit code non-zero only on real errors.
13. **Fact model + schemas** – all models from `00-master.md` defined now (some unused yet); `schemas/facts/*.schema.json` exported; drift test.
14. **First sample** – `samples/struts-oracle-app/` skeleton (enough files for inventory tests) with `expected.yaml` for inventory counts.

## Acceptance criteria

- `legacy-audit init` in an empty folder creates the starter files.
- `legacy-audit scan --config samples/struts-oracle-app/audit.yaml` produces a run folder with inventory JSON/CSV/SQLite, `run.json`, `audit-summary.json`; counts match `expected.yaml`.
- Second `scan` with no changes analyses 0 files (from log) and produces identical facts (ids equal).
- Adding a file to `audit-inputs/autosys/` changes coverage from missing to present on the next run.
- `diff` between those two runs shows the coverage change.
- A `git` source pointing at a local bare repo (test fixture) clones, scans, records SHA; token never appears in any output file (test greps outputs).
- Nothing is written inside the source tree (test compares tree hash before/after).
- Tests pass on Linux; path handling tests include Windows-style paths.
