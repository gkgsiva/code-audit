from __future__ import annotations

import hashlib
import shutil
from pathlib import Path

import pytest
from typer.testing import CliRunner

from legacy_audit.cli import app

REPO = Path(__file__).resolve().parents[1]
SAMPLES = REPO / "samples"


@pytest.fixture(autouse=True)
def _no_pdf_browser(request: pytest.FixtureRequest, monkeypatch: pytest.MonkeyPatch) -> None:
    """`report --type all` would print PDFs with any browser found; only real_browser tests do."""
    if "real_browser" not in request.keywords:
        from legacy_audit.report import pdf

        monkeypatch.setattr(pdf, "find_browser", lambda configured=None: None)


@pytest.fixture
def runner() -> CliRunner:
    return CliRunner()


def invoke(runner: CliRunner, *args: str):
    result = runner.invoke(app, list(args), catch_exceptions=False)
    return result


@pytest.fixture
def sample_copy(tmp_path: Path):
    """Copy a sample app to a temp folder (without any previous output)."""

    def _copy(name: str) -> Path:
        dest = tmp_path / name
        shutil.copytree(SAMPLES / name, dest, ignore=shutil.ignore_patterns("audit-output"))
        return dest

    return _copy


def tree_hash(root: Path) -> str:
    """Hash of every path, size and content under root (detects any write)."""
    h = hashlib.sha256()
    for p in sorted(root.rglob("*")):
        h.update(p.relative_to(root).as_posix().encode())
        if p.is_file():
            h.update(p.read_bytes())
    return h.hexdigest()


def write(path: Path, text: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path
