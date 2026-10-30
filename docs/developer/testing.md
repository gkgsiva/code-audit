# Tests and parity checks

Run everything with VS Code task 4 (`mvn -B verify` in `backend/`) and task 5 (frontend). CI
(`.github/workflows/ci.yml`) runs the backend on Ubuntu and Windows, the frontend on Ubuntu,
and starts the three containers to check health and a login.

## Backend tests

| Module | Test | What it checks |
| --- | --- | --- |
| audit-model | `AppInfoTest` | Name and version |
| audit-engine | `RulesParityTest` | Ignore rules, languages, categories, line counts, encodings equal Python's (`parity/rules.json`) |
| audit-engine | `ParityTest` | Each of the 10 samples: `inventory.json` byte for byte, components, coverage findings and input coverage equal Python's |
| audit-engine | `AnalyzerParityTest` | Each sample file: every ported analyzer returns the same facts as Python (`parity/<sample>/analyzers.json`) |
| audit-engine | `CaseParityTest` | Each ported analyzer on the cases recorded from Python (`parity/cases/<analyzer>.json.gz`, 2,000+ for Phase 2, 1,100+ more for Phase 3, 350 more for Phase 4) |
| audit-engine | `SqlToolsParityTest` | SQL helpers on 1,458 statements (`parity/sqltools.json.gz`) |
| audit-engine | `RunParityTest` | Each of the 10 samples: every entity file of the whole run (including findings, libraries with vulnerabilities and advice, input coverage), `effort.json`, `target-mapping.json`, `sbom.cdx.json`, `audit-summary.json` and the vulnerability sources equal Python's (`parity/<sample>/run/`); only the run id, times, tool version and folders are left out |
| audit-engine | `ExpectedTest` | Each sample's `expected.yaml`: files, lines, components, roles, headline counts, services, screens, integrations, web services, batch jobs, unscheduled batches |
| audit-engine | `PgCorpusTest` | SQL to PostgreSQL text and grades on 3,132 statements recorded from Python (`parity/pg*.json.gz`) |
| audit-engine | `FindingsTest`, `SecurityTest` | The rules engine (conditions, messages, config switches) and dependency security (versions, end of life, advice, report import, matching, SBOM, scanner runs) as in the Python tests |
| audit-engine | `ProofsTest` | The Phase 0 parser proofs |
| audit-engine | `WorkspaceParityTest` | 40 settings edits (`setTopLevel` with every kind of value PyYAML quotes or wraps), the component choices on 10 samples and the pre-scan on 10 samples give the same files and answers as the Python GUI (`workspace-parity/workspace.json`) |
| audit-reports | `JinjaTest` | The template engine: syntax, filters, escaping, whitespace control as Jinja2 |
| audit-reports | `ReportParityTest` | 11 sample runs (6 plain scans, 4 with enrichment, 1 audit only): every file of the technical, functional, management and summary reports equals what Python built from the same run (`report-parity/<set>/<sample>/`, README there); the sample folder is written `@SAMPLE@` / `@REL@` on both sides. A file that differs is written to `audit-reports/target/report-parity-actual/` for a diff |
| audit-reports | `PdfReportsTest` | PDF settings, cover, page numbers, titles, browser lookup; with `PDF_BROWSER=<browser program>` (or on Linux CI) it prints the summary and functional PDFs and checks pages, bookmarks and metadata |
| audit-reports | `DevCompareTest` | Developer check, skipped unless `REPORT_REF` is set: builds the reports for every run of a folder of Python reference runs into `reports-java/` beside `reports/` |
| audit-ai | `ValidateParityTest` | 22 validator cases recorded from Python (the enrichment of the Python tests, edited: wrong ids, wrong figures, missing evidence, bad choices, extra fields, target mismatches, stale facts): the messages, their order and `status.json` equal Python's (`validate-parity/`) |
| audit-ai | `McpParityTest` | 20 MCP requests (initialize, tools/list, each tool, bad arguments, unknown tool) on two runs: every answer equals the Python server's (`mcp-parity/`) |
| audit-ai | `PromptParityTest` | The system and user prompt of every stage for 8 runs and 2 context sizes (224 prompts) equal Python's; compared by SHA-256 and length (`prompt-parity/expected.json`) |
| audit-ai | `CopilotFilesTest` | The files `init` writes (agent, chat modes, prompts, instructions, `audit.yaml`) equal Python's, except the `/audit-report` prompt (decision 229); `mcp.json` starts the jar |
| audit-ai | `AiClientTest`, `EnrichTest` | Model settings from `audit.yaml`, variables and options; the request each provider gets; dropping refused parameters; proxy; a whole enrichment against a local fake model with openai, ollama, azure-openai and anthropic, including one repair round: every stage passes the validator |
| audit-ai | `CarryOverTest` | A new scan takes over the previous run's enrichment and marks stale stages; edits in a copied `latest` are kept |
| audit-cli | `MainTest`, `DiffCommandTest` | The `version` command, a clear error for a missing samples folder; two scans of one sample and `diff previous latest` with no changes |
| audit-server | `WorkspaceFlowTest` | Settings form and text (a bad save is rolled back), input files, component choices, tool checks, two scans, the runs list, compare, the component and source pages, `?run=previous`, and viewers refused every change |
| audit-server | `AuthFlowTest`, `ProjectScanFlowTest` | Login, roles, projects, members, a real scan through the API (H2 database), building the summary report in the background, reading its page and the data pack, a viewer refused a build; the enrichment states, validate, and the plain refusal to run with Copilot |

