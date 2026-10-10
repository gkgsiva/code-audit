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

For `plan`, copy `effort.low`, `effort.likely` and `effort.high` as figures, and give each wave
the effort of its components from `effort.by_component.<component>.likely`; a split of one
component across waves is an estimate.

When you finish, run `legacy-audit validate` once more for all stages and report what is
still stale or missing.
