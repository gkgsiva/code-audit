# legacy-audit: rules and conventions for working on this repository

This repository builds **legacy-audit**, a tool a cloud-migration team uses to audit legacy
applications (Java/Struts/Spring, Swing, C/C++, Pro*C, COBOL, JCL, shell, Autosys, IBM MQ, CFT,
Angular/Node.js, VB6, VB.NET, C#/.NET) before moving them to **Java 25, Spring Boot 4.x,
Angular 21, PostgreSQL and Google Cloud**.

(These instructions are for developers of the tool. The Copilot files that `legacy-audit init`
installs into an audited application are in `backend/audit-ai/src/main/resources/legacyaudit/ai/copilot/`.)

## Read these first

1. `docs/developer/README.md`: the modules, class by class, and how to add to each.
2. `docs/decisions.md`: every decision with its reason; the newest win over older ones and over
   `docs/spring/plan.md`.
3. `docs/progress.md`: what is done and the known gaps.
4. `docs/validate-in-vscode.md`: how each change is checked from VS Code (tasks `audit: N. ...`).

## What is where

- `backend/` (Java 25, Maven): `audit-model` (config and facts), `audit-engine` (the scan:
  inventory, analyzers, linker, metrics, rules, SQL to PostgreSQL, security, results store),
  `audit-reports` (HTML reports with the Jinja templates, PDFs with PDFBox), `audit-ai`
  (validator, model clients, prompts, Copilot files, MCP server, doctor), `audit-cli` (the
  `legacy-audit` command, picocli), `audit-server` (Spring Boot REST API, security, background
  jobs, admin, Git clones; Flyway migrations for PostgreSQL).
- `frontend/` (Angular 21, standalone components, signals): one folder per page, every API call
  in `api.service.ts`.
- `rules/` rule packs, `schemas/` JSON Schemas, `samples/` sample applications with
  `expected.yaml`, `docker/` and `docker-compose.yml` for the web app.

## Non-negotiable rules

1. **Scripts count, AI interprets.** Every number in an output comes from deterministic code and
   carries evidence (`file`, `line`). Nothing in the engine or the reports calls a model; only
   `legacy-audit enrich` and the web app's **Run here** do, when asked.
2. **No network at scan time**, except what the user chose: cloning a Git source and
   OSV-Scanner's online lookup. No CDN links in generated HTML; everything is bundled.
3. **Never write into the scanned source.** Outputs go to `audit-output/`; inputs come from the
   source and `audit-inputs/`.
4. **Re-runnable.** Every run is a folder under `audit-output/runs/<run-id>/`; unchanged files
   come from the cache; linking, metrics, rules and totals are always worked out again;
   `audit-overrides.yaml` applies on every run.
5. **Missing inputs are gaps, not errors.** Report them and carry on.
6. **Secrets** (Git tokens, AI keys) come from environment variables and never reach settings,
   outputs or logs; passwords found in scanned files are masked.
7. **Same results as before.** The parity tests (`*ParityTest`) compare facts, reports, prompts
   and settings files with what the earlier version wrote. A change that alters an output must
   say why in `docs/decisions.md` and update the fixtures on purpose.

## Conventions

- Java: plain classes and records, Javadoc on every class and public method; no Lombok, no JPA
  (`JdbcClient` with plain SQL that both PostgreSQL and H2 accept). A new migration is a new
  `V<n>__what.sql`; never edit a merged one.
- REST: controllers in the feature's package, access checks through `ProjectService` and
  `CurrentUser`, refusals as `ApiException` with plain words for non-technical users.
- Angular: a standalone component per page with its `.html`, TSDoc on the class, a
  `<page>.spec.ts` that answers the API calls with `HttpTestingController`.
- Every new dependency needs a line in `docs/decisions.md` with the reason.
- Cross-platform: most users are on Windows laptops, some on WSL2. Use `Path`, never shell-only
  tricks, and test paths with Windows separators.
- Words on screen and in user docs: short, plain, step by step.

## Finishing a change

1. `cd backend && mvn -B verify` and `cd frontend && npm ci && npx ng test --watch=false && npx ng build`.
2. Update `docs/progress.md`, `docs/decisions.md`, `docs/developer/` and add a section with
   numbered steps to `docs/validate-in-vscode.md` (Windows and WSL2), plus a VS Code task when a
   new check needs one.
3. Add one line to `docs/history.md` for the pull request. Commit messages are plain
   descriptions of the change.
