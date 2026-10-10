"""Rule packs: YAML files in ``rules/`` (shipped with the tool) plus an optional team folder
(``rules.packs_dir``) whose rules replace ours by id or add new ones.

A rule matches either

- facts: ``match: {entity: <collection>, where: {<field>: <condition>}}``, evaluated every run
  over the linked facts (and the metric collections ``units``, ``dead_code``, ``binaries``); or
- text: ``pattern: {regex: ..., languages: [...]}``, evaluated per file by the ``patterns``
  analyzer and cached with the file (the patterns' hash is part of the cache key).

Conditions: a plain value means equal; otherwise a mapping of ``eq``, ``ne``, ``in``,
``not_in``, ``regex``, ``gt``, ``gte``, ``lt``, ``lte``, ``exists``, ``empty``, ``contains``.
"""

from __future__ import annotations

import hashlib
import json
import re
from functools import lru_cache
from pathlib import Path
from typing import Any

import yaml
from pydantic import BaseModel, ConfigDict, Field, field_validator

PACK_FILES: dict[str, str] = {
    "risk": "risk-rules.yaml",
    "postgresql": "pg-rules.yaml",
    "dependency": "dependency-upgrades.yaml",
}
SEVERITIES = ("info", "low", "medium", "high", "critical")


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class FactMatch(_Strict):
    entity: str
    where: dict[str, Any] = Field(default_factory=dict)


class TextPattern(_Strict):
    regex: str
    ignore_case: bool = False
    multiline: bool = False
    languages: list[str] = Field(default_factory=list, description="Empty = every language")
    ignore: str | None = Field(None, description="Regex; matched text to skip")
    unless_file: str | None = Field(None, description="Regex; skip files that contain it")
    max_per_file: int = 20

    @field_validator("regex", "ignore", "unless_file")
    @classmethod
    def _compiles(cls, v: str | None) -> str | None:
        if v is not None:
            re.compile(v)
        return v

    def compiled(self) -> re.Pattern[str]:
        flags = (re.I if self.ignore_case else 0) | (re.M if self.multiline else 0)
        return re.compile(self.regex, flags)


class Rule(_Strict):
    id: str
    severity: str
    category: str
    message: str = Field(description="Python format string over the matched item's fields")
    remediation: str | None = None
    severity_field: str | None = Field(
        None, description="Item field whose value (when a valid severity) replaces `severity`"
    )
    match: FactMatch | None = None
    pattern: TextPattern | None = None

    @field_validator("severity")
    @classmethod
    def _severity(cls, v: str) -> str:
        if v not in SEVERITIES:
            raise ValueError(f"severity {v!r} is not one of {SEVERITIES}")
        return v


class Pack(_Strict):
    pack: str
    description: str | None = None
    rules: list[Rule] = Field(default_factory=list)
    data: dict[str, Any] = Field(default_factory=dict, description="Tables used by the code")


def builtin_dir() -> Path:
    """``legacy_audit/_rules`` in an installed wheel, the repository's ``rules/`` otherwise."""
    here = Path(__file__).resolve().parent.parent
    packaged = here / "_rules"
    return packaged if packaged.is_dir() else here.parent / "rules"


def _read(path: Path) -> dict[str, Any]:
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(data, dict):
        raise ValueError(f"{path}: expected a mapping")
    return data


@lru_cache(maxsize=32)
def _load(name: str, team_dir: str | None) -> Pack:
    file = PACK_FILES.get(name, f"{name}.yaml")
    base = _read(builtin_dir() / file) if (builtin_dir() / file).is_file() else {"pack": name}
    pack = Pack.model_validate(base)
    if team_dir:
        extra = Path(team_dir) / file
        if extra.is_file():
            team = Pack.model_validate({"pack": name, **_read(extra)})
            ids = {r.id for r in team.rules}
            pack.rules = [r for r in pack.rules if r.id not in ids] + team.rules
            for key, value in team.data.items():
                if isinstance(value, dict) and isinstance(pack.data.get(key), dict):
                    pack.data[key] = {**pack.data[key], **value}
                elif isinstance(value, list) and isinstance(pack.data.get(key), list):
                    pack.data[key] = value + pack.data[key]  # team entries are matched first
                else:
                    pack.data[key] = value
    return pack


def load_pack(name: str, team_dir: Path | None = None) -> Pack:
    return _load(name, str(team_dir) if team_dir else None).model_copy(deep=True)


def text_rules(enabled: list[str], team_dir: Path | None) -> list[Rule]:
    """Every text rule of the enabled packs (disabled ones too: switching a rule off is applied
    when findings are built, so it needs no re-scan)."""
    out = []
    for name in enabled:
        out += [r for r in load_pack(name, team_dir).rules if r.pattern]
    return sorted(out, key=lambda r: r.id)


def rules_key(rules: list[Rule]) -> str:
    """Hash of the text patterns: part of the file-cache key, so changing one re-scans."""
    raw = json.dumps([r.pattern.model_dump() for r in rules if r.pattern], sort_keys=True)
    ids = [r.id for r in rules]
    return hashlib.sha256((raw + json.dumps(ids)).encode("utf-8")).hexdigest()[:12]
