# audit-engine

Package root: `com.legacyaudit.engine`. Plain Java, no Spring. It is the scan itself: the
command line and the web backend both call `Scanner.run`.

## Packages

| Package | Main classes | Purpose |
| --- | --- | --- |
| `scan` | `Scanner`, `Sources`, `ScanLog` | The scan pipeline and its log |
| `inventory` | `Walker`, `GitIgnore`, `FileAnalyzer`, `Languages`, `LanguageTables`, `Encodings`, `Loc`, `FileCache`, `InputCoverages`, `Paths`, `Rx` | Which files there are and what they are |
| `components` | `ComponentDetector`, `Grouping`, `ComponentOverrides`, `BuildInfo`, `Candidate`, `Kinds` | Splitting the code into components |
| `analyzers` | `Analyzer`, `Analyzers`, `Facts`, one class per analyzer, shared helpers | Reading what is inside each file (see [analyzers.md](analyzers.md)) |
| `analyzers.ts` | `TsTree`, `TsNode`, `TsNative` | tree-sitter syntax trees for Java (and C, C#, TypeScript in later phases) |
| `store` | `FactWriter`, `Runs`, `ScanLock` | Run folders and their JSON, CSV and SQLite files |
| `proof` | `Proofs` | The Phase 0 parser proof report (VS Code task 6) |

## The pipeline (`scan.Scanner`)

`Scanner.run(LoadedConfig, Options)` returns a `Result` (run folder, `RunInfo`, inventory,
components, findings, coverage, per-file facts). `Options` carries: full rescan (ignore the
cache), the output folder, a `Progress` callback (stage, done, total), a cancel flag and a log
listener. The web backend uses the last three for its live status screen.

Steps, in order:

1. **Lock and run folder.** `ScanLock` takes an operating-system lock on `runs/.lock` (one scan
   per output folder; released even after a crash). `Runs.newRunId` names the run
   (`20261029-185148-dd9a60`), and `ScanLog` writes `logs/scan.log` with secrets masked.
2. **Sources.** `Sources` turns `source.paths` into folders on disk (Git sources come later).
   The supplementary inputs folder (`audit-inputs/`) is scanned as an extra root.
3. **Walk.** `Walker` lists the files in a stable order (a folder's files, then each
   sub-folder, sorted), honouring `include`/`exclude` and the default excludes. Patterns use
   Git's `.gitignore` syntax through `GitIgnore`, a port of Python's `pathspec`.
4. **Inventory and analyzers.** For each file, in a thread pool (`inventory.workers`, default
   CPU count - 1, at most 8):
   - `FileCache` checks size, modification time and SHA-256 against the last run. The cache key
     includes every analyzer's name and version (`Analyzers.versionKey`), so a changed analyzer
     re-reads its files.
   - `FileAnalyzer` finds the encoding (`Encodings`: BOMs, EBCDIC, juniversalchardet), the
     language and category (`Languages` with `LanguageTables`), and the line counts (`Loc`).
   - `Analyzers.run` gives the file to every analyzer that supports it and collects their
     `Facts`. The file record and the facts are stored in the cache as one JSON payload.
5. **Components.** `ComponentDetector` finds components from build files (Maven, Gradle,
   `.csproj`, `package.json` ...) and the folder layout, `Grouping` groups them
   (`inventory.component_grouping`), and `ComponentOverrides` applies `audit-overrides.yaml`.
6. **Coverage.** `InputCoverages` reports which supplementary inputs were provided; skipped and
   too-large files become findings; `run.analyzers` gets files, lines and unparsed lines per
   analyzer.
7. **Export.** `FactWriter` writes `data/<entity>.json` (the source of truth, `PyJson.pretty`),
   one CSV per entity and `facts.sqlite`; `run.json` is written last, then `Runs.setLatest`
   points `latest` at the new run.

Phase 5 adds linking, metrics, rules and effort between steps 5 and 7.

## Run folders (`store.Runs`)

```
<output>/
  cache/                   the file cache (FileCache)
  latest -> runs/<run-id>  symbolic link, or a copy where links are not allowed
  runs/
    .lock                  held while a scan runs
    LATEST, HISTORY        newest run id, all run ids
    <run-id>/
      run.json             RunInfo: counts, timings, coverage, warnings
      logs/scan.log
      data/                inventory.json, components.json, findings.json, ... + CSV + facts.sqlite
```
`Runs.resolve(output, ref)` finds a run by id (or its start), `latest`, `previous` or a path. An unfinished run folder is
removed when a scan is cancelled or fails.

## Helpers that keep results equal to Python

| Helper | Why |
| --- | --- |
| `inventory.Rx` | Python's `re`: Unicode `\w \s \d \b`, only `\n` ends a line, `re.match` = `lookingAt` |
| `analyzers.TextUtil` | Python's `strip`, `split`, `isspace`, `posixpath` functions, `None` for null |
| `inventory.Paths` | `PurePosixPath` pieces |
| `model.json.PyJson` | `json.dumps` text and key order |
| `analyzers.PyUrl`, `PyError`, `PyJsonRead` | `urllib.parse.urlsplit`, Python exception names, `json.loads` |

Use them instead of the JDK equivalents in any code that produces facts.

## Threading

- One `Scanner.run` per output folder at a time (`ScanLock`); several projects can scan at once.
- Analyzers run on worker threads. They must not keep state between files: create per-file
  state inside `analyze`. Shared caches (tree-sitter parsers per thread, the SQL parse cache) are
  thread-safe.

## Adding to the engine

- New analyzer: see [analyzers.md](analyzers.md).
- New supplementary input kind: add it to `InputCoverages` with its folder name and label.
- New output file: add an `Entity` in the export step; `FactWriter` writes JSON, CSV and SQLite
  from it.
