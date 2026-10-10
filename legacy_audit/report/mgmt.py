"""``legacy-audit report --type mgmt``: the management deck as a static HTML slider.

``deck.json`` (from ``deck_builder``) is written next to the page and is also what the PPTX
builder reads, so both decks show the same slides and numbers. Opens from disk; Chart.js and
Mermaid are the vendored copies of the technical report.
"""

from __future__ import annotations

import re
import shutil
from importlib.resources import files
from pathlib import Path
from typing import Any

from jinja2 import ChainableUndefined, Environment, PackageLoader, select_autoescape

from legacy_audit.config import Branding
from legacy_audit.enrich.status import latest_copy
from legacy_audit.report.deck_builder import _num, build_deck
from legacy_audit.store.writer import write_json

_COLOR = re.compile(r"^#[0-9A-Fa-f]{3,8}$")
_FONT_BAD = re.compile(r"[;{}<>\\]")


def _env() -> Environment:
    return Environment(
        loader=PackageLoader("legacy_audit.report", "mgmt_html"),
        autoescape=select_autoescape(["html"]),
        undefined=ChainableUndefined,
        trim_blocks=True,
        lstrip_blocks=True,
    )


def _copy_tree(src: Any, dest: Path) -> None:
    dest.mkdir(parents=True, exist_ok=True)
    for item in src.iterdir():
        if item.is_dir():
            _copy_tree(item, dest / item.name)
        elif item.is_file():
            (dest / item.name).write_bytes(item.read_bytes())


def write_deck(run_dir: Path, branding: Branding | None = None) -> tuple[Path, dict[str, Any]]:
    """Build deck.json into ``reports/mgmt/`` and return its path and content."""
    deck = build_deck(run_dir, branding)
    out = run_dir / "reports" / "mgmt"
    out.mkdir(parents=True, exist_ok=True)
    write_json(out / "deck.json", deck)
    return out / "deck.json", deck


def build_mgmt_report(
    run_dir: Path, branding: Branding | None = None, config_dir: Path | None = None
) -> Path:
    """Build ``reports/mgmt/index.html`` (and ``deck.json``) for a run; returns the page."""
    branding = branding or Branding()
    _, deck = write_deck(run_dir, branding)
    out = run_dir / "reports" / "mgmt"
    _render(deck, branding, config_dir, out)
    latest = latest_copy(run_dir)
    if latest is not None:
        dest = latest / "reports" / "mgmt"
        if dest.exists():
            shutil.rmtree(dest)
        shutil.copytree(out, dest)
    return out / "index.html"


def _render(deck: dict[str, Any], branding: Branding, config_dir: Path | None, out: Path) -> None:
    assets = out / "assets"
    if assets.exists():
        shutil.rmtree(assets)
    report = files("legacy_audit.report")
    _copy_tree(report.joinpath("mgmt_assets"), assets)
    _copy_tree(report.joinpath("assets").joinpath("vendor"), assets / "vendor")
    logo = None
    if branding.logo:
        src = Path(branding.logo)
        if not src.is_absolute() and config_dir is not None:
            src = config_dir / src
        if src.is_file() and src.suffix.lower() in (".png", ".jpg", ".jpeg", ".svg", ".gif"):
            shutil.copyfile(src, assets / f"logo{src.suffix.lower()}")
            logo = f"assets/logo{src.suffix.lower()}"
    color = branding.primary_color if _COLOR.match(branding.primary_color) else "#1f4e79"
    font = _FONT_BAD.sub("", branding.font)
    html = (
        _env()
        .get_template("deck.html")
        .render(deck=deck, fmt=_num, logo=logo, brand_color=color, brand_font=font)
    )
    (out / "index.html").write_text(html, encoding="utf-8")
