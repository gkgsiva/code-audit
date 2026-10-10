"""Phase 5: enrichment validation, stamping, staleness and carry-over."""

from __future__ import annotations

import json
import os
import shutil
from pathlib import Path
from typing import Any

import pytest
from typer.testing import CliRunner

from legacy_audit.cli import app
from legacy_audit.config import load_config
from legacy_audit.enrich.status import compute_status
from legacy_audit.enrich.validate import validate_run
from legacy_audit.scan import run_scan
from legacy_audit.security import vulns
from tests.conftest import SAMPLES

FIXTURES = Path(__file__).parent / "fixtures" / "enrichment" / "struts-oracle-app"


@pytest.fixture(autouse=True)
def _no_security_tools(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(vulns, "find_tool", lambda configured, names: None)


@pytest.fixture(scope="module")
def struts_run(tmp_path_factory: pytest.TempPathFactory) -> Path:
    out = tmp_path_factory.mktemp("struts")
    return run_scan(
        load_config(SAMPLES / "struts-oracle-app" / "audit.yaml"), output_dir=out
    ).run_dir


def _with_fixtures(run_dir: Path, tmp_path: Path) -> Path:
    """A private copy of the run with the hand-written enrichment in it."""
    dest = tmp_path / "runs" / run_dir.name
    shutil.copytree(run_dir, dest, ignore=shutil.ignore_patterns("enrichment"))
    shutil.copytree(FIXTURES, dest / "enrichment")
    return dest


def _edit(run_dir: Path, stage: str, change: Any) -> None:
    path = run_dir / "enrichment" / f"{stage}.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    change(data)
    path.write_text(json.dumps(data), encoding="utf-8")


def test_fixtures_validate_and_get_stamped(struts_run: Path, tmp_path: Path) -> None:
    run_dir = _with_fixtures(struts_run, tmp_path)
    rep = validate_run(run_dir)
    assert rep.ok, [str(m) for m in rep.messages]
    assert not rep.warnings, [str(m) for m in rep.warnings]
    assert len(rep.checked) == 12 and rep.stamped == rep.checked
    meta = json.loads((run_dir / "enrichment" / "plan.json").read_text(encoding="utf-8"))["meta"]
    assert meta["built_from"]["run_id"] == run_dir.name
    assert {"components", "effort", "stage:risks"} <= set(meta["built_from"]["facts_hash_by_input"])
    status = json.loads((run_dir / "enrichment" / "status.json").read_text(encoding="utf-8"))
    assert status["stale"] == [] and status["missing"] == []
    assert validate_run(run_dir).stamped == []  # already stamped: nothing re-recorded


def test_wrong_count_fails_with_a_clear_message(struts_run: Path, tmp_path: Path) -> None:
    run_dir = _with_fixtures(struts_run, tmp_path)
    _edit(run_dir, "overview", lambda d: d["figures"][0].update(value=12))
    rep = validate_run(run_dir)
    assert not rep.ok
    (err,) = rep.errors
    assert err.stage == "overview" and err.where == "figures[0]"
    assert "says 12 but the scan says 4 (counts.services.total)" in err.text
    assert "overview" not in rep.stamped
    # the same number marked as an estimate is accepted
    _edit(run_dir, "overview", lambda d: d["figures"][0].update(estimate=True))
    again = validate_run(run_dir)
    assert again.ok, [str(m) for m in again.messages]


def test_missing_evidence_fails(struts_run: Path, tmp_path: Path) -> None:
    run_dir = _with_fixtures(struts_run, tmp_path)
    _edit(run_dir, "risks", lambda d: d["risks"][0].update(evidence=[]))
    rep = validate_run(run_dir, ["risks"])
    assert [(e.where, e.text) for e in rep.errors] == [
        ("risks.0", "needs evidence [{file, line}] or assumption: true")
    ]


def test_evidence_must_exist(struts_run: Path, tmp_path: Path) -> None:
    run_dir = _with_fixtures(struts_run, tmp_path)

    def bad(d: dict[str, Any]) -> None:
        d["risks"][0]["evidence"] = [{"file": "src/Nope.java", "line": 3}]
        d["risks"][2]["evidence"] = [{"file": "sql/pkg_order_pricing.pkb", "line": 9999}]

    _edit(run_dir, "risks", bad)
    texts = [e.text for e in validate_run(run_dir, ["risks"]).errors]
    assert "evidence file 'src/Nope.java' is not in the scanned source" in texts
    assert any(t.startswith("line 9999 is outside 'sql/pkg_order_pricing.pkb'") for t in texts)


def test_references_must_exist(struts_run: Path, tmp_path: Path) -> None:
    run_dir = _with_fixtures(struts_run, tmp_path)
    _edit(run_dir, "service_mapping", lambda d: d["mappings"][0].update(service_id="service:x"))
    _edit(run_dir, "architecture", lambda d: d["layers"][0].update(components=["billing"]))
    texts = {e.text for e in validate_run(run_dir).errors}
    assert texts == {
        "id 'service:x' is not in the scan facts",
        "component 'billing' is not in components.json",
    }


def test_prose_counts_are_checked(struts_run: Path, tmp_path: Path) -> None:
    run_dir = _with_fixtures(struts_run, tmp_path)

    def text(d: dict[str, Any]) -> None:
        d["purpose"] = "It has 9 services. Of these, about 2 services matter; 2 of the 4 services."

    _edit(run_dir, "overview", text)
    rep = validate_run(run_dir, ["overview"])
    assert rep.ok
    assert [w.text.split(" but")[0] for w in rep.warnings] == ["text says '9 services'"]


def test_schema_errors_name_the_field(struts_run: Path, tmp_path: Path) -> None:
    run_dir = _with_fixtures(struts_run, tmp_path)
    _edit(run_dir, "risks", lambda d: d["risks"][0].update(severity="huge"))
    (err,) = validate_run(run_dir, ["risks"]).errors
    assert err.where == "risks.0.severity" and "critical" in err.text


def _minimal(stage: str, components: list[str]) -> dict[str, Any]:
    a = {"assumption": True}
    body: dict[str, Any] = {
        "overview": {"purpose": "p"},
        "classification": {
            "components": [
                {
                    "component": c,
                    "capability": "x",
                    "kind": "mixed",
                    "criticality": "low",
                    "reason": "r",
                    **a,
                }
                for c in components
            ]
        },
        "flows": {"flows": []},
        "risks": {"risks": []},
        "plan": {"approach": "a", "waves": []},
        "narratives": {
            k: {"text": "t"} for k in ("executive", "architect", "functional", "developer")
        },
    }[stage]
    return {"meta": {"stage": stage}, **body}


def test_new_autosys_input_marks_dependent_stages_stale(sample_copy) -> None:  # type: ignore[no-untyped-def]
    app_dir = sample_copy("spring-mvc-batch-app")
    autosys = app_dir / "audit-inputs" / "autosys"
    parked = app_dir.parent / "autosys-later"
    shutil.move(autosys, parked)
    cfg = load_config(app_dir / "audit.yaml")
    run1 = run_scan(cfg).run_dir
    comps = [c["name"] for c in json.loads((run1 / "data" / "components.json").read_text())]
    (run1 / "enrichment").mkdir()
    for stage in ("overview", "classification", "flows", "risks", "plan", "narratives"):
        (run1 / "enrichment" / f"{stage}.json").write_text(json.dumps(_minimal(stage, comps)))
    assert validate_run(run1).ok

    run2 = run_scan(cfg).run_dir  # nothing changed: nothing stale
    assert (run2 / "enrichment" / "flows.json").exists()
    assert compute_status(run2)["stale"] == []

    shutil.move(parked, autosys)  # the Autosys export arrives
    run3 = run_scan(cfg).run_dir
    status = json.loads((run3 / "enrichment" / "status.json").read_text(encoding="utf-8"))
    assert {"flows", "risks", "plan", "narratives"} <= set(status["stale"])
    assert "classification" not in status["stale"]
    assert "batch_jobs" in status["stages"]["flows"]["changed"]
    assert status["stages"]["narratives"]["stale_upstream"]  # stale through flows, risks, plan

    # re-running a stage and re-stamping it clears it; downstream stays stale until re-run too
    rep = validate_run(run3, ["flows"], restamp=True)
    assert rep.stamped == ["flows"]
    assert "flows" not in rep.status["stale"] and "narratives" in rep.status["stale"]


def test_enrichment_written_into_a_copied_latest_is_kept(
    sample_copy, monkeypatch: pytest.MonkeyPatch
) -> None:  # type: ignore[no-untyped-def]
    def no_symlink(*args, **kwargs):  # type: ignore[no-untyped-def]
        raise OSError("symlinks not allowed")

    monkeypatch.setattr(os, "symlink", no_symlink)
    app_dir = sample_copy("struts-oracle-app")
    cfg = load_config(app_dir / "audit.yaml")
    run1 = run_scan(cfg).run_dir
    latest = app_dir / "audit-output" / "latest"
    assert latest.is_dir() and not latest.is_symlink()
    shutil.copytree(FIXTURES, latest / "enrichment")  # where Copilot writes
    assert validate_run(run1).ok
    assert (run1 / "enrichment" / "plan.json").exists()  # synced into the run folder
    stamped = json.loads((latest / "enrichment" / "plan.json").read_text(encoding="utf-8"))
    assert stamped["meta"]["built_from"]  # and the stamp is visible in latest too

    run2 = run_scan(cfg).run_dir
    assert (run2 / "enrichment" / "plan.json").exists()
    assert (latest / "enrichment" / "plan.json").exists()  # latest now copies run2
    assert compute_status(run2)["stale"] == []


def test_validate_command(struts_run: Path, tmp_path: Path) -> None:
    run_dir = _with_fixtures(struts_run, tmp_path)
    cfg = SAMPLES / "struts-oracle-app" / "audit.yaml"
    runner = CliRunner()
    ok = runner.invoke(app, ["validate", "-c", str(cfg), "--run", str(run_dir)])
    assert ok.exit_code == 0, ok.output
    assert "12 checked, 0 error(s)" in ok.output
    _edit(run_dir, "overview", lambda d: d["figures"][0].update(value=12))
    bad = runner.invoke(app, ["validate", "-c", str(cfg), "--run", str(run_dir)])
    assert bad.exit_code == 1
    assert "says 12 but the scan says 4" in bad.output
