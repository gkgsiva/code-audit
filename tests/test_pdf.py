"""PDF copies of the reports: browser detection, the PDF print settings, page order of the joined
reports and, where a browser is installed (Edge on Windows CI, Chrome on Linux CI), real PDFs."""

from __future__ import annotations

import sys
from pathlib import Path

import pytest
from pypdf import PdfReader

from legacy_audit.config import AuditConfig, PdfConfig, load_config
from legacy_audit.report import pdf
from legacy_audit.report.functional import build_functional_report
from legacy_audit.report.mgmt import build_mgmt_report
from legacy_audit.report.summary import build_summary_report
from legacy_audit.report.tech import build_tech_report
from legacy_audit.scan import run_scan
from legacy_audit.security import vulns
from tests.conftest import SAMPLES, invoke

HAS_BROWSER = pdf.find_browser() is not None


@pytest.fixture(autouse=True)
def _no_security_tools(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(vulns, "find_tool", lambda configured, names: None)


def test_config_defaults_to_a4_and_checks_the_paper() -> None:
    cfg = AuditConfig.model_validate({"application": {"name": "x"}})
    assert cfg.pdf.paper == "A4" and cfg.pdf.enabled and cfg.pdf.browser_path is None
    with pytest.raises(ValueError):
        PdfConfig.model_validate({"paper": "A3"})


def test_inject_adds_paper_size_and_row_limit_after_head() -> None:
    page = '<!doctype html><html><head><meta charset="utf-8"></head><body></body></html>'
    out = pdf.inject(page, PdfConfig(paper="Letter", max_table_rows=25))
    assert out.index("<head>") < out.index("LEGACY_AUDIT_PDF") < out.index("<meta charset")
    assert "@page { size: Letter;" in out and "maxRows: 25" in out
    deck = pdf.inject(page, PdfConfig(), deck=True)
    assert "@page" not in deck and "LEGACY_AUDIT_PDF" in deck  # the deck keeps its 16:9 slides


@pytest.mark.real_browser
def test_configured_browser_must_exist(tmp_path: Path) -> None:
    assert pdf.find_browser(str(tmp_path / "missing" / "msedge.exe")) is None
    msg = pdf.missing_message(str(tmp_path / "missing" / "msedge.exe"))
    assert "pdf.browser_path" in msg and "missing" in msg
    fake = tmp_path / "Microsoft" / "Edge" / "msedge.exe"
    fake.parent.mkdir(parents=True)
    fake.write_text("")
    found = pdf.find_browser(str(fake))
    assert found is not None and found.label == "Microsoft Edge" and not found.windows_from_wsl


def test_windows_looks_for_edge_then_chrome(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(sys, "platform", "win32")
    monkeypatch.setattr(pdf.shutil, "which", lambda name: None)
    monkeypatch.setenv("PROGRAMFILES(X86)", r"C:\Program Files (x86)")
    monkeypatch.setenv("PROGRAMFILES", r"C:\Program Files")
    monkeypatch.setenv("LOCALAPPDATA", r"C:\Users\siva\AppData\Local")
    found = pdf.candidates()
    edge = [i for i, c in enumerate(found) if c.endswith("msedge.exe")]
    chrome = [i for i, c in enumerate(found) if c.endswith("chrome.exe")]
    assert edge and chrome and max(edge) < min(chrome)
    assert "Program Files (x86)" in found[0]


def test_wsl_also_finds_windows_edge(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setattr(pdf, "is_wsl", lambda: True)
    monkeypatch.delenv("PLAYWRIGHT_BROWSERS_PATH", raising=False)
    found = pdf.candidates()
    edge = "/mnt/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
    assert edge in found
    assert pdf._from_wsl(edge)
    assert not pdf._from_wsl("/usr/bin/google-chrome")


def test_browser_runs_offline_on_a_throwaway_profile(tmp_path: Path) -> None:
    printer = pdf.Printer(pdf.Browser("chrome", "Google Chrome"), PdfConfig())
    cmd = printer.command("file:///r/index.html", str(tmp_path / "o.pdf"), str(tmp_path / "p"))
    assert cmd[0] == "chrome" and cmd[-1] == "file:///r/index.html"
    assert "--headless" in cmd and "--host-resolver-rules=MAP * ~NOTFOUND" in cmd
    assert f"--print-to-pdf={tmp_path / 'o.pdf'}" in cmd
    assert f"--user-data-dir={tmp_path / 'p'}" in cmd


def test_no_browser_is_a_message_not_an_error(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(pdf, "find_browser", lambda configured=None: None)
    res = pdf.build_pdfs(tmp_path)
    assert not res.files and "no Microsoft Edge" in res.messages[0]


def test_cli_pdf_without_browser_says_so_and_fails(
    sample_copy, runner, monkeypatch: pytest.MonkeyPatch
) -> None:  # type: ignore[no-untyped-def]
    monkeypatch.setattr(pdf, "find_browser", lambda configured=None: None)
    root = sample_copy("struts-oracle-app")
    assert invoke(runner, "scan", "-c", str(root / "audit.yaml")).exit_code == 0
    res = invoke(runner, "report", "--type", "pdf", "-c", str(root / "audit.yaml"))
    assert res.exit_code == 1
    assert "no Microsoft Edge" in res.output.replace("\n", " ")
    assert list((root / "audit-output" / "runs").glob("*/reports/tech/index.html"))
    # "all" carries on without PDFs: they are a coverage gap there
    res = invoke(runner, "report", "--type", "all", "-c", str(root / "audit.yaml"))
    assert res.exit_code == 0, res.output


def _built(tmp_path: Path) -> Path:
    loaded = load_config(SAMPLES / "struts-oracle-app" / "audit.yaml")
    run_dir = run_scan(loaded, output_dir=tmp_path / "out").run_dir
    build_tech_report(run_dir, loaded.inputs_dir)
    build_functional_report(run_dir, loaded.inputs_dir)
    build_mgmt_report(run_dir)
    build_summary_report(run_dir)
    return run_dir


def test_joined_reports_follow_the_menu_with_detail_pages(tmp_path: Path) -> None:
    run_dir = _built(tmp_path)
    tech = run_dir / "reports" / "tech"
    parts = pdf.parts_for("tech", tech, PdfConfig())
    titles = [p.title for p in parts if p.level == 0]
    assert titles[:4] == ["Overview", "Components", "Architecture", "Flows"]
    comps = titles.index("Components")
    main = [p for p in parts if p.level == 0]
    first_detail = parts.index(main[comps]) + 1
    assert parts[first_detail].level == 1 and parts[first_detail].page.parent.name == "components"
    assert all(p.level == 0 for p in pdf.parts_for("tech", tech, PdfConfig(detail_pages=False)))
    fn = pdf.parts_for("functional", run_dir / "reports" / "functional", PdfConfig())
    assert fn[0].title == "Summary"


@pytest.mark.real_browser
@pytest.mark.skipif(not HAS_BROWSER, reason="no Edge, Chrome or Chromium on this machine")
def test_prints_all_four_reports(tmp_path: Path) -> None:
    run_dir = _built(tmp_path)
    res = pdf.build_pdfs(run_dir, PdfConfig())
    assert set(res.files) == {"summary", "mgmt", "functional", "tech"}, res.messages
    assert not res.messages
    tech = PdfReader(res.files["tech"])
    assert len(tech.pages) > 10
    width, height = float(tech.pages[0].mediabox.width), float(tech.pages[0].mediabox.height)
    assert abs(width - 595) < 2 and abs(height - 842) < 2  # A4 portrait
    cover = tech.pages[0].extract_text().lower()
    assert "technical report" in cover and "contents" in cover and "overview" in cover
    assert f"Page 2 of {len(tech.pages)}" in tech.pages[1].extract_text()
    assert [o.title for o in tech.outline if not isinstance(o, list)][:2] == [
        "Overview",
        "Components",
    ]
    summary = PdfReader(res.files["summary"])
    text = " ".join(p.extract_text() for p in summary.pages)
    assert "Database and queries" in text and "Page 1 of" in text
    deck = PdfReader(res.files["mgmt"])
    assert float(deck.pages[0].mediabox.width) > float(deck.pages[0].mediabox.height)  # slides
    assert not list((run_dir / "reports" / "tech").glob("_pdf-*.html"))  # temp copies removed


@pytest.mark.skipif(not sys.platform.startswith("linux"), reason="the Linux sandbox fallback")
def test_retries_without_sandbox_when_the_browser_aborts(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Ubuntu 24 (AppArmor) and containers stop Chrome's sandbox: the browser aborts."""
    fake = tmp_path / "chrome"
    fake.write_text(
        f"#!{sys.executable}\n"
        "import sys\n"
        "if '--no-sandbox' not in sys.argv:\n"
        "    print('[1:1:ERROR:zygote_host_impl_linux.cc] No usable sandbox!', file=sys.stderr)\n"
        "    sys.exit(134)\n"
        "out = next(a.split('=', 1)[1] for a in sys.argv if a.startswith('--print-to-pdf='))\n"
        "open(out, 'wb').write(b'%PDF-1.4 fake')\n"
    )
    fake.chmod(0o755)
    monkeypatch.setattr(pdf.os, "geteuid", lambda: 1000)
    printer = pdf.Printer(pdf.Browser(str(fake), "Chromium"), PdfConfig())
    page = tmp_path / "page.html"
    page.write_text("<html><head></head><body>x</body></html>")
    printer.print_file(page, tmp_path / "a.pdf")
    assert (tmp_path / "a.pdf").read_bytes().startswith(b"%PDF") and not printer.sandbox
    printer.print_file(page, tmp_path / "b.pdf")  # later pages go straight without it
    assert (tmp_path / "b.pdf").is_file()

    broken = pdf.Printer(pdf.Browser(str(tmp_path / "missing"), "Chromium"), PdfConfig())
    with pytest.raises(pdf.PdfError, match="could not start"):
        broken.print_file(page, tmp_path / "c.pdf")
