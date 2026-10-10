"""Phase 7: scan-drawn screen and batch flows, the functional document and the new stages."""

from __future__ import annotations

import json
import re
import shutil
from pathlib import Path
from urllib.parse import unquote

import pytest
from typer.testing import CliRunner

from legacy_audit.cli import app
from legacy_audit.config import load_config
from legacy_audit.enrich.validate import validate_run
from legacy_audit.report import flows
from legacy_audit.report.data import RunData
from legacy_audit.report.functional import build_functional_report
from legacy_audit.report.mgmt import build_mgmt_report
from legacy_audit.report.tech import build_tech_report
from legacy_audit.scan import run_scan
from legacy_audit.security import vulns
from tests.conftest import SAMPLES
from tests.test_report import SAMPLE_NAMES, _check

ENRICHMENT = Path(__file__).parent / "fixtures" / "enrichment"


@pytest.fixture(autouse=True)
def _no_security_tools(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(vulns, "find_tool", lambda configured, names: None)


def _scan(sample: str, out: Path) -> Path:
    return run_scan(load_config(SAMPLES / sample / "audit.yaml"), output_dir=out).run_dir


def _enriched(sample: str, out: Path) -> Path:
    run_dir = _scan(sample, out)
    shutil.copytree(ENRICHMENT / sample, run_dir / "enrichment")
    rep = validate_run(run_dir)
    assert rep.ok, [str(m) for m in rep.messages]
    return run_dir


def _valid_offline(root: Path) -> list[Path]:
    pages = sorted(root.rglob("*.html"))
    for page in pages:
        c = _check(page)
        assert c.has_doctype and not c.errors and c.stack == [], (page, c.errors[:5], c.stack)
        for ref in c.refs:
            assert not re.match(r"(?i)^(https?:)?//", ref), (page, ref)
            assert (page.parent / unquote(ref)).exists(), (page, ref)
    return pages


def test_parse_schedule_reads_cron_and_autosys() -> None:
    cron = flows.parse_schedule("30 2 * * 1-5", "cron")
    assert cron["days"] == [0, 1, 2, 3, 4] and cron["times"] == ["02:30"]
    assert cron["text"] == "Runs every weekday (Mon to Fri) at 02:30."
    jil = flows.parse_schedule('days_of_week: mo,tu,we,th,fr; start_times: "01:30"', "autosys")
    assert jil["days"] == [0, 1, 2, 3, 4] and jil["times"] == ["01:30"]
    assert flows.parse_schedule(None, "cron")["days"] == []


def test_batch_flow_follows_the_box(tmp_path: Path) -> None:
    rd = RunData.load(_scan("spring-mvc-batch-app", tmp_path))
    by_name = {f.name: f for f in flows.batch_flows(rd)}
    box = by_name["BIL_NIGHTLY_BOX"]
    assert [s["name"] for s in box.steps] == ["BIL_USAGE_WAIT", "BIL_INVOICE", "BIL_ARCHIVE"]
    assert box.schedule["text"] == "Runs every weekday (Mon to Fri) at 01:30."
    assert {t["name"]: t["crud"] for t in box.tables} == {
        "INVOICES": "C",
        "INVOICE_AUDIT": "C",
        "USAGE_RECORDS": "R",
    }
    assert "BIL_INVOICE: is retried 1 time(s) automatically (n_retrys: 1)" in box.on_failure
    assert "BIL_ARCHIVE runs after BIL_INVOICE whether it succeeds or fails." in box.on_failure
    assert ("job:BIL_INVOICE", "job:BIL_ARCHIVE", "success or failure") in box.edges
    # the Spring Batch job is started by the Autosys job through its launch script
    assert by_name["invoiceJob"].schedule["started_by"] == ["BIL_INVOICE (via run_invoice_job.sh)"]
    week = {r["name"]: r for r in flows.week_grid(list(by_name.values()))}
    assert week["BIL_NIGHTLY_BOX"]["cells"][:5] == ["01:30"] * 5
    assert "flowchart LR" in box.mermaid and "classDef" in box.mermaid


def test_screen_flow_shows_services_and_tables(tmp_path: Path) -> None:
    rd = RunData.load(_scan("struts-oracle-app", tmp_path))
    detail = next(f for f in flows.screen_flows(rd) if f.name == "orderDetail.jsp")
    roles = {s["name"]: s["role"] for s in detail.services}
    assert roles == {"/orderDetail": "loads the screen", "/orderSearch": "opens"}
    assert "ORDERS" in {t["name"] for t in detail.tables}
    assert any(s["type"] == "SOAP" for s in detail.systems)
    block = detail.deck_block()
    keys = {n["key"] for c in block["columns"] for n in c["nodes"]}
    assert all(e["from"] in keys and e["to"] in keys for e in block["edges"])
    mx = flows.crud_matrix(rd)
    assert mx["cells"]["orders-web|ORDERS"] == "RU"


@pytest.mark.parametrize("sample", SAMPLE_NAMES)
def test_functional_document_builds_offline_without_enrichment(sample: str, tmp_path: Path) -> None:
    run_dir = _scan(sample, tmp_path)
    index = build_functional_report(run_dir)
    pages = _valid_offline(index.parent)
    names = {p.name for p in pages}
    assert {"index.html", "use-cases.html", "business-flows.html", "screens.html", "batch.html",
            "rules.html", "data.html", "interfaces.html", "glossary.html"} <= names  # fmt: skip
    text = (index.parent / "use-cases.html").read_text(encoding="utf-8")
    assert "/audit-enrich functional" in text  # placeholder says how to fill it


def test_functional_document_shows_the_enrichment(tmp_path: Path) -> None:
    run_dir = _enriched("struts-oracle-app", tmp_path)
    index = build_functional_report(run_dir)
    _valid_offline(index.parent)
    text = "".join(p.read_text(encoding="utf-8") for p in index.parent.rglob("*.html"))
    for words in ("Order servicing", "Sign in", "Only known users can sign in"):
        assert words in text
    assert "Orders2009!" not in text


def test_tech_report_has_flows_and_data_model(tmp_path: Path) -> None:
    run_dir = _enriched("spring-mvc-batch-app", tmp_path)
    root = build_tech_report(run_dir).parent
    pages = _valid_offline(root)
    names = {p.relative_to(root).as_posix() for p in pages}
    assert {"flows.html", "data-model.html"} <= names
    assert any(n.startswith("flows/") for n in names)
    text = (root / "flows.html").read_text(encoding="utf-8")
    assert "BIL_NIGHTLY_BOX" in text and "invoiceJob" in text


def test_deck_has_section_slides_and_graphics(tmp_path: Path) -> None:
    run_dir = _enriched("spring-mvc-batch-app", tmp_path)
    html = build_mgmt_report(run_dir).read_text(encoding="utf-8")
    deck = json.loads((run_dir / "reports" / "mgmt" / "deck.json").read_text(encoding="utf-8"))
    ids = [s["id"] for s in deck["slides"]]
    assert "section-functional" in ids and "fn-batch-calendar" in ids
    assert any(i.startswith("fn-batch-") and "nightly" in i for i in ids)
    kinds = {b["type"] for s in deck["slides"] for b in s["blocks"]}
    assert {"gauges", "capmap", "flow", "heatmap"} <= kinds
    assert (
        'class="slide section"' in html and 'class="ring"' in html and 'table class="heat' in html
    )


def test_new_stages_check_their_references(tmp_path: Path) -> None:
    run_dir = _enriched("struts-oracle-app", tmp_path)
    path = run_dir / "enrichment" / "functional.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    data["screens"][0]["screen_id"] = "screen:nope"
    path.write_text(json.dumps(data), encoding="utf-8")
    texts = " ".join(e.text for e in validate_run(run_dir, ["functional"]).errors)
    assert "screen:nope" in texts


def test_report_command_builds_the_functional_document(sample_copy) -> None:  # type: ignore[no-untyped-def]
    app_dir = sample_copy("c-proc-batch")
    cfg = app_dir / "audit.yaml"
    assert CliRunner().invoke(app, ["scan", "-c", str(cfg)]).exit_code == 0
    res = CliRunner().invoke(app, ["report", "-c", str(cfg), "--type", "functional"])
    assert res.exit_code == 0, res.output
    assert "Functional document" in res.output
    assert list((app_dir / "audit-output").rglob("reports/functional/batch.html"))
