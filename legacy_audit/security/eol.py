"""End-of-life flags for libraries and tech-stack items (data in ``rules/eol.yaml``)."""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from legacy_audit.model import Library, TechItem
from legacy_audit.security.versions import in_range


@dataclass(frozen=True)
class EolEntry:
    name: str
    pattern: re.Pattern[str]
    group: re.Pattern[str] | None
    versions: str
    eol_date: str | None
    replacement: str | None


def _entries(items: list[dict[str, Any]], key: str) -> list[EolEntry]:
    out = []
    for e in items:
        out.append(
            EolEntry(
                name=str(e.get("name") or e[key]),
                pattern=re.compile(str(e[key]), re.I),
                group=re.compile(str(e["group"]), re.I) if e.get("group") else None,
                versions=str(e.get("versions", "*")),
                eol_date=str(e["eol_date"]) if e.get("eol_date") else None,
                replacement=e.get("replacement"),
            )
        )
    return out


def _library_entry(lib: Library, entries: list[EolEntry]) -> EolEntry | None:
    for e in entries:
        if not e.pattern.fullmatch(lib.artifact):
            continue
        if e.group is not None and lib.group and not e.group.fullmatch(lib.group):
            continue
        if in_range(lib.version, e.versions):
            return e
    return None


def apply_eol(libraries: list[Library], tech: list[TechItem], data: dict[str, Any]) -> None:
    """Set ``eol``, ``eol_date`` and ``replacement`` in place. ``eol`` stays None when the
    version is unknown and only a version range could decide."""
    lib_entries = _entries(data.get("libraries", []), "artifact")
    tech_entries = _entries(data.get("technologies", []), "name")
    for lib in libraries:
        hit = _library_entry(lib, lib_entries)
        if hit is not None:
            lib.eol, lib.eol_date, lib.replacement = True, hit.eol_date, hit.replacement
        elif lib.eol is None and lib.version:
            lib.eol = False
    for item in tech:
        decided = False
        for e in tech_entries:
            if not e.pattern.fullmatch(item.name):
                continue
            ok = in_range(item.version, e.versions)
            if ok:
                item.eol, item.eol_date, item.replacement = True, e.eol_date, e.replacement
                decided = True
                break
            if ok is False:
                decided = True
        if (not decided and item.eol is None and item.version) or (decided and item.eol is None):
            item.eol = False
