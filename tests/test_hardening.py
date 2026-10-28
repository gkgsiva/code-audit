"""Phase 6 hardening: parallel analysis, cancel, interrupted runs, odd files, doctor."""

from __future__ import annotations

import json
import os
import stat
import sys
import threading
from pathlib import Path

import pytest
from typer.testing import CliRunner

from legacy_audit import doctor
from legacy_audit.cli import app
from legacy_audit.config import load_config
from legacy_audit.inventory import worker
from legacy_audit.inventory.walker import extended_path
from legacy_audit.parallel import Cancelled, ordered_map
from legacy_audit.scan import run_scan
from legacy_audit.security import vulns
from legacy_audit.store.lock import ScanBusy, ScanLock
from legacy_audit.store.runs import list_runs, runs_dir
from tests.conftest import tree_hash, write
from tests.stress_tree import generate


@pytest.fixture(autouse=True)
def _no_security_tools(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(vulns, "find_tool", lambda configured, names: None)


def _app(tmp_path: Path, extra: str = "") -> Path:
    write(tmp_path / "src" / "a" / "Main.java", "package a;\npublic class Main {}\n")
    write(tmp_path / "src" / "a" / "run.sh", "#!/bin/sh\necho hi\n")
    return write(
        tmp_path / "audit.yaml", f"application:\n  name: t\nsource:\n  paths: [src]\n{extra}"
    )


def _square(n: int) -> int:
    return n * n


def test_ordered_map_keeps_order_with_a_pool() -> None:
    items = list(range(150))
    assert list(ordered_map(_square, items, 3)) == [n * n for n in items]


def test_ordered_map_cancels() -> None:
    cancel = threading.Event()
    got = []
    with pytest.raises(Cancelled):
        for r in ordered_map(_square, list(range(200)), 2, cancel=cancel):
            got.append(r)
            if len(got) == 10:
                cancel.set()
    assert len(got) == 10


def _data(run_dir: Path, name: str) -> object:
    return json.loads((run_dir / "data" / name).read_text(encoding="utf-8"))


def test_parallel_scan_matches_serial(tmp_path: Path) -> None:
    generate(tmp_path / "src", 30_000)
    serial = run_scan(
        load_config(_app(tmp_path, "inventory:\n  workers: 1\n")),
        output_dir=tmp_path / "out1",
    )
    parallel = run_scan(
        load_config(_app(tmp_path, "inventory:\n  workers: 2\n")),
        output_dir=tmp_path / "out2",
    )
    assert serial.run.files_analyzed >= 64  # big enough for the pool
    for name in ("inventory.json", "sql_statements.json", "findings.json", "dependencies.json"):
        assert _data(serial.run_dir, name) == _data(parallel.run_dir, name), name
    # the re-run grades nothing again (grade cache) and gives the same grades
    again = run_scan(load_config(_app(tmp_path, "inventory:\n  workers: 2\n")),
                     output_dir=tmp_path / "out2")  # fmt: skip
    assert again.run.files_cached == again.run.files_total
    assert _data(again.run_dir, "sql_statements.json") == _data(
        parallel.run_dir, "sql_statements.json"
    )


def test_cancel_removes_the_unfinished_run(tmp_path: Path) -> None:
    cfg = load_config(_app(tmp_path))
    run_scan(cfg)
    cancel = threading.Event()

    def progress(stage: str, n: int, total: int) -> None:
        if stage == "link":
            cancel.set()

    with pytest.raises(Cancelled):
        run_scan(cfg, progress=progress, cancel=cancel)
    rd = runs_dir(cfg.output_dir)
    assert len([d for d in rd.iterdir() if d.is_dir()]) == 1  # only the first run
    assert len(list_runs(cfg.output_dir)) == 1
    run_scan(cfg)  # the lock was released
    assert len(list_runs(cfg.output_dir)) == 2


def test_interrupted_run_is_cleaned_up(tmp_path: Path) -> None:
    cfg = load_config(_app(tmp_path))
    leftover = runs_dir(cfg.output_dir) / "20200101-000000-dead00"
    write(leftover / "logs" / "scan.log", "killed half way\n")
    result = run_scan(cfg)
    assert not leftover.exists()
    assert list_runs(cfg.output_dir) == [result.run.run_id]


def test_one_scan_at_a_time(tmp_path: Path) -> None:
    cfg = load_config(_app(tmp_path))
    with ScanLock(runs_dir(cfg.output_dir)), pytest.raises(ScanBusy, match="Another scan"):
        run_scan(cfg)
    run_scan(cfg)  # released


def test_large_and_unreadable_files_are_findings(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    write(tmp_path / "src" / "big.sql", "SELECT 1 FROM dual;\n" * 200)
    real = worker.analyze_file

    def flaky(abs_path, path, *args):  # type: ignore[no-untyped-def]
        if path.endswith("run.sh"):
            raise PermissionError(13, "Permission denied")
        return real(abs_path, path, *args)

    monkeypatch.setattr(worker, "analyze_file", flaky)
    result = run_scan(load_config(_app(tmp_path, "inventory:\n  max_file_size_mb: 0.001\n")))
    by_rule = {f.rule_id: f for f in result.facts["findings"]}  # type: ignore[attr-defined]
    assert by_rule["file-too-large"].file == "big.sql"
    assert "max_file_size_mb" in by_rule["file-too-large"].message
    assert by_rule["file-unreadable"].file == "a/run.sh"
    assert "Permission denied" in by_rule["file-unreadable"].message
    paths = [r.path for r in result.facts["inventory"]]  # type: ignore[attr-defined]
    assert "a/run.sh" not in paths and "big.sql" in paths


@pytest.mark.skipif(sys.platform == "win32", reason="needs symlink rights")
def test_symlink_loop_does_not_hang(tmp_path: Path) -> None:
    cfg_path = _app(tmp_path)
    os.symlink(tmp_path / "src", tmp_path / "src" / "a" / "loop", target_is_directory=True)
    result = run_scan(load_config(cfg_path))
    assert sorted(r.path for r in result.facts["inventory"]) == ["a/Main.java", "a/run.sh"]  # type: ignore[attr-defined]


def test_read_only_source_tree(tmp_path: Path) -> None:
    cfg_path = _app(tmp_path, "output_dir: ../out\n")
    src = tmp_path / "src"
    before = tree_hash(src)
    modes = {}
    for p in [*src.rglob("*"), src]:
        modes[p] = p.stat().st_mode
        p.chmod(stat.S_IREAD | (stat.S_IEXEC if p.is_dir() else 0))
    try:
        result = run_scan(load_config(cfg_path))
    finally:
        for p, m in modes.items():
            p.chmod(m)
    assert result.run.files_total == 2
    assert tree_hash(src) == before


def test_extended_paths_for_windows() -> None:
    assert extended_path(r"C:\legacy\app", windows=True) == "\\\\?\\C:\\legacy\\app"
    assert extended_path("C:/legacy/app", windows=True) == "\\\\?\\C:\\legacy\\app"
    assert extended_path(r"\\fileserver\share\app", windows=True) == (
        "\\\\?\\UNC\\fileserver\\share\\app"
    )
    assert extended_path("\\\\?\\C:\\x", windows=True) == "\\\\?\\C:\\x"
    assert extended_path("/opt/legacy", windows=False) == "/opt/legacy"


@pytest.mark.skipif(sys.platform != "win32", reason="Windows path length limit")
def test_paths_longer_than_260_characters(tmp_path: Path) -> None:
    deep = Path(extended_path(tmp_path / "src")) / ("d" * 50) / ("e" * 50) / ("f" * 50)
    deep = deep / ("g" * 50) / ("h" * 50)
    deep.mkdir(parents=True)
    (deep / "Deep.java").write_text("package x;\npublic class Deep {}\n", encoding="utf-8")
    write(tmp_path / "audit.yaml", "application:\n  name: t\nsource:\n  paths: [src]\n")
    result = run_scan(load_config(tmp_path / "audit.yaml"))
    paths = [r.path for r in result.facts["inventory"]]  # type: ignore[attr-defined]
    assert len(paths) == 1 and paths[0].endswith("/Deep.java")


def test_doctor_without_node_or_dependency_check(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import legacy_audit.report.pptx as pptx

    monkeypatch.setattr(pptx, "_node", lambda: None)
    monkeypatch.setattr(pptx, "_mmdc", lambda: None)
    monkeypatch.setattr(doctor.shutil, "which", lambda name: None)
    checks = {c.name: c for c in doctor.run_checks(output_dir=tmp_path / "out")}
    assert checks["Node.js"].status == "missing" and not checks["Node.js"].required
    assert checks["OWASP Dependency-Check"].status == "missing"
    assert checks["Mermaid CLI (mmdc)"].status == "missing"
    assert checks["Python"].ok and checks["Output folder"].ok
    assert not checks["Git"].required  # local source

    cfg = _app(tmp_path)
    result = CliRunner().invoke(app, ["doctor", "-c", str(cfg)])
    assert result.exit_code == 0, result.output
    assert "Ready to scan" in result.output
    assert "Node.js" in result.output and "missing" in result.output
