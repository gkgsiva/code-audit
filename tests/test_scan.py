"""Phase 1 acceptance criteria for scan, re-run, coverage, diff and overrides."""

import json
import logging
import os
import sqlite3
from pathlib import Path

from legacy_audit.config import load_config
from legacy_audit.scan import run_scan
from legacy_audit.store.runs import list_runs, resolve_run
from legacy_audit.store.summary import MAX_BYTES
from tests.conftest import invoke, tree_hash, write


def _facts(run_dir: Path) -> dict:
    data = run_dir / "data"
    return {
        p.stem: json.loads(p.read_text(encoding="utf-8"))
        for p in sorted(data.glob("*.json"))
        # The summary and the SBOM carry the run id and time.
        if p.stem not in ("audit-summary", "sbom.cdx")
    }


def test_init_creates_starter_files(runner, tmp_path: Path):
    ws = tmp_path / "legacy"
    result = invoke(runner, "init", str(ws))
    assert result.exit_code == 0
    assert (ws / "audit.yaml").is_file()
    assert (ws / "audit-overrides.yaml").is_file()
    for kind in ("autosys", "mq", "cft", "ddl", "crontab", "server-config"):
        assert (ws / "audit-inputs" / kind).is_dir()
    loaded = load_config(ws / "audit.yaml")
    assert loaded.config.application.name == "legacy"
    # Second init keeps the user's files.
    (ws / "audit.yaml").write_text("application: {name: mine}\n")
    invoke(runner, "init", str(ws))
    assert "mine" in (ws / "audit.yaml").read_text()


def test_init_then_scan_workspace_excludes_tool_files(runner, tmp_path: Path):
    ws = tmp_path / "legacy"
    write(ws / "src" / "A.java", "class A {}\n")
    invoke(runner, "init", str(ws))
    result = invoke(runner, "scan", "--config", str(ws / "audit.yaml"))
    assert result.exit_code == 0, result.output
    # Second scan must not pick up the first scan's output folder.
    invoke(runner, "scan", "--config", str(ws / "audit.yaml"))
    inv_path = ws / "audit-output" / "latest" / "data" / "inventory.json"
    inv = json.loads(inv_path.read_text(encoding="utf-8"))
    assert [r["path"] for r in inv] == ["src/A.java"]


def test_scan_outputs(sample_copy):
    app = sample_copy("struts-oracle-app")
    result = run_scan(load_config(app / "audit.yaml"))
    run_dir = result.run_dir
    assert run_dir.parent == app / "audit-output" / "runs"
    for name in (
        "inventory.json",
        "inventory.csv",
        "components.json",
        "facts.sqlite",
        "audit-summary.json",
        "input_coverage.json",
    ):
        assert (run_dir / "data" / name).is_file(), name
    run = json.loads((run_dir / "run.json").read_text(encoding="utf-8"))
    assert run["config_hash"] and run["tool_version"] and run["timings"]
    assert run["counting_rules"]["service"].startswith("One independently invokable")
    assert (run_dir / "logs" / "scan.log").is_file()
    assert (app / "audit-output" / "latest" / "run.json").is_file()
    assert resolve_run(app / "audit-output", "latest") == run_dir

    conn = sqlite3.connect(run_dir / "data" / "facts.sqlite")
    (n,) = conn.execute("SELECT COUNT(*) FROM inventory").fetchone()
    assert n == run["files_total"]
    tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert {"inventory", "components", "services", "input_coverage"} <= tables
    conn.close()
    assert (run_dir / "data" / "audit-summary.json").stat().st_size < MAX_BYTES


def test_rescan_without_changes_is_cached_and_identical(sample_copy):
    app = sample_copy("struts-oracle-app")
    loaded = load_config(app / "audit.yaml")
    first = run_scan(loaded)
    second = run_scan(loaded)
    assert first.run.files_analyzed == first.run.files_total
    assert second.run.files_analyzed == 0
    assert second.run.files_cached == second.run.files_total
    log = (second.run_dir / "logs" / "scan.log").read_text()
    assert " 0 analyzed" in log
    assert _facts(first.run_dir) == _facts(second.run_dir)
    assert first.run.run_id != second.run.run_id


def test_full_rescan_ignores_cache(sample_copy):
    app = sample_copy("struts-oracle-app")
    loaded = load_config(app / "audit.yaml")
    run_scan(loaded)
    full = run_scan(loaded, full=True)
    assert full.run.files_analyzed == full.run.files_total


def test_changed_added_and_deleted_files(sample_copy):
    app = sample_copy("struts-oracle-app")
    loaded = load_config(app / "audit.yaml")
    first = run_scan(loaded)
    src = app / "orders-web" / "src" / "com" / "acme" / "orders"
    target = src / "service" / "OrderService.java"
    target.write_text(target.read_text() + "// changed\n")
    os.utime(target, ns=(1, 1))  # different mtime even on coarse clocks
    write(src / "util" / "Dates.java", "class Dates {}\n")
    (src / "form" / "OrderSearchForm.java").unlink()
    second = run_scan(loaded)
    assert second.run.files_analyzed == 2
    assert second.run.files_removed == 1
    assert second.run.files_total == first.run.files_total

    # Touching a file without changing it keeps the cached analysis.
    os.utime(target, ns=(2, 2))
    third = run_scan(loaded)
    assert third.run.files_analyzed == 0


