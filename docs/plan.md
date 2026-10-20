# Legacy Code Audit Tool — Solution Plan

Source: the team's plan document, exported 2026-10-10. This file is the specification the build follows. Decisions taken in discussion are summarised in `docs/decisions.md`; where the two differ, `decisions.md` wins.

## Recommendation

Build a hybrid: a deterministic Python scan engine as the core, a GitHub Copilot custom agent in VS Code for the AI analysis, and a small local web GUI on top of the same engine. Neither an agent alone nor a GUI alone does the job well.

| Option | Strength | Weakness | Verdict |
| --- | --- | --- | --- |
| Copilot agent only | Flexible reasoning, no UI to build | Cannot hold a large codebase in context; counts vary run to run; hard to repeat across 20 apps | Use for interpretation, not counting |
| GUI app only | Repeatable, easy for non-developers | Copilot has no API for external apps, and external LLMs are not approved, so a GUI alone has no AI | Use for selection, runs and viewing |
| Hybrid (recommended) | Numbers come from scripts and are reproducible; AI adds architecture, risks, narrative; one engine serves both | Two front-ends to maintain | Best fit |

The key design rule: **scripts count, AI interprets.** Every number in the reports (services, screens, queries, LOC) comes from the scanner and is traceable to a file and line. The agent reads the scanner's JSON, opens sample files to confirm, and writes enrichment (architecture, flows, risks, assumptions, target mapping, plan) back as JSON that the report generator merges.

How people use it:

1. Analyst opens the legacy repo in VS Code and runs the GUI (or the CLI) to pick components and start the scan.
2. Scanner writes facts to `audit-output/data/` (JSON + CSV + SQLite).
3. Analyst switches Copilot Chat to the **Legacy Auditor** agent and runs `/audit-enrich`; the agent works through the facts stage by stage.
4. `/audit-report` builds the HTML technical documentation, the HTML management deck and the PPTX.
5. The GUI shows results, charts and links to every output.

## Audit scope and who produces each answer

| # | Dimension | Produced by | Main evidence |
| --- | --- | --- | --- |
| 1 | Current technology stack | Scanner | File extensions, build files (pom.xml, build.xml, Makefile, JCL), library jars, framework config, compiler/runtime versions |
| 2 | Modules (web, batch, CFT, script, report, utility) | Scanner + agent | Folder layout, entry points (`main`, servlets, Struts actions, Spring controllers, JCL steps, cron/shell); agent confirms ambiguous ones |
| 3 | External communications | Scanner | URLs, hosts, IBM MQ/JMS queues, SOAP/WSDL, sockets, FTP/SFTP/CFT, email, file drops |
| 4 | Architecture | Agent | Module and dependency graph from scanner |
| 5 | Communication flow | Scanner + agent | Call graph, screen → action → service → DB chains, batch job chains |
| 6 | Number of services | Scanner | Classified service units (see Counting rules) |
| 7 | Number of DB queries | Scanner | Embedded SQL, JDBC/JdbcTemplate strings, ORM/iBATIS/MyBatis maps, HQL/JPQL, Pro*C and COBOL EXEC SQL, stored procedures; Oracle/DB2 syntax flagged for PostgreSQL |
| 8 | Target stack | Fixed input | Java 25, Spring Boot 4.x, Angular 21, PostgreSQL, GCP managed services (configurable) |
| 9 | Services to migrate | Scanner + agent | Service inventory with complexity and target mapping |
| 10 | Number of screens | Scanner | JSP/JSF/HTML/ASP pages, Struts forward targets, Spring MVC views, CICS/BMS maps, Pro*C/terminal forms |
| 11 | Risks | Agent | Rule hits from scanner (dead code, hard-coded IPs, vendor SQL, no tests, vulnerable dependencies) plus agent judgement |
| 12 | Assumptions | Agent | Gaps in scanned evidence and missing inputs |
| 13 | Target architecture | Agent | Mapping rules + module graph |
| 14 | Detailed migration plan | Agent | Waves derived from dependencies and complexity |
| 15 | Other | Both | Dependency health (OWASP vulnerabilities, EOL, licences, upgrades), code quality, duplication, dead code, test coverage, config and secrets, data volume questions, effort estimate |

## Solution architecture

Five layers share one fact store: the GUI and the Copilot agent both drive the same scan engine, and every report reads the same facts plus the agent's validated enrichment.

```
                     Legacy workspace (code + late inputs: Autosys JIL, MQ, CFT exports)
                                         |
        Local GUI (FastAPI + browser)    |     Copilot agent "Legacy Auditor"
        pick components, run, view       |     runs CLI, reasons over facts
                     \                   |                 /
                      v                  v                v
              +-----------------------------------------------------+
              |   Python scan engine (deterministic)                |
              |   inventory, classify, analyze, link, metrics,      |
              |   rules, export                                     |
              +-----------------------------------------------------+
                     |                                    ^
                     v                                    | agent writes
          Fact store (JSON/CSV/SQLite) ----------> Enrichment JSON (validated)
                     \                                   /
                      v                                 v
              Report generators (Jinja2 + Chart.js + Mermaid; pptxgenjs)
                 |                    |                       |
             Data pack         Technical HTML         Management deck (HTML + PPTX)
```

