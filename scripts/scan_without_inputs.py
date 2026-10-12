"""Scan a sample as if its scheduler and other inputs had not been provided.

    python scripts/scan_without_inputs.py samples/c-proc-batch [--open]

Real estates often arrive without the Autosys JIL, JCL or crontab. This copies the sample to
``build/no-inputs/<sample>/`` without its ``audit-inputs/`` folder (the sample itself is not
touched), scans the copy and builds the functional document and the technical report. The
Batch pages should still list every batch script, program and listener, marked
"no schedule found", with what their code comments say. ``--open`` opens the functional
document's Batch page.
"""

from __future__ import annotations

import shutil
import sys
import webbrowser
from pathlib import Path

from typer.testing import CliRunner

from legacy_audit.cli import app

ROOT = Path(__file__).resolve().parents[1]


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    sample = Path(argv[0]).resolve()
    if not (sample / "audit.yaml").exists():
        print(f"{sample} is not a sample (no audit.yaml).")
        return 2
    copy = ROOT / "build" / "no-inputs" / sample.name
    if copy.exists():
        shutil.rmtree(copy)
    shutil.copytree(sample, copy, ignore=shutil.ignore_patterns("audit-output", "audit-inputs"))
    cfg = str(copy / "audit.yaml")
    print(f"Copied {sample.name} without audit-inputs to {copy}")
    for args in (["scan", "-c", cfg], ["report", "-c", cfg, "--type", "functional"],
                 ["report", "-c", cfg, "--type", "tech"]):  # fmt: skip
        res = CliRunner().invoke(app, args)
        print(res.output)
        if res.exit_code:
            return res.exit_code
    batch = copy / "audit-output" / "latest" / "reports" / "functional" / "batch.html"
    print(f"Batch page: {batch}")
    if "--open" in argv:
        webbrowser.open(batch.as_uri())
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
