"""Run one analyzer on an in-memory file."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from legacy_audit.analyzers.base import Analyzer, AnalyzerContext, Facts
from legacy_audit.config import AuditConfig
from legacy_audit.inventory.language import detect_category, detect_language
from legacy_audit.model import FileRecord


def config(**extra: Any) -> AuditConfig:
    return AuditConfig.model_validate({"application": {"name": "test"}, **extra})


def record(path: str, language: str | None = None, text: str = "") -> FileRecord:
    lang = language or detect_language(path, text[:4096])
    category = detect_category(path, lang, text[:4096])
    return FileRecord(id=f"file:{path}", path=path, root="src", language=lang,
                      category=category, size=len(text), sha256="0")  # fmt: skip


def run(analyzer: Analyzer, path: str, text: str, language: str | None = None,
        cfg: AuditConfig | None = None, abs_path: Path | None = None) -> Facts:  # fmt: skip
    rec = record(path, language, text)
    assert analyzer.supports(rec), f"{analyzer.name} does not support {path} ({rec.language})"
    ctx = AnalyzerContext(config=cfg or config(), abs_path=abs_path or Path(path), text=text)
    return analyzer.analyze(rec, ctx)


def entities(facts: Facts, name: str) -> list[Any]:
    return facts.entities.get(name, [])
