"""Load the hand-written demo enrichment into a scanned sample, then validate it.

    python scripts/demo_enrichment.py samples/struts-oracle-app [--break]

Stands in for Copilot when checking phase 5 without it: copies
``tests/fixtures/enrichment/<sample>/*.json`` into the sample's
``audit-output/latest/enrichment/`` (as the agent would) and runs ``legacy-audit validate``.
``--break`` changes one figure to a wrong count first, to see the validator reject it.
"""

from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

from typer.testing import CliRunner

from legacy_audit.cli import app

ROOT = Path(__file__).resolve().parents[1]


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    sample = Path(argv[0]).resolve()
    fixtures = ROOT / "tests" / "fixtures" / "enrichment" / sample.name
    if not fixtures.is_dir():
        print(
            f"No demo enrichment for {sample.name}; available: "
            + ", ".join(p.name for p in fixtures.parent.iterdir())
        )
        return 2
    latest = sample / "audit-output" / "latest"
    if not (latest / "run.json").exists():
        print(f"Scan {sample.name} first (task 'audit: 4. scan a sample').")
        return 2
    target = latest / "enrichment"
    target.mkdir(exist_ok=True)
    for f in fixtures.glob("*.json"):
        shutil.copyfile(f, target / f.name)
    if "--break" in argv:
        path = target / "overview.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        data["figures"][0]["value"] = 12
        data["meta"].pop("built_from", None)
        path.write_text(json.dumps(data, indent=2), encoding="utf-8")
        print("Changed overview.json > figures[0] (services) to 12.")
    print(f"Copied {len(list(fixtures.glob('*.json')))} stage files into {target}")
    res = CliRunner().invoke(app, ["validate", "-c", str(sample / "audit.yaml")])
    print(res.output)
    return res.exit_code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
