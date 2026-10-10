# Phase 3 — Metrics, rules, PostgreSQL grading and dependency security

Goal: turn facts into judgements that deterministic code can make — complexity, findings, SQL readiness, vulnerable/outdated libraries, upgrade recommendations and effort ranges.

## Metrics

- Cyclomatic complexity per function/method/paragraph (decision-point counting per language), fan-in/fan-out from code edges, duplication (token-window hashing, report % per component), dead-code candidates (unreferenced classes/programs/screens/jobs not reachable from any entry point), test file ratio.
- Complexity band per service, screen, batch job and component (Simple / Medium / Complex / Very complex) from weights in `audit.yaml`.

## Rule engine

- YAML rule packs in `rules/`: `risk-rules.yaml`, `pg-rules.yaml`, `dependency-upgrades.yaml`, `target-mapping.yaml`. A rule matches facts (by kind and field predicates or regex on evidence) and emits `Finding`s with severity, category, message, remediation, evidence. Rules can be disabled or re-weighted in config.
- Starter risk rules: hard-coded IPs/hosts/credentials, `System.exit`, direct file-system paths, platform-specific calls, EBCDIC/packed-decimal handling, no tests, very high complexity, unresolved references, binaries without source, Security Manager / `sun.*` usage, XML external entity parsers without hardening, SQL built by string concatenation.

## PostgreSQL grading

For each `SqlStatement`: transpile with `sqlglot` from source dialect to PostgreSQL. Grade *auto* (clean transpile, no flagged constructs), *review* (vendor functions/syntax in `pg-rules.yaml`: `NVL`, `DECODE`, `ROWNUM`, `CONNECT BY`, `(+)`, `SYSDATE`, sequences `.NEXTVAL`, hints, `MERGE` differences, DB2 `FETCH FIRST`/special registers), *manual* (PL/SQL packages/procedures, dynamic SQL, unparseable). Store reasons and the suggested PostgreSQL text for *auto*/*review*.

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
