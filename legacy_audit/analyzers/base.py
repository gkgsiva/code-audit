"""Common analyzer interface.

An analyzer looks at one file and returns facts; it never touches the store. Results are cached
per file and keyed by every analyzer's ``name`` and ``version``, so bumping a version re-analyzes
the files it supports on the next run.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, ClassVar, Protocol

from legacy_audit.model import FileRecord


@dataclass
class AnalyzerContext:
    """Read-only information an analyzer may use."""

    application: str
    root_paths: dict[str, str] = field(default_factory=dict)


@dataclass
class Facts:
    """Facts from one file, keyed by entity name (see ``model.ENTITIES``)."""

    items: dict[str, list[dict[str, Any]]] = field(default_factory=dict)

    def add(self, entity: str, fact: dict[str, Any]) -> None:
        self.items.setdefault(entity, []).append(fact)


class Analyzer(Protocol):
    name: ClassVar[str]
    version: ClassVar[str]

    def supports(self, file: FileRecord) -> bool: ...

    def analyze(self, file: FileRecord, text: str, ctx: AnalyzerContext) -> Facts: ...


_REGISTRY: list[Analyzer] = []


def register(analyzer: Analyzer) -> Analyzer:
    _REGISTRY.append(analyzer)
    return analyzer


def registered() -> list[Analyzer]:
    return list(_REGISTRY)


def version_key(inventory_version: str) -> str:
    parts = [f"inventory={inventory_version}"]
    parts += [f"{a.name}={a.version}" for a in sorted(_REGISTRY, key=lambda a: a.name)]
    return ";".join(parts)
