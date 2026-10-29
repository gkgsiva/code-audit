# audit-cli

Package: `com.legacyaudit.cli`. The laptop command line: one runnable jar,
`backend/audit-cli/target/legacy-audit.jar`, built by `mvn -B verify` (task 4). It uses
picocli and calls the engine directly; it needs no database and no Docker.

```
java -jar legacy-audit.jar version
java -jar legacy-audit.jar scan -c audit.yaml [-o <output-dir>] [--full] [-v]
java -jar legacy-audit.jar proofs [--samples samples] [--out report.md]
```

| Class | What it does |
| --- | --- |
| `Main` | Entry point; lists the commands; `version` and `proofs` |
| `ScanCommand` | `scan`: loads the config, runs `Scanner.run`, prints the run folder, the file counts, the files per analyzer, the input coverage lines and warnings. `-v` prints the scan log as it is written |

Exit codes: `0` done, `1` invalid config or another scan running in the same output folder,
`2` wrong options (picocli) or a missing samples folder for `proofs`.

The jar's manifest has `Enable-Native-Access: ALL-UNNAMED`, which tree-sitter needs.

Commands still to come, with the same names as the Python tool: `report` (Phase 6),
`enrich`, `init`, `mcp` (Phase 7), `diff`, `validate`, `facts`, `doctor`, `purge-cache`
(Phases 5 and 9).
