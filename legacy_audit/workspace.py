"""Workspace operations shared by the CLI and the GUI: create the starter files, edit
``audit.yaml`` and ``audit-overrides.yaml`` without losing the user's comments, and the fast
component pre-scan behind the GUI's component picker."""

from __future__ import annotations

import json
from collections import Counter
from dataclasses import dataclass
from importlib.resources import files
from pathlib import Path
from typing import Any

import yaml

from legacy_audit.config import INPUT_KINDS, LoadedConfig, parse_config
from legacy_audit.inventory.components import detect_components
from legacy_audit.inventory.files import skip_by_extension
from legacy_audit.inventory.language import detect_category, detect_language
from legacy_audit.inventory.walker import Filters, anchored_excludes, walk
from legacy_audit.model import FileRecord, Origin, make_id
from legacy_audit.overrides import Overrides, load_overrides

CONFIG_FILE = "audit.yaml"
OVERRIDES_FILE = "audit-overrides.yaml"


def template(name: str) -> str:
    return files("legacy_audit.templates").joinpath(name).read_text(encoding="utf-8")


@dataclass
class Created:
    path: str
    action: str  # created | kept | updated | unchanged (Copilot files)


def init_workspace(
    folder: Path, name: str | None = None, force: bool = False, copilot_layout: str = "both"
) -> list[Created]:
    """Create audit.yaml, audit-overrides.yaml, audit-inputs/<kind>/ and the Copilot files."""
    from legacy_audit.copilot.install import install

    folder = folder.resolve()
    folder.mkdir(parents=True, exist_ok=True)
    app_name = name or folder.name or "application"
    out: list[Created] = []
    for fname, content in (
        (CONFIG_FILE, template("audit.yaml").format(name=json.dumps(app_name))),
        (OVERRIDES_FILE, template("audit-overrides.yaml")),
    ):
        target = folder / fname
        if target.exists() and not force:
            out.append(Created(fname, "kept"))
            continue
        target.write_text(content, encoding="utf-8", newline="\n")
        out.append(Created(fname, "created"))
    for kind in INPUT_KINDS:
        d = folder / "audit-inputs" / kind
        if not d.exists():
            d.mkdir(parents=True)
            (d / ".gitkeep").touch()
            out.append(Created(f"audit-inputs/{kind}/", "created"))
    out += [
        Created(i.path, i.action) for i in install(folder, copilot_layout, _copilot_model(folder))
    ]
    return out


def _copilot_model(folder: Path) -> str | None:
    """``ai.copilot_model`` from the workspace's audit.yaml, if it loads."""
    from legacy_audit.config import ConfigError, load_config

    try:
        return load_config(folder / CONFIG_FILE).config.ai.copilot_model
    except (ConfigError, OSError):
        return None


# ---- audit.yaml edits ---------------------------------------------------------------------------


def _block(lines: list[str], key: str) -> tuple[int, int] | None:
    """Line range of a top-level ``key:`` block: the key line and the indented lines under it."""
    start = next((i for i, ln in enumerate(lines) if ln.startswith(f"{key}:")), None)
    if start is None:
        return None
    end = start + 1
    while end < len(lines) and (lines[end][:1] in (" ", "\t") or not lines[end].strip()):
        end += 1
    while end > start + 1 and not lines[end - 1].strip():  # keep blank lines outside
        end -= 1
    return start, end


def set_top_level(text: str, key: str, value: Any) -> str:
    """Replace one top-level block, keeping every other line (and comment) as it was."""
    dumped = yaml.safe_dump({key: value}, sort_keys=False, allow_unicode=True, width=100)
    new = dumped.rstrip("\n").split("\n")
    lines = text.split("\n")
    span = _block(lines, key)
    if span is None:
        body = text.rstrip("\n")
        return (body + "\n\n" if body else "") + "\n".join(new) + "\n"
    a, b = span
    trailing = lines[a][len(key) + 1 :].split("#", 1)
    if len(new) == 1 and len(trailing) == 2:  # keep an inline comment on a one-line value
        new[0] = f"{new[0]}  #{trailing[1]}"
    return "\n".join([*lines[:a], *new, *lines[b:]])


def update_config(path: Path, changes: dict[str, Any]) -> None:
    """Set top-level keys of audit.yaml; the result is validated before it is written."""
    text = path.read_text(encoding="utf-8")
    for key, value in changes.items():
        text = set_top_level(text, key, value)
    parse_config(yaml.safe_load(text), str(path))  # raises ConfigError, file left untouched
    path.write_text(text, encoding="utf-8", newline="\n")


