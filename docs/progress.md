# Progress

Update this file at the end of every phase, and whenever a session stops mid-phase.

| Phase | Status | Notes |
| --- | --- | --- |
| 0 – Planning | Done | `docs/plan.md`, `docs/decisions.md`, prompts written 2026-10-10 |
| 1 – Foundations | Done | Package, config, local + git sources, inventory, components, coverage, runs, file cache, overrides, diff, summary, CLI, fact model + schemas, `struts-oracle-app` inventory sample (2026-10-10) |
| 2 – Core analyzers | Done | 15 analyzers (build, deps, config, java, web, spring, sql, shell, c_cpp, proc, cobol/BMS, jcl, autosys, mq, cft), linker (edges, merges, Maven versions, screen/partial and COBOL sub-program rules, service reach, component classification), headline counts, analyzer coverage in `run.json`, 3 new samples with `expected.yaml`, re-run and 200k LOC stress tests (2026-10-10) |
| 3 – Rules, metrics, dependency security | Done | Code units and cyclomatic complexity, duplication, fan-in/out, dead-code candidates, test ratio, complexity bands; rule engine with 4 YAML packs (19 risk rules, PostgreSQL constructs, dependency rules, EOL) and config switches; PostgreSQL grading with sqlglot; Dependency-Check/OSV import or run, EOL, upgrade advice, CycloneDX SBOM, optional jdeps/jdeprscan; effort model; 4 samples extended (2026-10-10). Added after review: dynamic SQL rebuilt in Java, C/ODBC/OCI, Pro*C and COBOL with holes, variants and grading; VS Code tasks and `docs/validate-in-vscode.md` (2026-10-10) |
| 4 – Technical HTML report | Not started | |
| 5 – Copilot agent, enrichment, management outputs | Not started | |
| 6 – GUI and hardening | Not started | |

## Phase 3 result

Acceptance criteria (from `docs/prompts/phase-3-rules-metrics-security.md`), all checked by tests:

1. Each sample's PostgreSQL grades, finding counts by rule and vulnerable-library counts (from the
   recorded reports in `audit-inputs/security/`) match `expected.yaml` (`tests/test_samples.py`).
2. Dependency-Check missing from PATH gives a coverage row `dependency-check: missing` and no crash
   (`tests/test_security.py::test_missing_dependency_check_is_a_coverage_gap`).
3. Changing a weight re-bands and re-prices on re-run with 0 files re-analysed
   (`tests/test_metrics.py::test_changing_weights_rebands_without_reanalysis`); disabling or
   re-grading a rule works the same way (`tests/test_rules.py`).
4. `sbom.cdx.json` validates against the vendored CycloneDX 1.5 schema (`tests/test_security.py`).

Added after the first review (Siva, 2026-10-10): SQL built at run time is rebuilt in Java
(builders, `+=`, `String.format`, ternaries), C/C++ (`strcpy`/`strcat`/`sprintf` buffers; ODBC,
DB2 CLI, OCI, libpq, MySQL, SQLite calls), Pro*C (`PREPARE`/`EXECUTE IMMEDIATE` from a buffer) and
COBOL (`VALUE`/`MOVE`/`STRING` into a host variable), with fragments, optional parts, value and
structural holes and variants (`tests/analyzers/test_dynamic_sql.py`, decisions 55–60). Rules
`sql-concatenation` (value holes) and `sql-runtime-structure` (structural holes); grading per
decision 58. VS Code validation steps are in `docs/validate-in-vscode.md`, with tasks in
`.vscode/` (decision 61).

New outputs in `data/`: `findings.*`, `metrics.*` (bands, duplication, test ratio, dead code,
fan-in/out), graded `sql_statements.*` (`pg_grade`, `reason`, `pg_text`), `libraries.*` (CVEs,
EOL, recommendation), `sbom.cdx.json`, `effort.json`, `target-mapping.json`; `run.json` gains
`vulnerability_sources` (status, database date); `audit-summary.json` gains phase 3 counts, top
findings, risky libraries, component metrics and the effort range.

## Next step

Start phase 4 (technical HTML report): read `docs/prompts/phase-4-*.md`. Every number it needs is
in `audit-output/runs/<id>/data/` and `audit-summary.json`; `legacy_audit/store/counts.py` is the
one place headline counts come from; counting rules and complexity/effort weights are in
`run.json` and the config.

## Known gaps

- Struts 2, JSF and Spring Web Flow are recognised (tech stack, actions/beans) but with fewer
  facts than Struts 1 and Spring MVC; Gradle is regex-only.
- Spring Data derived queries are recorded by method name; their SQL is not derived.
- C/C++: calls through function pointers and macros are not followed; `#ifdef` branches are all
  read. COBOL: `CALL` through a variable is resolved only from a `VALUE` or `MOVE` literal.
- Shell: commands built from variables (`$BIN/prog`) are linked when a file with that name exists,
  never reported as unresolved (decision 34); `eval` and functions are not followed.
- Service reach stops at 4 hops (decision 33).
- LOC approximations (see decision 20): comment markers inside strings, Java in JSP scriptlets.
- Re-including a file inside a default-excluded folder needs the whole folder re-included
  (`files.include: ["build/"]`), as in `.gitignore`.
- `purge-cache` removes the whole Git clone cache; there is no per-repo purge yet.
- Python 3.11 and Windows are covered by CI only (local runs were Linux, Python 3.13).
- Stress test: 200k LOC (1.6k files) scans in about 16 s and re-runs from cache in about 4 s on
  the build container; laptop timings still to be measured.
- Vulnerabilities come only from Dependency-Check / OSV-Scanner results (imported or run); the
  tool does not download or sync a database itself. Transitive dependencies that are not in the
  inventory are counted as unmatched report entries (logged), not listed.
- EOL and upgrade targets are rule data (`rules/eol.yaml`, `rules/dependency-upgrades.yaml`) and
  need a periodic review; `verify: true` marks targets to confirm.
- Complexity and effort weights are first defaults, not calibrated on a real estate yet.
- Dead-code candidates do not see reflection, Spring XML beans by class name in non-scanned files,
  or callers outside the scanned estate; the agent confirms them.
- Undeclared jars without a group get no package URL, so OSV results (by package) cannot match
  them; Dependency-Check matches them by SHA-1 or file name.
- Dynamic SQL is followed within one file and in source order: text filled in another file, by a
  called function's return value, through pointers/aliases, `memcpy`, COBOL `UNSTRING`/`INSPECT`,
  or a C++ `std::string` is a hole (or a `DYNAMIC` statement); at most 5 variants are kept.
