"""What a report reads: one run folder (``run.json``, ``data/*.json``, ``logs/``), plus where the
scanned files are on disk so evidence can link to them.

Reports only display facts: every number comes from these files, nothing is recounted here
except grouping rows that are already in the data (e.g. findings per severity for a chart).
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from typing import Any
from urllib.parse import quote

ENTITIES = (
    "inventory",
    "components",
    "entry_points",
    "services",
    "screens",
    "sql_statements",
    "db_objects",
    "integrations",
    "dependencies",
    "batch_jobs",
    "libraries",
    "tech_stack",
    "findings",
    "metrics",
    "input_coverage",
)
_SLUG = re.compile(r"[^A-Za-z0-9._-]+")


def slug(name: str) -> str:
    return _SLUG.sub("-", name).strip("-").lower() or "root"


def _load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


@dataclass
class RunData:
    run_dir: Path
    run: dict[str, Any]
    summary: dict[str, Any]
    entities: dict[str, list[dict[str, Any]]]
    effort: dict[str, Any] | None
    target_mapping: list[dict[str, Any]]
    has_sbom: bool
    roots: dict[str, Path] = field(default_factory=dict)  # inventory root name -> folder
    by_id: dict[str, dict[str, Any]] = field(default_factory=dict)
    file_root: dict[str, str] = field(default_factory=dict)  # file path -> root name
    file_component: dict[str, str] = field(default_factory=dict)  # file path -> component

    @classmethod
    def load(cls, run_dir: Path, inputs_dir: Path | None = None) -> RunData:
        data = run_dir / "data"
        run = _load(run_dir / "run.json") or {}
        entities = {name: _load(data / f"{name}.json") or [] for name in ENTITIES}
        rd = cls(
            run_dir=run_dir,
            run=run,
            summary=_load(data / "audit-summary.json") or {},
            entities=entities,
            effort=_load(data / "effort.json"),
            target_mapping=_load(data / "target-mapping.json") or [],
            has_sbom=(data / "sbom.cdx.json").exists(),
        )
        for src in run.get("sources", []):
            if src.get("path"):
                rd.roots[src["name"]] = Path(src["path"])
        if inputs_dir is None and run.get("config_path"):
            inputs_dir = Path(run["config_path"]).parent / "audit-inputs"
        if inputs_dir is not None:
            rd.roots.setdefault("audit-inputs", inputs_dir.parent)
        for items in entities.values():
            for item in items:
                if "id" in item:
                    rd.by_id[item["id"]] = item
        for f in entities["inventory"]:
            rd.file_root[f["path"]] = f.get("root", "")
            if f.get("component"):
                rd.file_component[f["path"]] = f["component"]
        return rd

    # ---- helpers ------------------------------------------------------------------------------
    def items(self, name: str) -> list[dict[str, Any]]:
        return self.entities.get(name, [])

    def component_of(self, entity_id: str) -> str | None:
        """Component of a file, service, screen, SQL statement, job... (None if unknown)."""
        item = self.by_id.get(entity_id)
        if item is None:
            return None
        comp = item.get("component")
        if comp:
            return str(comp)
        path = item.get("file") or item.get("path")
        return self.file_component.get(path) if path else None

    def label(self, entity_id: str) -> str:
        item = self.by_id.get(entity_id)
        if item is None:
            return entity_id.split(":", 1)[-1]
        return str(item.get("name") or item.get("path") or item.get("target") or entity_id)

    def abs_path(self, file: str) -> Path | None:
        """Where a fact's file is on disk (None when the source is not local)."""
        root = self.file_root.get(file)
        if root and root in self.roots:
            return self.roots[root] / PurePosixPath(file)
        for base in self.roots.values():
            p = base / file
            if p.exists():
                return p
        return None

    def evidence_links(
        self, report_root: Path, file: str | None, line: int | None
    ) -> dict[str, Any]:
        """``{text, href, vscode}`` for one evidence location. ``href`` is relative to the report
        root when the source is on the same drive (else a ``file://`` URI); ``vscode`` opens the
        file at the line in VS Code."""
        if not file:
            return {"text": "", "href": None, "vscode": None}
        text = f"{file}:{line}" if line else file
        p = self.abs_path(file)
        if p is None:
            return {"text": text, "href": None, "vscode": None}
        try:
            href = Path(os.path.relpath(p, report_root)).as_posix()
            href = quote(href)
            absolute = False
        except ValueError:  # another drive on Windows
            href = p.resolve().as_uri()
            absolute = True
        posix = p.resolve().as_posix()
        if not posix.startswith("/"):
            posix = "/" + posix  # vscode://file/C:/...
        vscode = "vscode://file" + quote(posix) + (f":{line}" if line else "")
        return {"text": text, "href": href, "abs": absolute, "vscode": vscode}

    def first_evidence(self, item: dict[str, Any]) -> tuple[str | None, int | None]:
        ev = item.get("evidence") or []
        if ev:
            return ev[0].get("file"), ev[0].get("line")
        return item.get("file"), item.get("line")
