"""Walk source roots honouring include/exclude globs (gitignore syntax) and default excludes."""

from __future__ import annotations

import os
import re
import sys
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


# Folders that compilers and IDEs write into. They are skipped when what they hold is compiled
# output (class files, objects, executables) and no source or script: Eclipse's ``bin/`` holds
# .class files plus copies of the .properties/.xml next to the sources, so scanning it counts
# everything twice and makes ``bin`` look like a component. A Unix ``bin/`` of ksh scripts is
# kept, because scripts are source.
BUILD_OUTPUT_DIRS = {
    "bin", "classes", "test-classes", "obj", "objs", "debug", "release", "x64", "x86", "win32",
    "lib64", "build-output", "output",
}  # fmt: skip
COMPILED_EXTENSIONS = {
    ".class", ".o", ".obj", ".so", ".sl", ".a", ".dll", ".exe", ".lib", ".pdb", ".ilk", ".exp",
    ".pyc", ".pyo", ".gnt", ".int", ".idy", ".lbr", ".dylib",
}  # fmt: skip
SOURCE_EXTENSIONS = {
    ".java", ".jsp", ".jspf", ".c", ".h", ".cpp", ".cc", ".cxx", ".hpp", ".pc", ".pco", ".cbl",
    ".cob", ".cpy", ".jcl", ".sh", ".ksh", ".bash", ".csh", ".zsh", ".pl", ".pm", ".py", ".bat",
    ".cmd", ".sql", ".pks", ".pkb", ".jil", ".js", ".ts", ".html", ".htm", ".groovy",
}  # fmt: skip
# Precompilers write a generated file next to its source; the source is what we analyse.
GENERATED_BY = {
    ".c": (".pc", "the Pro*C precompiler"),
    ".cpp": (".pc", "the Pro*C precompiler"),
    ".cob": (".pco", "the Pro*COBOL precompiler"),
    ".cbl": (".pco", "the Pro*COBOL precompiler"),
    ".java": (".sqlj", "the SQLJ translator"),
}
_OUTPUT_ENTRY = re.compile(r"<classpathentry\b[^>]*\bkind=\"output\"[^>]*\bpath=\"([^\"]+)\"")
_SCAN_LIMIT = 5000  # files looked at to decide whether a folder is build output


@dataclass(frozen=True)
class SkippedPath:
    """A folder or file the walker left out on purpose (reported, never silent)."""

    root: str
    rel: str
    reason: str
    files: int = 1


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
    skip_build_output: bool = True

    @classmethod
    def build(
        cls,
        include: list[str],
        exclude: list[str],
        default_excludes: bool = True,
        extra_excludes: Iterable[str] = (),
        skip_build_output: bool = True,
    ) -> Filters:
        ex = [*(DEFAULT_EXCLUDES if default_excludes else []), *exclude, *extra_excludes]
        return cls(make_spec(include or ["**"]), make_spec(ex), skip_build_output)

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


def extended_path(path: Path | str, windows: bool = sys.platform == "win32") -> str:
    r"""On Windows, the ``\\?\`` form of an absolute path, which is not limited to 260
    characters (legacy trees nest deeply). Elsewhere the path unchanged."""
    raw = str(path)
    if not windows or raw.startswith("\\\\?\\"):
        return raw
    if sys.platform == "win32":
        raw = os.path.abspath(raw)
    raw = raw.replace("/", "\\")
    if raw.startswith("\\\\"):  # UNC share: \\server\share -> \\?\UNC\server\share
        return "\\\\?\\UNC\\" + raw[2:]
    return "\\\\?\\" + raw


def _is_compiled(path: Path, ext: str) -> bool | None:
    """True for compiled output, False for source, None for anything else (resources)."""
    if ext in COMPILED_EXTENSIONS:
        return True
    if ext in SOURCE_EXTENSIONS:
        return False
    if ext:
        return None
    try:  # no extension: an executable (ELF, PE, XCOFF) or a script
        with path.open("rb") as fh:
            head = fh.read(4)
    except OSError:
        return None
    if head.startswith(b"#!"):
        return False
    if head.startswith((b"\x7fELF", b"MZ", b"\x01\xdf", b"\x01\xf7", b"\xcf\xfa", b"\xfe\xed")):
        return True
    return None


