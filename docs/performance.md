# Performance

Target: a 2-million-line estate scans in under 15 minutes on an analyst laptop with 8 GB of
memory.

## How it was measured

A mixed estate was generated with the earlier tool's stress generator (Java and JSP with SQL,
shell, C, COBOL, build files): 2,000,000 lines in 15,903 files. It was scanned with the
`legacy-audit` command (`scan`, then the same scan again with nothing changed) and the per-stage
timings were read from `run.json` (`timings`).

Environment: the build container, 4 CPUs, Linux, Java 25, local disk, `inventory.workers: 0`
(3 threads). Laptop numbers are still to be measured (see below).

## Results, 2M lines (15,903 files)

| Run | Heap | Time |
| --- | --- | --- |
| First full scan (empty cache) | 2.5 GB | 93 s (1.6 min) |
| Unchanged re-run | 2 GB | 45 to 60 s |
| The Python tool, full scan, for comparison | | 180 to 253 s |
| The Python tool, unchanged re-run | | 106 s |

Per stage (seconds):

| Stage | Full scan | Re-run |
| --- | --- | --- |
| inventory (walk, hash, analyse) | 52 | 8 |
| link | 5 | 7 |
| metrics | 4 | 3 |
| assess (rules, PostgreSQL grades, security) | 10 | 17 |
| export (JSON, CSV, SQLite, SBOM) | 17 | 20 |

The run folder is about 750 MB for 2M lines (`audit-output/runs/<id>/`), mostly fact files in
three formats. Older runs can be deleted by hand (the web app keeps the number of runs set on
the Admin page); the cache is in `audit-output/cache/`.

## Memory

The scan keeps the facts of the whole estate in memory while it links them, so memory grows
with the estate: about 2 GB for 2M lines.

- The launchers `legacy-audit` and `legacy-audit.cmd` start Java with up to half the computer's
  memory for the heap (`-XX:MaxRAMPercentage=50`) and shared copies of repeated text
  (`-XX:+UseStringDeduplication`). On an 8 GB laptop that is 4 GB, enough for 2M lines.
- For a larger estate, or a smaller laptop, set `LEGACY_AUDIT_JAVA_OPTS` before the scan, for
  example `set LEGACY_AUDIT_JAVA_OPTS=-Xmx6g` (Windows) or `export LEGACY_AUDIT_JAVA_OPTS=-Xmx6g`
  (WSL2). It comes after the defaults and wins.
- `java -jar legacy-audit.jar` without the launcher uses Java's default (a quarter of the
  memory): add the same options by hand for large estates.
- The backend image gives the server up to 75% of the container's memory, with the same
  shared text.
- A scan that runs out of memory stops with `java.lang.OutOfMemoryError: Java heap space`; give
  it more with `LEGACY_AUDIT_JAVA_OPTS` and run it again (the cache keeps the files already read).

What was changed to fit in 2 GB:

- JSON and CSV fact files are written as they are produced instead of being built as one text
  first (the 2M-line re-run used to run out of memory there).
- During the rules, each kind of fact is turned into the rule engine's form once and let go after
  the last rule that reads it.

## Still to measure

- A Windows laptop: antivirus scanning slows the walk and the writes. Scan a large real
  application and add the numbers here.
- A real estate: generated code is more regular than real code.
