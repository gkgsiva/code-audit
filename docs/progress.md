# Progress

Update this file at the end of every phase, and whenever a session stops mid-phase.

| Phase | Status | Notes |
| --- | --- | --- |
| 0 – Planning | Done | `docs/plan.md`, `docs/decisions.md`, prompts written 2026-10-10 |
| 1 – Foundations | Done | Package, config, local + git sources, inventory, components, coverage, runs, file cache, overrides, diff, summary, CLI, fact model + schemas, `struts-oracle-app` inventory sample (2026-10-10) |
| 2 – Core analyzers | Not started | |
| 3 – Rules, metrics, dependency security | Not started | |
| 4 – Technical HTML report | Not started | |
| 5 – Copilot agent, enrichment, management outputs | Not started | |
| 6 – GUI and hardening | Not started | |

## Next step

Start phase 2: read `docs/prompts/phase-2-analyzers.md`. Analyzers plug into
`legacy_audit/analyzers/base.py` (register them there); their per-file facts are already cached
by the scan pipeline (`payload["facts"]` in the file cache) but not yet merged into the entity
outputs — phase 2 adds that merge plus the linker.

## Known gaps

- Phase 1 only: services, screens, SQL, integrations, batch jobs, libraries, findings and tech
  stack are written as empty files; the struts-oracle-app `expected.yaml` covers inventory only.
- LOC approximations (see decision 20): comment markers inside strings, Java in JSP scriptlets.
- Re-including a file inside a default-excluded folder needs the whole folder re-included
  (`files.include: ["build/"]`), as in `.gitignore`.
- `purge-cache` removes the whole Git clone cache; there is no per-repo purge yet.
- Python 3.11 and Windows are covered by CI only (local runs were Linux, Python 3.13).
