"""Per-file result cache keyed by (root, path) and validated by size/mtime, SHA-256 and the
analyzer version key. Linking, metrics, rules and totals are never cached."""

from __future__ import annotations

import json
import sqlite3
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

_SCHEMA = """
CREATE TABLE IF NOT EXISTS files (
    root TEXT NOT NULL,
    path TEXT NOT NULL,
    size INTEGER NOT NULL,
    mtime_ns INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    version TEXT NOT NULL,
    payload TEXT NOT NULL,
    PRIMARY KEY (root, path)
)
"""


@dataclass
class CacheEntry:
    size: int
    mtime_ns: int
    sha256: str
    version: str
    payload: dict[str, Any] = field(default_factory=dict)


class FileCache:
    def __init__(self, path: Path, version: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self.version = version
        self.conn = sqlite3.connect(path)
        self.conn.execute(_SCHEMA)
        self._seen: set[tuple[str, str]] = set()

    def get(self, root: str, path: str) -> CacheEntry | None:
        self._seen.add((root, path))
        row = self.conn.execute(
            "SELECT size, mtime_ns, sha256, version, payload FROM files WHERE root=? AND path=?",
            (root, path),
        ).fetchone()
        if row is None or row[3] != self.version:
            return None
        return CacheEntry(row[0], row[1], row[2], row[3], json.loads(row[4]))

    def put(self, root: str, path: str, entry: CacheEntry) -> None:
        self._seen.add((root, path))
        self.conn.execute(
            "INSERT OR REPLACE INTO files VALUES (?, ?, ?, ?, ?, ?, ?)",
            (
                root,
                path,
                entry.size,
                entry.mtime_ns,
                entry.sha256,
                self.version,
                json.dumps(entry.payload, sort_keys=True),
            ),
        )

    def prune(self) -> int:
        """Drop entries for files not seen in this run. Returns how many were dropped."""
        rows = self.conn.execute("SELECT root, path FROM files").fetchall()
        gone = [(r, p) for r, p in rows if (r, p) not in self._seen]
        self.conn.executemany("DELETE FROM files WHERE root=? AND path=?", gone)
        return len(gone)

    def commit(self) -> None:
        self.conn.commit()

    def close(self) -> None:
        self.conn.commit()
        self.conn.close()
