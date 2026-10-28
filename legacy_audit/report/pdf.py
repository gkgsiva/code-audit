"""``legacy-audit report --type pdf``: PDF copies of the HTML reports.

The HTML reports are printed by a browser in headless mode (Microsoft Edge, Chrome or Chromium),
so charts and diagrams look as they do on screen. Nothing is downloaded and the browser runs with
host names unresolvable, so the pages cannot reach the network. Without a browser the PDFs are a
coverage gap: skipped with a message, never an error.

Each HTML page is printed from a temporary copy (``_pdf-*.html`` next to it, removed afterwards)
that adds the paper size and ``window.LEGACY_AUDIT_PDF``, which tells the page scripts to print
up to ``pdf.max_table_rows`` rows per table. The multi-page reports (technical, functional) are
printed page by page and joined with ``pypdf`` behind a cover with contents, with bookmarks and
"page i of n" stamped on every page.
"""

from __future__ import annotations

import html
import json
import logging
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path, PureWindowsPath
from typing import Any

from legacy_audit.config import PdfConfig
from legacy_audit.enrich.status import latest_copy

log = logging.getLogger(__name__)
logging.getLogger("pypdf").setLevel(logging.ERROR)  # Chromium's PDFs trip harmless xref warnings

# (report folder under reports/, PDF file name, title on the cover)
REPORTS = {
    "summary": ("summary", "summary.pdf", "Summary report"),
    "mgmt": ("mgmt", "management.pdf", "Management deck"),
    "functional": ("functional", "functional.pdf", "Functional document"),
    "tech": ("tech", "technical.pdf", "Technical report"),
}
# Detail pages that follow a main page in the joined PDFs: {main page id: sub-folder}.
DETAIL_FOLDERS = {
    "tech": {"components": "components", "flows": "flows"},
    "functional": {"business-flows": "flows"},
}
PAPER_MM = {"A4": (210.0, 297.0), "Letter": (215.9, 279.4)}
LINUX_NAMES = (
    "microsoft-edge", "microsoft-edge-stable", "google-chrome", "google-chrome-stable",
    "chromium", "chromium-browser", "chrome",
)  # fmt: skip
WINDOWS_PROGRAMS = (
    ("Microsoft Edge", r"Microsoft\Edge\Application\msedge.exe"),
    ("Google Chrome", r"Google\Chrome\Application\chrome.exe"),
)
MAC_APPS = (
    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Chromium.app/Contents/MacOS/Chromium",
)
_H1 = re.compile(r"<h1[^>]*>(.*?)</h1>", re.S | re.I)
_TITLE = re.compile(r"<title>(.*?)</title>", re.S | re.I)
_TAG = re.compile(r"<[^>]+>")


class PdfError(Exception):
    """A page could not be printed (browser failed, timed out or wrote nothing)."""


@dataclass
class Browser:
    path: str
    label: str
    windows_from_wsl: bool = False  # Windows Edge/Chrome started from WSL: paths go as \\wsl$


@dataclass
class PdfResult:
    files: dict[str, Path] = field(default_factory=dict)  # report key -> PDF
    messages: list[str] = field(default_factory=list)
    browser: Browser | None = None


# ---- finding a browser -------------------------------------------------------------------


def is_wsl() -> bool:
    if not sys.platform.startswith("linux"):
        return False
    try:
        return "microsoft" in Path("/proc/version").read_text(encoding="utf-8").lower()
    except OSError:
        return False


def _label(path: str) -> str:
    low = path.lower()
    if "edge" in low:
        return "Microsoft Edge"
    if "chromium" in low or "pw-browsers" in low:
        return "Chromium"
    return "Google Chrome"


