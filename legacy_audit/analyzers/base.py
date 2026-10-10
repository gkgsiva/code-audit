"""Common analyzer interface.

An analyzer looks at one file and returns facts; it never touches the store and never looks at
other files (so its result can be cached per file). Cross-file resolution is the linker's job:
analyzers declare ``Symbol``s (what this file defines) and ``Ref``s (what it points at by name),
and the linker turns them into ``CodeEdge``s after every file is analysed.

Results are cached per file and keyed by every analyzer's ``name`` and ``version``, so bumping a
version re-analyses the files on the next run.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, ClassVar, Protocol

from pydantic import BaseModel, ConfigDict, Field

from legacy_audit.config import AuditConfig
from legacy_audit.model import ENTITIES, Fact, FileRecord


class _Model(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Symbol(_Model):
    """Something a file defines, findable by name: a class, program, copybook, job, view..."""

    kind: str
    name: str
    file: str
    line: int | None = None
    owner: str | None = Field(None, description="Id of the fact this symbol stands for")
    data: dict[str, str] = Field(default_factory=dict)


class Ref(_Model):
    """A by-name reference the linker resolves against symbols or file paths."""

    kind: str = Field(description="Edge kind: calls, includes, forwards, invokes, uses, ...")
    source: str = Field(description="Id of the referencing fact, or the file id")
    source_label: str
    target: str
    target_kinds: list[str] = Field(description="Symbol kinds, or 'path' / 'file'")
    file: str
    line: int | None = None
    report_unresolved: bool = True
    data: dict[str, str] = Field(default_factory=dict)


class Unparsed(_Model):
    start: int
    end: int
    reason: str


class Coverage(_Model):
    lines: int = 0
    unparsed: list[Unparsed] = Field(default_factory=list)

    def gap(self, start: int, end: int, reason: str) -> None:
        self.unparsed.append(Unparsed(start=start, end=end, reason=reason))


@dataclass
class Facts:
    """Everything one analyzer found in one file."""

    entities: dict[str, list[Fact]] = field(default_factory=dict)
    symbols: list[Symbol] = field(default_factory=list)
    refs: list[Ref] = field(default_factory=list)
    coverage: Coverage = field(default_factory=Coverage)

    def add(self, entity: str, fact: Fact) -> Fact:
        self.entities.setdefault(entity, []).append(fact)
        return fact

    def symbol(self, kind: str, name: str, file: str, line: int | None = None, **kw: Any) -> None:
        self.symbols.append(Symbol(kind=kind, name=name, file=file, line=line, **kw))

    def ref(self, **kw: Any) -> None:
        self.refs.append(Ref(**kw))

    def extend(self, other: Facts) -> None:
        for entity, items in other.entities.items():
            self.entities.setdefault(entity, []).extend(items)
        self.symbols.extend(other.symbols)
        self.refs.extend(other.refs)
        self.coverage.unparsed.extend(other.coverage.unparsed)

    def dump(self) -> dict[str, Any]:
        return {
            "entities": {
                e: [f.model_dump(mode="json") for f in items] for e, items in self.entities.items()
            },
            "symbols": [s.model_dump(mode="json") for s in self.symbols],
            "refs": [r.model_dump(mode="json") for r in self.refs],
            "coverage": self.coverage.model_dump(mode="json"),
        }

    @classmethod
    def load(cls, data: dict[str, Any]) -> Facts:
        return cls(
            entities={
                e: [ENTITIES[e].model_validate(i) for i in items]
                for e, items in data.get("entities", {}).items()
            },
            symbols=[Symbol.model_validate(s) for s in data.get("symbols", [])],
            refs=[Ref.model_validate(r) for r in data.get("refs", [])],
            coverage=Coverage.model_validate(data.get("coverage", {})),
        )


@dataclass
class AnalyzerContext:
    """What an analyzer may read: the file, its text and the configuration."""

    config: AuditConfig
    abs_path: Path
    text: str | None

    def read_bytes(self) -> bytes:
        return self.abs_path.read_bytes()


class Analyzer(Protocol):
    name: ClassVar[str]
    version: ClassVar[str]

    def supports(self, file: FileRecord) -> bool: ...

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts: ...


_REGISTRY: list[Analyzer] = []


def register(analyzer: Analyzer) -> Analyzer:
    if all(a.name != analyzer.name for a in _REGISTRY):
        _REGISTRY.append(analyzer)
    return analyzer


def registered() -> list[Analyzer]:
    return list(_REGISTRY)


def version_key(inventory_version: str) -> str:
    parts = [f"inventory={inventory_version}"]
    parts += [f"{a.name}={a.version}" for a in sorted(_REGISTRY, key=lambda a: a.name)]
    return ";".join(parts)