def test_coverage_change_and_diff(runner, sample_copy):
    app = sample_copy("struts-oracle-app")
    cfg = str(app / "audit.yaml")
    invoke(runner, "scan", "--config", cfg)
    before = json.loads((app / "audit-output" / "latest" / "run.json").read_text(encoding="utf-8"))
    autosys = {c["kind"]: c for c in before["coverage"]}["autosys"]
    assert autosys["status"] == "missing"

    write(app / "audit-inputs" / "autosys" / "orders.jil", "insert_job: ORD_NIGHTLY\n")
    invoke(runner, "scan", "--config", cfg)
    after = json.loads((app / "audit-output" / "latest" / "run.json").read_text(encoding="utf-8"))
    autosys = {c["kind"]: c for c in after["coverage"]}["autosys"]
    assert autosys["status"] == "present" and autosys["file_count"] == 1

    out = app / "diff.json"
    result = invoke(runner, "diff", "previous", "latest", "--config", cfg, "--json", str(out))
    assert result.exit_code == 0
    assert "autosys" in result.output
    d = json.loads(out.read_text(encoding="utf-8"))
    changed = d["entities"]["input_coverage"]["changed"]
    assert len(changed) == 1
    assert changed[0]["fields"]["status"] == ["missing", "present"]
    assert [a["label"] for a in d["entities"]["inventory"]["added"]] == [
        "audit-inputs/autosys/orders.jil"
    ]


def test_nothing_written_inside_source_tree(sample_copy):
    app = sample_copy("struts-oracle-app")
    before = tree_hash(app / "orders-web")
    loaded = load_config(app / "audit.yaml")
    run_scan(loaded)
    run_scan(loaded)
    assert tree_hash(app / "orders-web") == before


def test_overrides_applied_every_run(sample_copy):
    app = sample_copy("struts-oracle-app")
    write(
        app / "audit-overrides.yaml",
        """
files:
  exclude: ["README.txt"]
components:
  assign: {"sql/**": orders-db}
  rename: {orders-web: orders-portal}
  type: {orders-db: db}
""",
    )
    loaded = load_config(app / "audit.yaml")
    for _ in range(2):
        result = run_scan(loaded)
        comps = {c.name: c for c in result.facts["components"]}  # type: ignore[attr-defined]
        assert set(comps) == {"orders-portal", "orders-db"}
        assert comps["orders-db"].type == "db" and comps["orders-db"].files == 3
        assert comps["orders-portal"].type == "web"
        paths = {r.path for r in result.facts["inventory"]}  # type: ignore[attr-defined]
        assert "README.txt" not in paths
        assert result.run.overrides["components_rename"] == {"orders-web": 1}


def test_unused_override_is_a_warning(sample_copy):
    app = sample_copy("struts-oracle-app")
    write(app / "audit-overrides.yaml", "components:\n  rename: {nope: x}\n")
    result = run_scan(load_config(app / "audit.yaml"))
    assert any("nope" in w for w in result.run.warnings)


def test_missing_inputs_dir_is_not_an_error(tmp_path: Path):
    write(tmp_path / "code" / "a.sh", "echo hi\n")
    write(tmp_path / "audit.yaml", "application: {name: t}\nsource: {paths: [code]}\n")
    result = run_scan(load_config(tmp_path / "audit.yaml"))
    assert all(c.status == "missing" for c in result.run.coverage)


def test_bad_config_exit_code(runner, tmp_path: Path):
    write(tmp_path / "audit.yaml", "application: {name: t}\nbogus: 1\n")
    result = invoke(runner, "scan", "--config", str(tmp_path / "audit.yaml"))
    assert result.exit_code == 1
    assert "unknown key 'bogus'" in result.output


def test_missing_source_folder_exit_code(runner, tmp_path: Path):
    write(tmp_path / "audit.yaml", "application: {name: t}\nsource: {paths: [nowhere]}\n")
    result = invoke(runner, "scan", "--config", str(tmp_path / "audit.yaml"))
    assert result.exit_code == 1
    assert "Source folder not found" in result.output


def test_latest_pointer_copy_fallback(sample_copy, monkeypatch):
    def no_symlink(*a, **k):
        raise OSError("symlinks not allowed")

    monkeypatch.setattr(os, "symlink", no_symlink)
    app = sample_copy("struts-oracle-app")
    loaded = load_config(app / "audit.yaml")
    run_scan(loaded)
    second = run_scan(loaded)
    latest = app / "audit-output" / "latest"
    assert latest.is_dir() and not latest.is_symlink()
    latest_run = json.loads((latest / "run.json").read_text(encoding="utf-8"))
    assert latest_run["run_id"] == second.run.run_id
    assert len(list_runs(app / "audit-output")) == 2


def test_version_command(runner):
    assert "legacy-audit" in invoke(runner, "version").output


def test_logging_handler_removed_after_scan(sample_copy):
    app = sample_copy("struts-oracle-app")
    run_scan(load_config(app / "audit.yaml"))
    assert not any(
        isinstance(h, logging.FileHandler) for h in logging.getLogger("legacy_audit").handlers
    )
