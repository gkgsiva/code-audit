"""Time a full scan and a re-run on a generated legacy estate (Java/JSP, SQL, shell, C, COBOL).

    python scripts/perf.py [lines] [--workers N] [--keep FOLDER]

Default 2,000,000 lines, the target in docs/decisions.md (under 15 minutes on a laptop).
Results go to docs/performance.md by hand; the script prints one line per run and the per-stage
timings from run.json.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from legacy_audit.config import load_config
from legacy_audit.parallel import worker_count
from legacy_audit.scan import run_scan
from tests.stress_tree import generate


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("lines", nargs="?", type=int, default=2_000_000)
    ap.add_argument("--workers", type=int, default=0, help="0 = auto (CPUs - 1, max 8)")
    ap.add_argument("--keep", type=Path, help="Generate into this folder and keep it")
    args = ap.parse_args()

    base = args.keep or Path(tempfile.mkdtemp(prefix="legacy-audit-perf-"))
    try:
        src = base / "src"
        if not src.exists():
            t = time.perf_counter()
            n = generate(src, args.lines)
            print(f"generated {n:,} lines in {time.perf_counter() - t:.0f} s into {src}")
        cfg = base / "audit.yaml"
        cfg.write_text(
            "application:\n  name: perf\nsource:\n  paths: [src]\n"
            f"inventory:\n  workers: {args.workers}\n",
            encoding="utf-8",
        )
        print(f"workers: {worker_count(args.workers)}")
        for label, full in (("full scan", True), ("re-run", False)):
            t = time.perf_counter()
            result = run_scan(load_config(cfg), full=full)
            elapsed = time.perf_counter() - t
            run = json.loads((result.run_dir / "run.json").read_text(encoding="utf-8"))
            print(
                f"{label}: {elapsed:.0f} s, {run['files_total']:,} files "
                f"({run['files_analyzed']:,} analysed, {run['files_cached']:,} from cache)"
            )
            print("  " + ", ".join(f"{k} {v:.0f} s" for k, v in run["timings"].items()))
    finally:
        if args.keep is None:
            shutil.rmtree(base, ignore_errors=True)


if __name__ == "__main__":
    main()