The agent reads the fact store and writes enrichment; it never edits the facts. All AI work runs through the team's Copilot; no external LLM is used.

## Python scan engine

A plugin-based Python 3.11+ package (`legacy_audit`) with one analyzer per technology, a shared fact store, and a CLI the GUI and the agent both call. It runs offline, needs no compiler for the legacy code, and should process 1M+ LOC in minutes.

**Pipeline stages** (each writes its own output so a run can resume):

1. **Inventory** – walk the workspace, honour include/exclude globs, hash every file, detect language by extension + content sniffing (COBOL fixed-format columns, `EXEC SQL`), detect encoding (EBCDIC exports, Shift-JIS, cp1252).
2. **Classify** – tag files as source, config, build, SQL, screen, job, script, binary, test, generated.
3. **Analyze** – run the matching analyzer per file; each emits normalized facts (components, entry points, SQL statements, calls, external endpoints, screens, dependencies, findings).
4. **Link** – resolve references across files: Struts action → JSP, Spring controller → view, class → class, COBOL `CALL`/`COPY`, JCL step → program, Autosys job → script → program, SQL → table.
5. **Metrics** – LOC (code/comment/blank), cyclomatic complexity, fan-in/fan-out, duplication, dead-code candidates.
6. **Rules** – risk, PostgreSQL-compatibility and dependency rules (YAML), producing findings with severity.
7. **Export** – JSON, CSV, SQLite, CycloneDX SBOM, and a compact `audit-summary.json` sized for the agent's context.

**Analyzers** (start with regex + lightweight parsers; swap in real parsers where available):

| Technology | Parser approach | Key facts extracted |
| --- | --- | --- |
| Java (plain, EJB) | `javalang` or tree-sitter-java | packages, classes, methods, annotations, imports, JDBC/JPA/iBATIS SQL (including SQL built at run time, see *Dynamic SQL* below), REST/SOAP clients, `main()` and schedulers |
| Struts 1/2, JSP, JSF | XML parse of struts-config.xml / struts.xml, web.xml; JSP tag scan | actions, form beans, forwards, screens, filters, servlets, tiles |
| C / C++ | tree-sitter-c / tree-sitter-cpp | functions, `#include` graph, `main()`, sockets, file I/O, system calls, shared libs, SQL passed to ODBC / DB2 CLI / OCI / libpq / MySQL / SQLite calls and SQL built in `char` buffers |
| Pro*C / Pro*COBOL | pre-scan `EXEC SQL ... ;` blocks, then C analyzer | embedded SQL, cursors, host variables, Oracle-specific syntax, `PREPARE ... FROM :buf` / `EXECUTE IMMEDIATE :buf` rebuilt from the C that fills the buffer |
| COBOL | regex divisions + copybook resolver (optionally ProLeap or Koopa) | programs, paragraphs, `CALL`, `COPY`, `EXEC SQL` (dynamic SQL rebuilt from `VALUE`/`MOVE`/`STRING`), `EXEC CICS`, file `SELECT`s, BMS maps |
| JCL | line parser | jobs, steps, `PGM=`, DD datasets, job dependencies |
| Shell, Perl, Python, batch | regex + shebang | invoked programs, cron entries, ftp/sftp/scp/CFTUTIL commands, DB clients (sqlplus, db2) |
| SQL / PL/SQL / stored procs | `sqlglot` (Oracle/DB2/T-SQL dialects) | tables, DML/DDL type, joins, vendor functions, transpile test to PostgreSQL |
| Config (properties, XML, YAML, INI) | parsers | JDBC URLs, hosts, ports, queue names, credentials (masked) |
| Build (Maven, Ant, Gradle, Make) | parsers | dependencies and versions, build targets, Java/C++ version |