def candidates() -> list[str]:
    """Places a browser is looked for, best first, for this machine."""
    found: list[str] = []
    if sys.platform == "win32":
        roots = [
            os.environ.get("PROGRAMFILES(X86)"),
            os.environ.get("PROGRAMFILES"),
            os.environ.get("LOCALAPPDATA"),
        ]
        for _, rel in WINDOWS_PROGRAMS:
            found += [str(Path(r) / rel) for r in roots if r]
        found += [w for n in ("msedge", "chrome") if (w := shutil.which(n))]
        return found
    if sys.platform == "darwin":
        found += list(MAC_APPS)
    found += [w for n in LINUX_NAMES if (w := shutil.which(n))]
    pw = os.environ.get("PLAYWRIGHT_BROWSERS_PATH")  # a Playwright install (CI, dev containers)
    if pw:
        found += [str(p) for p in sorted(Path(pw).glob("chromium-*/chrome-linux/chrome"))]
    if is_wsl():
        for base in ("/mnt/c/Program Files (x86)", "/mnt/c/Program Files"):
            for _, rel in WINDOWS_PROGRAMS:
                found.append(base + "/" + rel.replace("\\", "/"))
    return found


def find_browser(configured: str | None = None) -> Browser | None:
    """``pdf.browser_path`` when set (it must exist), else the first browser found."""
    if configured:
        p = Path(os.path.expandvars(os.path.expanduser(configured)))
        hit = str(p) if p.is_file() else shutil.which(configured)
        if not hit:
            return None
        return Browser(hit, _label(hit), _from_wsl(hit))
    for c in candidates():
        if Path(c).is_file():
            return Browser(c, _label(c), _from_wsl(c))
    return None


def _from_wsl(path: str) -> bool:
    return path.lower().endswith(".exe") and path.startswith("/mnt/") and is_wsl()


def missing_message(configured: str | None) -> str:
    if configured:
        return (
            f"PDF skipped: the browser in pdf.browser_path was not found at {configured}. "
            "Fix the path, or set it to null to find Edge or Chrome automatically."
        )
    return (
        "PDF skipped: no Microsoft Edge, Google Chrome or Chromium found. Install one, or set "
        "pdf.browser_path in audit.yaml to the browser program."
    )


# ---- printing one page -------------------------------------------------------------------


