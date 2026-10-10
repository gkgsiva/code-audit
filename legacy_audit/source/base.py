from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from legacy_audit.model import SourceInfo


class SourceError(Exception):
    """A source could not be read or fetched."""


@dataclass
class SourceRoot:
    """One folder tree to scan. ``subpaths`` limits the walk to parts of it."""

    name: str
    path: Path
    type: str = "local"
    url: str | None = None
    ref: str | None = None
    commit: str | None = None
    subpaths: list[str] = field(default_factory=list)

    def info(self) -> SourceInfo:
        return SourceInfo(
            name=self.name,
            type=self.type,
            path=self.path.as_posix(),
            url=self.url,
            ref=self.ref,
            commit=self.commit,
        )
