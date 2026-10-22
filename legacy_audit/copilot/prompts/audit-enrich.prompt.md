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
| 3 | `architecture` | components, entry_points, dependencies, integrations, web_services, tech_stack, classification | layers, deployment units, Mermaid diagrams, notes |
| 4 | `flows` | services, screens, entry_points, batch_jobs, integrations, web_services, dependencies | key business flows in plain language, with steps citing fact ids |
| 5 | `service_mapping` | services, target-mapping.json, classification | target and approach per service |
| 6 | `data_migration` | db_objects, sql_statements (PG grades) | approach per object, figures, strategy |
| 7 | `dependency_plan` | libraries (CVEs, EOL, recommendations; Maven, npm, NuGet, COM), tech_stack | upgrade themes linked to plan waves |
| 8 | `risks` and `assumptions` | findings, libraries, tech_stack, integrations, batch_jobs, input_coverage | risk register with owners; assumptions and open questions (coverage gaps) |
| 9 | `target_architecture` | architecture, service_mapping, web_services, tech_stack, target-mapping.json | target components on GCP, diagrams, principles, what replaces each server, runtime and UI framework |
| 10 | `plan` | effort.json, components, risks, dependency_plan, target_architecture | approach, waves, effort figures, team, decisions needed, next steps |
| 11 | `narratives` | all stages above | executive, architect, functional and developer texts |
| 12 | `functional` | everything: components, services, screens (and the code of desktop windows and ASP.NET pages), batch_jobs, integrations, web_services, db_objects, sql_statements, dependencies, classification, flows | business summary, domains and capabilities, actors, use cases, business rules (each citing the code line), business data entities, one story per screen and per batch, interfaces, web services, glossary |
| 13 | `component_docs` | per component: entry points, services, screens, batch jobs, SQL, tables, integrations, web services, dependencies, metrics, findings, libraries | purpose, responsibilities, key classes/programs, inputs and outputs, tables, technical debt, migration notes |

## New kinds of application (Angular, Node.js, .NET, desktop, servers)

The scan also covers single-page front ends, Node.js services, .NET and desktop programs, and
the servers they run on. Treat them like the Java and mainframe parts:

- `classification`: `kind` is `frontend` for an Angular or AngularJS application, `api` for a
  Node.js or ASP.NET service that only exposes HTTP endpoints, `desktop` for a VB6, VB.NET,
  C# (WinForms, WPF) or Java Swing/JavaFX program; `online` stays for server-side web and
  mainframe screens.
- `architecture` and `flows`: entry points of kind `desktop-app` start a desktop program,
  `windows-service` a Windows service; `calls` edges link a front end to the API it calls, and
  `web_services.json` lists the REST and SOAP services offered and called. A desktop program
  that reads the database straight from its forms is a two-tier client: say so.
- `service_mapping`: use the targets in `target-mapping.json` (`desktop-screen`, `spa-screen`,
  `aspnet-screen`, `dotnet-service`, `node-service`, `windows-service`).
- `dependency_plan`: libraries include npm packages, NuGet packages and VB6 COM references and
  ActiveX controls (source `com`, no equivalent on GCP). Put every vulnerable library in a theme
  (its id in `library_ids`): the summary report shows each one's theme and action next to its
  vulnerabilities. Runtimes and frameworks at end of life in `tech_stack` (.NET Framework,
  AngularJS, Visual Basic 6, old Node.js) are themes too.
- `risks`: end-of-life servers, runtimes and frameworks in `tech_stack` (`eol: true`) and COM
  or ActiveX dependencies are risks; cite their evidence.
- `target_architecture`: fill `platform_changes` with one entry per application server, web
  server and runtime in `tech_stack` (categories `app-server`, `web-server`, `runtime`) and per
  desktop or UI framework that goes away (Windows Forms, WPF, VB6, AngularJS, ASP.NET Web
  Forms): `name` as the tech stack names it, its `tech_ids`, `today` (what it does for the
  application, one line) and `target` (what replaces it, one line). The reports show `target`
  marked (AI) in the servers tables instead of the default from `target-mapping.yaml`.