**Estate-specific analyzers** (Autosys, IBM MQ and CFT appear in most of the team's applications):

- **Autosys JIL** – parse `insert_job` definitions: job type, box membership, command, machine, `condition` dependencies (success, failure, done), start times and days. Link each command to the script or program in the repo; output job chains with a dependency graph.
- **IBM MQ** – find queue managers, queues and channels in code (JMS / MQ Java classes, MQI calls such as `MQOPEN`, `MQPUT`, `MQGET` in C and COBOL), in config, and in MQSC exports; record producer and consumer per queue.
- **CFT** – parse CFT configuration exports (partner, send and receive definitions) and `CFTUTIL` calls in scripts; record partner, flow id, direction and file pattern as integrations.

**Spring and Java framework analyzers** (many legacy Java apps are older Spring rather than Struts):

- **Spring core** – XML bean config (`applicationContext*.xml`, `*-servlet.xml`) and annotation config (`@Configuration`, `@Component`, `@Service`, `@Repository`); bean graph, profiles, property sources.
- **Spring MVC / Web Flow** – `@Controller`/`@RequestMapping` handlers and XML URL mappings counted as services; view names resolved to JSP/Thymeleaf/Velocity screens; Web Flow definitions as multi-step screens.
- **Spring Boot (1.x/2.x/3.x)** – `@SpringBootApplication`, starters, `application.properties/yml`, actuator; version recorded for the upgrade path.
- **Spring Batch** – job and step definitions (XML and Java), readers/writers, linked to the Autosys jobs that launch them.
- **Spring data access** – `JdbcTemplate`/`NamedParameterJdbcTemplate` SQL, Spring Data repositories (derived queries, `@Query`), Hibernate/JPA entities and HQL/JPQL, iBATIS/MyBatis mappers — all fed into the SQL catalogue and PostgreSQL grading.
- **Spring Integration / JMS / remoting** – `JmsTemplate`, listener containers, MQ connection factories, HTTP invoker, RMI and Spring-WS endpoints, recorded as integrations.
- **Spring Security** – auth mechanism (LDAP, form, SiteMinder headers, custom filters), mapped to the target identity option.
- **Upgrade signals** – `javax.*` imports needing the Jakarta namespace, removed APIs, XML-only config, Spring version; used to size the move to Spring Boot 4.

**Dynamic SQL (built at run time):** SQL that code assembles is rebuilt into the statement it sends, not only literals at a call site. The scripts follow the variable that holds the text, in source order, up to the call that executes it:

- **Java** – `+` / `+=` concatenation, `s = s + ...`, `StringBuilder`/`StringBuffer`/`StringJoiner` `append` chains, `String.format`/`formatted`, `concat`, ternaries, constants.
- **C / C++** – `strcpy`/`strncpy`, `strcat`/`strncat`, `sprintf`/`snprintf` (also writing at `buf + strlen(buf)`), `#define` and file-level string constants, ternaries; executed by ODBC / DB2 CLI (`SQLExecDirect`, `SQLPrepare`), OCI (`OCIStmtPrepare`), libpq, MySQL, SQLite calls.
- **Pro*C** – the same C, executed by `EXEC SQL PREPARE s FROM :buf` / `EXECUTE IMMEDIATE :buf` (`VARCHAR` host variables through `.arr`).
- **COBOL / Pro*COBOL** – `VALUE` clauses, `MOVE`, `STRING ... DELIMITED BY ... INTO` (with `WITH POINTER` or the target as first operand = append), `INITIALIZE`, DB2 varying-length host variables (level-49 text item), executed by `PREPARE ... FROM :var` / `EXECUTE IMMEDIATE :var`.
- **MyBatis / iBATIS** – dynamic tags (`<if>`, `<where>`, `<foreach>`, `isNotEmpty`...) and `${...}` / `$x$` substitutions.

Code inside `if` / loops / `switch` / `catch` (COBOL: `IF`, `EVALUATE`, inline `PERFORM`) is conditional, so what it adds is an *optional fragment*. A piece only known at run time (a parameter, user input, a call result) becomes `?` and is classified from the text before it: a **value hole** (after `=`, a comparison or arithmetic operator, inside quotes or an `IN (...)`/`VALUES (...)` list — an injection risk) or a **structural hole** (a table, column or clause — the statement itself is unknown). Each statement records how it was built, its fragment, optional-fragment and hole counts, up to 5 *variants* (without the optional parts, other ternary branches or reassignments) and evidence for every fragment's line. A buffer, constant or variable that is only used as a fragment is not counted as a statement of its own. When the variable cannot be followed (filled in another file or from input), the statement stays type `DYNAMIC` with one structural hole.

**PostgreSQL readiness check:** every extracted SQL statement is parsed with `sqlglot` in its source dialect and transpiled to PostgreSQL. Statements are graded *auto* (transpiles cleanly), *review* (vendor functions like `NVL`, `DECODE`, `ROWNUM`, `CONNECT BY`, hints, `(+)` joins) or *manual* (PL/SQL packages, DB2-specific, unparseable, and dynamic SQL with a structural hole or that could not be rebuilt). Rebuilt dynamic SQL without structural holes is graded on its full text and on each variant, the worst grade wins, and it is at least *review* (each variant must be checked).

**CLI:** `legacy-audit init`, `scan --config audit.yaml`, `report --type tech|mgmt|pptx|all`, `validate`, `diff`, `serve` (GUI), `purge-cache`. The same commands are what the Copilot agent runs in the terminal.

**Config file (`audit.yaml`):** application name, source (local paths or optional Git repos), included components, exclusions, language overrides, supplementary inputs folder, target stack and versions, rule packs to enable, complexity and effort weights, branding.

## Data model (JSON / CSV / SQLite)

One normalized fact model feeds every output. JSON is the source of truth, CSV is a flat export per entity for Excel users, SQLite lets the GUI and agent query without loading everything.

| File | One row per | Key fields |
| --- | --- | --- |
| `inventory.csv/json` | file | path, language, category, LOC code/comment/blank, size, hash, encoding, component |
| `components.json` | module/component | id, name, type (web, batch, online-mainframe, CFT, script, config, report, library, DB), roles (every kind of work it holds), composition (files by category), technology, files, LOC, entry points |
| `services.csv/json` | service unit | id, name, kind (Struts action, Spring handler, servlet, EJB, SOAP op, COBOL program, batch job, C daemon), component, inputs/outputs, complexity, tables used, external calls |
| `screens.csv/json` | screen | id, name, technology (JSP, JSF, BMS, form), action, fields count, component, complexity |
| `sql_statements.csv/json` | SQL statement | id, file, line, type (SELECT/INSERT/…/PROC/DYNAMIC), source, text, tables, dialect, dynamic yes/no, built_with, fragments, optional_fragments, value_holes, structural_holes, variants, PG grade auto/review/manual, reason |
| `db_objects.json` | table / proc / view | name, referenced by, CRUD matrix |
| `integrations.csv/json` | external endpoint | type (HTTP, SOAP, MQ, FTP, CFT, SMTP, socket, file share, DB link), target, direction, protocol, source file |
| `dependencies.json` | code edge | from, to, kind (calls, includes, forwards, reads, writes, triggers) |
| `libraries.csv/json` + `sbom.cdx.json` | third-party library | group, artifact, version, scope, licence, where used, CVEs, EOL, recommended version |
| `batch_jobs.json` | job | name, scheduler, schedule, steps, programs, datasets, predecessors |
| `tech_stack.json` | technology | name, version, category, evidence files, end-of-life flag |
| `findings.csv/json` | finding | rule id, severity, category (security, PG compat, dependency, dead code, hard-coded config, EOL), file, line, message |
| `metrics.json` | component | LOC, complexity avg/max, duplication %, test files, dead-code candidates |
| `audit-summary.json` | run | totals and top-N lists, under ~30 KB so the agent can read it in one go |
| `enrichment/*.json` | agent output | architecture, flows, risks, assumptions, target mapping, plan, narratives per audience, functional view, component documentation |

Every record carries `evidence: [{file, line}]`. Schemas are versioned JSON Schema files in `schemas/`; enrichment is validated against them before reports are built.

## Re-runs and late inputs

The tool runs many times on the same application: start with the bare repository, add Autosys JIL or other exports whenever the team sends them, re-run, and every report regenerates from the latest facts.

**Inputs live in two places:**

- The repository itself, read-only; the tool never writes into source folders.
- `audit-inputs/` beside it, for material that arrives later: `autosys/*.jil`, `mq/*.mqsc`, `cft/`, `ddl/*.sql`, `crontab/`, `server-config/`. A missing folder is reported as a coverage gap, not an error.

**How a re-run works:**

1. Each run gets an id and a snapshot folder `audit-output/runs/<run-id>/`; `latest` points to the newest. History is kept for comparison.
2. A file-hash cache skips re-analysis of unchanged files. Linking, metrics, rules and totals are always recomputed across everything, so new JIL files connect to scripts and programs already scanned.
3. A run diff lists what changed: new jobs, integrations, services, counts up or down.
4. Each enrichment file records a hash of the facts it was built from. After a re-run, stages whose inputs changed are marked stale (new JIL makes batch flows, risks and plan stale, but not screens). `/audit-enrich` with the stale-only option re-runs just those stages.
5. Human corrections (re-classified components, merged services, accepted risks) live in `audit-overrides.yaml` and are re-applied on every run.
6. Reports always rebuild from the latest run and show its id, date and input coverage (e.g. "Autosys: not yet provided"), which also feeds the assumptions list automatically.

In the GUI, an **Inputs** screen shows which supplementary inputs are present; the Run screen has **Re-run** (incremental) and **Full rescan**.

## Remote repositories (optional)

A local folder stays the default source. Optionally the tool takes Git repo URLs and clones them itself.

- **Source config:** `source.type: local | git`. For `git`: one or more URLs (GitHub, GitLab, Bitbucket, Azure DevOps), branch or tag, optional sub-paths.
- **Clone cache:** shallow clone into `~/.legacy-audit/cache/<repo>`, outside the VS Code project. Re-runs do `git fetch`; each run records the commit SHA, shown in every report. Unchanged commits reuse cached results.
- **Token handling:** from an environment variable, the OS keychain or Git Credential Manager; never written to `audit.yaml`, outputs or logs. Read-only, repo-scoped tokens. Token stripped from the stored remote URL after clone.
- **Agent access:** the agent works from facts; for evidence it opens files in the cache folder or uses the MCP `read_evidence` tool.
- **Cleanup:** `legacy-audit purge-cache` (and a GUI button).

Reading code through the hosting API without cloning is not supported.

## AI agent in VS Code (GitHub Copilot)

A custom agent defined in the workspace, driven by prompt files, runs the scanner in the terminal and reasons over its JSON. Copilot's file names have changed across releases (custom chat modes became custom agents), so the installer must support both layouts and the docs must say which VS Code / Copilot version they were tested with.

**Files `legacy-audit init` installs into the legacy workspace:**

| File | Purpose |
| --- | --- |
| `.github/agents/legacy-auditor.agent.md` (older builds: `.github/chatmodes/legacy-auditor.chatmode.md`) | Agent persona, allowed tools (terminal, file read/search, edit), hard rules |
| `.github/copilot-instructions.md` | Always-on rules: never invent counts, cite evidence, write only to `audit-output/` |
| `.github/prompts/audit-scan.prompt.md` | Run or resume the scan |
| `.github/prompts/audit-enrich.prompt.md` | Staged enrichment (all or stale-only) |
| `.github/prompts/audit-report.prompt.md` | Validate enrichment, build HTML + PPTX |
| `.github/prompts/audit-qa.prompt.md` | Cross-check enrichment against facts; list contradictions |
| Optional MCP server `legacy-audit-mcp` | `query_facts`, `get_component`, `get_sql`, `read_evidence` over SQLite |

**Agent rules:** numbers come only from scanner output (re-classification allowed but recorded with reason); one component at a time; every claim cites evidence or is marked as an assumption; strict JSON matching `schemas/enrichment/*.schema.json`, validated after each stage; stop and ask when evidence conflicts.

**Enrichment stages:** 1 Orient (`overview.json`) · 2 Classify · 3 Architecture (`architecture.json`, with Mermaid) · 4 Flows (`flows.json`) · 5 Services (`service_mapping.json`) · 6 Data (`data_migration.json`) · 7 Dependencies (`dependency_plan.json`: group upgrades into themes, link to waves) · 8 Risks & assumptions (`risks.json`, `assumptions.json`) · 9 Target architecture (`target_architecture.json`) · 10 Plan (`plan.json`) · 11 Narratives (`narratives.json`: executive, architect, functional, developer) · 12 Functional (`functional.json`: business domains and capabilities, actors, use cases, business rules, data entities, one story per screen and per batch, interfaces, glossary) · 13 Component documentation (`component_docs.json`: what each component does, key elements, inputs/outputs, tech debt, migration notes). Stages 12 and 13 read everything the scan found for the application (services, screens, SQL, tables, batch, integrations, libraries, metrics, findings) before writing. For large estates stages loop per component with a carried summary file.

**Model choice:** the enrichment is model agnostic. Copilot in VS Code is the default (any model Copilot offers; `ai.copilot_model` pins one in the agent file). `legacy-audit enrich` writes the same stages with any other model set in `audit.yaml > ai` (OpenAI-compatible endpoints, Azure OpenAI, Anthropic, Ollama), packing the facts and batch sources into the prompt and sending the validator's errors back for repair. The scan never calls a model.

## Counting rules

Published in every report and configurable.

| Metric | Counted as | Notes |
| --- | --- | --- |
| Service | One independently invokable business operation: Struts action, Spring MVC handler, servlet endpoint, SOAP/REST operation, remotely exposed EJB method, CICS transaction, COBOL main program, batch job, C/C++ daemon or executable | Utility libraries and copybooks are shared components, not services |
| Screen | One rendered UI page or map: JSP/JSF/HTML page that is a forward/view target, BMS map, terminal form | JSP fragments/tiles counted separately as "partials" |
| DB query | One distinct SQL statement after normalizing whitespace and bind variables; SQL built at run time counts once per execution site, rebuilt to its full text (fragments are not statements) | Report total, distinct, by type, by PG grade; dynamic SQL flagged with its holes and variants |
| Stored procedure | One package/procedure/function | Counted separately; graded manual or review |
| Integration | One interface per (type, target), whatever the direction or spelling of each usage (decision 119) | Same FTP host used by 10 scripts = 1 integration, 10 usages; read and written = one row, direction both |
| Batch job | One schedulable job (Autosys job, JCL job, cron entry, shell wrapper, Spring Batch job); without a scheduler definition, one per batch component (decision 116) | Steps listed under it; Autosys boxes are containers, not jobs; the ksh scripts of a batch component are its steps, not batches |
| Library | One distinct (group, artifact, version) | Undeclared jars identified by manifest / hash |

**Complexity per item** (Simple / Medium / Complex / Very complex): weighted score from LOC, cyclomatic complexity, tables, integrations, vendor-specific SQL and fan-out; weights in `audit.yaml`.

**Effort model** (optional, labelled as an estimate): person-days per item by type and complexity; weights set by the team from past migrations; shown as low / likely / high.

## Dependency management and security

Every audit inventories third-party dependencies, checks them against known vulnerabilities (OWASP approach) and end-of-life data, and recommends the upgrade or replacement that fits the target.

**1. Inventory (scanner):** declared dependencies from Maven (`pom.xml`, parent BOMs), Gradle, Ant/Ivy, `package.json`/lock files, C/C++ build files; undeclared jars in `lib/`, `WEB-INF/lib/` and app-server folders identified by `META-INF/MANIFEST.MF`, `pom.properties` and SHA-1; bundled JavaScript (jQuery, Dojo, AngularJS, Bootstrap) by header and hash. Output a CycloneDX SBOM plus `libraries.csv`.

**2. Vulnerability check (OWASP):** OWASP Dependency-Check against the SBOM and jars; optionally OSV-Scanner as a second source. Both use local vulnerability data, so scans stay offline once the database is synced; support a team-hosted NVD mirror or a copied database folder, and record the database date in reports. Per dependency: CVE ids, CVSS and severity, fixed-in version, direct vs transitive. Map to OWASP Top 10 *A06 Vulnerable and Outdated Components*; flag licence risks (GPL/AGPL). If the tools or database are not available, the stage is skipped and reported as a coverage gap — never a silent pass.

**3. Upgrade recommendations (rules in `rules/dependency-upgrades.yaml`):**

| Rule | Recommendation produced |
| --- | --- |
| Dependency has a Spring Boot 4 managed version | Drop the explicit version; use the Boot BOM |
| `javax.*` artifact (servlet, persistence, validation, mail, JAXB) | Jakarta EE 11 equivalent |
| Library end-of-life (Struts 1, Log4j 1.x, Commons-Collections 3, old Hibernate, Axis 1) | Named replacement (Spring MVC + Angular, SLF4J + Logback/Log4j2, current Hibernate, Spring-WS/JAX-WS RI) |
| Vulnerable but maintained | Lowest version that fixes all CVEs and runs on Java 25 |
| Not compatible with Java 25 | Flag; check removed JDK APIs (Security Manager, `sun.*`, applets, CORBA, Nashorn) with `jdeps`/`jdeprscan` on compiled classes when available |
| Frontend libraries | Angular 21 equivalent or removal; AngularJS treated as rewrite |

OpenRewrite recipes (Java 25, Spring Boot 4, Jakarta) are listed as automation candidates where code is retained.

**Target versions (configurable):** Java 25 (LTS); Spring Boot 4.1 on Spring Framework 7 and Jakarta EE 11 (Boot 4.0 OSS support ends 31 Dec 2026); Angular 21 with TypeScript 5.9 and Node 22 or 24; PostgreSQL. Angular 22 is already released — keep the Angular version a config value.

**Report outputs:** dependency dashboard (severity, EOL, licence), top 20 risky libraries, full upgrade table, SBOM, and a management slide on security exposure today and after migration.

## Outputs

All generated from the same facts + enrichment, so numbers never disagree.

**1. Data pack** – `audit-output/data/`: JSON, CSV, SQLite, SBOM, plus a `README` of field definitions and counting rules.

**2. Technical HTML documentation** – static, self-contained site (Jinja2, Chart.js, Mermaid, DataTables) that opens from disk with no server, and with no CDN (all JS vendored): overview and tech stack with EOL flags; one page per component; architecture and flow diagrams; service, screen, SQL, integration, batch and library catalogues with evidence links; PostgreSQL readiness; dependency security; findings, risks, assumptions, target mapping, plan; appendix with counting rules, run config, input coverage, scan log.

**2b. Functional document** – a second static site (`reports/functional/`) for business and functional teams, in plain language: summary with a capability map, users and use cases, business flows, one flow per screen and per batch, business rules, data, interfaces, glossary.

- **Flow per screen** (drawn from the scan): where the user comes from, the screen, the services it loads or submits to, the tables each service reads or changes (with C/R/U/D), the other systems it calls, and the screens it leads to.
- **Flow per batch** (drawn from the scan): what starts it (schedule, file arrival, another job), its steps in order with success/failure conditions, what each step runs, tables and files read and written, what happens when it fails (alarms, retries, steps that run anyway), plus a week calendar of when every batch runs. Batch scripts, programs and listeners that no scheduler input starts still get a flow, marked "no schedule found", so a missing JIL/JCL/crontab never empties the Batch page; each batch shows the header comments of its source files (copied, credentials masked).
- The Copilot agent's stage 12 adds the words: purpose, user actions, business impact, rules. The diagrams, tables and schedules come from the scan and are always present; missing words show a placeholder naming `/audit-enrich functional`.

**2c. Summary report** – one offline page (`reports/summary/index.html`) in four sections: 1 Project (short description, technology stack, modules with description, technology, complexity and lines of code), 2 Database and queries, 3 Integrations, 4 Batches; each with KPI tiles, charts and tables, every table also as CSV. Its **PowerPoint (PPTX)** button downloads `summary.pptx`, built on the corporate `.potx` like the management PPTX (decision 124).

Both HTML sites use one colour system (8 series colours, a sequential blue ramp, status colours, light and dark): icon tiles, gauges, doughnuts, stacked bars, radar per component, bubble chart for vulnerable libraries, CRUD and risk heat maps, Gantt roadmap, coloured Mermaid flows with a legend.

**3. Management presentation** – one slide definition (`deck.json`) rendered twice:

- **HTML deck** (simple slider + Chart.js) for screen-sharing
- **PPTX** via Node `pptxgenjs` with native, editable charts; diagrams embedded as PNG rendered from Mermaid with `mermaid-cli`

| Section | Audience | Content |
| --- | --- | --- |
| Executive summary (2–3 slides) | Top management | Size of estate, migration scope in one number set, top 5 risks, approach, timeline and effort range |
| Functional view (5–12 slides) | Business / functional | Capability map, users and use cases, screens per component, key business flows, a flow slide for the main screens and batches, batch calendar, business rules, what changes for users |
| Current architecture (3–4 slides) | Architects | Tech stack, component diagram, integrations map, data landscape |
| Technical deep-dive (4–6 slides) | Tech leads, developers | Services by complexity, SQL readiness, batch landscape, dependency security, code quality, findings |
| Target architecture (2–3 slides) | Architects | Java 25 / Spring Boot / Angular / PostgreSQL / GCP component view, mapping table |
| Plan (2–3 slides) | All | Waves, roadmap, team, dependencies, decisions needed |
| Risks, assumptions, next steps | All | Mitigation owners, open questions |

Each section opens with a coloured divider slide; graphics are native in the PPTX (gauges as doughnut charts, heat maps as tables, flows, capability maps, layers and the roadmap as shapes), so they stay editable.

Branding is a config slot (logo, colours, fonts, PPT master) with a neutral default theme; the corporate template plugs in later without code changes.

## GUI application

A local web app (FastAPI backend + single-page front-end, assets vendored) started with `legacy-audit serve`, opened in the browser or a VS Code webview.

1. **Project setup** – local folder(s) or optionally Git repo URLs, application name, target stack, load/save `audit.yaml`.
2. **Inputs** – which supplementary inputs (Autosys, MQ, CFT, DDL…) are present; open the inputs folder.
3. **Component picker** – tree of detected folders with auto-detected type and language (fast pre-scan); tick/untick, include/exclude patterns, override type.
4. **Run** – start, cancel, Re-run (incremental), Full rescan; live progress per stage and analyzer; warnings.
5. **Results dashboard** – KPI tiles (LOC, components, services, screens, SQL, integrations, batch jobs, vulnerable libraries); charts by technology, complexity, PG grade, finding severity.
6. **Explorer** – searchable tables per entity; drill component → service → SQL → source line (`vscode://file/...` link).
7. **AI enrichment status** – stages done / stale / failed validation; copy the next prompt into Copilot Chat.
8. **Reports** – generate and open technical HTML, management deck, PPTX; download data pack as ZIP.
9. **Compare runs** – diff two runs.

No external LLM is approved, so the GUI never calls a model. Scanning, re-runs and report generation need no AI.

## Target mapping rules

`rules/target-mapping.yaml` gives the default target per legacy element; the agent applies it and records exceptions with a reason.

| Legacy element | Default target |
| --- | --- |
| JSP / JSF / Struts screen, Spring MVC view, BMS map | Angular 21 component (route per screen, shared components for repeated forms) |
| Struts action, Spring MVC handler, servlet, EJB, SOAP op | Spring Boot 4 REST controller + service, grouped into bounded-context services |
| COBOL / Pro*C online program | Spring Boot service (rewritten) or rehost candidate if logic is very large |
| Batch job (Autosys/JCL/cron + shell, C, COBOL, Spring Batch) | Spring Batch on Cloud Run jobs or GKE; Autosys job chains → Cloud Composer, simple schedules → Cloud Scheduler + Workflows |
| Oracle / DB2 / other DB | Cloud SQL for PostgreSQL or AlloyDB; procedures → Java service logic or PL/pgSQL |
| IBM MQ / JMS | Pub/Sub, or IBM MQ kept (e.g. on GKE) where ordering or transactions require it |
| FTP / SFTP / CFT file transfer | Cloud Storage + Storage Transfer Service, or managed SFTP gateway; events via Pub/Sub |
| Shared file system | Cloud Storage or Filestore |
| Email (SMTP) | SMTP relay / SendGrid with credentials in Secret Manager |
| Config / properties | Spring config + Secret Manager |
| Reports (Crystal, Jasper, Oracle Reports) | JasperReports on Spring Boot or Looker Studio, case by case |
| Auth (LDAP, SiteMinder, custom) | Identity Platform / IAP, or corporate IdP via OIDC |
| Logging, monitoring | Cloud Logging, Cloud Monitoring, Cloud Trace |
| Runtime | GKE Autopilot or Cloud Run; CI/CD with Cloud Build + Artifact Registry |

## Project structure

```
code-audit/
  .github/copilot-instructions.md
  pyproject.toml
  audit.example.yaml
  legacy_audit/
    cli.py                 # init, scan, report, validate, diff, serve, purge-cache
    config.py
    source/                # local + optional git clone cache
    inventory/             # walker, language + encoding detection, file-hash cache
    analyzers/             # java, struts, jsp, spring, c_cpp, proc, cobol, jcl,
                           # autosys, mq, cft, shell, sql, config, build, deps
    linker/                # cross-file resolution, call graph
    metrics/               # LOC, complexity, duplication, dead code
    rules/                 # rule engine (risk, PG, dependency)
    security/              # Dependency-Check / OSV integration, SBOM
    store/                 # SQLite + JSON/CSV exporters, runs, diff
    enrich/                # enrichment schemas, validate, merge, staleness
    report/
      tech_html/           # Jinja2 templates, vendored Chart.js, Mermaid, DataTables
      mgmt_html/           # slide templates
      deck_builder.py      # builds deck.json
    gui/                   # FastAPI app + static front-end
    mcp/                   # optional MCP server for Copilot
    copilot/               # agent, instructions, prompt files installed by `init`
  pptx/                    # Node: build-pptx.js (pptxgenjs), package.json
  rules/                   # target-mapping.yaml, risk-rules.yaml, pg-rules.yaml, dependency-upgrades.yaml
  schemas/                 # facts + enrichment JSON Schemas
  samples/                 # small sample legacy apps + expected counts
  tests/
  docs/
```

Legacy workspace after `legacy-audit init`:

```
<legacy workspace>/
  .github/agents/  .github/prompts/  .github/copilot-instructions.md
  audit.yaml  audit-overrides.yaml
  audit-inputs/   autosys/ mq/ cft/ ddl/ crontab/ server-config/
  audit-output/
    runs/<run-id>/  latest/
      data/  enrichment/  reports/tech/  reports/mgmt/  reports/summary/  reports/deck.pptx  logs/
```

## Build plan

Seven phases, each ending with something usable on a real legacy app. Detailed prompts are in `docs/prompts/`.

1. **Foundations** – repo, config, schemas, source handling (local + optional git), inventory walker, LOC, language/encoding detection, CSV/JSON/SQLite export, CLI, run history and file-hash cache. *Exit:* inventory of a sample app, re-runnable.
2. **Core analyzers** – Java + Struts/JSP + Spring, build files and dependency inventory, SQL with `sqlglot` PG grading, config, shell; then C/C++, Pro*C, COBOL/copybooks, JCL, Autosys JIL, IBM MQ, CFT. Linker and service/screen classification. *Exit:* correct counts on `samples/`, checked against expected-count files.
3. **Rules, metrics and dependency security** – complexity, duplication, dead code, dynamic SQL reconstruction (Java, C, Pro*C, COBOL), risk/PG/dependency rule packs, OWASP Dependency-Check and SBOM, effort model. *Exit:* findings, complexity bands, rebuilt dynamic SQL with grades, vulnerability and upgrade tables.
4. **Technical HTML report** – templates, catalogues, charts, Mermaid diagrams, evidence links. *Exit:* report usable by architects.
5. **Copilot agent + enrichment + management outputs** – agent files and installer, enrichment schemas and validator, staleness, optional MCP server, management HTML deck and PPTX builder. *Exit:* full pipeline on one real application.
6. **GUI + hardening** – FastAPI GUI, run comparison, performance on 1M+ LOC, packaging, user guide. *Exit:* analysts run audits without the tool's developers.
7. **Functional documentation and graphics** – enrichment stages 12 (functional) and 13 (component documentation); scan-drawn screen and batch flows; the functional document; a colour and graphics pass on the technical report, the HTML deck and the PPTX. *Exit:* a business reader can follow every screen and batch from the documents alone.

Every phase ends with VS Code validation steps in `docs/validate-in-vscode.md` (tasks and debug configurations in `.vscode/`), so the team can check the result themselves.

Pilot on one real application after phase 5, compare counts with a manual audit, tune rules, then roll out.

## Risks, assumptions and open questions

| Risk | Mitigation |
| --- | --- |
| COBOL / Pro*C dialects and copybooks the parser misses | Regex-based start with a coverage report of unparsed lines; ProLeap/Koopa later |
| Missing source (binaries, generated code, vendor jars) | Inventory flags binaries and unresolved calls as an explicit gap |
| Dynamic SQL and reflection hide queries and calls | Rebuild SQL assembled in Java, C, Pro*C and COBOL (fragments, optional parts, holes, variants); what stays unknown is a hole or a `DYNAMIC` statement, graded manual; counts marked as "minimum" |
| AI invents architecture or numbers | Scripts-count rule, evidence on every claim, schema validation, QA prompt |
| Client code cannot leave the network | Scanner fully offline; only AI is the team's approved Copilot |
| Copilot features change between versions | Thin agent assets; logic in Python and prompts; installer supports both layouts |
| Scheduler and runtime config live outside the repo | Drop Autosys JIL, MQ and CFT exports into `audit-inputs/` whenever they arrive and re-run |
| Vulnerability database unavailable offline | Mirror or copied DB; stage reported as a gap with DB date, never a silent pass |

**Assumptions:** analysts have VS Code with Copilot agent mode, Python 3.11+, Node 18+ (Java runtime only if OWASP Dependency-Check is used); full source for in-scope components is available; DDL can be exported; effort weights come from past migrations.

**Answered:** no external LLM (Copilot only) · Autosys, IBM MQ and CFT are the usual scheduler/MQ/transfer products · corporate PPT template exists and will be added later.

**Open:** typical estate size (LOC, number of apps) · Angular 21 fixed, or "latest LTS" · any frameworks beyond Spring to add (EJB 2.x, JSF, GWT, Tapestry…).
