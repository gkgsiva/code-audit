# CLAUDE.md — Legacy Code Audit Tool

This repository builds **legacy-audit**: a tool a cloud-migration team uses to audit legacy applications (Java/Struts/Spring, C/C++, Pro*C, COBOL, JCL, shell, Autosys, IBM MQ, CFT) before modernizing them to **Java 25, Spring Boot 4.x, Angular 21, PostgreSQL and GCP managed services**.

## Read these first

1. `docs/plan.md` — the full specification. Treat it as the source of truth for scope, data model, counting rules and outputs.
2. `docs/decisions.md` — decisions taken after the plan was written. Wins over `plan.md` where they differ.
3. `docs/prompts/00-master.md` — how the build is organised.
4. `docs/prompts/phase-N-*.md` — the phase you are working on. Work one phase at a time, in order.
5. `docs/progress.md` — what has been done so far. Read it at the start of every session; update it at the end of every phase (and whenever you stop mid-phase).

If the user says "continue" or "start", find the first phase in `docs/progress.md` that is not marked done and work on it.

## What the tool is

- A deterministic **Python 3.11+ scan engine** (`legacy_audit/`) that walks a legacy codebase and writes facts (JSON, CSV, SQLite, CycloneDX SBOM).
- **GitHub Copilot custom-agent files** that `legacy-audit init` installs into the *legacy* workspace, so analysts can run AI enrichment (architecture, flows, risks, plan) in VS Code. The analysts use Copilot; Claude Code is only used to *build* this tool.
- **Report generators**: technical HTML site, management HTML deck, and an editable PPTX (Node + pptxgenjs).
- A **local web GUI** (FastAPI) to pick components, run and re-run scans, and view results.

## Non-negotiable rules

1. **Scripts count, AI interprets.** Every number in any output comes from deterministic code and carries `evidence: [{file, line}]`. Nothing in the engine calls an LLM.
2. **No external network at scan time.** No cloud APIs, no telemetry, no CDN links in generated HTML — vendor all JS/CSS. The only optional network use is cloning a Git source and syncing the vulnerability database, both explicit.
3. **Never write into the scanned source.** Outputs go to `audit-output/`, inputs come from the source tree and `audit-inputs/`.
4. **Re-runnable.** Every run is a snapshot under `audit-output/runs/<run-id>/` with `latest` pointing to the newest; unchanged files are skipped via a hash cache; linking, metrics, rules and totals are always recomputed; `audit-overrides.yaml` is re-applied every run.
5. **Missing inputs are coverage gaps, not errors.** Report them (e.g. "Autosys JIL: not provided") and continue.
6. **Secrets.** Never write tokens or passwords to config, outputs or logs. Mask credentials found in scanned config.
7. **Counting rules are code and docs.** They live in config with defaults from `docs/plan.md` § Counting rules, and every report prints them.

## Tech choices

- Python 3.11+, packaged with `pyproject.toml` (hatchling), CLI with `typer`, models with `pydantic` v2, templates with `jinja2`, SQL parsing with `sqlglot`, Java parsing with `javalang` or `tree-sitter` (pick one per analyzer and note why), GUI with `fastapi` + `uvicorn` and a plain HTML/JS front-end (no build step).
- Node 18+ only for `pptx/` (`pptxgenjs`) and Mermaid rendering (`@mermaid-js/mermaid-cli`), both optional at runtime: if Node is missing, PPTX/PNG generation is skipped with a clear message.
- OWASP Dependency-Check / OSV-Scanner are external optional tools, detected on PATH or configured paths.
- Keep runtime dependencies small; every new dependency needs a one-line reason in `docs/decisions.md`.

## Conventions

- Layout as in `docs/plan.md` § Project structure.
- Each analyzer is a plugin implementing a common interface (`supports(file) -> bool`, `analyze(file, ctx) -> Facts`), registered in one place; analyzers never touch the store directly.
- Schemas: JSON Schema files in `schemas/` generated from or checked against the pydantic models; a test fails if they drift.
- Tests with `pytest`. Every analyzer has unit tests on small fixtures, and every sample app in `samples/` has an `expected.yaml` of counts that an integration test checks.
- Lint/format with `ruff`; type-check with `mypy` (strict on `legacy_audit/`, lenient on tests).
- Log with the standard `logging` module; a run log is written to `audit-output/runs/<id>/logs/`.
- Cross-platform: the team mostly uses Windows laptops. Use `pathlib`, avoid shell-only tricks, test path handling with Windows-style paths.

## Working style for this repo

- Before coding a phase, restate its acceptance criteria and list the files you will create.
- Commit in small, working steps with clear messages; keep `main` green (tests pass).
- When the spec is ambiguous, pick the simplest option consistent with the rules above, record it in `docs/decisions.md`, and carry on; ask only for decisions that are expensive to reverse.
- At the end of a phase: run the full test suite, run the CLI on every sample app, update `docs/progress.md` (done, known gaps, next step), add the phase's section to `docs/validate-in-vscode.md`, and summarise for the user, including how to validate the phase from VS Code.