def build_output_files(folder: Path) -> int:
    """Number of files in ``folder`` when it looks like compiler or IDE output (compiled files
    and no source or script), else 0."""
    compiled = files = 0
    for dirpath, dirnames, filenames in os.walk(folder):
        dirnames[:] = [d for d in dirnames if not (Path(dirpath) / d).is_symlink()]
        for f in filenames:
            files += 1
            kind = _is_compiled(Path(dirpath) / f, os.path.splitext(f)[1].lower())
            if kind is False:
                return 0
            compiled += kind is True
            if files >= _SCAN_LIMIT:
                return files if compiled else 0
    return files if compiled else 0


def eclipse_output_dirs(folder: Path) -> list[str]:
    """Output folders declared in an Eclipse ``.classpath`` (``kind="output"``), relative to
    ``folder``."""
    try:
        text = (folder / ".classpath").read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    return [to_posix(m) for m in _OUTPUT_ENTRY.findall(text) if not os.path.isabs(m)]


def walk(
    root_name: str,
    root: Path,
    filters: Filters,
    subpaths: list[str] | None = None,
    skipped: list[SkippedPath] | None = None,
) -> Iterator[WalkedFile]:
    """Yield selected files in a stable (sorted) order. Symlinked folders and files are not
    followed (no loops, nothing outside the tree); on Windows paths over 260 characters work.

    With ``filters.skip_build_output``, compiler and IDE output folders and files a precompiler
    generated from a source next to them are left out and appended to ``skipped``."""
    root = Path(extended_path(root))
    starts = [root / to_posix(s) for s in subpaths] if subpaths else [root]
    declared: set[str] = set()  # Eclipse output folders, relative to the root
    for start in starts:
        if not start.exists():
            continue
        for dirpath, dirnames, filenames in os.walk(start):
            here = Path(dirpath)
            rel_dir = to_posix(here.relative_to(root).as_posix()) if here != root else ""
            if filters.skip_build_output and ".classpath" in filenames:
                for out in eclipse_output_dirs(here):
                    declared.add(f"{rel_dir}/{out}" if rel_dir else out)
            keep = []
            for d in sorted(dirnames):
                rel = f"{rel_dir}/{d}" if rel_dir else d
                if (here / d).is_symlink() or filters.dir_excluded(rel):
                    continue
                if filters.skip_build_output:
                    reason = ""
                    if rel in declared:
                        n = build_output_files(here / d) or sum(
                            len(fs) for _, _, fs in os.walk(here / d)
                        )
                        reason = "Eclipse output folder (.classpath)"
                    elif d.lower() in BUILD_OUTPUT_DIRS:
                        n = build_output_files(here / d)
                        reason = "compiled output (no source or script inside)" if n else ""
                    if reason:
                        if skipped is not None:
                            skipped.append(SkippedPath(root_name, rel + "/", reason, n))
                        continue
                keep.append(d)
            dirnames[:] = keep
            names = {f.lower() for f in filenames}
            for f in sorted(filenames):
                rel = f"{rel_dir}/{f}" if rel_dir else f
                abs_path = here / f
                if abs_path.is_symlink() or not abs_path.is_file():
                    continue
                if not filters.file_selected(rel):
                    continue
                stem, ext = os.path.splitext(f)
                gen = GENERATED_BY.get(ext.lower())
                if filters.skip_build_output and gen and (stem + gen[0]).lower() in names:
                    if skipped is not None:
                        skipped.append(SkippedPath(root_name, rel, f"generated by {gen[1]}"))
                    continue
                yield WalkedFile(root_name, abs_path, rel)
