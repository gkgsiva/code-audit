"""The shared fact model.

Every fact has a stable ``id`` (a deterministic hash of its kind and key fields, so ids survive
re-runs) and ``evidence`` pointing at the file and line it came from. Fields follow
``docs/plan.md`` § Data model. Models not filled in phase 1 are defined now so the schemas are
stable from the start.
"""

from __future__ import annotations

import hashlib
from enum import StrEnum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


def make_id(kind: str, *parts: object) -> str:
    """Deterministic id: ``<kind>:<16 hex chars>`` from the kind and key fields."""
    raw = "\x1f".join([kind, *(str(p) for p in parts)])
    return f"{kind}:{hashlib.sha256(raw.encode('utf-8')).hexdigest()[:16]}"


class FactModel(BaseModel):
    model_config = ConfigDict(extra="forbid", use_enum_values=True)


class Evidence(FactModel):
    """Where a fact was found. ``line`` is None when the fact is about the whole file."""

    file: str
    line: int | None = None
    snippet: str | None = None


class Fact(FactModel):
    id: str
    evidence: list[Evidence] = Field(default_factory=list)


class Origin(StrEnum):
    SOURCE = "source"
    INPUT = "input"


class Category(StrEnum):
    SOURCE = "source"
    CONFIG = "config"
    BUILD = "build"
    SQL = "sql"
    SCREEN = "screen"
    JOB = "job"
    SCRIPT = "script"
    BINARY = "binary"
    TEST = "test"
    GENERATED = "generated"
    DOC = "doc"
    OTHER = "other"


class FileRecord(Fact):
    path: str = Field(description="Path relative to the source root, POSIX style")
    root: str = Field(description="Name of the source root the file belongs to")
    origin: Origin = Origin.SOURCE
    component: str | None = None
    language: str
    category: Category
    size: int
    sha256: str
    encoding: str | None = None
    loc_code: int = 0
    loc_comment: int = 0
    loc_blank: int = 0


class ComponentType(StrEnum):
    WEB = "web"
    BATCH = "batch"
    CFT = "cft"
    SCRIPT = "script"
    REPORT = "report"
    LIBRARY = "library"
    DB = "db"
    UNKNOWN = "unknown"


class Component(Fact):
    name: str
    type: ComponentType = ComponentType.UNKNOWN
    root: str = Field(description="Source root name")
    path: str = Field(description="Component folder relative to the source root ('' = root)")
    build_files: list[str] = Field(default_factory=list)
    technology: list[str] = Field(default_factory=list)
    files: int = 0
    loc_code: int = 0
    entry_points: list[str] = Field(default_factory=list)
    detected_by: str = Field(description="build-file, folder, root, config or override")


class EntryPoint(Fact):
    name: str
    kind: str
    file: str
    component: str | None = None


class Service(Fact):
    name: str
    kind: str
    component: str | None = None
    inputs: list[str] = Field(default_factory=list)
    outputs: list[str] = Field(default_factory=list)
    complexity: str | None = None
    tables: list[str] = Field(default_factory=list)
    external_calls: list[str] = Field(default_factory=list)


class Screen(Fact):
    name: str
    technology: str
    action: str | None = None
    fields_count: int = 0
    component: str | None = None
    complexity: str | None = None
    partial: bool = False


class PgGrade(StrEnum):
    AUTO = "auto"
    REVIEW = "review"
    MANUAL = "manual"


class SqlStatement(Fact):
    file: str
    line: int
    type: str
    tables: list[str] = Field(default_factory=list)
    dialect: str | None = None
    dynamic: bool = False
    pg_grade: PgGrade | None = None
    reason: str | None = None
    normalized: str | None = None
    component: str | None = None


class DbObject(Fact):
    name: str
    object_type: str
    referenced_by: list[str] = Field(default_factory=list)
    crud: dict[str, list[str]] = Field(
        default_factory=dict, description="Operation (C/R/U/D) to referencing ids"
    )


