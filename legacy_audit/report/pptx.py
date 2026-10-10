"""``legacy-audit report --type pptx``: the editable PowerPoint, built by Node from deck.json.

Python writes ``reports/mgmt/deck.json`` and runs ``pptx/build-pptx.js`` (``pptxgenjs``) with
Node 18+. Diagrams are rendered to PNG first when Mermaid CLI (``mmdc``) is available. Node and
both packages are optional: when one is missing the PPTX is skipped with a message saying what to
install; nothing else fails.

Where the builder is found: ``$LEGACY_AUDIT_PPTX_DIR``, the copy packaged inside
``legacy_audit/_pptx`` (wheel installs), or ``pptx/`` of a source checkout. Its Node packages are
installed once with ``npm install`` in that folder.
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
from legacy_audit.store.writer import write_json

NODE_TIMEOUT = 300


@dataclass
class PptxResult:
    path: Path | None
    message: str
    diagrams: int = 0


def builder_dir() -> Path | None:
    env = os.environ.get("LEGACY_AUDIT_PPTX_DIR")
    here = Path(__file__).resolve().parent.parent
    for d in ([Path(env)] if env else []) + [here / "_pptx", here.parent / "pptx"]:
        if (d / "build-pptx.js").is_file():
            return d
    return None


def _node() -> str | None:
    return os.environ.get("LEGACY_AUDIT_NODE") or shutil.which("node")


def _mmdc(pdir: Path) -> str | None:
    env = os.environ.get("LEGACY_AUDIT_MMDC")
    if env:
        return env
    for name in ("mmdc.cmd", "mmdc"):
        local = pdir / "node_modules" / ".bin" / name
        if local.is_file():
            return str(local)
    return shutil.which("mmdc")


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
    run_dir: Path, branding: Branding | None = None, config_dir: Path | None = None
) -> PptxResult:
    """Build ``reports/deck.pptx`` for a run, or say why it was skipped."""
    branding = branding or Branding()
    deck_path, deck = write_deck(run_dir, branding)
    pdir = builder_dir()
    node = _node()
    if node is None:
        return PptxResult(None, "PPTX skipped: Node.js 18+ is not installed (or not on PATH).")
    if pdir is None:
        return PptxResult(
            None,
            "PPTX skipped: the pptx builder (build-pptx.js) was not found; "
            "set LEGACY_AUDIT_PPTX_DIR to the pptx/ folder.",
        )
    if not (pdir / "node_modules" / "pptxgenjs").is_dir():
        return PptxResult(
            None, f"PPTX skipped: run 'npm install' once in {pdir} (installs pptxgenjs)."
        )
    mgmt = deck_path.parent
    diagrams = 0
    mmdc = _mmdc(pdir)
    if mmdc:
        diagrams = render_diagrams(deck, mgmt, mmdc)
    if branding.logo:
        logo = Path(branding.logo)
        if not logo.is_absolute() and config_dir is not None:
            logo = config_dir / logo
        if logo.is_file():
            deck["branding"]["logo_file"] = str(logo.resolve())
    write_json(deck_path, deck)
    out = run_dir / "reports" / "deck.pptx"
    try:
        proc = subprocess.run(
            [node, str(pdir / "build-pptx.js"), str(deck_path), str(out)],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=NODE_TIMEOUT,
        )
    except (OSError, subprocess.SubprocessError) as err:
        return PptxResult(None, f"PPTX failed: {err}")
    if proc.returncode != 0 or not out.is_file():
        tail = (proc.stderr or proc.stdout).strip().splitlines()[-3:]
        return PptxResult(None, "PPTX failed: " + " | ".join(tail))
    latest = latest_copy(run_dir)
    if latest is not None:
        (latest / "reports").mkdir(parents=True, exist_ok=True)
        shutil.copyfile(out, latest / "reports" / "deck.pptx")
        shutil.copytree(mgmt, latest / "reports" / "mgmt", dirs_exist_ok=True)
    note = "" if mmdc else " Diagrams not embedded (Mermaid CLI 'mmdc' not found)."
    if branding.pptx_template:
        note += " branding.pptx_template is reserved and not applied yet."
    return PptxResult(out, f"PPTX written.{note}", diagrams)
