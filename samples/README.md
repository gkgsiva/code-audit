# Sample legacy apps

Small, hand-written synthetic applications used to test every phase. Each folder has its own `audit.yaml`, optional `audit-inputs/`, and an `expected.yaml` with the exact counts the scanner must produce. Keep each under ~2,000 lines; they need to be realistic in structure, not in business logic.

All four exist (phases 1–2). `tests/test_samples.py` scans each one and compares the result with its `expected.yaml`: inventory and components (phase 1), then headline counts, services, screens, integrations, batch jobs and unresolved references (phase 2). When an analyzer change moves a number, check the new value by hand before updating `expected.yaml`.

| Sample | Technologies | What it exercises |
| --- | --- | --- |
| `struts-oracle-app/` | Struts 1, JSP, servlets, JDBC, Oracle SQL (NVL, ROWNUM, CONNECT BY, `(+)`), Log4j 1.x, Commons-Collections 3 in `WEB-INF/lib`, SOAP client (Axis 1), Ant build | Services, screens, partials, SQL grading, undeclared jars, EOL/vulnerable libraries |
| `spring-mvc-batch-app/` | Spring 4 XML config + annotations, Spring MVC + JSP, JdbcTemplate, Hibernate HQL, MyBatis mapper, Spring Batch, JmsTemplate to IBM MQ, Spring Security LDAP, Maven multi-module | Spring analyzers, bean graph, batch jobs, MQ integrations, `javax` → Jakarta signals, BOM-managed upgrades |
| `c-proc-batch/` | C, Pro*C (cursors, host variables), ksh scripts calling the binaries, `CFTUTIL SEND`, sqlplus, Makefile, MQI calls | C/Pro*C analyzers, scripts → programs linking, CFT and MQ integrations |
| `cobol-cics-db2/` | COBOL programs + copybooks, CICS transactions + BMS maps, DB2 EXEC SQL, JCL jobs | COBOL/CICS screens and services, copybook resolution, JCL batch chains, DB2 grading |
| `*/audit-inputs/` fixtures | Autosys JIL (boxes, CMD jobs, conditions s()/f()/d(), file watcher) and MQSC in `spring-mvc-batch-app`; CFT partner/flow export and crontab in `c-proc-batch`; Oracle DDL in `struts-oracle-app` | Late inputs and re-run behaviour (`tests/test_linking_rerun.py` adds the JIL after a first scan) |

Some references are deliberately missing so unresolved-reference reporting is exercised: `layout.jsp` (struts-oracle-app), `archive_usage.sh` (spring-mvc-batch-app) and `purge_logs.sh` (c-proc-batch).

Vulnerability results for tests come from recorded Dependency-Check / OSV JSON reports stored in `tests/fixtures/`, never from the network.
