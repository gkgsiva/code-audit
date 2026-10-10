# Progress

Update this file at the end of every phase, and whenever a session stops mid-phase.

| Phase | Status | Notes |
| --- | --- | --- |
| 0 – Planning | Done | `docs/plan.md`, `docs/decisions.md`, prompts written 2026-10-10 |
| 1 – Foundations | Done | Package, config, local + git sources, inventory, components, coverage, runs, file cache, overrides, diff, summary, CLI, fact model + schemas, `struts-oracle-app` inventory sample (2026-10-10) |
| 2 – Core analyzers | Done | 15 analyzers (build, deps, config, java, web, spring, sql, shell, c_cpp, proc, cobol/BMS, jcl, autosys, mq, cft), linker (edges, merges, Maven versions, screen/partial and COBOL sub-program rules, service reach, component classification), headline counts, analyzer coverage in `run.json`, 3 new samples with `expected.yaml`, re-run and 200k LOC stress tests (2026-10-10) |
| 3 – Rules, metrics, dependency security | Not started | |
| 4 – Technical HTML report | Not started | |
| 5 – Copilot agent, enrichment, management outputs | Not started | |
| 6 – GUI and hardening | Not started | |

## Next step

Start phase 3 (rules, metrics, dependency security): read `docs/prompts/phase-3-rules-metrics-security.md`. Facts
are complete per entity in `audit-output/runs/<id>/data/`; `legacy_audit/store/counts.py`
computes the headline counts. SQL statements already carry `normalized`, `vendor_features`,
`dynamic` and `parsed` for grading; libraries carry group/artifact/version/SHA-1 for the SBOM and
vulnerability lookup; `javax.*` imports are `upgrade-signal` tech items.

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
