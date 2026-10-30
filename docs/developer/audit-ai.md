# audit-ai

Package root: `com.legacyaudit.ai` (plain Java, no Spring). Everything about the AI part of the
tool: checking the enrichment that Copilot or a model writes, writing it with a model of your
choice, the Copilot files that `init` installs, and the read-only MCP server that gives Copilot
the facts. The command line (`init`, `validate`, `enrich`, `facts`, `mcp`) and the web backend
(the Enrichment page) call the same classes.

The scan itself never calls an AI. Enrichment is a separate step on a finished run: 14 stages
(`engine.enrich.Stages`), each one JSON file in `<run>/enrichment/`, read by the reports.

## Packages

| Package | Classes | Python original |
| --- | --- | --- |
| `ai.enrich` | `SchemaCheck`, `Validator` | `enrich/schemas.py`, `enrich/validate.py` |
| `ai.model` | `AiSettings`, `AiClient`, `AiError`, `PromptContext`, `Enricher` | `ai/settings.py`, `ai/client.py`, `ai/context.py`, `ai/runner.py` |
| `ai.copilot` | `CopilotFiles`, `Workspace` | `copilot/install.py`, `workspace.py` |
| `ai.mcp` | `Facts`, `McpServer` | `mcp/facts.py`, `mcp/server.py` |
| `ai.doctor` | `Doctor` | `doctor.py` (without the PowerPoint checks) |

The stage list, the status file (`status.json`: missing, stale, invalid stages) and the
carry-over between runs live in `audit-engine` (`engine.enrich.Stages`, `Status`), because the
scan and the reports need them too.

## Checking the enrichment (`ai.enrich`)

- **`SchemaCheck`** checks one stage's JSON against its schema and returns errors as
  `(location, message)`, with the same words as pydantic in the Python tool ("Field required",
  "Extra inputs are not permitted", "Input should be 'a', 'b' or 'c'"). It also applies the
  claim rule: an object that can carry evidence must cite evidence or say `assumption: true`.
  The schemas are `schemas/enrichment/*.schema.json` (copied into the jar by the module's pom);
  `prompt-schemas/<stage>.json` holds the compact form the prompts show to a model.
- **`Validator.validate(runDir, stages, restamp)`** runs, per stage file, in order: the schema
  check; references (ids in `*_id` / `*_ids` fields and component names exist in the facts);
  numbers (a figure's value equals the scan's at its `metric` path unless `estimate: true`;
  counts written in prose such as "12 services" that differ are warnings); evidence (each cited
  file is in the scanned source and the line is inside it); and, for the target stages, that
  suggestions follow `data/target.json` (warnings). A stage with no errors gets
  `meta.built_from` stamped. It returns a `Report` of `Message`s (level, stage, where, text) in
  Python's order and rewrites `status.json`. `restamp` stamps the checked stages again with the
  current facts (after they were re-run), so they stop counting as stale.

## Writing the enrichment with a model (`ai.model`)

1. **`AiSettings.of(cfg, provider, model, baseUrl)`** reads the `ai:` section of `audit.yaml`,
   then the `LEGACY_AUDIT_AI_PROVIDER` / `_MODEL` / `_BASE_URL` / `_API_KEY_ENV` variables, then
   the command line. The API key is never in the settings: `keyEnv()` names the variable that
   holds it (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `AZURE_OPENAI_API_KEY` by default).
2. **`AiClient`** sends one request and returns the text. Two families: OpenAI-style chat
   (`openai`, `ollama`, `azure-openai`, any compatible gateway) and Anthropic messages. It uses the
   JDK `HttpClient`; proxy from `HTTPS_PROXY` / `HTTP_PROXY` / `NO_PROXY`, an extra company
   certificate from `ai.ca_bundle`. When a model refuses a parameter (`max_tokens`,
   `temperature`, `response_format`) it drops or renames it and tries again, as Python does.
   Provider `copilot` raises `AiError` with what to do instead (run `/audit-enrich` in VS Code).
3. **`PromptContext.build(runDir, stage, budget)`** writes the system and user prompt for a
   stage: the stage instructions, its schema and the facts it needs, cut to `ai.context_chars`.
   The text is the same as Python's, character for character (see `PromptParityTest`).
4. **`Enricher.enrich(runDir, stages, client, progress)`** runs the stages in order. Each answer
   goes through `AiClient.extractJson`, then the validator; on errors the model gets them back
   for up to `ai.repair_rounds` repair rounds. A valid stage is written with `meta.author`
   (`provider:model`) and `meta.built_from`. `pickStages` picks all stages, the named ones, or
   (with `stale`) only the missing and stale ones. `check(client)` sends a tiny test request.

## Copilot files and new workspaces (`ai.copilot`)

- **`Workspace.init(folder, name, force, layout)`** writes `audit.yaml` (from the template in
  `resources/legacyaudit/ai/audit.yaml`), `audit-overrides.yaml`, `audit-inputs/` and the
  Copilot files. Existing files are kept unless `force`.
- **`CopilotFiles.install(folder, layout, model, jar)`** writes `.github/copilot-instructions.md`,
  the agent (`.github/agents/legacy-audit.agent.md` and/or the older `chatmodes` file), the
  prompts (`/audit-enrich`, `/audit-validate`, `/audit-report`, ...) and `.vscode/mcp.json`.
  The texts are Python's (`resources/legacyaudit/ai/copilot/`); the one change is the
  `audit-report` prompt, which builds HTML and PDF instead of PowerPoint (decision 229). When the
  running jar is known, `mcp.json` starts it with the same Java (decision 231).

## MCP server (`ai.mcp`)

`McpServer.serve(in, out)` speaks MCP (JSON-RPC 2.0, one message per line) over stdio, the
same way the Python server does: `initialize`, `tools/list`, `tools/call`, `ping`. Its five
tools are read-only and come from `Facts`:

| Tool | What it returns |
| --- | --- |
| `query_facts` | Rows of one kind of fact with filters, fields, search and paging (`engine.store.FactsQuery`) |
| `get_component` | One component with its files, entities, links and findings |
| `get_sql` | One SQL statement with its tables, PostgreSQL text and grade |
| `read_evidence` | Lines of a source file around a line; lines that look like secrets are masked |
| `coverage` | What the scan could and could not read |

Errors come back as a tool result with `isError` and Python's text (`FactsError: ...`,
`KeyError: 'id'`).

## How the callers use it

| Caller | Classes |
| --- | --- |
| `legacy-audit init` | `Workspace`, `CopilotFiles` |
| `legacy-audit validate` | `Validator` |
| `legacy-audit enrich` | `AiSettings`, `AiClient`, `Enricher` |
| `legacy-audit facts` | `engine.store.FactsQuery` (the same rows as `query_facts`) |
| `legacy-audit mcp` | `McpServer` |
| `legacy-audit scan` | `Status.carryOver` (engine): the previous run's enrichment is copied and stale stages are marked |
| web backend `server.enrichment` | `Validator`, `AiSettings`, `AiClient`, `Enricher` |
| `legacy-audit doctor`, web backend `server.workspace` | `Doctor.run(cfg, outputDir)`: one `Check` per tool (name, status ok/missing/old/error, required, detail, needed for) |
| web backend `server.results` | `Facts.getComponent`, `Facts.readEvidence` for the component and source pages |

## Tests

`ValidateParityTest`, `McpParityTest`, `PromptParityTest` and `CopilotFilesTest` compare with
the Python tool; `AiClientTest` and `EnrichTest` check the requests of each provider and a whole
enrichment against a local fake model; `CarryOverTest` checks the carry-over. See
[testing.md](testing.md).
