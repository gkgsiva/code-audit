"""Maven versions a module inherits from its parent chain (``dependencyManagement`` and
properties), resolved once every ``pom.xml`` has been read."""

from __future__ import annotations

import json
import re

from legacy_audit.analyzers.base import Symbol
from legacy_audit.model import Library, make_id

_PROP = re.compile(r"\$\{([^}]+)\}")


def _substitute(value: str, props: dict[str, str]) -> str:
    for _ in range(5):
        new = _PROP.sub(lambda m: props.get(m.group(1), m.group(0)), value)
        if new == value:
            break
        value = new
    return value


def resolve_versions(libraries: list[Library], symbols: list[Symbol]) -> list[Library]:
    poms: dict[str, Symbol] = {}
    managed: dict[str, str] = {}
    unresolved: dict[str, Symbol] = {}
    for s in symbols:
        if s.kind == "maven-pom":
            poms.setdefault(s.name, s)
        elif s.kind == "maven-managed":
            managed.setdefault(s.name, s.data.get("version", ""))
        elif s.kind == "maven-unresolved":
            unresolved[s.name] = s
    if not unresolved:
        return libraries

    def chain(coord: str) -> list[str]:
        out: list[str] = []
        while coord and coord in poms and coord not in out:
            out.append(coord)
            coord = poms[coord].data.get("parent", "")
        return out

    def props(coords: list[str]) -> dict[str, str]:
        merged: dict[str, str] = {}
        for c in reversed(coords):  # parent first, child overrides
            merged.update(json.loads(poms[c].data.get("properties", "{}")))
        return merged

    out: list[Library] = []
    for lib in libraries:
        sym = unresolved.get(lib.id)
        if sym is None:
            out.append(lib)
            continue
        coords = chain(sym.data.get("pom", ""))
        values = props(coords)
        raw = sym.data.get("raw", "")
        version = _substitute(raw, values) if raw else None
        if not version or "${" in version:
            for c in coords:
                v = managed.get(f"{c}|{sym.data.get('key', '')}")
                if v:
                    version = _substitute(v, values)
                    break
        if version and "${" not in version:
            lib = lib.model_copy(
                update={
                    "version": version,
                    "id": make_id("library", lib.language, lib.group or "", lib.artifact, version),
                }
            )
        out.append(lib)
    return out
