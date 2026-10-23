"""The migration target chosen in audit.yaml > target.profile (decision 161): spring-boot-angular
(default), wildfly-gke (code kept, database to PostgreSQL) and none (audit only)."""

from __future__ import annotations

import json
import shutil
from pathlib import Path

import pytest

from legacy_audit.ai import context
from legacy_audit.ai.providers import AiError
from legacy_audit.ai.runner import pick_stages
from legacy_audit.config import Target, load_config
from legacy_audit.enrich.stages import BY_NAME, STAGES
from legacy_audit.enrich.status import active_stages, compute_status, stamp
from legacy_audit.enrich.validate import validate_run
from legacy_audit.report.data import RunData
from legacy_audit.report.deck_builder import build_deck
from legacy_audit.report.servers import server_rows
from legacy_audit.report.summary import build_summary_report
from legacy_audit.report.tech import build_tech_report
from legacy_audit.scan import run_scan
from legacy_audit.security import vulns
from legacy_audit.target import TARGET_STAGES, fill, info, resolve
from tests.conftest import SAMPLES

ENRICHMENT = Path(__file__).parent / "fixtures" / "enrichment"


@pytest.fixture(autouse=True)
def _no_security_tools(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(vulns, "find_tool", lambda configured, names: None)


def _scan(tmp: Path, sample: str, profile: str) -> Path:
    loaded = load_config(SAMPLES / sample / "audit.yaml")
    loaded.config.target.profile = profile  # type: ignore[assignment]
    return run_scan(loaded, output_dir=tmp / profile).run_dir


@pytest.fixture(scope="module")
def angular(tmp_path_factory: pytest.TempPathFactory) -> dict[str, Path]:
    tmp = tmp_path_factory.mktemp("targets")
    mp = pytest.MonkeyPatch()
    mp.setattr(vulns, "find_tool", lambda configured, names: None)
    runs = {p: _scan(tmp, "angular-node-app", p) for p in ("spring-boot-angular", "wildfly-gke",
                                                            "none")}  # fmt: skip
    mp.undo()
    return runs


def test_default_target_and_labels() -> None:
    t = Target()
    assert t.profile == "spring-boot-angular" and t.wildfly == "41" and t.java == "25"
    assert info(t)["label"] == "Java 25, Spring Boot 4.1, Angular 21, PostgreSQL on GCP"
    wf = info(Target(profile="wildfly-gke"))
    assert wf["label"] == "WildFly 41 (Jakarta EE 11, Java 25) on GKE, database on PostgreSQL"
    none = info(Target(profile="none"))
    assert none["audit_only"] and none["skipped_stages"] == list(TARGET_STAGES)


def test_resolve_profiles_and_by_target() -> None:
    data = {
        "items": [
            {"id": "a", "target": "Spring", "by_target": {"wildfly-gke": {"target": "WildFly"}}},
            {"id": "b", "target": "x", "by_target": {"wildfly-gke,none": "skip"}},
        ],
        "word": "default",
        "profiles": {"none": {"word": "nothing", "items": []}},
    }
    assert [i["target"] for i in resolve(data, "spring-boot-angular")["items"]] == ["Spring", "x"]
    wf = resolve(data, "wildfly-gke")
    assert wf["items"] == [{"id": "a", "target": "WildFly"}] and wf["word"] == "default"
    assert resolve(data, "none") == {"items": [], "word": "nothing"}


def test_fill_phrases() -> None:
    phrases = {"move": "and must move to WildFly", "empty": ""}
    assert fill("Specific to X [[move]]", phrases) == "Specific to X and must move to WildFly"
    assert fill("Specific to X [[empty]].", phrases) == "Specific to X."
    assert fill("no placeholder", phrases) == "no placeholder"


def test_target_recorded(angular: dict[str, Path]) -> None:
    for profile, run_dir in angular.items():
        data = json.loads((run_dir / "data" / "target.json").read_text(encoding="utf-8"))
        assert data["profile"] == profile
        run = json.loads((run_dir / "run.json").read_text(encoding="utf-8"))
        summary = json.loads((run_dir / "data" / "audit-summary.json").read_text("utf-8"))
        assert run["target"] == data == summary["target"]
        assert RunData.load(run_dir).audit_only is (profile == "none")


def test_wildfly_keeps_the_code(angular: dict[str, Path]) -> None:
    rd = RunData.load(angular["wildfly-gke"])
    rows = {r["name"]: r for r in server_rows(rd)}
    assert rows["Oracle WebLogic Server"]["target"].startswith("WildFly 41 on Java 25")
    assert "Spring" not in rows["Nginx"]["target"]
    assert rows["Node.js"]["target"].startswith("Node.js 24 LTS in a container on GKE")
    screen = next(m for m in rd.target_mapping if m["id"] == "screen")
    assert screen["target"].startswith("Kept:") and "by_target" not in screen
    findings = [f for f in rd.items("findings") if f["rule_id"].startswith("server-")]
    assert findings and all("[[" not in f["message"] for f in findings)
    assert all("WildFly 41" in (f.get("remediation") or "") for f in findings)
    angularjs = next(lib for lib in rd.items("libraries") if lib["artifact"] == "angular")
    assert "Angular 21" not in json.dumps(angularjs["recommendation"])  # front end stays
    # the default target still says Spring Boot
    default = {r["name"]: r for r in server_rows(RunData.load(angular["spring-boot-angular"]))}
    assert default["Oracle WebLogic Server"]["target"].startswith("Spring Boot 4")


def test_audit_only_suggests_nothing(angular: dict[str, Path]) -> None:
    run_dir = angular["none"]
    rd = RunData.load(run_dir)
    assert rd.target_mapping == [] and rd.effort is None
    assert not rd.summary["counts"]["pg_grades"]
    assert all(not s.get("pg_grade") for s in rd.items("sql_statements"))
    assert all(r["target"] == "" for r in server_rows(rd))
    findings = [f for f in rd.items("findings") if f["rule_id"].startswith("server-")]
    assert findings and not any("must move" in f["message"] for f in findings)
    assert [s.name for s in active_stages(run_dir)] == [
        s.name for s in STAGES if s.name not in TARGET_STAGES
    ]
    assert set(compute_status(run_dir)["missing"]).isdisjoint(TARGET_STAGES)
    with pytest.raises(AiError, match="audit only"):
        pick_stages(run_dir, ["plan"], stale=False)
    assert "plan" not in [s.name for s in pick_stages(run_dir, None, stale=True)]


def test_audit_only_reports(angular: dict[str, Path]) -> None:
    run_dir = angular["none"]
    res = build_summary_report(run_dir)
    data = json.loads((res.page.parent / "summary.json").read_text(encoding="utf-8"))
    sections = {s["key"]: s for s in data["sections"]}
    project = {t["key"]: t for t in sections["project"]["tables"]}
    assert "Target" not in project["servers"]["columns"]
    assert "Migration target: Audit only (no target)." in sections["project"]["intro"]
    db = sections["data"]
    assert "pg-grade" not in [c["key"] for c in db["charts"]]
    assert "Auto" not in next(t for t in db["tables"] if t["key"] == "sql-by-module")["columns"]
    assert "Audit only (no target)" in res.page.read_text(encoding="utf-8")
    tech = build_tech_report(run_dir)
    assert not (tech.parent / "postgresql.html").exists()
    assert "postgresql.html" not in tech.read_text(encoding="utf-8")
    deck = build_deck(run_dir)
    ids = [s["id"] for s in deck["slides"]]
    assert not [i for i in ids if i.startswith(("tgt-", "plan-"))] and "tech-sql" not in ids
    assert "exec-approach" not in ids
    assert [s["key"] for s in deck["sections"]] == [
        "exec", "functional", "current", "technical", "risks"
    ]  # fmt: skip
    # the default target keeps its target and plan slides
    full = [s["id"] for s in build_deck(angular["spring-boot-angular"])["slides"]]
    assert "tgt-mapping" in full and "tech-sql" in full


def test_target_in_reports_and_prompts(angular: dict[str, Path]) -> None:
    run_dir = angular["wildfly-gke"]
    label = "WildFly 41 (Jakarta EE 11, Java 25) on GKE, database on PostgreSQL"
    tech = build_tech_report(run_dir)
    assert f"target: {label}" in tech.read_text(encoding="utf-8")
    assert label in (tech.parent / "appendix.html").read_text(encoding="utf-8")
    deck = build_deck(run_dir)
    assert deck["slides"][0]["subtitle"].endswith(f"Target: {label}")
    user = context.build(run_dir, BY_NAME["target_architecture"], 60_000)[1]
    assert f"**Migration target: {label}**" in user


def test_changing_the_target_makes_target_stages_stale(tmp_path: Path) -> None:
    out = tmp_path / "out"
    loaded = load_config(SAMPLES / "desktop-dotnet-app" / "audit.yaml")
    run_dir = run_scan(loaded, output_dir=out).run_dir
    shutil.copytree(ENRICHMENT / "desktop-dotnet-app", run_dir / "enrichment")
    assert validate_run(run_dir, restamp=True).ok
    assert compute_status(run_dir)["stale"] == []
    # a stage stamped before the target was recorded counts as built for the default target
    path = run_dir / "enrichment" / "target_architecture.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    del data["meta"]["built_from"]["facts_hash_by_input"]["target"]
    path.write_text(json.dumps(data), encoding="utf-8")
    assert compute_status(run_dir)["stale"] == []
    stamp(run_dir, BY_NAME["target_architecture"], force=True)

    loaded.config.target.profile = "wildfly-gke"  # type: ignore[assignment]
    rescan = run_scan(loaded, output_dir=out).run_dir
    stale = set(compute_status(rescan)["stale"])
    assert stale == {"dependency_plan", "target_architecture"}  # the stages reading the target
    # what the validator says about Spring Boot text once the target is WildFly
    warnings = [m.text for m in validate_run(rescan, ["target_architecture"]).warnings]
    assert any("but the target is WildFly 41" in w for w in warnings)


def test_validator_warns_on_migration_text_when_audit_only(tmp_path: Path) -> None:
    loaded = load_config(SAMPLES / "angular-node-app" / "audit.yaml")
    loaded.config.target.profile = "none"  # type: ignore[assignment]
    run_dir = run_scan(loaded, output_dir=tmp_path / "out").run_dir
    shutil.copytree(ENRICHMENT / "angular-node-app", run_dir / "enrichment")
    rep = validate_run(run_dir)
    assert "target_architecture" not in rep.checked  # not needed, not checked
    texts = [str(m) for m in rep.warnings]
    assert any("not needed: the target is none" in t for t in texts)
