"""``audit-overrides.yaml``: human corrections re-applied on every run.

```yaml
files:
  exclude: ["legacy/old-copy/**"]    # dropped from all facts
  include: ["build/generated-sql/"]  # re-included despite default excludes
components:
  assign: {"src/reports/**": reports}  # move files to a (possibly new) component
  rename: {web-old: customer-portal}
  type: {customer-portal: web}
```
Each override is reported in ``run.json`` with how many items it matched; unused ones are
reported as warnings so stale overrides are noticed.
"""

from __future__ import annotations

from collections import defaultdict
from pathlib import Path
from typing import Any

import yaml
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from legacy_audit.config import ConfigError, _format_errors
from legacy_audit.inventory.components import infer_type
from legacy_audit.inventory.walker import make_spec
from legacy_audit.model import Component, ComponentType, Evidence, FileRecord, make_id


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class FileOverrides(_Strict):
    include: list[str] = Field(default_factory=list)
    exclude: list[str] = Field(default_factory=list)


class ComponentOverrides(_Strict):
    assign: dict[str, str] = Field(default_factory=dict)
    rename: dict[str, str] = Field(default_factory=dict)
    type: dict[str, ComponentType] = Field(default_factory=dict)


class Overrides(_Strict):
    files: FileOverrides = Field(default_factory=FileOverrides)
    components: ComponentOverrides = Field(default_factory=ComponentOverrides)


def load_overrides(path: Path) -> Overrides:
    if not path.is_file():
        return Overrides()
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except yaml.YAMLError as err:
        raise ConfigError(f"{path} is not valid YAML: {err}") from None
    try:
        return Overrides.model_validate(data)
    except ValidationError as err:
        raise ConfigError(f"{path} is invalid:\n{_format_errors(err)}") from None


def apply_component_overrides(
    ov: Overrides,
    records: list[FileRecord],
    components: list[Component],
    report: dict[str, Any],
    warnings: list[str],
) -> list[Component]:
    """Apply assign, rename and type overrides; update file records in place."""
    by_name = {c.name: c for c in components}

    # assign: move files matching a glob to a component (created if it does not exist).
    assigned: dict[str, int] = {}
    for pattern, target in ov.components.assign.items():
        spec = make_spec([pattern])
        hits = [r for r in records if r.component is not None and spec.match_file(r.path)]
        assigned[pattern] = len(hits)
        if not hits:
            warnings.append(f"Override components.assign '{pattern}' matched no files")
            continue
        if target not in by_name:
            by_name[target] = Component(
                id=make_id("component", "override", target),
                name=target,
                root=hits[0].root,
                path="",
                detected_by="override",
                evidence=[Evidence(file=hits[0].path)],
            )
        for r in hits:
            r.component = target

    renamed: dict[str, int] = {}
    for old, new in ov.components.rename.items():
        comp = by_name.pop(old, None)
        renamed[old] = 1 if comp else 0
        if comp is None:
            warnings.append(f"Override components.rename '{old}' matches no component")
            continue
        comp.name = new
        by_name[new] = comp
        for r in records:
            if r.component == old:
                r.component = new

    typed: dict[str, int] = {}
    for name, ctype in ov.components.type.items():
        comp = by_name.get(name)
        typed[name] = 1 if comp else 0
        if comp is None:
            warnings.append(f"Override components.type '{name}' matches no component")
            continue
        comp.type = ctype
        comp.detected_by = (
            comp.detected_by if comp.detected_by == "override" else (f"{comp.detected_by}+override")
        )

    # Recompute per-component totals after files moved.
    members: dict[str, list[FileRecord]] = defaultdict(list)
    for r in records:
        if r.component:
            members[r.component].append(r)
    result = []
    for comp in by_name.values():
        files = members.get(comp.name, [])
        if not files:
            continue
        comp.files = len(files)
        comp.loc_code = sum(f.loc_code for f in files)
        if comp.detected_by == "override" and comp.name not in ov.components.type:
            comp.type = infer_type(files)
        result.append(comp)

    report.update(
        {
            "file": "audit-overrides.yaml",
            "files_include": ov.files.include,
            "files_exclude": ov.files.exclude,
            "components_assign": assigned,
            "components_rename": renamed,
            "components_type": typed,
        }
    )
    return sorted(result, key=lambda c: c.name)
