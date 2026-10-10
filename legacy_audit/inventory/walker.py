"""Walk source roots honouring include/exclude globs (gitignore syntax) and default excludes."""

from __future__ import annotations

import os
from collections.abc import Iterable, Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import pathspec

DEFAULT_EXCLUDES = [
    ".git/",
    ".svn/",
    ".hg/",
    "CVS/",
    ".idea/",
    ".vscode/",
    ".settings/",
    ".gradle/",
    "node_modules/",
    "bower_components/",
    "target/",
    "build/",
    "dist/",
    "out/",
    "__pycache__/",
    ".venv/",
    ".mypy_cache/",
    ".pytest_cache/",
    ".DS_Store",
    "Thumbs.db",
    ".gitkeep",
]


def to_posix(rel: str | os.PathLike[str]) -> str:
    """Relative path in POSIX form, whatever separators the input used."""
    return os.fspath(rel).replace("\\", "/").strip("/")


def make_spec(patterns: Iterable[str]) -> pathspec.PathSpec[Any]:
    return pathspec.GitIgnoreSpec.from_lines([p.replace("\\", "/") for p in patterns])


@dataclass(frozen=True)
class WalkedFile:
    root: str
    abs_path: Path
    rel: str  # relative to the root, POSIX


@dataclass
class Filters:
    include: pathspec.PathSpec[Any]
    exclude: pathspec.PathSpec[Any]

    @classmethod
    def build(
        cls,
        include: list[str],
        exclude: list[str],
        default_excludes: bool = True,
        extra_excludes: Iterable[str] = (),
    ) -> Filters:
        ex = [*(DEFAULT_EXCLUDES if default_excludes else []), *exclude, *extra_excludes]
        return cls(make_spec(include or ["**"]), make_spec(ex))

    def dir_excluded(self, rel_dir: str) -> bool:
        return self.exclude.match_file(rel_dir + "/")

    def file_selected(self, rel: str) -> bool:
        return self.include.match_file(rel) and not self.exclude.match_file(rel)


def anchored_excludes(root: Path, paths: Iterable[Path]) -> list[str]:
    """Patterns excluding tool-owned paths (output folder, config files) inside ``root``."""
    out = []
    root = root.resolve()
    for p in paths:
        try:
            rel = p.resolve().relative_to(root)
        except ValueError:
            continue
        if rel.parts:
            out.append("/" + rel.as_posix() + ("/" if p.is_dir() or not p.suffix else ""))
    return out


def walk(
    root_name: str, root: Path, filters: Filters, subpaths: list[str] | None = None
) -> Iterator[WalkedFile]:
    """Yield selected files in a stable (sorted) order."""
    starts = [root / to_posix(s) for s in subpaths] if subpaths else [root]
    for start in starts:
        if not start.exists():
            continue
        for dirpath, dirnames, filenames in os.walk(start):
            here = Path(dirpath)
            rel_dir = to_posix(here.relative_to(root).as_posix()) if here != root else ""
            keep = []
            for d in sorted(dirnames):
                rel = f"{rel_dir}/{d}" if rel_dir else d
                if (here / d).is_symlink() or filters.dir_excluded(rel):
                    continue
                keep.append(d)
            dirnames[:] = keep
            for f in sorted(filenames):
                rel = f"{rel_dir}/{f}" if rel_dir else f
                abs_path = here / f
                if abs_path.is_symlink() or not abs_path.is_file():
                    continue
                if filters.file_selected(rel):
                    yield WalkedFile(root_name, abs_path, rel)
