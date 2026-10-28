---
applyTo: "legacy_audit/copilot/**,legacy_audit/mcp/**,legacy_audit/templates/**"
---

# Files installed into audited workspaces

The files in `legacy_audit/copilot/` are a **product feature**: `legacy-audit init`
(`copilot/install.py`) copies them into the analyst's audited workspace as the
`legacy-auditor` agent, the `/audit-scan`, `/audit-enrich`, `/audit-report`, `/audit-qa`
prompts, a `copilot-instructions.md` for that workspace and `.vscode/mcp.json`. They are not
instructions for developing this repository.

- Write them for Copilot Chat working on an audit: numbers only from
  `audit-output/latest/data/`, evidence for every claim, write only under
  `audit-output/latest/enrichment/`, run `legacy-audit validate` after each stage.
- Every YAML front-matter value is quoted (`install._q`); an unquoted value holding ": "
  makes VS Code drop the file (decision 173). `tests/test_copilot.py` parses every header.
- A new installed file must also be added to `scan.COPILOT_PATHS` so the scan never reads it
  as legacy source.
- When a new entity, field or stage matters to the agent, update "Where things are" and the
  rules in `agent.md` and the stage instructions in `prompts/audit-enrich.prompt.md`; keep
  `legacy_audit/ai/context.py` (the `legacy-audit enrich` prompts) consistent with them.
- Analysts get changes only by running `legacy-audit init` again in each audit folder (their
  own edits are kept; the new version is written as `<file>.new`). Say so in
  `docs/validate-in-vscode.md` and `docs/copilot-agent.md`.
- The MCP server (`mcp/`) and `legacy-audit facts` are read-only: never add a tool that
  writes, and keep `read_evidence` inside the scanned roots with secrets masked.
- `legacy_audit/templates/audit.yaml` is the starter config `init` writes: keep it short and
  commented for non-technical users; every key must exist in `config.py`.
