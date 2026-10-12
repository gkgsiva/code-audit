Enrich the scan facts, stage by stage: ${input:target:all, stale, or a stage name such as flows}.

## Which stages to run

- `all`: every stage below, in order.
- `stale`: read `audit-output/latest/enrichment/status.json` and run the stages listed in
  `stale` and `missing`, in the order below.
- a stage name: that stage only.

## For each stage

1. Read what the stage needs (table below): start from `audit-summary.json`, then targeted
   queries (`legacy-audit facts <entity> --filter component=<name> --limit 50`, or the MCP tools)
   rather than whole files. On a large estate work one component at a time and keep your notes
   in `audit-output/latest/enrichment/_working-summary.md`.
2. Write `audit-output/latest/enrichment/<stage>.json` matching
   `.github/legacy-audit/schemas/<stage>.schema.json`. Start with
   `"meta": {"stage": "<stage>", "author": "copilot-agent"}` and leave `built_from` out: the
   validator records it. If `run.json` > `overrides` lists overrides you took into account, name
   them in `meta.overrides_noted`.
3. Run `legacy-audit validate --stage <stage> --restamp` and fix every error. Repeat until it
   passes.
4. Tell the user in two or three lines what the stage concluded, then go on.

Every number is a figure `{label, metric, value}` copied from `audit-summary.json` (or
`effort.<key>` from `effort.json`); every claim has `evidence` or `assumption: true`; ids come
from the facts (`service:…`, `library:…`, `db_object:…`), component names from
`components.json`.

## Stages

| # | Stage (file) | Read | Write |
| --- | --- | --- | --- |
| 1 | `overview` | audit-summary.json, tech_stack, components | purpose, users, headline figures, 3–6 key points |
| 2 | `classification` | components, entry_points, services per component | capability, kind, criticality per component |
| 3 | `architecture` | components, dependencies, integrations, tech_stack, classification | layers, deployment units, Mermaid diagrams, notes |
| 4 | `flows` | services, screens, batch_jobs, integrations, dependencies | key business flows in plain language, with steps citing fact ids |
| 5 | `service_mapping` | services, target-mapping.json, classification | target and approach per service |
| 6 | `data_migration` | db_objects, sql_statements (PG grades) | approach per object, figures, strategy |
| 7 | `dependency_plan` | libraries (CVEs, EOL, recommendations) | upgrade themes linked to plan waves |
| 8 | `risks` and `assumptions` | findings, libraries, integrations, batch_jobs, input_coverage | risk register with owners; assumptions and open questions (coverage gaps) |
| 9 | `target_architecture` | architecture, service_mapping, target-mapping.json | target components on GCP, diagrams, principles |
| 10 | `plan` | effort.json, components, risks, dependency_plan, target_architecture | approach, waves, effort figures, team, decisions needed, next steps |
| 11 | `narratives` | all stages above | executive, architect, functional and developer texts |
| 12 | `functional` | everything: components, services, screens, batch_jobs, integrations, db_objects, sql_statements, dependencies, classification, flows | business summary, domains and capabilities, actors, use cases, business rules (each citing the code line), business data entities, one story per screen and per batch, interfaces, glossary |
| 13 | `component_docs` | per component: services, screens, SQL, tables, integrations, dependencies, metrics, findings, libraries | purpose, responsibilities, key classes/programs, inputs and outputs, tables, technical debt, migration notes |

## Read everything (stages 12 and 13)

The functional document and the component pages are only as good as what you read. Before
writing `functional` or `component_docs`, go through every fact file below and tick it off in
`audit-output/latest/enrichment/_working-summary.md` (one line per file: read, rows, what you
took from it). On a large estate do it one component at a time with
`legacy-audit facts <entity> --filter component=<name>`.

- [ ] `audit-summary.json` and `run.json` (coverage gaps become open questions)
- [ ] `components.json`, `entry_points.json`, `metrics.json`
- [ ] `services.json` (each service's `screens`, `tables`, `sql` and `external_calls`)
- [ ] `screens.json` and the `submits` / `links` / `forwards` edges in `dependencies.json`
- [ ] `sql_statements.json` and `db_objects.json` (CRUD per table)
- [ ] `batch_jobs.json` (boxes, conditions, schedules, `on_failure`) and the `runs` /
      `invokes` / `triggers` edges; scripts and batch programs that no job runs (they are
      batches too when the scheduler export is missing)
- [ ] the source of every batch script and program, comments included
- [ ] `integrations.json`, `libraries.json`, `findings.json`, `tech_stack.json`
- [ ] the source lines you cite (open them: rules and calculations live in the code)

For `functional`:

- `screens`: one entry per screen that is not a partial, with `screen_id`, what the user does
  there, what is shown and where the user goes next. The report draws the flow (screen,
  services, tables, next screens) from the scan; you write the words.
- `batches`: one entry per batch on the **Flows** page of the technical report: an Autosys
  box, a job that is not in a box (cron, JCL, Spring Batch), and also batch work that no
  scheduler definition starts (marked "no schedule found": one per batch component, whose
  ksh scripts, programs and Java mains are its entry points, not batches of their own; and
  message listeners). `job_id` is the flow's id: the box's or job's `batch_job:` id, the
  batch component's `component:` id, a program outside any component's `service:` id or the
  listener's `entry:` id.
  Say why it exists, when it runs in business words (when no schedule was found, say so and
  give what the code or its comments suggest, as an assumption), its steps (citing job,
  program and file ids), inputs, outputs, what happens when it fails and the business impact
  if it does not run.
- **Read each batch's source, not only the facts.** Open every script, program and SQL file
  the batch runs (the flow page lists them under "What the code says about itself", with the
  header comment the scan found) and read:
  - the comments: the header, and the comments above each paragraph, function or step; they
    usually say what the program does in business words;
  - the main flow: what is read, what is checked, what is computed, what is written, in order;
  - the controls: record counts, totals, reconciliations, `exit` / `RETURN-CODE` / `abend`
    paths, restart and checkpoint logic.
  Then fill `programs` (one line per program or script: what it does in business words,
  with its `fact_id`), `controls` (checks, totals and error exits you found) and `restart`
  (how to re-run after a failure, if the code or its comments say). Cite the lines in
  `evidence`. When a comment and the code disagree, trust the code and say so.
- `business_rules`: validations, calculations, eligibility, status changes and limits you
  can point to in the code (`evidence` with file and line).
- Write for business readers: no class names in `summary`, `purpose` or rule text.

For `plan`, copy `effort.low`, `effort.likely` and `effort.high` as figures, and give each wave
the effort of its components from `effort.by_component.<component>.likely`; a split of one
component across waves is an estimate.

When you finish, run `legacy-audit validate` once more for all stages and report what is
still stale or missing.