- `component_docs`: for a desktop component, the key elements are its windows and the classes
  their event handlers call; for a front end, its routed pages and the services that call the
  API; for an API, its routes or controllers.

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
- [ ] the code of every desktop window (VB6 form, WinForms, WPF, Swing, JavaFX) and ASP.NET
      page (code-behind): the event handlers hold the business logic and the database calls
- [ ] `web_services.json` (operations, callers) and the code of each operation it offers
- [ ] `sql_statements.json` and `db_objects.json` (CRUD per table)
- [ ] `batch_jobs.json` (scheduled jobs only: boxes, conditions, schedules, `on_failure`;
      `counts.batch_jobs.total` adds the batch components in `unscheduled_batches`, so an
      empty `batch_jobs.json` with a non-zero total is expected) and the `runs` /
      `invokes` / `triggers` edges; scripts and batch programs that no job runs (they are
      batches too when the scheduler export is missing)
- [ ] the source of every batch script and program, comments included
- [ ] `integrations.json`, `libraries.json`, `findings.json`, `tech_stack.json`
- [ ] the source lines you cite (open them: rules and calculations live in the code)

For `functional`:

- `screens`: one entry per screen that is not a partial, with `screen_id`, what the user does
  there, what is shown and where the user goes next. The report draws the flow (screen,
  services, tables, next screens) from the scan; you write the words. Desktop windows and
  ASP.NET pages have their logic in their own code: read the event handlers (button clicks,
  grid double-clicks, form load) and say what each action does; checks and calculations there
  are business rules, cited by line.
- `batches`: one entry per batch on the **Flows** page of the technical report: an Autosys
  box, a job that is not in a box (cron, JCL, Spring Batch), and also batch work that no
  scheduler definition starts (marked "no schedule found": one per batch component, whose
  ksh scripts, programs and Java mains are its entry points, not batches of their own; and
  message listeners and Windows services). Jobs started by Windows Task Scheduler, Hangfire,
  Quartz.NET or node-cron are scheduled jobs like Autosys ones; the flow page lists the code
  they run. `job_id` is the flow's id: the box's or job's `batch_job:` id, the
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
- `batches` feed the **All batches** list (technical report, Batch jobs page, and
  `data/batches.csv`): keep `purpose` to one short sentence (it is shown as "What it does")
  and `when` to one short phrase (shown as "When it runs" when no scheduler definition says).
- `interfaces`: one entry per integration in `integrations.json`, at least every `HTTP`,
  `SOAP`, `FTP`, `SFTP`, `SCP` and `CFT` row. `integration_id` is its id. `partner` names the
  system on the other side in business words, `purpose` says in one sentence what the
  application uses it for (it replaces the scan's `description` in the Integrations list),
  `data_exchanged` what goes across. Rows whose `purpose` field is `login / authentication`
  (Okta and other identity providers) are user sign-in: say who signs in and to what.
- `web_services`: one entry per row of `web_services.json` (REST and SOAP services the
  application offers, `side: offered`, or calls, `side: called`). `web_service_id` is its id.
  `partner` says who calls it (offered) or which system provides it (called), in business words;
  `purpose` says in one sentence what it is for (it replaces the scan's `description` in the Web
  services lists); `migration` suggests the target in one line, for example "Spring Boot 4 REST
  controller", "keep the SOAP contract with Spring-WS for existing callers, add a REST API", or
  "call through Spring's RestClient". Read the operations and their `handler` code; cite it in
  `evidence`.
- `business_rules`: validations, calculations, eligibility, status changes and limits you
  can point to in the code (`evidence` with file and line).
- Write for business readers: no class names in `summary`, `purpose` or rule text.

For `plan`, copy `effort.low`, `effort.likely` and `effort.high` as figures, and give each wave
the effort of its components from `effort.by_component.<component>.likely`; a split of one
component across waves is an estimate.

When you finish, run `legacy-audit validate` once more for all stages and report what is
still stale or missing.