class Printer:
    def __init__(self, browser: Browser, cfg: PdfConfig) -> None:
        self.browser, self.cfg = browser, cfg
        self.sandbox = True  # turned off when the browser cannot start its sandbox (Linux)
        self._win_temp: str | None = None

    def _win(self, p: Path) -> str:
        """A WSL path as Windows sees it (\\\\wsl.localhost\\<distro>\\...)."""
        out = subprocess.run(
            ["wslpath", "-w", str(p)], capture_output=True, text=True, check=True, timeout=30
        )
        return out.stdout.strip()

    def _arg(self, p: Path) -> str:
        return self._win(p) if self.browser.windows_from_wsl else str(p)

    def _url(self, p: Path) -> str:
        if self.browser.windows_from_wsl:
            return PureWindowsPath(self._win(p)).as_uri()
        return p.resolve().as_uri()

    def _profile(self, scratch: Path) -> str | None:
        """A throw-away browser profile, so an open Edge/Chrome window is never touched."""
        if not self.browser.windows_from_wsl:
            return str(scratch)
        if self._win_temp is None:
            try:
                out = subprocess.run(
                    ["cmd.exe", "/c", "echo %TEMP%"],
                    capture_output=True, text=True, timeout=30, check=True,
                )  # fmt: skip
                self._win_temp = out.stdout.strip().splitlines()[-1]
            except (OSError, subprocess.SubprocessError, IndexError):
                self._win_temp = ""
        if not self._win_temp:
            return None
        return str(
            PureWindowsPath(self._win_temp) / f"legacy-audit-pdf-{os.getpid()}-{id(scratch)}"
        )

    def _page_px(self) -> int:
        """Width of the printed area (paper less 2 x 11 mm margins) in CSS pixels."""
        return _mm_px(PAPER_MM[self.cfg.paper][0] - 22)

    def command(self, url: str, pdf_arg: str, profile: str | None) -> list[str]:
        cmd = [
            self.browser.path,
            "--headless",
            "--disable-gpu",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-extensions",
            "--disable-sync",
            "--disable-background-networking",
            "--disable-component-update",
            "--host-resolver-rules=MAP * ~NOTFOUND",  # offline: no host name resolves
            "--hide-scrollbars",
            f"--window-size={self._page_px()},1200",  # lay out at the printed width
            "--no-pdf-header-footer",
            "--run-all-compositor-stages-before-draw",
            "--virtual-time-budget=20000",  # lets charts and diagrams finish drawing
            f"--print-to-pdf={pdf_arg}",
        ]
        if profile:
            cmd.append(f"--user-data-dir={profile}")
        root = sys.platform.startswith("linux") and hasattr(os, "geteuid") and os.geteuid() == 0
        if root or not self.sandbox:
            cmd.append("--no-sandbox")  # Chromium refuses to run as root otherwise
        cmd.append(url)
        return cmd

    def print_file(self, page: Path, pdf: Path) -> None:
        """Print an HTML file as it is (it must carry its own @page rules)."""
        pdf.parent.mkdir(parents=True, exist_ok=True)
        out = self._run(page, pdf)
        if out is None:
            return
        if sys.platform.startswith("linux") and self.sandbox and not self.browser.windows_from_wsl:
            # Linux without user namespaces (AppArmor on Ubuntu 24, containers): the sandbox
            # aborts the browser. The pages are local report files and the network is off.
            self.sandbox = False
            retry = self._run(page, pdf)
            if retry is None:
                log.info("PDF: %s runs without its sandbox on this machine", self.browser.label)
                return
            self.sandbox, out = True, retry
        lines = (out.stderr or out.stdout or "").strip().splitlines()
        tail = next((ln for ln in reversed(lines) if "ERROR" in ln or "FATAL" in ln), "")
        tail = tail or (lines[-1] if lines else "")
        raise PdfError(f"{page.name}: no PDF written (exit {out.returncode}) {tail[-300:]}")

    def _run(self, page: Path, pdf: Path) -> subprocess.CompletedProcess[str] | None:
        """One browser run; None when the PDF was written, else the finished process."""
        if pdf.exists():
            pdf.unlink()
        with tempfile.TemporaryDirectory(prefix="legacy-audit-pdf-") as scratch:
            cmd = self.command(self._url(page), self._arg(pdf), self._profile(Path(scratch)))
            try:
                out = subprocess.run(
                    cmd,
                    capture_output=True,
                    text=True,
                    timeout=self.cfg.timeout_seconds,
                    check=False,
                    stdin=subprocess.DEVNULL,
                )
            except subprocess.TimeoutExpired:
                raise PdfError(
                    f"{page.name}: the browser did not finish in {self.cfg.timeout_seconds} s "
                    "(raise pdf.timeout_seconds)"
                ) from None
            except OSError as err:
                raise PdfError(f"{page.name}: could not start {self.browser.path}: {err}") from None
        waits = 20 if out.returncode == 0 else 2  # Windows browsers can return before closing it
        for _ in range(waits):
            if pdf.is_file() and pdf.stat().st_size > 0:
                return None
            time.sleep(0.25)
        return out

    def print_page(self, page: Path, pdf: Path, *, deck: bool = False) -> None:
        """Print a report page through a temporary copy that adds the PDF settings."""
        text = page.read_text(encoding="utf-8")
        copy = page.with_name(f"_pdf-{page.stem}.html")
        copy.write_text(inject(text, self.cfg, deck=deck), encoding="utf-8")
        try:
            self.print_file(copy, pdf)
        finally:
            copy.unlink(missing_ok=True)


def _mm_px(mm: float) -> int:
    return round(mm * 96 / 25.4)


