# Phase 2 — Core analyzers and linker

Goal: extract services, screens, SQL, integrations, batch jobs, libraries and code edges for every in-scope technology, link them across files, and classify components.

## Analyzer plugin contract

`Analyzer` with `name`, `version`, `supports(file: FileRecord) -> bool`, `analyze(file, ctx) -> Facts`. `ctx` gives read access to the file text, config and a lookup for other files' paths (not their facts). Analyzers are pure; caching uses `version`. Register in `legacy_audit/analyzers/__init__.py`. Each analyzer reports lines it could not parse (coverage %) so gaps are visible.

## Analyzers (build in this order; each with fixtures + unit tests)

1. **build** – Maven (`pom.xml` incl. parent and `dependencyManagement`), Gradle (best-effort regex), Ant/Ivy, Makefile; output `Library` (declared) and `TechItem` (Java/C++ versions, frameworks).
2. **deps** – undeclared jars (`lib/`, `WEB-INF/lib/`, app-server folders): read `MANIFEST.MF` and `pom.properties` inside the jar (zip), else name/version from filename; SHA-1 recorded. `package.json`/lock files; bundled JS libraries by header comment + filename. Output `Library` with `declared: bool`, `where_used`.
3. **config** – properties/XML/YAML/INI: JDBC URLs (→ DB tech + dialect), hosts/ports/URLs (→ `Integration`), queue names, CFT partners; credentials masked.
4. **java** – classes, methods, annotations, imports, `main()`, schedulers (Quartz, Timer), JDBC strings (`prepareStatement`, `createQuery`, `executeQuery`) → `SqlStatement`, HTTP/SOAP clients (HttpURLConnection, HttpClient, Axis, JAX-WS, RestTemplate) → `Integration`, socket/FTP/mail usage, `javax.*` imports (upgrade signal). Choose `javalang` or `tree-sitter-java`; record why in `decisions.md`.
5. **struts / web.xml / jsp / jsf** – Struts 1 (`struts-config.xml`) and 2 (`struts.xml`, annotations): actions → `Service`, forwards → `Screen` links; tiles; `web.xml` servlets, filters, listeners, mappings → `Service`; JSP pages (forms, fields count, includes), JSP fragments → partials; JSF pages and managed beans.
6. **spring** – XML bean definitions and component scan; `@Controller/@RestController` + mappings → `Service`; view resolution to screens; Web Flow; Boot app + version; Spring Batch jobs/steps → `BatchJob`; `JdbcTemplate`, `@Query`, Spring Data derived queries (record method name; SQL not derivable → flagged), Hibernate/JPA entities, HQL/JPQL, iBATIS/MyBatis XML mappers → `SqlStatement`; `JmsTemplate`, listener containers, MQ connection factories, Spring-WS, HTTP invoker, RMI → `Integration`; Spring Security config → auth `TechItem`.
7. **sql** – `.sql`, PL/SQL packages, DDL: statements, procedures/packages → `DbObject`; table extraction with `sqlglot` (dialect from config/JDBC URL, default Oracle). Grading happens in phase 3 but store the parsed form now.
8. **shell** – sh/ksh/bash/bat/cmd/perl/python scripts: invoked programs and scripts, `sqlplus`/`db2`/`isql` calls (inline SQL → `SqlStatement`), `ftp/sftp/scp/CFTUTIL/mail` → `Integration`, cron lines.
9. **c_cpp** – tree-sitter-c/cpp: functions, `main()`, includes, sockets, `system()`/`exec*`, file I/O, MQI calls (`MQCONN/MQOPEN/MQPUT/MQGET`).
10. **proc** – Pro*C/Pro*COBOL: extract `EXEC SQL ... ;` blocks (cursors, host variables) → `SqlStatement`, then hand remaining code to the C analyzer.
11. **cobol** – fixed/free format, divisions, PROGRAM-ID, paragraphs, `CALL`, `COPY` (copybook resolution via search paths in config), `EXEC SQL`, `EXEC CICS` (transactions, `SEND MAP` → `Screen`), BMS maps, file `SELECT`s, MQI calls.
12. **jcl** – jobs, steps, `PGM=`, `PROC`, DD datasets → `BatchJob`.
13. **autosys** – JIL from `audit-inputs/autosys/` (and any `.jil` in source): `insert_job`, `job_type` (CMD, BOX, FW…), `box_name`, `command`, `machine`, `condition` (s(), f(), d(), n(), with AND/OR), `start_times`, `days_of_week`, `date_conditions`. BOX = container. Output `BatchJob` with predecessors; file-watchers as integrations.
14. **mq** – MQSC exports (`DEFINE QLOCAL/QREMOTE/QALIAS/CHANNEL`) from `audit-inputs/mq/`, plus queue names collected by java/spring/c/cobol/config analyzers → one `Integration` per queue with producers/consumers.
15. **cft** – CFT configuration exports (`CFTPART`, `CFTSEND`, `CFTRECV`, `CFTTCP`) from `audit-inputs/cft/` and `CFTUTIL SEND/RECV` calls in scripts → `Integration` (partner, IDF/flow id, direction, file pattern).

## Linker

After all files are analysed: resolve references into `CodeEdge`s and fill cross-links — Struts/Spring action → screen; class → class; screen → action; COBOL `CALL`/`COPY`; JCL step → program; Autosys job command → script → program/jar → main class; Spring Batch job ↔ launching script; SQL → tables; integration producer/consumer → component. Unresolved references are kept as findings (`unresolved-reference`).

## Classification

- Component type (web, batch, CFT/file-transfer, script, report, library, DB, online-mainframe) from entry points and contents; overridable.
- Services and screens per counting rules in `docs/plan.md`; dedupe SQL by normalized text (whitespace, literals → placeholders, bind variables).

## Samples (each with `expected.yaml`)

`struts-oracle-app` (Struts 1 + JSP + JDBC + Oracle SQL + Log4j 1 + WEB-INF/lib jars), `spring-mvc-batch-app` (Spring 4 XML + MVC + JdbcTemplate + Hibernate + Spring Batch + JMS to IBM MQ), `c-proc-batch` (C + Pro*C + shell + CFTUTIL), `cobol-cics-db2` (COBOL + copybooks + CICS + BMS + DB2 SQL + JCL), and `audit-inputs` fixtures (Autosys JIL with boxes and conditions, MQSC, CFT export). See `samples/README.md`.

## Acceptance criteria

- Every analyzer has unit tests; each sample's counts match `expected.yaml` exactly.
- Adding the Autosys fixture to a sample's `audit-inputs/` and re-running links jobs to the scripts already scanned (test asserts the edges) without re-analysing unchanged source files.
- Coverage % per analyzer appears in `run.json`; unparsed regions recorded.
- A 200k LOC synthetic stress tree (generated in a test fixture script, not committed) scans without errors and within a sensible time (log the number; target under 2 minutes).
