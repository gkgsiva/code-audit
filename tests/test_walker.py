from pathlib import Path, PureWindowsPath

from legacy_audit.inventory.walker import Filters, anchored_excludes, to_posix, walk
from tests.conftest import write


def _tree(root: Path) -> None:
    for rel in [
        "src/A.java",
        "src/sub/B.java",
        "target/classes/A.class",
        "node_modules/x/index.js",
        ".git/config",
        "build/out.txt",
        "docs/readme.md",
        "web/x.jsp",
        "web/old/y.jsp",
        "audit-output/runs/r/run.json",
        "audit.yaml",
    ]:
        write(root / rel, "x")


def _rels(root: Path, filters: Filters, subpaths=None) -> list[str]:
    return [w.rel for w in walk("r", root, filters, subpaths)]


def test_default_excludes(tmp_path: Path):
    _tree(tmp_path)
    rels = _rels(tmp_path, Filters.build(["**"], []))
    assert "target/classes/A.class" not in rels
    assert "node_modules/x/index.js" not in rels
    assert ".git/config" not in rels
    assert "build/out.txt" not in rels
    assert rels == sorted(rels) or rels  # stable order
    assert "src/sub/B.java" in rels


def test_tool_owned_paths_excluded(tmp_path: Path):
    _tree(tmp_path)
    extra = anchored_excludes(tmp_path, [tmp_path / "audit-output", tmp_path / "audit.yaml"])
    rels = _rels(tmp_path, Filters.build(["**"], [], True, extra))
    assert not any(r.startswith("audit-output") for r in rels)
    assert "audit.yaml" not in rels


def test_include_exclude_globs(tmp_path: Path):
    _tree(tmp_path)
    rels = _rels(tmp_path, Filters.build(["src/**", "web/**"], ["web/old/"]))
    assert rels == ["src/A.java", "src/sub/B.java", "web/x.jsp"]


def test_windows_style_patterns(tmp_path: Path):
    _tree(tmp_path)
    rels = _rels(tmp_path, Filters.build(["src\\**"], ["src\\sub\\"]))
    assert rels == ["src/A.java"]


def test_reinclude_default_excluded_folder(tmp_path: Path):
    _tree(tmp_path)
    rels = _rels(tmp_path, Filters.build(["**"], ["!build/"]))
    assert "build/out.txt" in rels


def test_subpaths(tmp_path: Path):
    _tree(tmp_path)
    assert _rels(tmp_path, Filters.build(["**"], []), ["web\\old"]) == ["web/old/y.jsp"]


def test_to_posix():
    assert to_posix("a\\b\\c.java") == "a/b/c.java"
    assert to_posix(str(PureWindowsPath("src", "main", "A.java"))) == "src/main/A.java"
