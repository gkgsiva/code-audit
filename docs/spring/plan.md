# legacy-audit on Spring Boot and Angular: plan

Agreed with Siva on 2026-10-29. The tool is rebuilt as a **Spring Boot 4 backend (Java 25, built
with Maven)**, an **Angular 21 frontend** and a **PostgreSQL** database, started together with one
`docker compose` command. All current features move across in 10 phases (0 to 9), each its own
pull request into the branch `spring-boot-angular`, each started only after Siva's OK.

The Python tool on `office-main` stays the reference: every phase must give the same counts on
the sample apps before it is done. Since 2026-10-29 the Python code is no longer on this branch
(Siva: it is not used going forward); its results for the samples are kept as test fixtures in
`backend/audit-engine/src/test/resources/parity/`, made from `office-main`. **The PowerPoint report, its formats and the corporate .potx
templates are not part of the rewrite** (Siva, 2026-10-29); the Python tool keeps them.

## Decisions taken with the plan

1. A command-line jar for laptops stays (`java -jar legacy-audit.jar ...`), built from the same
   engine as the web app.
2. The Python tool gets bug fixes only while the rewrite runs.
3. The new code lives in `backend/` and `frontend/`; the Python code was removed from this branch
   on 2026-10-29 (decision 202).
4. Scan facts are stored in PostgreSQL **and** in the run files (`audit-output/runs/<run-id>/`).

## Architecture

| Container | What it is | What it does |
| --- | --- | --- |
| `frontend` | Angular 21 app served by nginx | The screens; passes `/api` on to the backend, so users open one address |
| `backend` | Spring Boot 4 on Java 25 | REST API, scans in the background, reports, login and roles, Git |
| `db` | PostgreSQL | Users, projects, scan history and job status, scan facts; schema changes by Flyway |

Each run still writes its snapshot to `audit-output/runs/<run-id>/` (JSON, CSV, SQLite, SBOM,
reports), so Copilot, the MCP tools and anyone reading the files see what they see today.

### Maven modules (`backend/`)

| Module | Holds | Spring? |
| --- | --- | --- |
| `audit-model` | Facts records, configuration (audit.yaml, overrides), JSON Schema | No |
| `audit-engine` | Inventory, analyzers, linker, metrics, rules, targets, security, run store | No |
| `audit-reports` (Phase 6) | Technical, functional, management and summary HTML, PDF, diagrams | No |
| `audit-ai` (Phase 7) | Enrichment validator, AI providers, Copilot files, MCP server | No (decision 230) |
| `audit-cli` | The `legacy-audit` command as one runnable jar | No |
| `audit-server` | REST API, background jobs, login and roles, admin, dashboard, Git clones | Yes |

## Parsers and libraries

| Language or feature | Python today | Java choice |
| --- | --- | --- |
| Java, Spring, Struts, Swing code | tree-sitter-java | tree-sitter-java, the same grammar (decision 208; JavaParser was the first choice) |
| C, C++, Pro*C host code | tree-sitter-c / -cpp | tree-sitter (tree-sitter-ng bindings, native libraries inside the jars); done in Phase 3 (decision 214) |
| C# | tree-sitter-c-sharp | tree-sitter (tree-sitter-ng) |
| TypeScript, Angular, AngularJS, Node | tree-sitter-typescript | tree-sitter (tree-sitter-ng) |
| SQL files, embedded and dynamic SQL | sqlglot | JSqlParser, after the same rewrites and with the same text fallback |
| COBOL, JCL, shell, Autosys JIL, IBM MQ, CFT, VB6, VB.NET | own text rules | the same rules ported to Java (all but VB in Phase 3) |
| XML (Spring, Struts, web.xml, WSDL, .csproj, servers) | ElementTree | JDK StAX / DOM |
| Maven pom.xml | own reader | maven-model |
| YAML and JSON | PyYAML, json | Jackson |
| File encodings | charset-normalizer | juniversalchardet |
| Ignore rules, Git clone and branches | pathspec, git | JGit |
| SBOM | own CycloneDX writer | cyclonedx-core-java |
| Vulnerabilities | OSV-Scanner, Dependency-Check | the same tools, called from Java |
| HTML reports | Jinja2 | own small Jinja2 in Java running the Python templates (decision 223) |
| PowerPoint | python-pptx | not carried over |
| PDF | headless Edge/Chrome | headless Chromium in the backend image |
| AI enrichment | own clients for 4 providers | own clients on the JDK HttpClient (decision 230; Spring AI was the first choice) |
| MCP server for Copilot | own server | own stdio server (decision 232; the MCP Java SDK was the first choice) |
| Command line | typer | picocli |
| Login | name-only test login | Spring Security, Okta (OIDC) ready |

