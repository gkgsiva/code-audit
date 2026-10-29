# code-audit (legacy-audit)

A tool for auditing legacy applications before migrating them to **Java 25, Spring Boot 4.x,
Angular 21, PostgreSQL and Google Cloud**.

It scans a codebase (Java/Struts/Spring, Java Swing, C/C++, Pro*C, COBOL, JCL, shell, Autosys
JIL, IBM MQ, CFT, Angular/Node.js, VB6, VB.NET, C#/.NET) and produces reproducible facts (JSON,
CSV, SQLite, SBOM) and reports. It can be re-run any number of times as new inputs arrive.

This branch (`spring-boot-angular`) rebuilds the tool as a **Spring Boot 4 backend** (Java 25,
Maven), an **Angular 21 frontend** and a **PostgreSQL** database, started with one
`docker compose` command, phase by phase: see [`docs/spring/plan.md`](docs/spring/plan.md) and
[`docs/progress.md`](docs/progress.md). The earlier Python version is on the branch
`office-main`; it is no longer used. The PowerPoint report is not part of the new version.

## Start it

1. Install Docker (Docker Desktop on Windows).
2. Copy `docker/env.example` to `.env` and fill in the folders (see
   [`docs/spring/docker.md`](docs/spring/docker.md)).
3. `docker compose up -d --build`, then open http://localhost:8770 and log in as `admin`.

## Folders

| Folder | What it holds |
| --- | --- |
| `backend/` | Maven modules: `audit-model` (facts, config), `audit-engine` (the scan), `audit-cli` (the `legacy-audit` command), `audit-server` (the web app's REST API) |
| `frontend/` | The Angular app |
| `docker/` | `env.example`, company certificates, build helpers |
| `samples/` | Ten small sample applications the tests scan |
| `rules/` | Rule packs (YAML) |
| `schemas/` | JSON Schema of the facts |
| `docs/` | Plan, decisions, progress, history (one line per pull request), VS Code checks |

## Validating from VS Code

Open the folder in VS Code and use **Terminal > Run Task...**: `audit: 1. start the web app
(Docker)`, `2. stop the web app`, `3. build the web app and save build.log`, `4. backend
build and tests`, `5. frontend build and tests` and `6. parser proof report`. What to check
after each phase is in [`docs/validate-in-vscode.md`](docs/validate-in-vscode.md).

## Development

```
cd backend && mvn -B verify                     # Java 25 and Maven
cd frontend && npm ci && npx ng test --watch=false && npx ng build   # Node.js 22
```