def page_css(cfg: PdfConfig) -> str:
    return (
        f"@page {{ size: {cfg.paper}; margin: 12mm 11mm 16mm; }}"
        f" @page wide {{ size: {cfg.paper} landscape; margin: 12mm 11mm 16mm; }}"
    )


def inject(text: str, cfg: PdfConfig, *, deck: bool = False) -> str:
    """The page with the PDF settings added at the top of <head>."""
    settings = (
        '<meta name="color-scheme" content="light">'
        "<script>window.LEGACY_AUDIT_PDF = "
        + json.dumps(
            {
                "maxRows": int(cfg.max_table_rows),
                "fit": not deck,  # the slides lay themselves out
                "landscapePx": _mm_px(PAPER_MM[cfg.paper][1] - 22),
            }
        )
        + ";</script>"
    )
    if not deck:  # the deck keeps its own 16:9 slide size, like the PowerPoint
        settings += (
            "<style>" + page_css(cfg) + "html, body { background: #fff !important; }"
            " .diagram svg, pre.mermaid svg { max-width: 100% !important; height: auto; }"
            " .chart, .chart-box, .panel, .card, figure, .tile, .kpi, tr { break-inside: avoid; }"
            # tables print whole: no scroll boxes (they clip on paper), text wraps in its cell
            " .wrap, .table-wrap, .catalogue, [data-catalogue] { overflow: visible !important;"
            " max-height: none !important; }"
            " table { width: 100% !important; max-width: 100% !important; }"
            " th { position: static !important; }"
            " td { overflow-wrap: break-word; white-space: normal !important; }"
            " th { overflow-wrap: normal; white-space: normal !important; }"
            "</style>"
        )
    settings += f"<script>{_PDF_JS}</script>"
    m = re.search(r"<head[^>]*>", text, re.I)
    at = m.end() if m else 0
    return text[:at] + settings + text[at:]


# Opens folded sections and shortens long server-written tables (the catalogue tables of the
# technical report read LEGACY_AUDIT_PDF themselves).
_PDF_JS = r"""
// A table wider than the page: smaller font down to 9px, then long unspaced values break; if
// still too wide, it goes on landscape pages ("wide", A4 across) and shrinks again down to
// 6.5px; last resort, any value breaks.
window.addEventListener("load", function () {
  var cfg = window.LEGACY_AUDIT_PDF;
  if (!cfg.fit) return;
  function wide(t, box) { return t.scrollWidth > box.clientWidth + 1; }
  function shrink(t, box, floor) {
    var size = parseFloat(getComputedStyle(t).fontSize) || 13;
    while (wide(t, box) && size > floor) {
      size -= 0.5;
      t.style.fontSize = size + "px";
      t.querySelectorAll("th, td").forEach(function (c) {
        c.style.fontSize = size + "px"; c.style.padding = "3px 4px";
      });
    }
  }
  document.querySelectorAll("table").forEach(function (t) {
    var box = t.closest("[data-catalogue], .wrap, .table-wrap") || t.parentElement;
    if (!box || !wide(t, box)) return;
    shrink(t, box, 9);
    if (wide(t, box)) {  // break long unspaced values (addresses, paths) before turning the page
      t.querySelectorAll("td").forEach(function (c) {
        if (/\S{24,}/.test(c.textContent)) c.style.overflowWrap = "anywhere";
      });
    }
    if (wide(t, box) && cfg.landscapePx) {
      var aside = window.innerWidth - box.getBoundingClientRect().width;  // ancestors' padding
      box.style.page = "wide";
      box.style.width = (cfg.landscapePx - aside) + "px";
      box.style.maxWidth = "none";
      t.style.fontSize = "";
      t.querySelectorAll("th, td").forEach(function (c) {
        c.style.fontSize = ""; c.style.padding = "";
      });
      shrink(t, box, 6.5);
    }
    if (wide(t, box)) {
      t.querySelectorAll("td").forEach(function (c) { c.style.overflowWrap = "anywhere"; });
    }
  });
});
document.addEventListener("DOMContentLoaded", function () {
  var max = window.LEGACY_AUDIT_PDF.maxRows;
  document.querySelectorAll("details").forEach(function (d) {
    d.open = !d.querySelector("pre.block");  // tables yes, raw diagram source no
  });
  document.querySelectorAll("table").forEach(function (t) {
    if (t.closest("[data-catalogue]") || !t.tBodies.length) return;
    var rows = t.tBodies[0].rows, n = rows.length;
    if (n <= max) return;
    for (var i = n - 1; i >= max; i--) rows[i].remove();
    var p = document.createElement("p");
    p.className = "note pdf-cut";
    p.textContent = "First " + max + " of " + n + " rows; the full list is in the HTML report " +
      "and its CSV download.";
    var box = t.closest(".wrap") || t;
    box.parentNode.insertBefore(p, box.nextSibling);
  });
});
"""


