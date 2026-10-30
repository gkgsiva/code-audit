# audit-cli

Package: `com.legacyaudit.cli`. The laptop command line: one runnable jar,
`backend/audit-cli/target/legacy-audit.jar`, built by `mvn -B verify` (task 4). It uses
picocli and calls the engine directly; it needs no database and no Docker.

```
java -jar legacy-audit.jar version
java -jar legacy-audit.jar scan -c audit.yaml [-o <output-dir>] [--full] [-v]
java -jar legacy-audit.jar report [-t tech|functional|mgmt|summary|pdf|all] [--run latest] [-c audit.yaml] [-o <output-dir>] [--open]
java -jar legacy-audit.jar diff <run-a> [<run-b>] [-c audit.yaml] [-o <output-dir>] [--json diff.json] [--show 10]
java -jar legacy-audit.jar proofs [--samples samples] [--out report.md]
java -jar legacy-audit.jar init [<folder>] [--name <app>] [--copilot-layout agents|chatmodes|both|none] [--force]
java -jar legacy-audit.jar validate [-s <stage>]... [--restamp] [--run latest] [-c audit.yaml] [-o <output-dir>]
java -jar legacy-audit.jar enrich [-s <stage>]... [--stale] [--check] [--provider p] [--model m] [--base-url u] [--run latest] [-c audit.yaml] [-o <output-dir>]
java -jar legacy-audit.jar facts <kind> [-f field=value]... [--fields a,b] [--limit 50] [--run latest] [-c audit.yaml] [-o <output-dir>]
java -jar legacy-audit.jar mcp [-c audit.yaml] [-o <output-dir>]
```

Next to the jar the build puts two small launchers, `legacy-audit` (Linux, macOS, WSL2) and
`legacy-audit.cmd` (Windows). With `backend/audit-cli/target` (or wherever the jar is copied)
on `PATH`, `legacy-audit scan ...` works as it did with the Python tool, which is what the
Copilot prompts and agent expect. `LEGACY_AUDIT_JAVA_OPTS` passes extra Java options.

| Class | What it does |
| --- | --- |
| `Main` | Entry point; lists the commands; `version` and `proofs` |
| `InitCommand` | `init`: a new workspace: `audit.yaml`, `audit-overrides.yaml`, `audit-inputs/` and the Copilot files (`ai.copilot.Workspace`); `.vscode/mcp.json` starts this jar |
| `ValidateCommand` | `validate`: checks the enrichment of a run (`ai.enrich.Validator`), prints the errors and warnings, then a table of the stages with their state (valid, invalid, missing, stale and why); exit code 1 on errors |
| `EnrichCommand` | `enrich`: writes the enrichment with the model under `ai:` (`ai.model.Enricher`), one line per stage; `--check` only tests the model settings; provider `copilot` explains to use `/audit-enrich` in VS Code; exit code 1 when a stage stays invalid |
| `FactsCommand` | `facts`: rows of one kind of fact as JSON (`engine.store.FactsQuery`), for Copilot and for people |
| `McpCommand` | `mcp`: the MCP server on standard input and output (`ai.mcp.McpServer`), started by VS Code from `.vscode/mcp.json` |
| `RunOptions`, `Table` | Shared options (`--run`, `-c`, `-o`) and finding the run folder; plain text tables as Python's `tabulate` |
| `DiffCommand` | `diff`: compares two runs (an id or its start, `latest`, `previous` or a folder; the second defaults to `latest`), prints a table of counts per entity and the first added (`+`), removed (`-`) and changed (`~`) records; `--json` writes the whole diff |
| `ReportCommand` | `report`: builds the chosen report of a run (default: the technical report of the latest run). `pdf` builds all four and prints them; `all` builds all four and the PDFs when `pdf.enabled` is true. Prints where each report went and how many enrichment stages are missing or stale; `--open` opens the first report in the browser. Exit code 1 when `pdf` wrote no PDF (no browser) |
| `ScanCommand` | `scan`: loads the config, runs `Scanner.run`, prints the run folder, the file counts, the files per analyzer, the input coverage lines and warnings. `-v` prints the scan log as it is written |

Exit codes: `0` done, `1` invalid config or another scan running in the same output folder,
`2` wrong options (picocli) or a missing samples folder for `proofs`.

The jar's manifest has `Enable-Native-Access: ALL-UNNAMED`, which tree-sitter needs.

Commands still to come, with the same names as the Python tool: `doctor`, `purge-cache`
(Phase 9).
