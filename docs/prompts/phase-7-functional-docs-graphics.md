# Phase 7 — Functional documentation and graphics

Goal: business and functional readers understand what the application does, screen by screen and batch by batch, from the generated documents alone; every output is more visual.

## Scan side (deterministic)

- Facts: `Service.sql` (ids of the SQL statements a service reaches) so screens and batches can be joined to tables; `BatchJob.description` and `BatchJob.on_failure` (Autosys `description`, `alarm_if_fail`, `n_retrys`, `max_run_alarm`, `term_run_time`, `box_failure`, `send_notification`, ...).
- `report/flows.py` builds, from facts only:
  - one **screen flow** per screen: screens it comes from, the screen, services that load it or that it submits to / opens, the tables those services touch with C/R/U/D, other systems called, screens it leads to;
  - one **batch flow** per Autosys box or stand-alone job: what starts it (schedule, file watcher, the job that runs it), steps in predecessor order with success/failure conditions, what each step runs, tables, files and systems, failure handling in plain words, and a week calendar of start times.

## Agent side (Copilot, validated by `legacy-audit validate`)

- Stage 12 **Functional** (`functional.json`): summary, domains and capabilities, actors, use cases, business rules, data entities, one story per screen and per batch (purpose, user actions or steps, inputs/outputs, on failure, business impact), interfaces, glossary. Every claim cites evidence or is marked as an assumption; every id must exist.
- Stage 13 **Component documentation** (`component_docs.json`): purpose, responsibilities, key elements, inputs/outputs, tables, tech debt, migration notes per component.
- The agent reads everything the scan found before writing (checklist in the prompt file).

## Outputs

- `legacy-audit report --type functional` (also in `all`, the GUI and VS Code tasks): `reports/functional/` — summary with capability map, use cases, business flows, screens, batch (cards, calendar, timeline, one page per batch), rules, data, interfaces, glossary. Diagrams come from the scan; words from stage 12, else a placeholder.
- Technical report: Flows and Data model pages, migration-readiness gauges, doughnut and stacked charts, radar per component, AI "what this component does" box, layered and today-to-target diagrams, risk heat map, Gantt roadmap, vulnerability bubble chart.
- Management deck and PPTX: coloured section dividers, readiness gauges, capability map, flow slides for the main screens and batches, batch calendar, layers, CRUD and risk heat maps, today-to-target flow, roadmap — native shapes, charts and tables in the PPTX.

## Acceptance criteria

- Screen and batch flows on the samples match the facts (tests on `struts-oracle-app` and `spring-mvc-batch-app`).
- The functional document builds offline and is valid HTML on every sample, with placeholders when there is no enrichment.
- Stage 12/13 fixtures validate; a wrong id fails.
- The PPTX holds native charts (gauges included) and tables (heat maps included), no chart pictures.
