# Using the Copilot agent

`legacy-audit` counts; GitHub Copilot interprets. This guide is for analysts who run the
enrichment in VS Code.

## Set up a legacy workspace

```
cd <legacy workspace>
legacy-audit init          # audit.yaml, audit-inputs/, and the Copilot files below
legacy-audit scan
```

`init` installs:

| File | Purpose |
| --- | --- |
| `.github/agents/legacy-auditor.agent.md` | The **legacy-auditor** custom agent (VS Code with custom agents) |
| `.github/chatmodes/legacy-auditor.chatmode.md` | The same agent for older VS Code builds (custom chat modes) |
| `.github/copilot-instructions.md` | Always-on rules: numbers only from the scan, cite evidence, write only to `audit-output/latest/enrichment/` |
| `.github/prompts/audit-scan.prompt.md` | `/audit-scan`: run or resume the scan and summarise coverage |
| `.github/prompts/audit-enrich.prompt.md` | `/audit-enrich all \| stale \| <stage>`: write the enrichment, stage by stage |
| `.github/prompts/audit-report.prompt.md` | `/audit-report`: validate and build the technical site, the functional document, the HTML deck and the PPTX |
| `.github/prompts/audit-qa.prompt.md` | `/audit-qa`: cross-check the enrichment against the facts |
| `.github/legacy-audit/schemas/*.schema.json` | JSON Schema of each enrichment file |
| `.vscode/mcp.json` | Registers the read-only `legacy-audit` MCP server |

`init --copilot-layout agents|chatmodes|both|none` picks the agent layout (default both). Running
`init` again updates files you have not changed; a file you edited is kept and the new version is
written next to it as `<file>.new`. The scanner never reads these files as legacy source.

**Versions.** The files follow the formats documented for VS Code 1.105–1.106 with GitHub Copilot
Chat (custom agents in `.github/agents/*.agent.md`; prompt files with `agent:` in the
front-matter; chat modes with `mode:` for older builds). They were written against that
documentation and checked by tests for shape, not run inside VS Code from the build environment.
Front-matter is kept to `description` and `tools`: if Copilot reports an unknown tool name, delete
the `tools:` line and the agent gets every tool.

**The agent or the `/audit-*` prompts do not show in Copilot Chat.**

1. VS Code must have the audit folder itself open (File > Open Folder: the folder that holds
   `audit.yaml` and `.github/`), not a folder above it. On WSL2, open it with `code .` from that
   folder, so the window says `WSL: Ubuntu` at the bottom left.
2. Run `legacy-audit init` in that folder after every pull: it refreshes the files. A line
   "kept your edits" means you changed a file; compare it with the `.new` file next to it and
   keep the new one.
3. Reload the window (Ctrl+Shift+P, **Developer: Reload Window**), open Copilot Chat in
   **Agent** mode and type `/audit`: the four prompts are listed, and **legacy-auditor** is in
   the agent list.
4. Still missing: open `.github/prompts/audit-enrich.prompt.md`; a red or yellow mark on its
   header names the problem (Problems panel, Ctrl+Shift+M).

## Run the enrichment

1. Open the workspace in VS Code, open Copilot Chat and pick the **legacy-auditor** agent.
2. `/audit-scan` (or `legacy-audit scan` in a terminal).
3. `/audit-enrich all`. The agent writes one file per stage into
   `audit-output/latest/enrichment/` and runs `legacy-audit validate --stage <name> --restamp`
   after each; it fixes errors before moving on.
4. `/audit-report` builds the reports (or `legacy-audit report --type all`).

### The functional and component documentation (stages 12 and 13)

`/audit-enrich functional` writes `functional.json`: the business domains and capabilities, who
uses the application, use cases, business rules, data entities, one story per screen (purpose,
what the user does, what it shows, where it leads) and per batch (purpose, when, steps, inputs and
outputs, what happens on failure, business impact), the interfaces and a glossary.
`/audit-enrich component_docs` writes `component_docs.json`: what each component does, its key
classes or programs, inputs and outputs, tables, technical debt and migration notes.