# ---- joining pages -----------------------------------------------------------------------


@dataclass
class Part:
    title: str
    page: Path
    level: int = 0  # 0 = main page, 1 = component/flow page under it
    pdf: Path | None = None
    pages: int = 0
    error: str | None = None


def page_title(page: Path) -> str:
    try:
        text = page.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return page.stem
    m = _H1.search(text) or _TITLE.search(text)
    title = html.unescape(_TAG.sub("", m.group(1))).strip() if m else ""
    return re.sub(r"\s+", " ", title.split(" · ")[0]) or page.stem


def parts_for(key: str, folder: Path, cfg: PdfConfig) -> list[Part]:
    """Pages of a multi-page report in menu order, detail pages after their main page."""
    if key == "tech":
        from legacy_audit.report.tech import NAV
    else:
        from legacy_audit.report.functional import FN_NAV as NAV
    out: list[Part] = []
    for page_id, href, title in NAV:
        if not (folder / href).is_file():
            continue
        out.append(Part(title, folder / href))
        sub = DETAIL_FOLDERS.get(key, {}).get(page_id)
        if cfg.detail_pages and sub and (folder / sub).is_dir():
            for p in sorted((folder / sub).glob("*.html")):
                if not p.name.startswith("_pdf-"):
                    out.append(Part(page_title(p), p, 1))
    return out


# Contents entries link here; the link is turned into a jump to that page of the PDF (_link).
GOTO = "https://legacy-audit.invalid/goto/"


def _cover_html(
    app: str,
    kind: str,
    run: dict[str, Any],
    entries: list[tuple[str, int, int | None]],
    cfg: PdfConfig,
) -> str:
    """Cover with the contents; ``entries`` are (title, level, page number or None)."""
    rows = []
    for title, level, num in entries:
        cls, text = ("sub" if level else "main"), html.escape(title)
        if num is None:
            rows.append(f'<li class="{cls}"><span>{text}</span><i></i><b>not printed</b></li>')
        else:
            rows.append(
                f'<li class="{cls}"><a href="{GOTO}{num - 1}"><span>{text}</span><i></i>'
                f"<b>{num}</b></a></li>"
            )
    date = html.escape((run.get("started_at") or "")[:16].replace("T", " "))
    return f"""<!doctype html><html><head><meta charset="utf-8"><title>{html.escape(kind)}</title>
<style>{page_css(cfg)}
body {{ font-family: "Segoe UI", Arial, sans-serif; color: #1d2733; margin: 0; }}
.kind {{ color: #1f4e79; text-transform: uppercase; letter-spacing: 2px; font-size: 12px;
  margin-top: 40mm; }}
h1 {{ font-size: 30px; margin: 6px 0 4px; }}
.meta {{ color: #5b6876; font-size: 12px; margin-bottom: 18mm; border-top: 3px solid #1f4e79;
  padding-top: 8px; }}
h2 {{ font-size: 16px; margin: 0 0 6px; }}
ol {{ list-style: none; padding: 0; margin: 0; font-size: 12.5px; }}
li {{ display: flex; align-items: baseline; gap: 6px; padding: 2px 0; break-inside: avoid; }}
li.main {{ font-weight: 600; margin-top: 4px; }}
li.sub {{ padding-left: 18px; color: #3b4652; font-size: 11.5px; }}
li a {{ display: flex; flex: 1; align-items: baseline; gap: 6px; color: inherit;
  text-decoration: none; }}
li i {{ flex: 1; border-bottom: 1px dotted #9aa5b1; }}
</style></head><body>
<div class="kind">{html.escape(kind)}</div>
<h1>{html.escape(app)}</h1>
<div class="meta">Run {html.escape(str(run.get("run_id", "")))} · {date} · legacy-audit
{html.escape(str(run.get("tool_version", "")))}. Every number comes from the scan; text marked
AI was written by the Copilot analysis and checked against it.</div>
<h2>Contents</h2><ol>{"".join(rows)}</ol></body></html>"""


