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
```

| Class | What it does |
| --- | --- |
| `Main` | Entry point; lists the commands; `version` and `proofs` |
| `DiffCommand` | `diff`: compares two runs (an id or its start, `latest`, `previous` or a folder; the second defaults to `latest`), prints a table of counts per entity and the first added (`+`), removed (`-`) and changed (`~`) records; `--json` writes the whole diff |
| `ReportCommand` | `report`: builds the chosen report of a run (default: the technical report of the latest run). `pdf` builds all four and prints them; `all` builds all four and the PDFs when `pdf.enabled` is true. Prints where each report went and how many enrichment stages are missing or stale; `--open` opens the first report in the browser. Exit code 1 when `pdf` wrote no PDF (no browser) |
| `ScanCommand` | `scan`: loads the config, runs `Scanner.run`, prints the run folder, the file counts, the files per analyzer, the input coverage lines and warnings. `-v` prints the scan log as it is written |

Exit codes: `0` done, `1` invalid config or another scan running in the same output folder,
`2` wrong options (picocli) or a missing samples folder for `proofs`.

The jar's manifest has `Enable-Native-Access: ALL-UNNAMED`, which tree-sitter needs.

Commands still to come, with the same names as the Python tool:
`enrich`, `init`, `mcp`, `validate`, `facts` (Phase 7), `doctor`, `purge-cache` (Phase 9).