Before writing, the agent reads everything the scan found for the application (services, screens,
SQL and tables, batch jobs, integrations, libraries, metrics, findings) and opens the source of
the main screens, services and jobs. For every batch (scheduled or not) it opens the scripts
and programs it runs, reads their comments and main flow, and adds to the story what each program
does, the controls in the code (counts, checks, reconciliations, error handling) and how to
restart after a failure. A script or program that no scheduler export starts is still a batch:
its story points at the script's file id or the program's service id. The diagrams in the functional document (one flow per screen
and per batch) come from the scan, not from the agent: the agent's words appear beside them, so a
missing or wrong story never changes a flow. Build the document with `legacy-audit report --type
functional` (or `all`); it is in `audit-output/latest/reports/functional/index.html`.

After a re-scan (new inputs, code changes), run `/audit-enrich stale`: `status.json` lists the
stages whose facts changed, and only those are redone.

## What `legacy-audit validate` checks

- the file matches its schema (every claim has `evidence` or `assumption: true`);
- every id (`service:…`, `library:…`, …) and component name exists in the facts;
- every figure's value equals the scan's at its `metric` path unless it is `estimate: true`;
- every evidence file exists in the scanned source and the line is inside it;
- counts written in prose ("12 services") that differ from the scan are warnings.

A stage that passes gets `meta.built_from` stamped: the hash of each fact file and upstream stage
it was built from. That is how a later scan knows the stage is stale.

## The MCP server (optional)

`legacy-audit mcp` serves the latest run's facts to Copilot over stdio, read-only:
`query_facts(kind, filters, limit, fields)`, `get_component(id)`, `get_sql(id)`,
`read_evidence(file, line, context)` (credentials masked; only files of the scanned source) and
`coverage()`. `init` writes `.vscode/mcp.json`:

```json
{
  "servers": {
    "legacy-audit": {
      "type": "stdio",
      "command": "legacy-audit",
      "args": ["mcp", "--config", "${workspaceFolder}/audit.yaml"]
    }
  }
}
```

In VS Code, open `.vscode/mcp.json` and choose **Start** above the server (or run **MCP: List
Servers**). If `legacy-audit` is installed in a virtual environment, put the full path of
`legacy-audit` (`.venv\Scripts\legacy-audit.exe` on Windows) in `command`. Without the server
the agent uses `legacy-audit facts <kind> --filter field=value` in the terminal, which returns the
same rows.

## Using another model

The enrichment is model agnostic: the same stages, schemas and validator apply whoever writes
them.

- **Copilot with another model.** Pick the model in Copilot Chat's model picker. To pin one for
  the agent, set `ai.copilot_model` in `audit.yaml` (the name as the picker shows it, e.g.
  `"Claude Sonnet 4.5 (copilot)"`) and run `legacy-audit init` again: the agent file gets a
  `model:` line.
- **Any model without Copilot.** Set `ai.provider` (`openai` for any OpenAI-compatible endpoint
  such as OpenAI, Gemini, Mistral, LM Studio, vLLM or a LiteLLM gateway; `azure-openai`;
  `anthropic`; `ollama` for a local model) and `ai.model`, put the API key in the environment
  variable named by `ai.api_key_env` (defaults: `OPENAI_API_KEY`, `AZURE_OPENAI_API_KEY`,
  `ANTHROPIC_API_KEY`), then:

  ```
  legacy-audit enrich --check          # tiny test request
  legacy-audit enrich                  # every stage; --stale for missing/stale ones; -s functional for one
  legacy-audit enrich --provider ollama --model qwen2.5:14b   # one-off override
  ```

  `LEGACY_AUDIT_AI_PROVIDER`, `LEGACY_AUDIT_AI_MODEL`, `LEGACY_AUDIT_AI_BASE_URL` and
  `LEGACY_AUDIT_AI_API_KEY_ENV` override the config. The GUI's **AI enrichment** screen has
  **Run missing and stale stages** / **Run all stages** buttons when a provider other than
  Copilot is set.

How it works: a model called over an API has no tools, so for each stage the tool packs into
the prompt what Copilot would read: this agent's rules, the `/audit-enrich` instructions, the
stage's JSON Schema, `audit-summary.json`, the fact files the stage reads, the earlier stages it
builds on and, for `functional` and `component_docs`, the first lines of every batch script and
program (comments included, credentials masked). Long lists are cut to `ai.context_chars` with a
note saying how many rows were left out. The answer is written to `enrichment/<stage>.json`,
validated, and the errors are sent back for `ai.repair_rounds` rounds. `meta.author` records the
model (`anthropic:claude-sonnet-4-5`). The scan itself never calls a model.
