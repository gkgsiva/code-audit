# Parity data for audit-ai

Saved once from the Python tool on `office-main`; regenerate only from there, never by hand.
The runs are the report-parity runs of audit-reports
(`audit-reports/src/test/resources/report-parity/<set>/<sample>/run.zip`).

| Folder | Test | What was recorded |
| --- | --- | --- |
| `validate-parity/` | `ValidateParityTest` | `cases.json`: a run, the edits made to its enrichment files and the stages to check; `expected.json`: Python's `validate_run` messages and `status.json` for each case |
| `mcp-parity/` | `McpParityTest` | `requests.jsonl`: JSON-RPC requests; `expected.json`: the Python MCP server's answers on two runs (the version is left out) |
| `prompt-parity/` | `PromptParityTest` | SHA-256 and length of the system and user prompt of every stage, 8 runs, context sizes 150,000 and 10,000 |
| `copilot-parity/` | `CopilotFilesTest` | SHA-256 of every file Python's `init` writes |
