"""Every sample app's counts must match its expected.yaml."""

import json
from pathlib import Path

import pytest
import yaml

from legacy_audit.config import load_config
from legacy_audit.scan import run_scan
from legacy_audit.security import vulns
from legacy_audit.store.counts import counts
from tests.conftest import SAMPLES

SAMPLE_DIRS = sorted(p.parent for p in SAMPLES.glob("*/expected.yaml"))


@pytest.mark.parametrize("sample", SAMPLE_DIRS, ids=[p.name for p in SAMPLE_DIRS])
def test_sample_counts(sample: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    # Vulnerabilities come only from the fixture reports, whatever is installed here.
    monkeypatch.setattr(vulns, "find_tool", lambda configured, names: None)
    expected = yaml.safe_load((sample / "expected.yaml").read_text(encoding="utf-8"))
    result = run_scan(load_config(sample / "audit.yaml"), output_dir=tmp_path / "out")
    summary_path = result.run_dir / "data" / "audit-summary.json"
    summary = json.loads(summary_path.read_text(encoding="utf-8"))

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
    if "component_roles" in expected:  # every kind of work found in each component
        roles = {c.name: c.roles for c in result.facts["components"]}  # type: ignore[attr-defined]
        assert roles == expected["component_roles"]
    if "unscheduled_batches" in expected:  # batch components no scheduler definition covers
        assert summary["unscheduled_batches"] == expected["unscheduled_batches"]
    assert {c.kind: c.status for c in result.run.coverage} == expected["coverage"]

    # Phase 2: headline counts and the named services, screens, integrations and jobs.
    facts = result.facts
    assert summary["counts"] == counts(facts)
    assert counts(facts) == {**expected["counts"], **expected["assessment"]}
    assert sorted(f"{s.kind}: {s.name}" for s in facts["services"]) == expected["services"]  # type: ignore[attr-defined]
    assert (
        sorted(s.name + (" (partial)" if s.partial else "") for s in facts["screens"])  # type: ignore[attr-defined]
        == expected["screens"]
    )
    assert (
        sorted(f"{i.type} {i.target} [{i.direction}]" for i in facts["integrations"])  # type: ignore[attr-defined]
        == expected["integrations"]
    )
    if "integration_purposes" in expected:
        assert {
            f"{i.type} {i.target}": i.purpose  # type: ignore[attr-defined]
            for i in facts["integrations"]
        } == expected["integration_purposes"]
    assert (
        sorted(f"{j.scheduler} {j.kind}: {j.name}" for j in facts["batch_jobs"])  # type: ignore[attr-defined]
        == expected["batch_jobs"]
    )
    assert result.run.unresolved_references == expected["unresolved_references"]
    # Every analyzer that ran reports its coverage.
    run_json = json.loads((result.run_dir / "run.json").read_text(encoding="utf-8"))
    assert run_json["analyzers"]
    for cov in run_json["analyzers"].values():
        assert cov["files"] > 0 and 0 <= cov["coverage_pct"] <= 100
