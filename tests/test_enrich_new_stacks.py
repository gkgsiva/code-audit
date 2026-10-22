"""Enrichment for the newer kinds of application: desktop and .NET, Angular and Node.js,
application servers, web services and dependency vulnerabilities (decisions 153-158)."""

from __future__ import annotations

import json
import shutil
from pathlib import Path

import pytest

from legacy_audit.ai import context
from legacy_audit.config import load_config
from legacy_audit.enrich.stages import BY_NAME
from legacy_audit.enrich.validate import validate_run
from legacy_audit.report.data import RunData
from legacy_audit.report.flows import batch_flows
from legacy_audit.report.servers import server_rows
from legacy_audit.report.summary import build_summary_report
from legacy_audit.scan import run_scan
from legacy_audit.security import vulns
from tests.conftest import SAMPLES

ENRICHMENT = Path(__file__).parent / "fixtures" / "enrichment"


@pytest.fixture(autouse=True)
def _no_security_tools(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(vulns, "find_tool", lambda configured, names: None)


@pytest.fixture(scope="module")
def desktop_run(tmp_path_factory: pytest.TempPathFactory) -> Path:
    out = tmp_path_factory.mktemp("desktop")
    cfg = load_config(SAMPLES / "desktop-dotnet-app" / "audit.yaml")
    return run_scan(cfg, output_dir=out).run_dir


def _enriched(run_dir: Path, sample: str, tmp_path: Path) -> Path:
    dest = tmp_path / "runs" / run_dir.name
    shutil.copytree(run_dir, dest, ignore=shutil.ignore_patterns("enrichment"))
    shutil.copytree(ENRICHMENT / sample, dest / "enrichment")
    return dest


def _summary(run_dir: Path) -> dict[str, dict[str, dict]]:  # type: ignore[type-arg]
    res = build_summary_report(run_dir)
    data = json.loads((res.page.parent / "summary.json").read_text(encoding="utf-8"))
    return {s["key"]: {t["key"]: t for t in s["tables"]} for s in data["sections"]}


def test_scheduled_jobs_show_the_code_they_run(desktop_run: Path) -> None:
    flows = {f.name: f for f in batch_flows(RunData.load(desktop_run))}
    # Hangfire: the class named where the job is registered, then the registration
    assert flows["close-invoices"].files == [
        "billing-api-cs/Jobs/InvoiceJobs.cs",
        "billing-api-cs/Program.cs",
    ]
    # Windows Task Scheduler running NightlyExport.exe: the component's main, then the task
    assert flows["NightlyExport"].files == [
        "nightly-export-cs/Program.cs",
        "nightly-export-cs/tasks/NightlyExport.xml",
    ]
    assert not [n for n in flows if "not started by any job" in n]  # its main is covered


def test_api_models_see_the_code_of_windows_jobs_and_services(desktop_run: Path) -> None:
    user = context.build(desktop_run, BY_NAME["functional"], 150_000)[1]
    batch = user[user.index("## Batch sources") :]
    assert "#### billing-api-cs/Jobs/InvoiceJobs.cs" in batch and "UPDATE INVOICE" in batch
    screens = user[user.index("## Screen code") :]
    assert "### Screen frmCustomer" in screens
    assert "Private Sub cmdSearch_Click()" in screens  # VB6: the code, after the form layout
    assert "Begin VB.TextBox" not in screens.split("### Screen frmMain")[0]
    assert "#### orders-winforms-vbnet/MainForm.vb" in screens  # code file, not the designer
    assert "#### inventory-wpf-cs/MainWindow.xaml.cs" in screens  # WPF code-behind
    web = user[user.index("## Web service code") :]
    assert "### Web service InvoicesController" in web and "INSERT INTO INVOICE" in web
    assert "### data/web_services.json" in context.build(desktop_run, BY_NAME["flows"], 150_000)[1]


def test_new_stage_inputs() -> None:
    assert "web_services" in BY_NAME["architecture"].inputs
    assert "entry_points" in BY_NAME["flows"].inputs
    assert "tech_stack" in BY_NAME["risks"].inputs
    assert "tech_stack" in BY_NAME["dependency_plan"].inputs
    assert {"web_services", "batch_jobs"} <= set(BY_NAME["component_docs"].inputs)


@pytest.mark.parametrize("sample", ["desktop-dotnet-app", "angular-node-app"])
def test_demo_enrichment_validates(sample: str, tmp_path: Path) -> None:
    out = tmp_path / "scan"
    run_dir = run_scan(load_config(SAMPLES / sample / "audit.yaml"), output_dir=out).run_dir
    rep = validate_run(_enriched(run_dir, sample, tmp_path))
    assert rep.ok, [str(m) for m in rep.messages]
    assert not rep.warnings, [str(m) for m in rep.warnings]  # "Angular 21 components" is no count


def test_wrong_tech_id_is_rejected(desktop_run: Path, tmp_path: Path) -> None:
    run_dir = _enriched(desktop_run, "desktop-dotnet-app", tmp_path)
    path = run_dir / "enrichment" / "target_architecture.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    data["platform_changes"][0]["tech_ids"] = ["tech:nope"]
    path.write_text(json.dumps(data), encoding="utf-8")
    assert "id 'tech:nope' is not in the scan facts" in {
        e.text for e in validate_run(run_dir).errors
    }


def test_ai_targets_in_the_tech_stack(desktop_run: Path, tmp_path: Path) -> None:
    tables = _summary(_enriched(desktop_run, "desktop-dotnet-app", tmp_path))["project"]
    stack = tables["tech-stack"]
    assert stack["columns"][-1] == "Target (AI)"
    vb6 = next(r for r in stack["rows"] if r[0] == "Visual Basic 6")
    assert vb6[-1] == "Angular 21 screens over the Spring Boot 4 API (AI)"
    assert next(r for r in stack["rows"] if r[0] == "SQL Server")[-1] == ""
    assert _summary(desktop_run)["project"]["tech-stack"]["columns"][-1] == "End of life"


def test_ai_targets_for_servers(tmp_path: Path) -> None:
    cfg = load_config(SAMPLES / "angular-node-app" / "audit.yaml")
    run_dir = _enriched(
        run_scan(cfg, output_dir=tmp_path / "scan").run_dir, "angular-node-app", tmp_path
    )
    enrichment = {
        "target_architecture": json.loads(
            (run_dir / "enrichment" / "target_architecture.json").read_text(encoding="utf-8")
        )
    }
    rows = {r["name"]: r for r in server_rows(RunData.load(run_dir), enrichment)}
    assert rows["Oracle WebLogic Server"]["target"].endswith("(AI)")
    assert rows["Oracle WebLogic Server"]["today"].startswith("Serves the AngularJS portal")
    assert rows["Apache Tomcat"]["target"] == rows["Apache Tomcat"]["target_rule"]  # no AI entry
    servers = _summary(run_dir)["project"]["servers"]
    assert any(
        r[0] == "Nginx" and r[-1] == "Cloud Load Balancing with Cloud CDN (AI)"
        for r in servers["rows"]
    )


def test_upgrade_plan_next_to_vulnerable_libraries(sample_copy) -> None:  # type: ignore[no-untyped-def]
    app_dir = sample_copy("spring-mvc-batch-app")  # comes with an OSV-Scanner report
    run_dir = run_scan(load_config(app_dir / "audit.yaml")).run_dir
    libs = RunData.load(run_dir).items("libraries")
    log4j = next(lib for lib in libs if lib["artifact"] == "log4j" and lib.get("vulnerabilities"))
    plan = {
        "meta": {"stage": "dependency_plan", "author": "copilot-agent"},
        "themes": [
            {"name": "Logging", "library_ids": [log4j["id"]], "action": "Move to Logback",
             "wave": "Wave 1", "rationale": "Log4j 1 is end of life.", "assumption": True}
        ],
    }  # fmt: skip
    (run_dir / "enrichment").mkdir(exist_ok=True)
    (run_dir / "enrichment" / "dependency_plan.json").write_text(json.dumps(plan), "utf-8")
    sec = _summary(run_dir)["security"]
    table = sec["vulnerable-libraries"]
    assert table["columns"][-1] == "Upgrade plan (AI)"
    row = next(r for r in table["rows"] if r[0] == "log4j:log4j")
    assert row[-1] == "Logging: Move to Logback (wave Wave 1) (AI)"
    assert all(r[-1] == "" for r in table["rows"] if r[0] != "log4j:log4j")