Task 8 runs only the analyzer comparisons, which is quicker while porting an analyzer. Task 10
runs the whole-run comparisons (`RunParityTest`, `ExpectedTest`, `PgCorpusTest`). Task 12 runs
the report comparisons (`ReportParityTest`, `JinjaTest`, `PdfReportsTest`). Task 14 runs the AI
and Copilot comparisons (`ValidateParityTest`, `McpParityTest`, `PromptParityTest`,
`CopilotFilesTest`). Task 17 runs the settings and component picker comparison
(`WorkspaceParityTest`).

## How the parity data was made

The Python tool on `office-main` is the reference. Its results were saved once and are kept as
test data in `backend/audit-engine/src/test/resources/parity/` (see the README there):

- **Samples**: `legacy-audit scan --full` on every sample app; the run files and the file cache
  give the inventory, components, coverage and the facts per analyzer and file.
- **Recorded cases**: the Python test suite was run with a small pytest plugin that saved
  every analyzer call (input file, config, output facts or error). These reach far more
  branches than the samples (broken files, odd syntax, every framework the tests cover).

- **Workspace**: `workspace-parity/workspace.json` holds what the Python GUI code
  (`workspace.set_top_level`, `set_component_choices`, `prescan`) returned for 40 edits and the
  10 samples, made by a small script run against `office-main`.

When a later phase ports an analyzer, its cases are already there; the test picks up every
registered analyzer by name. Regenerate the data only from `office-main`, never by hand.

## Reading a failure

A parity failure lists, per case, the file and both outputs:

```
java: 1 of 459 case(s) differ
case 17 (src/main/java/com/acme/OrderDao.java):
  python: {"entities":{"sql_statements":[...]}}
  java:   {"entities":{"sql_statements":[...]}}
```

Copy both lines into a diff tool. The usual causes are a Python behaviour the Java code did not
copy (regex flags, `strip`, sorting by code point, `None` vs `null`); use the helpers listed in
[audit-engine.md](audit-engine.md).

## Frontend tests

Vitest through `npx ng test --watch=false`. Page tests use Angular's `HttpTestingController` to
answer the API calls (see `projects.spec.ts`, `project.spec.ts`, `login.spec.ts`, `results.spec.ts`).
