"""Phase 4: the technical HTML report (``legacy-audit report --type tech``)."""

from __future__ import annotations

import json
import os
import re
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote

import pytest
from typer.testing import CliRunner

from legacy_audit.cli import app
from legacy_audit.config import load_config
from legacy_audit.report import diagrams
from legacy_audit.report.data import RunData
from legacy_audit.report.tech import build_tech_report
from legacy_audit.scan import run_scan
from legacy_audit.security import vulns
from tests.conftest import SAMPLES

SAMPLE_NAMES = sorted(p.parent.name for p in SAMPLES.glob("*/expected.yaml"))
VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source",
        "track", "wbr"}  # fmt: skip


class _Check(HTMLParser):
    """Basic validity: every element closed in order; collects external references and KPIs."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.stack: list[str] = []
        self.errors: list[str] = []
        self.refs: list[str] = []
        self.kpis: dict[str, str] = {}
        self.has_doctype = False

    def handle_decl(self, decl: str) -> None:
        self.has_doctype = decl.lower() == "doctype html"

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        a = dict(attrs)
        if tag == "script" and a.get("src"):
            self.refs.append(a["src"] or "")
        if tag == "link" and a.get("href"):
            self.refs.append(a["href"] or "")
        if tag == "img" and a.get("src"):
            self.refs.append(a["src"] or "")
        if a.get("data-kpi"):
            self.kpis[a["data-kpi"] or ""] = a.get("data-value") or ""
        if tag not in VOID:
            self.stack.append(tag)

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.handle_starttag(tag, attrs)
        if tag not in VOID:
            self.stack.pop()

    def handle_endtag(self, tag: str) -> None:
        if tag in VOID:
            return
        if not self.stack or self.stack[-1] != tag:
            self.errors.append(f"</{tag}> closes <{self.stack[-1] if self.stack else '-'}>")
            if tag in self.stack:
                while self.stack and self.stack.pop() != tag:
                    pass
            return
        self.stack.pop()


def _check(path: Path) -> _Check:
    c = _Check()
    c.feed(path.read_text(encoding="utf-8"))
    c.close()
    return c


@pytest.fixture(autouse=True)
def _no_security_tools(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(vulns, "find_tool", lambda configured, names: None)


def _scan_and_report(app_dir: Path, out: Path) -> tuple[Path, Path]:
    result = run_scan(load_config(app_dir / "audit.yaml"), output_dir=out)
    return result.run_dir, build_tech_report(result.run_dir)


@pytest.mark.parametrize("sample", SAMPLE_NAMES)
def test_report_builds_valid_offline_html(sample: str, tmp_path: Path) -> None:
    run_dir, index = _scan_and_report(SAMPLES / sample, tmp_path / "out")
    root = index.parent
    pages = sorted(root.rglob("*.html"))
    assert {p.name for p in pages} >= {
        "index.html", "components.html", "architecture.html", "services.html", "screens.html",
        "sql.html", "db-objects.html", "integrations.html", "batch-jobs.html", "libraries.html",
        "postgresql.html", "security.html", "findings.html", "appendix.html",
    }  # fmt: skip
    assert list((root / "components").glob("*.html"))
    for page in pages:
        c = _check(page)
        assert c.has_doctype, page
        assert not c.errors, (page, c.errors[:5])
        assert c.stack == [], (page, c.stack)
        for ref in c.refs:
            assert not re.match(r"(?i)^(https?:)?//", ref), (page, ref)  # no CDN, no network
            assert (page.parent / unquote(ref)).exists(), (page, ref)
    text = "".join(p.read_text(encoding="utf-8") for p in pages)
    assert not re.search(r"<(script|link)[^>]+(src|href)=\"https?://", text)
    # the scanned secret never reaches the report
    assert "Orders2009!" not in text
    assert "Orders2009!" not in "".join(
        p.read_text(encoding="utf-8") for p in (root / "data").glob("*.js")
    )
    assert (root / "assets" / "vendor" / "chart-4.4.7.umd.js").exists()
    assert (root / "assets" / "vendor" / "mermaid-11.4.1.min.js").exists()
    assert run_dir / "reports" / "tech" == root


def _summary_value(summary: dict, path: str):  # type: ignore[no-untyped-def,type-arg]
    d = summary
    for part in path.split("."):
        if not isinstance(d, dict) or part not in d:
            return 0
        d = d[part]
    return d


@pytest.mark.parametrize("sample", SAMPLE_NAMES)
def test_overview_kpis_equal_the_summary(sample: str, tmp_path: Path) -> None:
    run_dir, index = _scan_and_report(SAMPLES / sample, tmp_path / "out")
    summary = json.loads((run_dir / "data" / "audit-summary.json").read_text(encoding="utf-8"))
    summary["effort"] = json.loads((run_dir / "data" / "effort.json").read_text(encoding="utf-8"))
    kpis = _check(index).kpis
    assert len(kpis) >= 14
    for key, value in kpis.items():
        assert str(_summary_value(summary, key)) == value, key


def test_evidence_links_reach_the_source(sample_copy) -> None:  # type: ignore[no-untyped-def]
    # source and report on one drive (on Windows CI the checkout and temp dir are on different
    # drives, where links are file:// URIs instead; see the cross-drive test below)
    app_dir = sample_copy("struts-oracle-app")
    _, index = _scan_and_report(app_dir, app_dir / "audit-output")
    root = index.parent
    js = (root / "data" / "sql.js").read_text(encoding="utf-8")
    payload = json.loads(js.split("=", 2)[2].rstrip(";\n"))
    ev = payload["rows"][0][0]
    assert ev["href"] and not ev.get("abs")
    assert (root / unquote(ev["href"])).exists()
    assert ev["vscode"].startswith("vscode://file/") and re.search(r":\d+$", ev["vscode"])


def test_large_catalogue_stays_out_of_the_page(tmp_path: Path) -> None:
    run_dir, _ = _scan_and_report(SAMPLES / "c-proc-batch", tmp_path / "out")
    sql_path = run_dir / "data" / "sql_statements.json"
    base = json.loads(sql_path.read_text(encoding="utf-8"))[0]
    rows = [{**base, "id": f"sql:{i}", "line": i, "text": f"SELECT a{i} FROM t{i % 50}"}
            for i in range(20_000)]  # fmt: skip
    sql_path.write_text(json.dumps(rows), encoding="utf-8")
    index = build_tech_report(run_dir)
    page = index.parent / "sql.html"
    assert page.stat().st_size < 40_000  # rows are paged in by report.js, not in the HTML
    js = (index.parent / "data" / "sql.js").read_text(encoding="utf-8")
    assert js.count('"SELECT a') == 20_000
    js_text = (index.parent / "assets" / "report.js").read_text(encoding="utf-8")
    assert "PAGE_SIZE" in js_text


def test_cross_drive_evidence_is_an_absolute_uri(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    def other_drive(*args, **kwargs):  # type: ignore[no-untyped-def]
        raise ValueError("path is on mount 'D:', start on mount 'C:'")

    run_dir, index = _scan_and_report(SAMPLES / "struts-oracle-app", tmp_path / "out")
    rd = RunData.load(run_dir)
    file, line = rd.first_evidence(rd.items("sql_statements")[0])
    monkeypatch.setattr(os.path, "relpath", other_drive)
    ev = rd.evidence_links(index.parent, file, line)
    assert ev["abs"] and ev["href"].startswith("file:///")
    assert ev["vscode"].startswith("vscode://file/")


def test_rerun_keeps_older_reports(sample_copy) -> None:  # type: ignore[no-untyped-def]
    app_dir = sample_copy("c-proc-batch")
    runner = CliRunner()
    cfg = str(app_dir / "audit.yaml")
    assert runner.invoke(app, ["scan", "-c", cfg]).exit_code == 0
    first = runner.invoke(app, ["report", "-c", cfg])
    assert first.exit_code == 0, first.output
    runs = app_dir / "audit-output" / "runs"
    (run1,) = [p for p in runs.iterdir() if p.is_dir()]
    page1 = run1 / "reports" / "tech" / "index.html"
    before = (page1.read_bytes(), page1.stat().st_mtime_ns)
    assert runner.invoke(app, ["scan", "-c", cfg]).exit_code == 0
    assert runner.invoke(app, ["report", "-c", cfg]).exit_code == 0
    run2 = next(p for p in runs.iterdir() if p.is_dir() and p != run1)
    assert (run2 / "reports" / "tech" / "index.html").exists()
    assert (page1.read_bytes(), page1.stat().st_mtime_ns) == before
    assert run2.name in (app_dir / "audit-output" / "latest" / "reports" / "tech" /
                         "index.html").read_text(encoding="utf-8")  # fmt: skip


def test_latest_copy_gets_its_own_build(sample_copy, monkeypatch: pytest.MonkeyPatch) -> None:  # type: ignore[no-untyped-def]
    """Where symlinks are not allowed (Windows without developer mode) latest is a copy."""

    def no_symlink(*args, **kwargs):  # type: ignore[no-untyped-def]
        raise OSError("symlinks not allowed")

    monkeypatch.setattr(os, "symlink", no_symlink)
    app_dir = sample_copy("struts-oracle-app")
    result = run_scan(load_config(app_dir / "audit.yaml"))
    latest = app_dir / "audit-output" / "latest"
    assert latest.is_dir() and not latest.is_symlink()
    build_tech_report(result.run_dir)
    copy_root = latest / "reports" / "tech"
    js = (copy_root / "data" / "sql.js").read_text(encoding="utf-8")
    ev = json.loads(js.split("=", 2)[2].rstrip(";\n"))["rows"][0][0]
    assert (copy_root / unquote(ev["href"])).exists()  # links are right from the copy too


def test_report_command_errors(tmp_path: Path) -> None:
    runner = CliRunner()
    (tmp_path / "audit.yaml").write_text("application:\n  name: t\n", encoding="utf-8")
    cfg = str(tmp_path / "audit.yaml")
    res = runner.invoke(app, ["report", "-c", cfg])
    assert res.exit_code == 1 and "scan" in res.output
    res = runner.invoke(app, ["report", "-c", cfg, "--type", "nope"])
    assert res.exit_code == 1


def test_mermaid_labels_are_escaped(tmp_path: Path) -> None:
    assert diagrams._label('a "b" <c> q@host') == '"a #quot;b#quot; #lt;c#gt; q#64;host"'
    rd = RunData(tmp_path, {}, {}, {k: [] for k in ("components", "dependencies",
                 "integrations", "batch_jobs")}, None, [], False)  # fmt: skip
    assert diagrams.component_graph(rd).nodes == 0
    assert diagrams.integration_map(rd).note == "No integrations found."
    assert diagrams.batch_chains(rd) == []
