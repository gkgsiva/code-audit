# code-audit (legacy-audit)

A tool for auditing legacy applications before migrating them to **Java 25, Spring Boot 4.x,
Angular 21, PostgreSQL and Google Cloud**.

It scans a codebase (Java/Struts/Spring, Java Swing, C/C++, Pro*C, COBOL, JCL, shell, Autosys
JIL, IBM MQ, CFT, Angular/Node.js, VB6, VB.NET, C#/.NET) and produces reproducible facts (JSON,
CSV, SQLite, SBOM) and reports. It can be re-run any number of times as new inputs arrive.

This branch (`spring-boot-angular`) rebuilds the tool as a **Spring Boot 4 backend** (Java 25,
Maven), an **Angular 21 frontend** and a **PostgreSQL** database, started with one
`docker compose` command, in phases 0 to 9, all done: see [`docs/spring/plan.md`](docs/spring/plan.md) and
[`docs/progress.md`](docs/progress.md). The earlier Python version is on the branch
`office-main`; it is no longer used. The PowerPoint report is not part of the new version.

## Start it

1. Install Docker (Docker Desktop on Windows).
2. Copy `docker/env.example` to `.env` and fill in the folders (see
   [`docs/spring/docker.md`](docs/spring/docker.md)).
3. `docker compose up -d --build`, then open http://localhost:8770 and log in as `admin`.

On a laptop without Docker, build the `legacy-audit` command (Java 25 and Maven):
`cd backend && mvn -B -DskipTests package -pl audit-cli -am`, then use
`backend/audit-cli/target/legacy-audit.jar` with its launchers (see
[`docs/admin-guide.md`](docs/admin-guide.md)).

## Guides

| Guide | For |
| --- | --- |
| [`docs/user-guide.md`](docs/user-guide.md) | Analysts: one audit on the web app, step by step |
| [`docs/admin-guide.md`](docs/admin-guide.md) | Whoever runs the site or installs the laptop command |
| [`docs/copilot-agent.md`](docs/copilot-agent.md) | AI enrichment with GitHub Copilot in VS Code |
| [`docs/rules.md`](docs/rules.md) | Writing rule packs |
| [`docs/performance.md`](docs/performance.md) | Large estates: times and memory |
| [`docs/spring/docker.md`](docs/spring/docker.md) | Docker settings at the office |

## Folders

| Folder | What it holds |
| --- | --- |
| `backend/` | Maven modules: `audit-model` (facts, config), `audit-engine` (the scan), `audit-reports` (reports and PDFs), `audit-ai` (enrichment, Copilot files, MCP), `audit-cli` (the `legacy-audit` command), `audit-server` (the web app's REST API) |
| `frontend/` | The Angular app |
| `docker/` | `env.example`, company certificates, build helpers |
| `samples/` | Ten small sample applications the tests scan |
| `rules/` | Rule packs (YAML) |
| `schemas/` | JSON Schema of the facts |
| `docs/` | Guides, plan, decisions, progress, history (one line per pull request), VS Code checks |

## Validating from VS Code

Open the folder in VS Code and use **Terminal > Run Task...**. The tasks are numbered
`audit: 1.` to `audit: 22.`: start and stop the web app (1, 2), build log (3), backend and
frontend builds and tests (4, 5), parser proofs (6), scan a sample (7), comparisons with the
Python tool (8, 10, 12, 14, 17), last two scans (11), reports (13), AI enrichment (15, 16),
tools on this computer (18), admin pages (19), Git projects (20), the Java and Angular
references (9, 21) and the laptop command (22). What to check after each phase is in
[`docs/validate-in-vscode.md`](docs/validate-in-vscode.md).

## Development

How the code is organised, class by class and module by module:
[`docs/developer/README.md`](docs/developer/README.md).

```
cd backend && mvn -B verify                     # Java 25 and Maven
cd frontend && npm ci && npx ng test --watch=false && npx ng build   # Node.js 22
```
