"""The developer reference pages (docs/developer/reference/) match the code they describe."""

import importlib.util
from pathlib import Path

from tests.conftest import REPO


def _generator():
    path = REPO / "scripts" / "gen_dev_reference.py"
    spec = importlib.util.spec_from_file_location("gen_dev_reference", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_reference_pages_are_up_to_date():
    """Fails when the code changed without re-running `python scripts/gen_dev_reference.py`."""
    gen = _generator()
    out: Path = gen.OUT
    pages = gen.render_all()
    on_disk = {p.name for p in out.glob("*.md")}
    assert on_disk == set(pages), "reference pages added or removed"
    for name, text in pages.items():
        current = (out / name).read_text(encoding="utf-8").replace("\r\n", "\n")
        assert current == text, (
            f"docs/developer/reference/{name} is out of date: run "
            "python scripts/gen_dev_reference.py"
        )


def test_reference_lists_every_module_and_command():
    gen = _generator()
    pages = gen.render_all()
    for path in (REPO / "legacy_audit").rglob("*.py"):
        assert f"`{gen._module_name(path)}`" in pages["modules.md"], path
    for command in ("scan", "report", "init", "enrich", "serve", "validate"):
        assert f"## `legacy-audit {command}`" in pages["cli.md"]
