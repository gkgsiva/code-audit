# Phase 3 — Metrics, rules, PostgreSQL grading and dependency security

Goal: turn facts into judgements that deterministic code can make — complexity, findings, SQL readiness, vulnerable/outdated libraries, upgrade recommendations and effort ranges.

## Metrics

- Cyclomatic complexity per function/method/paragraph (decision-point counting per language), fan-in/fan-out from code edges, duplication (token-window hashing, report % per component), dead-code candidates (unreferenced classes/programs/screens/jobs not reachable from any entry point), test file ratio.
- Complexity band per service, screen, batch job and component (Simple / Medium / Complex / Very complex) from weights in `audit.yaml`.

## Rule engine

- YAML rule packs in `rules/`: `risk-rules.yaml`, `pg-rules.yaml`, `dependency-upgrades.yaml`, `target-mapping.yaml`. A rule matches facts (by kind and field predicates or regex on evidence) and emits `Finding`s with severity, category, message, remediation, evidence. Rules can be disabled or re-weighted in config.
- Starter risk rules: hard-coded IPs/hosts/credentials, `System.exit`, direct file-system paths, platform-specific calls, EBCDIC/packed-decimal handling, no tests, very high complexity, unresolved references, binaries without source, Security Manager / `sun.*` usage, XML external entity parsers without hardening, values concatenated into SQL text (`sql-concatenation`: value holes), SQL whose structure is only known at run time (`sql-runtime-structure`: structural holes).

## Dynamic SQL reconstruction

SQL built at run time must be rebuilt into the statement the code sends, in every language that builds it — not only literals at the call site. Follow the variable holding the text in source order up to the call that executes it:

- **Java**: `+`/`+=`, `s = s + ...`, `StringBuilder`/`StringBuffer`/`StringJoiner` chains, `String.format`/`formatted`, `concat`, ternaries, constants (also used as a builder's start).
- **C / C++**: `strcpy`/`strncpy`/`strlcpy`, `strcat`/`strncat`, `sprintf`/`snprintf` (writing at `buf + strlen(buf)` or formatting `"%s..."` from the buffer itself is an append; numeric conversions are values), `#define` and file-level strings, ternaries. Call sites: ODBC / DB2 CLI (`SQLExecDirect`, `SQLPrepare`), OCI (`OCIStmtPrepare[2]`), libpq, MySQL, SQLite. A buffer or constant that looks like SQL but reaches the database through a wrapper is still reported (source `c-string`).
- **Pro*C**: the same C, consumed by `EXEC SQL PREPARE s FROM :buf` / `EXECUTE IMMEDIATE :buf`; `VARCHAR` host variables via `.arr`.
- **COBOL / Pro*COBOL**: `VALUE` clauses (with continuation lines), `MOVE` (reference-modified target = append), `STRING ... DELIMITED BY ... INTO` (`WITH POINTER` or the target as first operand = append), `INITIALIZE`; DB2 varying-length host variables (level-49 length + text) are looked up through the text item; consumed by `PREPARE ... FROM :var` / `EXECUTE IMMEDIATE :var`. A variable filled in a paragraph further down the source uses its last value.
- **MyBatis / iBATIS**: dynamic tags are optional fragments; `${}`/`$x$` substitutions are holes.

Rules: code inside `if`/loops/`switch`/`catch` (COBOL `IF`/`EVALUATE`/inline `PERFORM`) is conditional, so what it adds is an optional fragment; a reassignment on some paths only keeps both texts. Unknown pieces become `?` and are classified by the text before them: **value** hole (after `=`, a comparison or arithmetic operator, inside quotes, in `IN (...)`/`VALUES (...)`) or **structural** hole (table, column, clause). `SqlStatement` records `dynamic`, `built_with`, `fragments`, `optional_fragments`, `value_holes`, `structural_holes`, up to 5 `variants` (whole alternative texts) and evidence for every fragment line. Fragments are not statements of their own; `?` is never a table. A host variable the code does not show being filled stays type `DYNAMIC` with one structural hole. Everything is deterministic code in the analyzers — no LLM.

## PostgreSQL grading

For each `SqlStatement`: transpile with `sqlglot` from source dialect to PostgreSQL. Grade *auto* (clean transpile, no flagged constructs), *review* (vendor functions/syntax in `pg-rules.yaml`: `NVL`, `DECODE`, `ROWNUM`, `CONNECT BY`, `(+)`, `SYSDATE`, sequences `.NEXTVAL`, hints, `MERGE` differences, DB2 `FETCH FIRST`/special registers), *manual* (PL/SQL packages/procedures, unparseable, type `DYNAMIC`, or any structural hole). Rebuilt dynamic SQL without structural holes is graded on its text and every variant, worst grade wins, at least *review*; the reason says how many fragments, optional parts, concatenated values and variants it was rebuilt from. Store reasons and the suggested PostgreSQL text for *auto*/*review*.

## Dependency security (OWASP)

- Build a CycloneDX 1.5+ SBOM (`sbom.cdx.json`) from `Library` facts (purl for Maven/npm where known; hashes for jars).
- Integrate **OWASP Dependency-Check** (CLI) and optionally **OSV-Scanner**: detect on PATH or configured path; run against the SBOM/jar folders with the configured local data directory; parse JSON reports; attach CVEs (id, CVSS, severity, fixed versions) to `Library`. Config: `security.dependency_check.path`, `data_dir`, `nvd_mirror_url`, `offline: true`. Record database date in `run.json`. If the tool or DB is missing → `InputCoverage` gap and a report banner, never a silent pass.
- Tests use recorded sample reports (fixtures) — do not call the network in tests.
- EOL data: a small curated `rules/eol.yaml` (Struts 1, Log4j 1.x, Commons-Collections 3.x, Spring ≤5.x OSS, Hibernate ≤5.x, Axis 1, Java ≤17 as source level, AngularJS, jQuery < 3.5…) with dates and replacements; maintained by the team.
- Upgrade recommendations from `dependency-upgrades.yaml`: Boot-managed → use BOM; `javax.*` → Jakarta EE 11 coordinates; EOL → named replacement; vulnerable but maintained → lowest version that fixes all known CVEs and supports Java 25 (from rule data; mark "verify" when unknown); frontend → Angular 21 equivalent or rewrite. Each recommendation: action, target coordinates, reason, effort hint, OpenRewrite recipe id when applicable.
- Optional: if compiled classes or jars exist, run `jdeps --jdk-internals` / `jdeprscan --release 25` when a JDK is available; parse output into findings.

## Effort model

Person-day weights per item type × complexity (and per PG grade, per upgrade action) in config; produce low/likely/high totals per component and overall, clearly labelled "estimate — weights from config".

## Outputs added

`findings.*`, `metrics.json`, `libraries.*` (with CVEs, EOL, recommendation), `sbom.cdx.json`, `sql_statements.*` with grades, `effort.json`, updated `audit-summary.json`.

## Acceptance criteria

- Each sample's expected grades, finding counts by rule and vulnerable-library counts (from fixture reports) match `expected.yaml`.
- Removing Dependency-Check from PATH produces a coverage gap and no crash.
- Changing a weight in `audit.yaml` changes bands/effort on re-run without re-analysing files.
- SBOM validates against the CycloneDX schema (vendored).
- SQL built at run time is rebuilt in Java, C/ODBC/OCI, Pro*C and COBOL with its fragments, optional parts, holes and variants (unit tests per language), and the samples' dynamic statements (Java `OrderDao.advancedSearch` and `InvoiceDao`, Pro*C `purge_old`, COBOL `ORDRPT`) are graded and flagged as `expected.yaml` says.
