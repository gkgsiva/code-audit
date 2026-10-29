"""Export the SQL statements the Python scan finds in every sample app.

The Spring Boot version reads this file in its Phase 0 proof to check that JSqlParser
finds the same tables as sqlglot. Run it again after a sample changes:

    python3 scripts/export_sql_corpus.py
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = (
    ROOT
    / "backend"
    / "audit-engine"
    / "src"
    / "main"
    / "resources"
    / "com"
    / "legacyaudit"
    / "engine"
    / "proof"
    / "sql-corpus.json"
)


def main() -> int:
    corpus: list[dict[str, object]] = []
    for sample in sorted(p for p in (ROOT / "samples").iterdir() if (p / "audit.yaml").is_file()):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp) / sample.name
            shutil.copytree(sample, work)
            subprocess.run(
                [sys.executable, "-m", "legacy_audit", "scan"],
                cwd=work,
                check=True,
                stdout=subprocess.DEVNULL,
            )
            data = work / "audit-output" / "latest" / "data" / "sql_statements.json"
            for st in json.loads(data.read_text(encoding="utf-8")):
                corpus.append(
                    {
                        "sample": sample.name,
                        "file": st["file"],
                        "line": st["line"],
                        "source": st["source"],
                        "dialect": st["dialect"],
                        "type": st["type"],
                        "dynamic": st["dynamic"],
                        "sqlglot_parsed": st["parsed"],
                        "text": st["normalized"] or st["text"],
                        "tables": sorted(t.upper() for t in st["tables"]),
                    }
                )
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(corpus, indent=1) + "\n", encoding="utf-8")
    print(f"{len(corpus)} statements written to {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