## Phases

| Phase | What gets built | Gate |
| --- | --- | --- |
| 0. Skeleton and proof | Maven modules, Angular shell, `docker-compose.yml` with the `.env` build options, CI; proofs of JavaParser, tree-sitter and JSqlParser on the samples | `docker compose` shows the login page and a health check; proof report with go / no-go per library |
| 1. Core and first screens | Facts model and schema, config and overrides, file walk, ignore rules, encodings, languages, LOC, components and grouping, Maven-driven components, hash cache, run snapshots, store and exports. Screens: projects, start a scan, live status and logs, inventory | Inventory, LOC and component counts match Python on all samples |
| 2. Analyzers: Java and SQL | Java, Spring, Struts, config files, build files and dependencies, app servers, SQL files, embedded and dynamic SQL in Java | Matching sample counts |
| 3. Analyzers: mainframe and batch | C, C++, Pro*C, COBOL, embedded SQL in C, Pro*C and COBOL, JCL, shell, Autosys, IBM MQ, CFT, integrations | Matching sample counts |
| 4. Analyzers: web, front ends, desktop | Web, REST, SOAP and .NET services, TypeScript, Angular, AngularJS, Node, C#, VB6, VB.NET, WinForms, WPF, Swing | Matching sample counts |
| 5. Linking and assessment | Linker, metrics, effort, tokens, risk and PostgreSQL rules, targets, versions, EOL, upgrades, vulnerabilities, SBOM, run diff; results screens | Every `expected.yaml` passes; parity check shows no differences |
| 6. Reports | Technical, functional, management and summary HTML, Mermaid diagrams, PDF; report screens and downloads | Side-by-side check of every report page |
| 7. AI and Copilot | Enrichment stages, validator, stale detection, `init` installing the Copilot files, MCP server | Enrichment on one sample passes the validator with each provider type |
| 8. Web app complete | Component picker, re-run, compare runs, admin (users, roles), dashboard, Git clones and cleanup, remaining screens; Okta-ready login | Every page of today's hosted site has its Angular equivalent |
| 9. Packaging and switch-over | Hardened images, laptop jar, user, admin, hosting and developer docs, VS Code tasks | Full suite and all samples green in Docker on Windows and WSL2; Siva decides when the branch replaces `office-main` |

## What stays the same

- `audit.yaml`, `audit-overrides.yaml`, `audit-inputs/` and `rules/*.yaml` keep their format.
- Run folders `audit-output/runs/<run-id>/` with `latest`, the facts JSON Schema in `schemas/`,
  CSV, SQLite and CycloneDX outputs.
- The counting rules and the ground rules: scripts count and AI interprets, no network at scan
  time, never write into the scanned source, missing inputs are coverage gaps.
- The four HTML reports and PDF export (not the PowerPoint).
- The Copilot agent and prompt files and the MCP tool names.
- Command names (`scan`, `report`, `enrich`, `init`, `diff`, `validate`, `facts`, `mcp`,
  `doctor`, `purge-cache`); `serve` and `host` are replaced by the containers.
- Hosted-site behaviour: roles admin, owner, contributor, viewer; Git branch per scan; clone
  cleanup; the `.env` names for proxy, mirrors, certificates and host folders.
- One PR per phase (Siva's OK before each until 2026-10-29, then all remaining phases in a row), VS Code steps for Windows and WSL2 in
  `docs/validate-in-vscode.md`, a line per PR in `docs/history.md`.
