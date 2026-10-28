"""Inventory and analysis of one file: the unit of work the scan runs in parallel.

``setup`` is called once per worker process with what every file needs; ``process`` reads one
file and returns what goes into the file cache. Nothing here touches the store or the cache, so
the result is the same in a worker as in the main process.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from legacy_audit.analyzers import Analyzer, AnalyzerContext, Facts, registered
from legacy_audit.config import AuditConfig
from legacy_audit.inventory.files import analyze_file
from legacy_audit.model import Evidence, FileRecord, Finding, Origin, Severity, make_id

log = logging.getLogger("legacy_audit")


@dataclass
class Job:
    abs_path: str
    path: str  # as reported (root-prefixed when there are several roots)
    root: str
    origin: str


@dataclass
class Done:
    size: int = 0
    mtime_ns: int = 0
    sha256: str = ""
    payload: dict[str, Any] = field(default_factory=dict)  # file-cache payload
    warnings: list[str] = field(default_factory=list)
    error: str | None = None  # the file could not be read at all


@dataclass
class _State:
    config: AuditConfig
    text_rules: list[Any]
    max_bytes: int
    analyzers: list[Analyzer]


_state: _State | None = None


def setup(config: AuditConfig, text_rules: list[Any], max_bytes: int) -> None:
    global _state
    _state = _State(config, text_rules, max_bytes, registered())


def run_analyzer(
    a: Analyzer, rec: FileRecord, ctx: AnalyzerContext, text: str | None, warnings: list[str]
) -> Facts:
    """Run one analyzer; a crash is a coverage gap and a finding, never a failed scan."""
    try:
        return a.analyze(rec, ctx)
    except Exception as err:
        warnings.append(f"Analyzer {a.name} failed on {rec.path}: {type(err).__name__}: {err}")
        facts = Facts()
        lines = text.count("\n") + 1 if text else 1
        facts.coverage.lines = lines
        facts.coverage.gap(1, lines, f"analyzer error: {type(err).__name__}")
        facts.add(
            "findings",
            Finding(
                id=make_id("finding", "analyzer-error", a.name, rec.path),
                rule_id="analyzer-error",
                severity=Severity.LOW,
                category="coverage",
                file=rec.path,
                message=f"The {a.name} analyzer could not read this file ({type(err).__name__}); "
                "it is reported as not parsed.",
                evidence=[Evidence(file=rec.path)],
            ),
        )
        return facts


def process(job: Job) -> Done:
    assert _state is not None, "worker.setup() was not called"
    cfg = _state.config
    abs_path = Path(job.abs_path)
    try:
        st = abs_path.stat()
        result = analyze_file(
            abs_path,
            job.path,
            job.root,
            Origin(job.origin),
            cfg.inventory.include_binaries,
            _state.max_bytes,
        )
    except OSError as err:  # permission denied, vanished, path too long...
        return Done(error=f"{type(err).__name__}: {err.strerror or err}")
    rec = result.record
    done = Done(size=st.st_size, mtime_ns=st.st_mtime_ns, sha256=rec.sha256 if rec else "")
    per_analyzer: dict[str, Facts] = {}
    if rec is not None and (result.text is not None or rec.language == "archive"):
        ctx = AnalyzerContext(
            config=cfg, abs_path=abs_path, text=result.text, text_rules=_state.text_rules
        )
        for a in _state.analyzers:
            if a.supports(rec):
                per_analyzer[a.name] = run_analyzer(a, rec, ctx, result.text, done.warnings)
    done.payload = {
        "record": rec.model_dump(mode="json") if rec else None,
        "facts": {name: f.dump() for name, f in per_analyzer.items()},
    }
    return done
