"""Print the SQL statements of an application's latest run, for checking results by eye.

    python scripts/show_sql.py samples/c-proc-batch            # every statement
    python scripts/show_sql.py samples/c-proc-batch --dynamic  # only SQL built at run time
    python scripts/show_sql.py samples/c-proc-batch --grade manual

Reads ``<app>/audit.yaml`` to find the output folder; prints file:line (clickable in the VS Code
terminal), grade, how the text was rebuilt, holes, variants and the PostgreSQL reason.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from legacy_audit.config import load_config
from legacy_audit.store.runs import RunNotFound, resolve_run


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("app", type=Path, help="Folder with audit.yaml (e.g. samples/c-proc-batch)")
    ap.add_argument("--dynamic", action="store_true", help="Only SQL built at run time")
    ap.add_argument("--grade", choices=["auto", "review", "manual"], help="Only this grade")
    ap.add_argument("--run", default="latest", help="Run id, 'latest' or 'previous'")
    args = ap.parse_args()
    cfg = load_config(args.app / "audit.yaml")
    try:
        run = resolve_run(cfg.output_dir, args.run)
    except RunNotFound:
        raise SystemExit(f"No run yet for {args.app}: run 'legacy-audit scan' first") from None
    rows = json.loads((run / "data" / "sql_statements.json").read_text(encoding="utf-8"))
    shown = 0
    for st in rows:
        if args.dynamic and not (st.get("dynamic") or st.get("type") == "DYNAMIC"):
            continue
        if args.grade and st.get("pg_grade") != args.grade:
            continue
        shown += 1
        print(f"{st['file']}:{st['line']}  [{st.get('pg_grade')}] {st['type']} ({st['source']})")
        print(f"    {st['text']}")
        if st.get("built_with"):
            print(
                f"    built with: {', '.join(st['built_with'])}; fragments {st['fragments']}, "
                f"optional {st['optional_fragments']}, value holes {st['value_holes']}, "
                f"structural holes {st['structural_holes']}"
            )
        for v in st.get("variants", []):
            print(f"    variant: {v}")
        if st.get("reason"):
            print(f"    reason: {st['reason']}")
        print()
    print(f"{shown} of {len(rows)} statements in run {run.name}")


if __name__ == "__main__":
    main()
