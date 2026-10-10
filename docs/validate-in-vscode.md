# Validating legacy-audit from VS Code

Every phase ends with a section here: how to check, from VS Code on a Windows laptop (or Linux /
macOS), that what the phase built works. The repository ships `.vscode/` with tasks, debug
configurations and recommended extensions, so no typing of commands is needed.

## Once: open and set up

1. `git clone https://github.com/gkgsiva/code-audit` and **File > Open Folder** on it (trust the
   folder when asked).
2. Accept the recommended extensions (Python, Python Debugger, Ruff, SQLite Viewer).
3. **Terminal > Run Task... > `audit: 1. set up (venv + install)`** (Python 3.11+ must be on
   PATH as `python`). Then **Ctrl+Shift+P > Python: Select Interpreter** and pick `.venv`.
4. **Terminal > Run Task... > `audit: 2. tests (pytest)`**: every test passes. The **Testing**
   view (flask icon) shows the same tests; you can run or debug any single one there.
5. **Terminal > Run Task... > `audit: 3. lint + type check`**: no errors.

To scan one sample: **Run Task > `audit: 4. scan a sample`** and pick it. The run is written to
`samples/<sample>/audit-output/runs/<run-id>/` (`latest` points to it). To step through the
scanner with breakpoints, use **Run and Debug (Ctrl+Shift+D) > `legacy-audit: scan a sample`**.
To scan your own application, open a terminal (`.venv` active) and run
`legacy-audit init` then `legacy-audit scan` in its folder.

Every expected number for the samples is in `samples/<sample>/expected.yaml`; the test
`tests/test_samples.py` compares a fresh scan with it, so a green test run is the full check.
The steps below are for looking at the results yourself.

## Phase 1: foundations (inventory, runs, cache)

1. Run task `audit: 4. scan a sample` > `struts-oracle-app`.
2. Open `samples/struts-oracle-app/audit-output/latest/run.json`: input coverage (Autosys,
   MQ... "not provided" are gaps, not errors), timings, counting rules.
3. Open `latest/data/audit-summary.json`: `totals` (files, LOC) and `by_language` match
   `samples/struts-oracle-app/expected.yaml` > `inventory`.
4. Run the task again: the terminal says `analyzed: 0, from cache: N` — unchanged files are
   skipped. `legacy-audit diff previous latest -c samples/struts-oracle-app/audit.yaml` shows no
   change.
5. Open `latest/data/facts.sqlite` (SQLite Viewer) and browse the `inventory` table.

## Phase 2: analyzers (services, screens, SQL, integrations, jobs)

1. Run task `audit: 5. scan all samples`.
2. In each `samples/<sample>/audit-output/latest/data/audit-summary.json`, `counts` match
   `expected.yaml` > `counts` (services by kind, screens, SQL by type and source, integrations,
   batch jobs, libraries).
3. Open `data/services.csv`, `screens.csv`, `integrations.csv`, `batch_jobs.csv`: every row has
   `evidence` with a file and line; Ctrl+click a `file:line` in the terminal to jump to it.
4. `run.json` > `analyzers`: coverage % per analyzer; `unresolved_references` lists what is
   missing from the source (e.g. `archive_usage.sh` in `spring-mvc-batch-app`).

## Phase 3: rules, metrics, PostgreSQL grading, dependency security

1. Run task `audit: 5. scan all samples`.
2. `data/findings.csv`: findings by rule with severity and evidence; `audit-summary.json` >
   `findings_by_severity` and `pg_grades` match `expected.yaml` > `assessment`.
3. **Dynamic SQL** — run task `audit: 6. show dynamic SQL of the latest run` and pick a sample.
   Each statement built at run time is printed rebuilt, with how it was built, its optional
   parts, holes and variants, for example (`c-proc-batch`):

   ```
   src/settle_load.pc:59  [manual] DELETE (embedded)
       DELETE FROM ? WHERE run_date < ADD_MONTHS(SYSDATE, -13) AND status <> 'FAILED'
       built with: concatenation, conditional, prepare-from-variable, string-format; ...
       variant: DELETE FROM ? WHERE run_date < ADD_MONTHS(SYSDATE, -13)
   ```

   Ctrl+click the `file:line` and check the code against the rebuilt text: Java
   (`struts-oracle-app` `OrderDao.advancedSearch`, `spring-mvc-batch-app` `InvoiceDao`), Pro*C
   (`c-proc-batch` `purge_old`), COBOL (`cobol-cics-db2` `ORDRPT` `DETAIL-PARA`). `?` marks a
   piece only known at run time: a *value* (injection risk, rule `sql-concatenation`) or part of
   the statement itself (rule `sql-runtime-structure`, graded manual).
   `python scripts/show_sql.py samples/<sample> --grade manual` lists every manual statement.
4. `data/sql_statements.csv`: `pg_grade`, `reason` and `pg_text` (suggested PostgreSQL) per
   statement.
5. `data/libraries.csv`: CVEs (from `audit-inputs/security/` reports), EOL and the upgrade
   recommendation; `data/sbom.cdx.json` is the CycloneDX SBOM; `data/effort.json` the
   low/likely/high estimate, labelled "estimate — weights from config".
6. Change a weight: add `complexity: {weights: {loc: 1000}}` to a sample's `audit.yaml`, run the
   scan again: the terminal says `analyzed: 0`, yet the `complexity` bands in `services.csv` and `effort.json` change.
   Undo the edit afterwards.
7. Without Dependency-Check installed, `run.json` > `coverage` shows `dependency-check: missing`
   (a gap, not "no vulnerabilities").