def _entries(parts: list[Part], offset: int) -> list[tuple[str, int, int | None]]:
    out: list[tuple[str, int, int | None]] = []
    n = offset + 1
    for p in parts:
        out.append((p.title, p.level, n if p.pages else None))
        n += p.pages
    return out


def _cover(
    printer: Printer,
    app: str,
    kind: str,
    run: dict[str, Any],
    entries_at: Any,
    work: Path,
    name: str,
) -> tuple[Path, int]:
    """Print the cover; the contents can run over a page, so reprint until the numbers hold."""
    pages = 1
    for _ in range(3):
        cover = _render_html(
            printer, _cover_html(app, kind, run, entries_at(pages), printer.cfg), work, name
        )
        n = _page_count(cover)
        if n == pages:
            break
        pages = n
    return cover, pages


def _link(writer: Any, targets: dict[str, int]) -> None:
    """Turn links to a contents entry (GOTO) or to a page printed in this PDF (``targets``:
    file URL -> first page) into jumps inside the PDF."""
    from pypdf.generic import ArrayObject, DictionaryObject, NameObject

    for page in writer.pages:
        for ref in page.get("/Annots") or []:
            annot = ref.get_object()
            action = annot.get("/A")
            if not action or action.get("/S") != "/URI":
                continue
            uri = str(action.get("/URI"))
            if uri.startswith(GOTO):
                index: int | None = int(uri[len(GOTO) :])
            else:
                index = targets.get(uri.split("#")[0])
            if index is None or index >= len(writer.pages):
                continue
            annot[NameObject("/A")] = DictionaryObject(
                {
                    NameObject("/S"): NameObject("/GoTo"),
                    NameObject("/D"): ArrayObject(
                        [writer.pages[index].indirect_reference, NameObject("/Fit")]
                    ),
                }
            )


