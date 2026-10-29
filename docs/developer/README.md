# legacy-audit developer guide

This guide explains how the Spring Boot and Angular version of legacy-audit is built: the
modules, the main classes and how a scan flows through them. It is for developers who change
or extend the tool. User and admin guides are separate.

The guide grows with each phase of the rewrite (see [docs/spring/plan.md](../spring/plan.md)).
Parts that a later phase adds are marked with that phase.

## Guides

| Guide | What it covers |
| --- | --- |
| [audit-model.md](audit-model.md) | The facts model, `audit.yaml` and overrides, JSON written like Python |
| [audit-engine.md](audit-engine.md) | The scan pipeline: file walk, inventory, components, analyzers, file cache, run folders |
| [analyzers.md](analyzers.md) | Every analyzer, the helpers they share, tree-sitter and SQL, how to add or change one |
| [audit-server.md](audit-server.md) | The web backend: REST API, login and roles, projects, background scans, database |
| [audit-cli.md](audit-cli.md) | The command-line jar `legacy-audit.jar` |
| [frontend.md](frontend.md) | The Angular app: routes, screens, services, styles, tests |
| [testing.md](testing.md) | Tests, the parity checks against the Python tool and how to add test data |

## Class reference

- **Backend (Javadoc)**: VS Code task **`audit: 9. build the developer reference (Javadoc)`**
  (or `mvn javadoc:aggregate` in `backend/`) writes
  `backend/target/reports/apidocs/index.html`. Every class has a description; package-private
  classes are included.
- **Frontend**: the Angular classes carry TSDoc comments; a generated reference (Compodoc) is
  added together with the remaining screens (Phase 8).

## Repository layout

```
backend/                 Maven multi-module build (Java 25, Spring Boot 4)
  pom.xml                parent: versions of every library, plugin settings
  audit-model/           facts, config, JSON (no Spring)
  audit-engine/          the scan itself (no Spring)
  audit-cli/             legacy-audit.jar, the command line (picocli)
  audit-server/          the web backend (Spring Boot, PostgreSQL)
  Dockerfile             backend image
frontend/                Angular 21 app, served by nginx in Docker
docker/                  env.example, build helpers (spring/), company certificates folder
docker-compose.yml       db (PostgreSQL 17) + backend + frontend, site on port 8770
samples/                 ten small legacy apps used by the tests and the VS Code tasks
rules/                   rule packs (risk, PostgreSQL, end of life, upgrades, targets), YAML
schemas/                 JSON Schemas (CycloneDX, AI enrichment)
docs/                    decisions, progress, history, VS Code steps, this guide
.vscode/                 tasks "audit: N. ..." and recommended extensions
```

## How the modules fit together

```
            audit-model   (facts, config, PyJson)
                 ^
                 |
            audit-engine  (Scanner, analyzers, inventory, store)
              ^       ^
              |       |
       audit-cli     audit-server  <--- HTTP /api --- frontend (Angular, nginx)
       (picocli)     (Spring Boot, PostgreSQL)
```

- **audit-model** and **audit-engine** are plain Java with no Spring, so the engine runs the
  same in the laptop jar and in the web backend.
- **audit-cli** wraps the engine in commands (`scan` now; `report`, `enrich`, `diff` ... in
  later phases).
- **audit-server** stores projects, members and scans in PostgreSQL, runs scans in the
  background through the engine, and serves `/api` to the Angular app.
- Later phases add `audit-reports` (Phase 6, HTML and PDF reports) and `audit-ai` (Phase 7,
  enrichment and the MCP server).

## What a scan does

1. `ConfigLoader` reads `audit.yaml` into an `AuditConfig` (audit-model).
2. `Scanner.run` (audit-engine) walks the source folders (`Walker`, `.gitignore` rules), and for
   each file `FileAnalyzer` finds the encoding, language, category and lines of code.
3. Every analyzer that supports the file reads it and returns `Facts` (entities, symbols,
   references, coverage). Results are kept per file in the file cache (`FileCache`), so an
   unchanged file is not read again.
4. Components are found from build files and folders (`ComponentDetector`, `Grouping`), then the
   overrides of `audit-overrides.yaml` are applied.
5. The run folder `audit-output/runs/<run-id>/` gets `run.json`, the logs and `data/` with JSON,
   CSV and SQLite (`FactWriter`).
6. Later phases add: linking the facts across files (Phase 5), metrics, rules and effort
   (Phase 5), reports (Phase 6) and AI enrichment (Phase 7).

## Ground rules for the code

- **Same results as the Python tool.** The Python tool on the `office-main` branch is the
  reference. Each phase is done when its results match Python on the sample apps and on cases
  recorded from Python (see [testing.md](testing.md)). Where Java and Python behave differently
  (regular expressions, string strip, JSON number format, sorting), small helpers give Python's
  behaviour: `Rx`, `TextUtil`, `PyJson`, `PyUrl`, `PyError`.
- **Scripts count, AI interprets.** The engine never calls an AI or the network during a scan
  and never writes into the scanned source.
- **Missing input is a coverage gap, not an error.** An analyzer that cannot read a file
  records the unread lines in its coverage; a crash becomes a finding, not a failed scan.
- **Plain words for people.** Messages shown to users are short and non-technical.
- Decisions with their reasons are in [docs/decisions.md](../decisions.md); each pull request
  adds a line to [docs/history.md](../history.md).

## Build and run

| What | How |
| --- | --- |
| Backend build and tests | `mvn -B verify` in `backend/` (task 4); needs Java 25 and Maven |
| Frontend build and tests | `npm ci`, `npx ng test --watch=false`, `npx ng build` in `frontend/` (task 5); needs Node.js 22 |
| Everything in Docker | `docker compose up -d --build` (task 1), then http://localhost:8770 |
| Scan a sample from the command line | task 7, or `java -jar backend/audit-cli/target/legacy-audit.jar scan -c samples/<app>/audit.yaml -o <out>` |
| Compare with the Python tool | task 8 |
| Class reference | task 9 |

Docker settings (proxy, mirrors, company certificates, folders) are explained in
[docs/spring/docker.md](../spring/docker.md).
