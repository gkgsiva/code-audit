"""Every sample app's counts must match its expected.yaml."""

import json
from pathlib import Path

import pytest
import yaml

from legacy_audit.config import load_config
from legacy_audit.scan import run_scan
from tests.conftest import SAMPLES

SAMPLE_DIRS = sorted(p.parent for p in SAMPLES.glob("*/expected.yaml"))


@pytest.mark.parametrize("sample", SAMPLE_DIRS, ids=[p.name for p in SAMPLE_DIRS])
def test_sample_counts(sample: Path, tmp_path: Path):
    expected = yaml.safe_load((sample / "expected.yaml").read_text(encoding="utf-8"))
    result = run_scan(load_config(sample / "audit.yaml"), output_dir=tmp_path / "out")
    summary = json.loads((result.run_dir / "data" / "audit-summary.json").read_text())

    inv = expected["inventory"]
    totals = summary["totals"]
    assert totals["files"] == inv["files"]
    assert totals["input_files"] == inv["input_files"]
    assert {
        "code": totals["loc_code"],
        "comment": totals["loc_comment"],
        "blank": totals["loc_blank"],
    } == inv["loc"]
    by_lang = {
        lang: {
            "files": v["files"],
            "code": v["loc_code"],
            "comment": v["loc_comment"],
            "blank": v["loc_blank"],
        }
        for lang, v in summary["by_language"].items()
    }
    assert by_lang == inv["by_language"]
    assert {k: v["files"] for k, v in summary["by_category"].items()} == inv["by_category"]

    comps = [
        {"name": c.name, "type": c.type, "files": c.files, "detected_by": c.detected_by}
        for c in result.facts["components"]  # type: ignore[attr-defined]
    ]
    assert comps == expected["components"]
    assert {c.kind: c.status for c in result.run.coverage} == expected["coverage"]
