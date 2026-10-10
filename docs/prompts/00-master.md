# Master prompt — building legacy-audit

You are building **legacy-audit**, described in `docs/plan.md` (spec), `docs/decisions.md` (later decisions, which win) and `CLAUDE.md` (rules and conventions). Read all three before writing code.

## Build order

| Phase | Prompt | Outcome |
| --- | --- | --- |
| 1 | `phase-1-foundations.md` | Package, config, source handling, inventory, store, runs, CLI |
| 2 | `phase-2-analyzers.md` | Language/framework analyzers, linker, services/screens/SQL/integrations/batch/libraries |
| 3 | `phase-3-rules-metrics-security.md` | Metrics, rule packs, PostgreSQL grading, OWASP dependency check, SBOM, effort |
| 4 | `phase-4-tech-report.md` | Static technical HTML site |
| 5 | `phase-5-agent-enrichment-mgmt.md` | Copilot agent assets + installer, enrichment schemas/validator/staleness, MCP server, management deck + PPTX |
| 6 | `phase-6-gui-hardening.md` | FastAPI GUI, run compare, performance, packaging, user guide |

Do one phase per session if possible. Each phase must leave `main` working: tests green, CLI runnable on every sample app.

## Definition of done (every phase)

- Acceptance criteria in the phase prompt all met, each with a test or a demonstrable CLI command.
- `ruff check`, `mypy legacy_audit`, `pytest` pass.
- `docs/progress.md` updated; new decisions appended to `docs/decisions.md`.
- `README.md` "Usage" section reflects what now works.
- `docs/validate-in-vscode.md` has a section for the phase: the VS Code tasks (`.vscode/tasks.json`) or debug configurations to run and what to look at in the outputs, so the team can validate the phase themselves; the end-of-phase summary repeats those steps.

## Fact model (shared by all phases)

Define once in `legacy_audit/model/` with pydantic and export JSON Schemas to `schemas/facts/`:

`FileRecord, Component, EntryPoint, Service, Screen, SqlStatement, DbObject, Integration, CodeEdge, BatchJob, Library, TechItem, Finding, Metric, RunInfo, InputCoverage` — fields as in `docs/plan.md` § Data model. Every fact has a stable `id` (deterministic hash of kind + key fields, so ids survive re-runs) and `evidence: list[Evidence(file, line, snippet?)]`.

## Sample apps (built in phase 1 and 2, used by every phase)

`samples/` holds small synthetic legacy apps, each with an `expected.yaml` listing exact counts (files by language, services, screens, SQL statements by PG grade, integrations, batch jobs, libraries, notable findings). Integration tests run the scanner on each and compare. Write the samples by hand; keep each under ~2,000 lines. See `samples/README.md` for the list.

## Things to avoid

- No LLM calls, no network calls during scan (except explicit git clone / vuln DB sync).
- No CDN references in generated HTML.
- No writing inside the scanned source tree.
- No counting logic inside report templates — templates only display facts.