class Integration(Fact):
    type: str
    target: str
    direction: str
    protocol: str | None = None
    usages: int = 0
    source_files: list[str] = Field(default_factory=list)


class CodeEdge(Fact):
    source: str
    target: str
    kind: str = Field(description="calls, includes, forwards, reads, writes or triggers")
    resolved: bool = True


class BatchJob(Fact):
    name: str
    scheduler: str
    schedule: str | None = None
    box: str | None = None
    steps: list[str] = Field(default_factory=list)
    programs: list[str] = Field(default_factory=list)
    datasets: list[str] = Field(default_factory=list)
    predecessors: list[str] = Field(default_factory=list)


class Library(Fact):
    group: str | None = None
    artifact: str
    version: str | None = None
    scope: str | None = None
    license: str | None = None
    declared: bool = True
    where_used: list[str] = Field(default_factory=list)
    cves: list[str] = Field(default_factory=list)
    eol: bool | None = None
    recommended_version: str | None = None


class TechItem(Fact):
    name: str
    version: str | None = None
    category: str
    eol: bool | None = None


class Severity(StrEnum):
    INFO = "info"
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


class Finding(Fact):
    rule_id: str
    severity: Severity
    category: str
    file: str | None = None
    line: int | None = None
    message: str


class Metric(Fact):
    component: str
    loc_code: int = 0
    loc_comment: int = 0
    loc_blank: int = 0
    files: int = 0
    complexity_avg: float | None = None
    complexity_max: int | None = None
    duplication_pct: float | None = None
    test_files: int = 0
    dead_code_candidates: int = 0


class CoverageStatus(StrEnum):
    PRESENT = "present"
    MISSING = "missing"


class InputCoverage(Fact):
    kind: str
    label: str
    status: CoverageStatus
    path: str
    file_count: int = 0


class SourceInfo(FactModel):
    name: str
    type: str
    path: str
    url: str | None = None
    ref: str | None = None
    commit: str | None = None


class RunInfo(FactModel):
    """Describes one scan run; written as ``run.json``. Not part of the compared facts."""

    run_id: str
    tool_version: str
    started_at: str
    finished_at: str | None = None
    config_path: str
    config_hash: str
    application: str
    sources: list[SourceInfo] = Field(default_factory=list)
    full_scan: bool = False
    files_total: int = 0
    files_analyzed: int = 0
    files_cached: int = 0
    files_removed: int = 0
    timings: dict[str, float] = Field(default_factory=dict)
    coverage: list[InputCoverage] = Field(default_factory=list)
    overrides: dict[str, Any] = Field(default_factory=dict)
    counting_rules: dict[str, str] = Field(default_factory=dict)
    warnings: list[str] = Field(default_factory=list)


# Every fact entity: file stem in the data folder -> model. Order is the export order.
ENTITIES: dict[str, type[Fact]] = {
    "inventory": FileRecord,
    "components": Component,
    "entry_points": EntryPoint,
    "services": Service,
    "screens": Screen,
    "sql_statements": SqlStatement,
    "db_objects": DbObject,
    "integrations": Integration,
    "dependencies": CodeEdge,
    "batch_jobs": BatchJob,
    "libraries": Library,
    "tech_stack": TechItem,
    "findings": Finding,
    "metrics": Metric,
    "input_coverage": InputCoverage,
}

# Models exported as JSON Schema (facts plus run info and evidence).
SCHEMA_MODELS: dict[str, type[BaseModel]] = {
    "evidence": Evidence,
    "file_record": FileRecord,
    "component": Component,
    "entry_point": EntryPoint,
    "service": Service,
    "screen": Screen,
    "sql_statement": SqlStatement,
    "db_object": DbObject,
    "integration": Integration,
    "code_edge": CodeEdge,
    "batch_job": BatchJob,
    "library": Library,
    "tech_item": TechItem,
    "finding": Finding,
    "metric": Metric,
    "input_coverage": InputCoverage,
    "run_info": RunInfo,
}
