# Performance

Target (decision 92 and `docs/prompts/phase-6-gui-hardening.md`): a 2M-line estate scans in under
15 minutes on an analyst laptop.

## How it was measured

`python scripts/perf.py [lines] [--workers N] [--keep FOLDER]` (VS Code task
"audit: 15. performance test (generated estate)") generates a mixed estate with `tests/stress_tree.py` (Java and
JSP with SQL, shell, C, COBOL, build files), runs a full scan and then an unchanged re-run, and
prints the per-stage timings from `run.json`.

Environment: the build container, 4 CPUs, Linux, Python 3.13, local disk; `inventory.workers: 0`
(3 worker processes). Laptop numbers are still to be measured (see below).

## Results, 2M lines (15,903 files)

| Run | Workers | Time | Notes |
| --- | --- | --- | --- |
| First full scan (empty caches) | 3 | 253 s (4.2 min) | before the linking fix below; linking took about 37 s of it |
| First full scan (empty caches) | 1 | 467 s (7.8 min) | before the linking fix |
| Full scan (`--full`, SQL grade cache warm) | 3 | 180 s (3.0 min) | after the linking fix |
| Unchanged re-run, before the linking fix | 3 | 136 s | |
| Unchanged re-run, after the linking fix | 3 | 106 s (1.8 min) | |

Per stage, after the linking fix (seconds):

| Stage | Full scan | Re-run |
| --- | --- | --- |
| inventory (walk, hash, analyse) | 99 | 26 |
| link | 10 | 9 |
| metrics | 3 | 2 |
| assess (rules, PostgreSQL grades, security) | 21 | 21 |
| export (JSON, CSV, SQLite, SBOM) | 43 | 43 |

With 500k lines: full scan 51 s, re-run 21 s.

The run writes about 2.6 GB for 2M lines (`audit-output/runs/<id>/`), mostly fact files in three
formats. Older runs can be deleted by hand; the cache is in `audit-output/cache/`.

## What changed in phase 6

- Files are analysed in worker processes (`legacy_audit/parallel.py`), results kept in walk order
  so outputs are identical to a single-process run (`tests/test_hardening.py`).
- SQL statements are graded in the same pool, and grades are cached in
  `audit-output/cache/grade-cache.sqlite`, keyed by the statement and the rule set; changing a
  PostgreSQL rule re-grades everything.
- Linking SQL to tables used list look-ups that grew with the square of the estate; they are sets
  now (link stage 37 s to 10 s at 2M lines).

## Still to measure

- A Windows laptop (process start-up is slower with `spawn`, antivirus scanning slows the walk and
  the writes). Run the task above with 500000 and 2000000 lines and add the numbers here.
- A real estate: generated code is more regular than real code.
- Export is now the largest fixed cost of a re-run. If laptops are slow, the first lever is
  writing CSV and SQLite only for the kinds that changed.
