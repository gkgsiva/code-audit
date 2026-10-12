"""Per-file inventory: hash, encoding, language, category and LOC."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

from legacy_audit.inventory.encoding import decode, detect_encoding, is_binary
from legacy_audit.inventory.language import (
    ARCHIVE_EXTENSIONS,
    BINARY_EXTENSIONS,
    detect_category,
    detect_language,
)
from legacy_audit.inventory.loc import count_loc
from legacy_audit.model import Category, Evidence, FileRecord, Origin, make_id

# Bump when inventory logic changes so cached results are recomputed.
INVENTORY_VERSION = "2"  # 2: makefile.*, .project, CMakeLists.txt languages
_HEAD = 16 * 1024


@dataclass
class FileResult:
    record: FileRecord | None  # None: skipped binary
    text: str | None = None  # decoded content for analyzers (not cached)


def _sha256_stream(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def skip_by_extension(rel: str, include_binaries: bool) -> bool:
    ext = PurePosixPath(rel).suffix.lower()
    return not include_binaries and ext in BINARY_EXTENSIONS and ext not in ARCHIVE_EXTENSIONS


def analyze_file(
    abs_path: Path,
    path: str,
    root: str,
    origin: Origin,
    include_binaries: bool,
    max_bytes: int,
) -> FileResult:
    size = abs_path.stat().st_size
    if size > max_bytes:
        sha = _sha256_stream(abs_path)
        data = b""
        with abs_path.open("rb") as fh:
            head_bytes = fh.read(_HEAD)
    else:
        data = abs_path.read_bytes()
        sha = hashlib.sha256(data).hexdigest()
        head_bytes = data[:_HEAD]

    binary = is_binary(head_bytes) or PurePosixPath(path).suffix.lower() in BINARY_EXTENSIONS
    if binary:
        language = detect_language(path, None)
        if language == "binary" and not include_binaries:
            return FileResult(None)
        rec = FileRecord(
            id=make_id("file", root, path),
            path=path,
            root=root,
            origin=origin,
            language=language,
            category=Category.BINARY,
            size=size,
            sha256=sha,
            evidence=[Evidence(file=path)],
        )
        return FileResult(rec)

    encoding = detect_encoding(data or head_bytes)
    text = decode(data, encoding) if data else None
    head = text[:_HEAD] if text is not None else decode(head_bytes, encoding)
    language = detect_language(path, head)
    category = detect_category(path, language, head)
    loc = count_loc(text, language) if text is not None else None
    rec = FileRecord(
        id=make_id("file", root, path),
        path=path,
        root=root,
        origin=origin,
        language=language,
        category=category,
        size=size,
        sha256=sha,
        encoding=encoding,
        loc_code=loc.code if loc else 0,
        loc_comment=loc.comment if loc else 0,
        loc_blank=loc.blank if loc else 0,
        evidence=[Evidence(file=path)],
    )
    return FileResult(rec, text)