def write_config_text(path: Path, text: str) -> None:
    """Replace audit.yaml with ``text`` after checking it (the GUI's raw editor)."""
    try:
        data = yaml.safe_load(text)
    except yaml.YAMLError as err:
        from legacy_audit.config import ConfigError

        raise ConfigError(f"Not valid YAML: {err}") from None
    parse_config(data, str(path))
    path.write_text(text, encoding="utf-8", newline="\n")


# ---- audit-overrides.yaml -----------------------------------------------------------------------

_OVERRIDES_HEADER = """\
# Human corrections, re-applied after analysis on every run and recorded in run.json.
# Overrides that match nothing are reported as warnings. Edited by legacy-audit serve too.
"""


def save_overrides(path: Path, overrides: Overrides) -> None:
    data = overrides.model_dump(mode="json")
    body = yaml.safe_dump(data, sort_keys=False, allow_unicode=True, width=100)
    path.write_text(_OVERRIDES_HEADER + "\n" + body, encoding="utf-8", newline="\n")


def component_exclude(path: str) -> str:
    """The files.exclude pattern the picker writes to untick a component folder."""
    return f"{path.strip('/')}/**" if path.strip("/") else "**"


def set_component_choices(
    overrides_path: Path, included: dict[str, bool], types: dict[str, str | None]
) -> Overrides:
    """Untick (exclude) or tick component folders and set or clear type overrides.

    ``included`` maps a component folder to ticked or not; ``types`` maps a component name to
    a type, or None to drop the override. Other overrides are left alone."""
    ov = load_overrides(overrides_path)
    excludes = list(ov.files.exclude)
    for folder, keep in included.items():
        pattern = component_exclude(folder)
        if keep and pattern in excludes:
            excludes.remove(pattern)
        elif not keep and pattern not in excludes:
            excludes.append(pattern)
    ov.files.exclude = excludes
    type_map = dict(ov.components.type)
    for name, t in types.items():
        if t:
            type_map[name] = t  # type: ignore[assignment]
        else:
            type_map.pop(name, None)
    ov = Overrides.model_validate(
        {
            "files": {"include": ov.files.include, "exclude": excludes},
            "components": {
                "assign": ov.components.assign,
                "rename": ov.components.rename,
                "type": type_map,
            },
        }
    )
    save_overrides(overrides_path, ov)
    return ov


# ---- Pre-scan -----------------------------------------------------------------------------------


def prescan(loaded: LoadedConfig) -> dict[str, Any]:
    """Components as a scan would detect them, from file names only (no file is read), with
    whether each is ticked and any type override. Git sources are cloned first."""
    from legacy_audit.scan import COPILOT_PATHS
    from legacy_audit.source import resolve_sources

    cfg = loaded.config
    ov = load_overrides(loaded.overrides_path)
    roots = resolve_sources(loaded)
    tool_owned = [
        loaded.output_dir,
        loaded.inputs_dir,
        loaded.path,
        loaded.overrides_path,
        *(loaded.base_dir / p for p in COPILOT_PATHS),
    ]
    multi = len(roots) > 1
    records: list[FileRecord] = []
    for root in roots:
        filters = Filters.build(
            cfg.include,
            [*cfg.exclude, *(f"!{p}" for p in ov.files.include)],
            cfg.inventory.use_default_excludes,
            anchored_excludes(root.path, tool_owned),
        )
        for wf in walk(root.name, root.path, filters, root.subpaths):
            if skip_by_extension(wf.rel, cfg.inventory.include_binaries):
                continue
            path = f"{root.name}/{wf.rel}" if multi else wf.rel
            language = detect_language(path, None)
            records.append(
                FileRecord(
                    id=make_id("file", root.name, path),
                    path=path,
                    root=root.name,
                    origin=Origin.SOURCE,
                    language=language,
                    category=detect_category(path, language, None),
                    size=0,
                    sha256="",
                )
            )
    components, file_to_comp = detect_components(records, [r.name for r in roots], cfg.components)
    langs: dict[str, Counter[str]] = {c.name: Counter() for c in components}
    for rec in records:
        comp = file_to_comp.get(rec.id)
        if comp:
            langs[comp][rec.language] += 1
    excludes = set(ov.files.exclude)
    out = []
    for c in components:
        out.append(
            {
                "name": c.name,
                "root": c.root,
                "path": c.path,
                "detected_type": str(c.type),
                "type_override": str(ov.components.type[c.name])
                if c.name in ov.components.type
                else None,
                "files": c.files,
                "languages": dict(langs[c.name].most_common(6)),
                "detected_by": c.detected_by,
                "build_files": c.build_files,
                # files.exclude applies inside every root, so the pattern is root-relative
                "included": component_exclude(c.path) not in excludes,
            }
        )
    return {
        "roots": [{"name": r.name, "path": str(r.path)} for r in roots],
        "files": len(records),
        "components": out,
        "excludes": sorted(excludes),
    }
