"""``legacy-audit report --type pptx``: the editable PowerPoint, built on a corporate template.

Python writes ``reports/mgmt/deck.json`` and builds ``reports/deck.pptx`` from it with
``python-pptx`` on the chosen template (``templates/*.potx``; see ``pptx_templates``). Every slide
uses one of the template's layouts, so the deck keeps the template's master, colours and fonts.
Diagrams are rendered to PNG first when Mermaid CLI (``mmdc``, Node) is available; without it the
Mermaid source goes to the speaker notes. When no template is found the PPTX is skipped with a
message saying what to do; nothing else fails.
"""

from __future__ import annotations

import os
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from legacy_audit.config import Branding
from legacy_audit.enrich.status import latest_copy
from legacy_audit.report.mgmt import write_deck
from legacy_audit.report.pptx_templates import TemplateError, resolve_template
from legacy_audit.store.writer import write_json

NODE_TIMEOUT = 300  # Mermaid CLI, per diagram


@dataclass
class PptxResult:
    path: Path | None
    message: str
    diagrams: int = 0
    template: str | None = None


def _node() -> str | None:  # Node.js runs Mermaid CLI; the PPTX itself needs no Node
    return os.environ.get("LEGACY_AUDIT_NODE") or shutil.which("node")


def _mmdc() -> str | None:
    return os.environ.get("LEGACY_AUDIT_MMDC") or shutil.which("mmdc")


def render_diagrams(deck: dict[str, Any], mgmt_dir: Path, mmdc: str) -> int:
    """Render each Mermaid block to ``diagrams/<slide>-<n>.png``; set ``png`` on the block."""
    out = mgmt_dir / "diagrams"
    out.mkdir(exist_ok=True)
    done = 0
    for slide in deck["slides"]:
        for n, block in enumerate(slide["blocks"]):
            if block.get("type") != "diagram" or not block.get("mermaid"):
                continue
            src = out / f"{slide['id']}-{n}.mmd"
            png = src.with_suffix(".png")
            src.write_text(block["mermaid"], encoding="utf-8")
            try:
                subprocess.run(
                    [mmdc, "-i", str(src), "-o", str(png), "-b", "white", "-s", "2"],
                    check=True,
                    capture_output=True,
                    timeout=NODE_TIMEOUT,
                )
            except (OSError, subprocess.SubprocessError):
                continue
            if png.is_file():
                block["png"] = f"diagrams/{png.name}"
                done += 1
    return done


def build_pptx(
    run_dir: Path,
    branding: Branding | None = None,
    config_dir: Path | None = None,
    template: str | None = None,
) -> PptxResult:
    """Build ``reports/deck.pptx`` for a run on a corporate template, or say why it was skipped.

    ``template`` (a name such as ``blue``, a file name or a path) wins over
    ``branding.pptx_template``; with neither, the first template found is used.
    """
    from legacy_audit.report.pptx_build import build_on_template

    branding = branding or Branding()
    deck_path, deck = write_deck(run_dir, branding)
    try:
        chosen = resolve_template(template or branding.pptx_template, config_dir)
    except TemplateError as err:
        return PptxResult(None, f"PPTX skipped: {err}.")
    mgmt = deck_path.parent
    diagrams = 0
    mmdc = _mmdc()
    if mmdc:
        diagrams = render_diagrams(deck, mgmt, mmdc)
    deck["pptx_template"] = {"id": chosen.id, "label": chosen.label, "file": chosen.path.name}
    write_json(deck_path, deck)
    out = run_dir / "reports" / "deck.pptx"
    try:
        build_on_template(deck, chosen, mgmt, out)
    except TemplateError as err:
        return PptxResult(None, f"PPTX failed: {err}.", template=chosen.label)
    except Exception as err:  # a damaged template or file in use: report it, never crash a run
        return PptxResult(None, f"PPTX failed: {type(err).__name__}: {err}", template=chosen.label)
    latest = latest_copy(run_dir)
    if latest is not None:
        (latest / "reports").mkdir(parents=True, exist_ok=True)
        shutil.copyfile(out, latest / "reports" / "deck.pptx")
        shutil.copytree(mgmt, latest / "reports" / "mgmt", dirs_exist_ok=True)
    note = "" if mmdc else " Diagrams not embedded (Mermaid CLI 'mmdc' not found)."
    return PptxResult(out, f"PPTX written ({chosen.label} template).{note}", diagrams, chosen.label)
