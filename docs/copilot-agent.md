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
the main screens, services and jobs. The diagrams in the functional document (one flow per screen
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
