# Debugging and known pitfalls

## Where to look first

| Question | Look at |
| --- | --- |
| What happened during a scan? | `audit-output/runs/<run-id>/logs/scan.log` (also `legacy-audit scan -v`) |
| Warnings, coverage, timings, unresolved references | `run.json` (`warnings`, `coverage`, `analyzers`, `timings`, `unresolved_references`, `overrides`) |
| Is a fact there, and where did it come from? | `data/<entity>.json` (every row has `evidence`), `data/facts.sqlite` (SQLite Viewer extension), or `legacy-audit facts <entity> -f field=value` |
| What changed between two runs? | `legacy-audit diff previous latest` |
| Is the cache hiding a change? | `legacy-audit scan --full` (ignores the per-file cache) |
| Step through the code | VS Code **Run and Debug** > `legacy-audit: scan a sample` (breakpoints in `legacy_audit/`), or `pytest: current test file` |
| Which tools are installed? | `legacy-audit doctor` |
| Is the enrichment valid and fresh? | `legacy-audit validate`, `enrichment/status.json` |

Parallel analysis runs in worker processes; to debug an analyzer with breakpoints, set
`inventory: {workers: 1}` in the sample's `audit.yaml` (then undo it), or call the analyzer
from a unit test.

## Pitfalls (learnt the hard way)

**Cached results hide analyzer changes.** Per-file results are cached and keyed by each
analyzer's `version`. Change an analyzer without bumping its `version` and existing audit
folders keep the old facts until the file changes. Changes to the per-file record (language,
LOC, line splitting) need `INVENTORY_VERSION` bumped in `inventory/files.py` (decision 159
bumped it to 5). Linking, metrics, rules and totals are never cached.

**Line numbers.** A line ends at `\n`, `\r\n` or a lone `\r` (old Mac files), and nothing else
(form feeds stay inside the line). Use `analyzers.textutil.split_lines` everywhere line numbers
are counted or read back (analyzers, validator, MCP `read_evidence`). `str.splitlines()` also
splits on form feeds and other characters, which once made evidence lines out of range and
stopped Copilot's enrichment (decision 159).

**Ids must be unique per entity.** `make_id(kind, *key_fields)` must get enough key fields to
tell two facts apart. A collision once failed every scan with `UNIQUE constraint failed:
web_services.id` (two SOAP services on one host). Now duplicates are dropped with a warning
(`store.writer.drop_duplicate_ids`, decision 178), but a warning still means a bug: fix the id.

**A failing linker step is a warning, not a failed run.** Optional cross-file steps are
wrapped so one bad case leaves the rest of the scan complete (decision 178). Follow that
pattern for new steps; never let an exception in one file or one step lose the run.

**Copilot file headers must be valid YAML.** In `legacy_audit/copilot/`, every front-matter
value is written quoted by `copilot/install.py` (`_q`). An unquoted `description:` holding ": "
made VS Code silently drop the agent and prompts (decision 173). `tests/test_copilot.py` parses
every header; keep it passing.

**Counts and lists that look inconsistent on purpose.** `counts.batch_jobs.total` = scheduled
jobs (rows of `batch_jobs.json`) + unscheduled batch entry points (`audit-summary.json >
unscheduled_batch_entries`), so `batch_jobs.json` can be empty while the total is not
(decisions 116, 160, 168). Integrations are one row per external service (decision 177), with
the old per-endpoint ids kept in `grouped_ids`.

**`latest` is a copy on Windows without developer mode.** `audit-output/latest` is a symlink
where the OS allows it, else a full copy of the run. The tool itself reads `runs/LATEST`.
Copilot writes enrichment into `latest/enrichment/`; `enrich.status.sync_from_latest` brings it
back into the run folder. Reports built for a run are also built into the `latest` copy
(`report.tech.build_tech_report`).

**Never scan the tool's own files.** `scan.COPILOT_PATHS`, the output folder, `audit.yaml`,
`audit-overrides.yaml` and `audit-inputs/` handling are what keep the tool from reading its own
outputs as legacy code. A new file `init` installs must be added to `COPILOT_PATHS`.

**Old `audit-overrides.yaml` names.** After component grouping changes (decision 176), an
override naming a component that no longer exists is reported as a warning ("matched nothing").
That is expected: the analyst renames it in the GUI's Components screen or in the file.

**After pulling a change, analysts may need to:** run `pip install -e .` again (new
dependency), `legacy-audit init` in each audit folder (new Copilot agent or prompt files),
rescan (new facts), and `/audit-enrich stale` (stages whose facts changed). Say which ones in
the validate section of each change.

**Windows paths.** Use `pathlib`; store and compare paths as POSIX (`as_posix()`); accept `\`
in config (`config._norm_path`). Very long paths and unreadable files become
`file-unreadable` findings, not failures. Open every text file with `encoding="utf-8"` and
write with `newline="\n"` where the output is compared in tests.

**WSL2.** `python3`, not `python`. Opening a browser or a folder from WSL needs `wslu`
(`BROWSER=wslview`); `xdg-open` messages in the terminal are harmless. The PDF printer looks for
Chrome/Chromium on Linux and retries without the Chrome sandbox (Ubuntu 24); using Windows Edge
from WSL2 is not tested on a real laptop yet.

**Scanners.** OSV-Scanner and Dependency-Check are optional and found on PATH or at
`security.<tool>.path`. A wrong `path:` gives "not found at <path>"; `path: null` uses PATH.
`osv_scanner.offline: false` (default) queries osv.dev; Dependency-Check needs a local NVD
database (`data_dir`). Missing scanners are a coverage gap, never an error.

**No network at scan time.** Do not add HTTP calls, CDN links or telemetry. Generated HTML must
open from `file://` with vendored JS/CSS only. The only network uses are explicit: Git clone,
vulnerability database sync/osv.dev, and `legacy-audit enrich` with a remote model.

**Secrets.** Never log, store or print tokens or passwords. Analyzers mask credentials they
find (`hits` keep matched text with secrets masked); `scan.SecretFilter` masks Git tokens in
the run log; the GUI and MCP mask secret-looking lines when they show evidence.
