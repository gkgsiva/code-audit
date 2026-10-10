"""Re-runs: late inputs link to cached sources; analyzer coverage; crash isolation; stress."""

from __future__ import annotations

import json
import logging
import shutil
import time
from pathlib import Path

import pytest

from legacy_audit.config import load_config
from legacy_audit.model import BatchJob, CodeEdge, FileRecord, Service
from legacy_audit.scan import run_scan
from tests.conftest import SAMPLES, write
from tests.stress_tree import generate

log = logging.getLogger(__name__)


def _edges(result) -> set[tuple[str, str, str]]:  # type: ignore[no-untyped-def]
    """(kind, source label, target label) for resolved edges, with readable labels."""
    facts = result.facts
    names: dict[str, str] = {}
    for f in facts["inventory"]:
        assert isinstance(f, FileRecord)
        names[f.id] = f.path.rsplit("/", 1)[-1]
    for j in facts["batch_jobs"]:
        assert isinstance(j, BatchJob)
        names[j.id] = j.name
    for s in facts["services"]:
        assert isinstance(s, Service)
        names[s.id] = s.name
    out = set()
    for e in facts["dependencies"]:
        assert isinstance(e, CodeEdge)
        if e.resolved:
            out.add((e.kind, names.get(e.source, e.source), names.get(e.target, e.target)))
    return out


def test_autosys_added_later_links_to_cached_scripts(tmp_path: Path) -> None:
    app = tmp_path / "app"
    shutil.copytree(SAMPLES / "spring-mvc-batch-app", app)
    jil = app / "audit-inputs" / "autosys" / "billing.jil"
    saved = jil.read_text(encoding="utf-8")
    shutil.rmtree(app / "audit-inputs" / "autosys")
    cfg = load_config(app / "audit.yaml")

    first = run_scan(cfg)
    assert not any(
        isinstance(j, BatchJob) and j.scheduler == "autosys" for j in first.facts["batch_jobs"]
    )
    assert {c.kind: c.status for c in first.run.coverage}["autosys"] == "missing"

    write(jil, saved)
    second = run_scan(cfg)
    # Only the new JIL file is analysed; every source file comes from the cache.
    assert second.run.files_analyzed == 1
    assert second.run.files_cached == first.run.files_total
    edges = _edges(second)
    assert ("invokes", "BIL_INVOICE", "run_invoice_job.sh") in edges
    assert ("triggers", "run_invoice_job.sh", "invoiceJob") in edges
    assert ("depends-on", "BIL_INVOICE", "BIL_USAGE_WAIT") in edges
    assert ("contains", "BIL_NIGHTLY_BOX", "BIL_INVOICE") in edges
    # The job takes the component of the script it runs, and reaches the job's tables.
    svc = next(
        s for s in second.facts["services"] if isinstance(s, Service) and s.name == "BIL_INVOICE"
    )
    assert svc.component == "billing-batch"
    assert "INVOICES" in svc.tables
    assert {c.kind: c.status for c in second.run.coverage}["autosys"] == "present"


def test_cached_facts_equal_fresh_facts(tmp_path: Path) -> None:
    app = tmp_path / "app"
    shutil.copytree(SAMPLES / "c-proc-batch", app)
    cfg = load_config(app / "audit.yaml")
    fresh = run_scan(cfg, full=True)
    cached = run_scan(cfg)
    assert cached.run.files_analyzed == 0
    for entity in ("services", "sql_statements", "integrations", "dependencies", "batch_jobs"):
        a = [f.model_dump() for f in fresh.facts[entity]]
        b = [f.model_dump() for f in cached.facts[entity]]
        assert a == b, entity


def test_run_json_has_analyzer_coverage(tmp_path: Path) -> None:
    app = tmp_path / "app"
    write(app / "audit.yaml", "application:\n  name: t\n")
    write(app / "src" / "Bad.java", "public class Bad { void x( { }\n")
    write(app / "src" / "Good.java", "public class Good { void x() { } }\n")
    result = run_scan(load_config(app / "audit.yaml"))
    run = json.loads((result.run_dir / "run.json").read_text())
    java = run["analyzers"]["java"]
    assert java["files"] == 2
    assert java["unparsed_lines"] >= 1
    assert java["coverage_pct"] < 100
    assert java["regions"][0]["file"] == "src/Bad.java"


def test_analyzer_crash_is_a_finding_not_a_failure(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from legacy_audit.analyzers.shell import ShellAnalyzer

    def boom(self, file, ctx):  # type: ignore[no-untyped-def]
        raise RuntimeError("boom")

    monkeypatch.setattr(ShellAnalyzer, "analyze", boom)
    app = tmp_path / "app"
    write(app / "audit.yaml", "application:\n  name: t\n")
    write(app / "run.sh", "#!/bin/sh\necho hi\n")
    result = run_scan(load_config(app / "audit.yaml"))
    rules = [f.rule_id for f in result.facts["findings"]]  # type: ignore[attr-defined]
    assert rules == ["analyzer-error"]
    assert result.run.analyzers["shell"].coverage_pct == 0.0


def test_stress_200k_loc(tmp_path: Path) -> None:
    src = tmp_path / "src"
    lines = generate(src, 200_000)
    write(tmp_path / "audit.yaml", "application:\n  name: stress\nsource:\n  paths: [src]\n")
    started = time.perf_counter()
    result = run_scan(load_config(tmp_path / "audit.yaml"))
    elapsed = time.perf_counter() - started
    log.warning(
        "stress: %d lines, %d files scanned in %.1f s", lines, result.run.files_total, elapsed
    )
    print(f"\nstress: {lines} lines, {result.run.files_total} files in {elapsed:.1f} s")
    assert lines >= 200_000
    assert not [f for f in result.facts["findings"] if f.rule_id == "analyzer-error"]  # type: ignore[attr-defined]
    assert elapsed < 120
    started = time.perf_counter()
    again = run_scan(load_config(tmp_path / "audit.yaml"))
    assert again.run.files_analyzed == 0
    assert time.perf_counter() - started < elapsed
