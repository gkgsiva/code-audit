"""Phase 6: the local GUI's API (FastAPI TestClient), its guards, and secrets in every output."""

from __future__ import annotations

import io
import json
import shutil
import zipfile
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from legacy_audit.enrich.validate import validate_run
from legacy_audit.gui.app import create_app
from legacy_audit.security import vulns
from legacy_audit.store.runs import resolve_run

BASE = "http://127.0.0.1:8765"
H = {"X-Legacy-Audit": "1"}
SECRET = "Orders2009!"  # db.password in samples/struts-oracle-app/orders-web/conf


@pytest.fixture(autouse=True)
def _no_security_tools(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(vulns, "find_tool", lambda configured, names: None)


def _client(folder: Path, opened: list[Path] | None = None) -> TestClient:
    sink = opened if opened is not None else []
    return TestClient(create_app(folder, 8765, opener=sink.append), base_url=BASE)


def _ok(resp: Any) -> Any:
    assert resp.status_code == 200, resp.text
    return resp.json()


def _wait(client: TestClient) -> dict[str, Any]:
    job = client.app.state.jobs.wait()  # type: ignore[attr-defined]
    assert job is not None
    info = _ok(client.get(f"/api/jobs/{job.id}"))
    assert info["state"] == "done", info
    return info  # type: ignore[no-any-return]


def _scan(client: TestClient, full: bool = False) -> dict[str, Any]:
    _ok(client.post("/api/scan", json={"full": full}, headers=H))
    return _wait(client)["result"]  # type: ignore[no-any-return]


def test_guards_refuse_foreign_hosts_and_cross_site_writes(tmp_path: Path) -> None:
    client = _client(tmp_path)
    assert client.get("/api/workspace").status_code == 200
    assert client.get("/api/workspace", headers={"Host": "evil.example:8765"}).status_code == 403
    assert client.post("/api/init", json={}).status_code == 403  # no header
    bad_origin = {**H, "Origin": "http://evil.example"}
    assert client.post("/api/init", json={}, headers=bad_origin).status_code == 403
    assert not (tmp_path / "audit.yaml").exists()


def test_clean_workspace_init_scan_dashboard_report(tmp_path: Path) -> None:
    """Acceptance: from an empty folder to a dashboard and a technical report, GUI only."""
    ws = tmp_path / "app"
    shutil.copytree(
        Path(__file__).parents[1] / "samples" / "struts-oracle-app" / "orders-web", ws / "src"
    )
    client = _client(ws)
    assert _ok(client.get("/api/workspace"))["config"] is False
    assert client.get("/api/config").status_code == 409
    created = _ok(client.post("/api/init", json={"name": "Orders"}, headers=H))["created"]
    assert {"path": "audit.yaml", "action": "created"} in created
    assert _ok(client.get("/api/workspace"))["application"] == "Orders"

    # edit the form fields: the comments of audit.yaml are kept
    cfg = _ok(client.get("/api/config"))
    fields = cfg["fields"]
    fields["source"] = {"type": "local", "paths": ["src"]}
    fields["application"]["description"] = "Struts 1 + Oracle"
    _ok(client.put("/api/config/fields", json=fields, headers=H))
    text = (ws / "audit.yaml").read_text(encoding="utf-8")
    assert "# Migration target (used by mapping rules, upgrade advice" in text
    assert "- src" in text and "Struts 1 + Oracle" in text
    bad = client.put("/api/config/fields", json={"target": {"java": []}}, headers=H)
    assert bad.status_code == 422
    assert (ws / "audit.yaml").read_text(encoding="utf-8") == text  # untouched on error

    result = _scan(client)
    assert result["files"] > 20
    runs = _ok(client.get("/api/runs"))
    assert runs[0]["run_id"] == result["run_id"] and runs[0]["latest"]
    dash = _ok(client.get("/api/runs/latest/dashboard"))
    kpis = {k["label"]: k["value"] for k in dash["kpis"]}
    assert kpis["Services"] == 4 and kpis["Screens"] == 4 and kpis["SQL statements"] > 0
    assert dash["charts"]["technology"]["java"] > 0
    assert sum(dash["charts"]["severity"].values()) > 0

    _ok(client.post("/api/runs/latest/reports", json={"type": "tech"}, headers=H))
    _wait(client)
    reports = _ok(client.get("/api/runs/latest/reports"))["reports"]
    assert reports["tech"] and reports["pptx"] is None
    page = client.get(reports["tech"])
    assert page.status_code == 200 and "<html" in page.text.lower()

    tpl = _ok(client.get("/api/pptx-templates"))
    assert [t["label"] for t in tpl["templates"]] == ["Blue", "Orange", "Purple"]
    assert tpl["default"] == "RG_TEMPLATE_PPT_HD_BLUE"  # audit.yaml names none
    thumb = client.get(tpl["templates"][1]["thumbnail"])
    assert thumb.status_code == 200 and thumb.headers["content-type"] == "image/jpeg"
    assert client.get("/api/pptx-templates/nope/thumbnail.jpg").status_code == 404
    body = {"type": "pptx", "template": "RG_TEMPLATE_PPT_HD_ORANGE"}
    _ok(client.post("/api/runs/latest/reports", json=body, headers=H))
    done = _wait(client)
    assert any("Orange template" in m for m in done["result"]["messages"]), done
    built = _ok(client.get("/api/runs/latest/reports"))
    assert built["reports"]["pptx"] and built["pptx_template"] == "Orange"


def test_explorer_evidence_and_traversal(sample_copy) -> None:  # type: ignore[no-untyped-def]
    ws = sample_copy("struts-oracle-app")
    client = _client(ws)
    _scan(client)
    dash = _ok(client.get("/api/runs/latest/dashboard"))
    kpis = {k["label"]: k["value"] for k in dash["kpis"]}
    assert kpis["Vulnerable libraries"] == 4
    assert sum(dash["charts"]["pg_grade"].values()) == kpis["SQL statements"] == 14
    assert list(dash["charts"]["severity"]) == ["critical", "high", "medium", "info"]
    res = _ok(client.get("/api/runs/latest/facts/services", params={"q": "health"}))
    assert [r["name"] for r in res["rows"]] == ["health"]
    res = _ok(client.get("/api/runs/latest/facts/services", params={"limit": 2}))
    assert res["total"] == 4 and res["returned"] == 2
    page2 = _ok(client.get("/api/runs/latest/facts/inventory", params={"limit": 5, "offset": 5}))
    assert page2["offset"] == 5 and page2["returned"] == 5
    by_kind = _ok(client.get("/api/runs/latest/facts/services", params={"kind": "servlet"}))
    assert [r["name"] for r in by_kind["rows"]] == ["health"]
    assert client.get("/api/runs/latest/facts/nope").status_code == 400

    comp = _ok(client.get("/api/runs/latest/component/orders-web"))
    assert comp["services"]["total"] == 4

    ev = _ok(client.get("/api/runs/latest/evidence", params={"file": "conf/orders-app.properties"}))
    assert "db.password" in ev["text"] and SECRET not in ev["text"]
    assert ev["vscode"].startswith("vscode://file/")
    for bad in ("../../../../etc/passwd", "/etc/passwd", "..\\..\\audit.yaml"):
        assert client.get("/api/runs/latest/evidence", params={"file": bad}).status_code == 404
    assert client.get("/api/runs/..%2F..%2Fx/dashboard").status_code in (400, 404)
    assert client.get("/api/runs/../dashboard").status_code in (400, 404)
    assert client.get("/runs/latest/reports/../../run.json").status_code == 404
    assert client.get("/runs/latest/reports/..%2F..%2Frun.json").status_code == 404


def test_inputs_upload_rerun_marks_enrichment_stale(sample_copy) -> None:  # type: ignore[no-untyped-def]
    """Acceptance: adding JIL from the Inputs screen and re-running updates the dashboard and
    marks dependent enrichment stale."""
    ws = sample_copy("spring-mvc-batch-app")
    jil = (ws / "audit-inputs" / "autosys" / "billing.jil").read_bytes()
    (ws / "audit-inputs" / "autosys" / "billing.jil").unlink()
    client = _client(ws)
    _scan(client)
    before = {k["label"]: k["value"] for k in _ok(client.get("/api/runs/latest/dashboard"))["kpis"]}
    run1 = resolve_run(ws / "audit-output", "latest")
    comps = [c["name"] for c in json.loads((run1 / "data" / "components.json").read_text())]
    from tests.test_enrich import _minimal

    (run1 / "enrichment").mkdir()
    for stage in ("overview", "classification", "flows", "risks", "plan", "narratives"):
        (run1 / "enrichment" / f"{stage}.json").write_text(json.dumps(_minimal(stage, comps)))
    assert validate_run(run1).ok

    inputs = _ok(client.get("/api/inputs"))
    autosys = next(k for k in inputs["kinds"] if k["kind"] == "autosys")
    assert autosys["files"] == []
    for bad in ("..evil.jil", ".hidden", "a%2Fb.jil"):
        assert client.put(f"/api/inputs/autosys/{bad}", content=b"x", headers=H).status_code in (
            400,
            404,
        )
    assert client.put("/api/inputs/nope/x.jil", content=b"x", headers=H).status_code == 400
    _ok(client.put("/api/inputs/autosys/billing.jil", content=jil, headers=H))
    assert (ws / "audit-inputs" / "autosys" / "billing.jil").read_bytes() == jil

    _scan(client)
    after = {k["label"]: k["value"] for k in _ok(client.get("/api/runs/latest/dashboard"))["kpis"]}
    assert after["Batch jobs"] > before["Batch jobs"]
    enr = _ok(client.get("/api/runs/latest/enrichment"))
    states = {s["name"]: s["state"] for s in enr["stages"]}
    assert states["flows"] == "stale" and states["classification"] == "valid"
    assert enr["next_prompt"] == "/audit-enrich stale"
    val = _ok(client.post("/api/runs/latest/validate", headers=H))
    assert {s["name"]: s["state"] for s in val["stages"]}["flows"] == "stale"

    diff = _ok(client.get("/api/compare", params={"a": "previous", "b": "latest"}))
    assert diff["entities"]["batch_jobs"]["added_count"] > 0

    _ok(client.delete("/api/inputs/autosys/billing.jil", headers=H))
    assert not (ws / "audit-inputs" / "autosys" / "billing.jil").exists()


def test_component_picker_writes_overrides(sample_copy) -> None:  # type: ignore[no-untyped-def]
    ws = sample_copy("spring-mvc-batch-app")
    client = _client(ws)
    pre = _ok(client.get("/api/components"))
    names = {c["name"]: c for c in pre["components"]}
    assert len(names) >= 2 and all(c["included"] for c in names.values())
    drop = next(c for c in pre["components"] if c["path"])
    keep = next(
        c
        for c in pre["components"]
        if c["name"] != drop["name"] and c["detected_type"] != "library"
    )  # a library used by the dropped module and one other would join the other (decision 176)
    body = {"included": {drop["path"]: False}, "types": {keep["name"]: "library"}}
    after = _ok(client.put("/api/components", json=body, headers=H))
    flags = {c["name"]: c for c in after["components"]}
    assert not flags[drop["name"]]["included"]
    assert flags[keep["name"]]["type_override"] == "library"
    text = (ws / "audit-overrides.yaml").read_text(encoding="utf-8")
    assert f"{drop['path']}/**" in text and "library" in text
    bad = client.put("/api/components", json={"types": {keep["name"]: "spaceship"}}, headers=H)
    assert bad.status_code == 422

    _scan(client)
    comps = _ok(client.get("/api/runs/latest/facts/components"))["rows"]
    assert drop["name"] not in {c["name"] for c in comps}
    assert {c["name"]: c["type"] for c in comps}[keep["name"]] == "library"

    # tick it again
    _ok(client.put("/api/components", json={"included": {drop["path"]: True}}, headers=H))
    assert f"{drop['path']}/**" not in (ws / "audit-overrides.yaml").read_text(encoding="utf-8")


def test_jobs_events_busy_cancel_and_open(sample_copy) -> None:  # type: ignore[no-untyped-def]
    ws = sample_copy("c-proc-batch")
    opened: list[Path] = []
    client = _client(ws, opened)
    job = _ok(client.post("/api/scan", json={}, headers=H))
    busy = client.post("/api/scan", json={}, headers=H)
    assert busy.status_code in (200, 409)  # 409 while the first one still runs
    _wait(client)
    with client.stream("GET", f"/api/jobs/{job['id']}/events") as stream:
        events = [json.loads(line[6:]) for line in stream.iter_lines() if line.startswith("data: ")]
    types = [e["type"] for e in events]
    assert types[0] == "started" and types[-1] == "end" and "progress" in types
    assert {e["stage"] for e in events if e["type"] == "progress"} >= {"inventory", "export"}
    assert _ok(client.post(f"/api/jobs/{job['id']}/cancel", headers=H))["state"] == "done"
    assert client.get("/api/jobs/nope").status_code == 404

    _ok(client.post("/api/open", json={"target": "inputs"}, headers=H))
    _ok(client.post("/api/runs/latest/open", headers=H))
    assert opened[0].name == "audit-inputs" and (opened[1] / "run.json").exists()
    assert client.post("/api/open", json={"target": "/etc"}, headers=H).status_code == 400

    checks = _ok(client.get("/api/doctor"))
    assert {c["name"] for c in checks} >= {
        "Python", "Node.js", "PowerPoint templates", "OWASP Dependency-Check"
    }  # fmt: skip
    spec = _ok(client.get("/api/openapi.json"))
    assert "/api/scan" in spec["paths"] and "/api/runs/{run}/dashboard" in spec["paths"]
    assert client.get("/").status_code == 200
    assert client.get("/static/app.js").status_code == 200
    assert client.get("/vendor/chart-4.4.7.umd.js").status_code == 200
    assert client.get("/assets/report.css").status_code == 200


def test_secrets_are_masked_in_every_output(sample_copy) -> None:  # type: ignore[no-untyped-def]
    ws = sample_copy("struts-oracle-app")
    assert SECRET in (ws / "orders-web" / "conf" / "orders-app.properties").read_text()
    client = _client(ws)
    _scan(client)
    _ok(client.post("/api/runs/latest/reports", json={"type": "all"}, headers=H))
    _wait(client)
    reports = _ok(client.get("/api/runs/latest/reports"))["reports"]
    assert reports["functional"] and reports["mgmt"]
    assert client.get(reports["functional"]).status_code == 200
    assert client.get(reports["mgmt"]).status_code == 200
    pack = client.get("/api/runs/latest/datapack.zip")
    assert pack.status_code == 200
    with zipfile.ZipFile(io.BytesIO(pack.content)) as zf:
        names = zf.namelist()
        assert any(n.endswith("data/facts.sqlite") for n in names)
        for n in names:
            assert SECRET.encode() not in zf.read(n), n
    hits = [
        p
        for p in (ws / "audit-output").rglob("*")
        if p.is_file() and SECRET.encode() in p.read_bytes()
    ]
    assert hits == []


def test_gui_builds_the_summary_report_on_a_chosen_template(sample_copy) -> None:  # type: ignore[no-untyped-def]
    client = _client(sample_copy("c-proc-batch"))
    _scan(client)
    body = {"type": "summary", "template": "RG_TEMPLATE_PPT_HD_PURPLE"}
    _ok(client.post("/api/runs/latest/reports", json=body, headers=H))
    done = _wait(client)
    assert done["state"] == "done", done
    reports = _ok(client.get("/api/runs/latest/reports"))["reports"]
    page = client.get(reports["summary"])
    assert page.status_code == 200 and "Built on the Purple template" in page.text
    pptx = client.get(reports["summary_pptx"])
    assert pptx.status_code == 200 and pptx.content[:2] == b"PK"


def test_gui_builds_pdfs_or_says_why_not(sample_copy, monkeypatch) -> None:  # type: ignore[no-untyped-def]
    from legacy_audit.report import pdf

    monkeypatch.setattr(pdf, "find_browser", lambda configured=None: None)
    client = _client(sample_copy("c-proc-batch"))
    _scan(client)
    _ok(client.post("/api/runs/latest/reports", json={"type": "pdf"}, headers=H))
    done = _wait(client)
    assert done["state"] == "done", done
    assert any("no Microsoft Edge" in m for m in done["result"]["messages"])
    reports = _ok(client.get("/api/runs/latest/reports"))["reports"]
    assert reports["summary"] and reports["tech"]  # the HTML reports are built first
    assert {"pdf_summary", "pdf_mgmt", "pdf_functional", "pdf_tech"} <= set(reports)
    assert reports["pdf_tech"] is None