def _numbers_html(sizes: list[tuple[float, float]], label: str, first: int) -> str:
    """One page per entry of ``sizes`` (mm, portrait or the landscape table pages) with its
    "page i of n" footer, to stamp onto the printed pages."""
    kinds = {sz: i for i, sz in enumerate(dict.fromkeys(sizes))}
    css = "".join(
        f"@page s{i} {{ size: {w:.1f}mm {h:.1f}mm; margin: 0; }}"
        f" .s{i} {{ page: s{i}; width: {w:.1f}mm; height: {h:.1f}mm; }}\n"
        for (w, h), i in kinds.items()
    )
    total, esc = len(sizes), html.escape(label)
    pages = "".join(
        f'<div class="p s{kinds[sz]}">'
        + (f"<span>{esc}</span><span>Page {i} of {total}</span>" if i >= first else "")
        + "</div>"
        for i, sz in enumerate(sizes, start=1)
    )
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>
{css}body {{ margin: 0; font-family: "Segoe UI", Arial, sans-serif; }}
.p {{ position: relative; break-after: page; overflow: hidden; }}
.p span {{ position: absolute; bottom: 6mm; font-size: 8.5px; color: #6b7785; }}
.p span:first-child {{ left: 11mm; }} .p span:last-child {{ right: 11mm; }}
</style></head><body>{pages}</body></html>"""


def _sizes(writer: Any) -> list[tuple[float, float]]:
    """Each page's size in mm, rounded so equal paper sizes match."""
    return [
        (
            round(float(p.mediabox.width) / 72 * 25.4, 1),
            round(float(p.mediabox.height) / 72 * 25.4, 1),
        )
        for p in writer.pages
    ]


def _render_html(printer: Printer, text: str, work: Path, name: str) -> Path:
    src, pdf = work / f"{name}.html", work / f"{name}.pdf"
    src.write_text(text, encoding="utf-8")
    printer.print_file(src, pdf)
    return pdf


def _page_count(pdf: Path) -> int:
    from pypdf import PdfReader

    return len(PdfReader(pdf).pages)


def join_report(
    printer: Printer,
    key: str,
    folder: Path,
    dest: Path,
    work: Path,
    run: dict[str, Any],
    app: str,
) -> list[str]:
    """Print every page of a multi-page report and join them into ``dest``; returns problems."""
    from pypdf import PdfReader, PdfWriter

    cfg, kind = printer.cfg, REPORTS[key][2]
    parts = parts_for(key, folder, cfg)
    if not parts:
        return [f"{kind}: no pages found in {folder}"]

    def one(i_part: tuple[int, Part]) -> None:
        i, part = i_part
        part.pdf = work / f"{key}-{i:04d}.pdf"
        try:
            printer.print_page(part.page, part.pdf)
            part.pages = _page_count(part.pdf)
        except (PdfError, OSError, ValueError) as err:
            part.error = str(err)

    workers = max(1, min(4, (os.cpu_count() or 2) // 2 or 1, len(parts)))
    with ThreadPoolExecutor(max_workers=workers) as pool:
        list(pool.map(one, enumerate(parts)))
    problems = [f"{kind}: {p.error}" for p in parts if p.error]
    if not any(p.pages for p in parts):
        return problems or [f"{kind}: nothing printed"]

    cover, cover_pages = _cover(
        printer, app, kind, run, lambda n: _entries(parts, n), work, f"{key}-cover"
    )

    writer = PdfWriter()
    writer.append(PdfReader(cover))
    parent, targets = None, {}
    for p in parts:
        if not p.pages or p.pdf is None:
            continue
        start = len(writer.pages)
        writer.append(PdfReader(p.pdf))
        targets[printer._url(p.page)] = start
        if p.level == 0:
            parent = writer.add_outline_item(p.title, start)
        else:
            writer.add_outline_item(p.title, start, parent=parent)
    _link(writer, targets)
    overlay = PdfReader(
        _render_html(
            printer,
            _numbers_html(_sizes(writer), f"{app} · {kind}", cover_pages + 1),
            work,
            f"{key}-n",
        )
    )
    for i, page in enumerate(writer.pages):
        if i >= cover_pages and i < len(overlay.pages):
            page.merge_page(overlay.pages[i])
    writer.add_metadata({"/Title": f"{app}: {kind}", "/Creator": "legacy-audit"})
    _shrink(writer)
    writer.page_mode = "/UseOutlines"
    tmp = dest.with_suffix(".tmp")
    with tmp.open("wb") as fh:
        writer.write(fh)
    os.replace(tmp, dest)
    return problems


def _shrink(writer: Any) -> None:
    """Each printed page carries its own copy of the fonts; keep one of each."""
    for page in writer.pages:
        page.compress_content_streams()
    writer.compress_identical_objects()


_SECTION = re.compile(r'<section[^>]*\bid="([\w-]+)"[^>]*>\s*<h2[^>]*>(.*?)</h2>', re.S | re.I)


def _sections(page: Path, reader: Any) -> list[tuple[str, int]]:
    """(title, page index) of each numbered part of the summary report, from its anchors."""
    out = []
    dests = reader.named_destinations
    for anchor, raw in _SECTION.findall(page.read_text(encoding="utf-8")):
        dest = dests.get(f"/{anchor}") or dests.get(anchor)
        if dest is None:
            continue
        title = re.sub(r"\s+", " ", html.unescape(_TAG.sub(" ", raw))).strip()
        out.append((title, reader.get_destination_page_number(dest)))
    return out


def single_report(
    printer: Printer,
    key: str,
    page: Path,
    dest: Path,
    work: Path,
    app: str,
    run: dict[str, Any] | None = None,
) -> list[str]:
    """Summary report and management deck: one HTML page, one PDF."""
    from pypdf import PdfReader, PdfWriter

    kind, run = REPORTS[key][2], run or {}
    raw = work / f"{key}.pdf"
    try:
        printer.print_page(page, raw, deck=key == "mgmt")
    except PdfError as err:
        return [f"{kind}: {err}"]
    reader = PdfReader(raw)
    writer = PdfWriter(clone_from=reader)
    first = 1
    sections = _sections(page, reader) if key == "summary" else []
    if sections:  # a cover with the contents, and bookmarks, for the five parts
        cover, first = _cover(
            printer,
            app,
            kind,
            run,
            lambda n: [(t, 0, at + n + 1) for t, at in sections],
            work,
            f"{key}-cover",
        )
        for i, cp in enumerate(PdfReader(cover).pages):
            writer.insert_page(cp, i)
        for title, at in sections:
            writer.add_outline_item(title, at + first)
        _link(writer, {})
        writer.page_mode = "/UseOutlines"
        first += 1
    if key != "mgmt":  # the slides carry their own numbers
        overlay = PdfReader(
            _render_html(
                printer,
                _numbers_html(_sizes(writer), f"{app} · {kind}", first),
                work,
                f"{key}-n",
            )
        )
        for i, p in enumerate(writer.pages):
            if i < len(overlay.pages):
                p.merge_page(overlay.pages[i])
    writer.add_metadata({"/Title": f"{app}: {kind}", "/Creator": "legacy-audit"})
    _shrink(writer)
    tmp = dest.with_suffix(".tmp")
    with tmp.open("wb") as fh:
        writer.write(fh)
    os.replace(tmp, dest)
    return []


def build_pdfs(
    run_dir: Path,
    cfg: PdfConfig | None = None,
    reports: tuple[str, ...] = ("summary", "mgmt", "functional", "tech"),
) -> PdfResult:
    """Print the HTML reports of a run that exist into ``reports/pdf/``."""

    cfg = cfg or PdfConfig()
    result = PdfResult()
    browser = find_browser(cfg.browser_path)
    if browser is None:
        result.messages.append(missing_message(cfg.browser_path))
        return result
    result.browser = browser
    printer = Printer(browser, cfg)
    try:
        run = json.loads((run_dir / "run.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        run = {}
    app = str(run.get("application") or "application")
    out = run_dir / "reports" / "pdf"
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="legacy-audit-pdf-") as tmp:
        work = Path(tmp)
        for key in reports:
            folder_name, file_name, kind = REPORTS[key]
            folder = run_dir / "reports" / folder_name
            if not (folder / "index.html").is_file():
                result.messages.append(f"{kind}: not built yet, no PDF")
                continue
            dest = out / file_name
            dest.unlink(missing_ok=True)
            if key in ("summary", "mgmt"):
                problems = single_report(printer, key, folder / "index.html", dest, work, app, run)
            else:
                problems = join_report(printer, key, folder, dest, work, run, app)
            result.messages += problems
            if dest.is_file():
                result.files[key] = dest
    latest = latest_copy(run_dir)
    if latest is not None and result.files:
        dest_dir = latest / "reports" / "pdf"
        dest_dir.mkdir(parents=True, exist_ok=True)
        for f in result.files.values():
            shutil.copyfile(f, dest_dir / f.name)
    return result
