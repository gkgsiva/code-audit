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
    ONLINE_MAINFRAME = "online-mainframe"
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
    kind: str = Field(description="main, scheduler, filter, listener, spring-boot-app, ...")
    file: str
    line: int | None = None
    component: str | None = None


class Service(Fact):
    name: str
    kind: str = Field(
        description="struts-action, spring-handler, servlet, soap-operation, rest-operation, "
        "ejb-method, cics-transaction, cobol-program, batch-job, c-executable, webflow"
    )
    component: str | None = None
    file: str | None = None
    handler: str | None = Field(None, description="Class, method or program implementing it")
    url: str | None = Field(None, description="URL pattern or path, when it has one")
    screens: list[str] = Field(default_factory=list, description="Screens it renders")
    inputs: list[str] = Field(default_factory=list)
    outputs: list[str] = Field(default_factory=list)
    complexity: str | None = Field(None, description="Simple, Medium, Complex or Very complex")
    complexity_score: float | None = None
    fan_in: int = Field(0, description="Resolved edges into the service")
    fan_out: int = Field(0, description="Resolved edges out of the service and its own file")
    tables: list[str] = Field(default_factory=list)
    external_calls: list[str] = Field(default_factory=list)


class Screen(Fact):
    name: str
    technology: str
    file: str | None = None
    action: str | None = None
    fields_count: int = 0
    component: str | None = None
    complexity: str | None = None
    complexity_score: float | None = None
    partial: bool = False
    targeted_by: list[str] = Field(
        default_factory=list, description="Services or pages that forward to / include it"
    )


class PgGrade(StrEnum):
    AUTO = "auto"
    REVIEW = "review"
    MANUAL = "manual"


class SqlStatement(Fact):
    file: str
    line: int
    type: str = Field(description="SELECT, INSERT, UPDATE, DELETE, MERGE, CALL, ...")
    source: str = Field(
        "unknown",
        description="jdbc, jdbc-template, hql, jpql, native, mybatis, ibatis, hibernate, "
        "spring-data, spring-xml, sql-file, plsql, embedded (Pro*C/COBOL), script, java-string",
    )
    text: str = Field("", description="Statement text (bind variables as written), max 4000")
    tables: list[str] = Field(default_factory=list)
    procedures: list[str] = Field(default_factory=list, description="Procedures it calls")
    dialect: str | None = None
    dynamic: bool = False
    parsed: bool = True
    fingerprint: str = Field("", description="Hash of the normalized text; equal = distinct")
    normalized: str | None = None
    vendor_features: list[str] = Field(default_factory=list)
    pg_grade: PgGrade | None = None
    reason: str | None = Field(None, description="Why the grade is not auto")
    pg_text: str | None = Field(None, description="Suggested PostgreSQL text (auto and review)")
    component: str | None = None


class DbObject(Fact):
    name: str
    object_type: str = Field(
        description="table, view, sequence, index, synonym, package, procedure, function, "
        "trigger, type"
    )
    defined_in: str | None = Field(None, description="File holding its DDL, if scanned")
    members: list[str] = Field(default_factory=list, description="Package procedures")
    referenced_by: list[str] = Field(default_factory=list)
    crud: dict[str, list[str]] = Field(
        default_factory=dict, description="Operation (C/R/U/D) to referencing ids"
    )
    pg_grade: PgGrade | None = Field(None, description="Set for packages, procedures, ...")
    reason: str | None = None


class Integration(Fact):
    type: str = Field(
        description="HTTP, SOAP, MQ, MQ-CHANNEL, MQ-QMGR, JMS, FTP, SFTP, SCP, CFT, SMTP, "
        "LDAP, SOCKET, FILE, DB"
    )
    target: str
    direction: str = Field(description="outbound, inbound, both, defined or unknown")
    protocol: str | None = None
    usages: int = 0
    source_files: list[str] = Field(default_factory=list)
    producers: list[str] = Field(default_factory=list, description="Files that send/write")
    consumers: list[str] = Field(default_factory=list, description="Files that receive/read")
    components: list[str] = Field(default_factory=list)
    details: dict[str, str] = Field(default_factory=dict)


class CodeEdge(Fact):
    source: str
    target: str
    kind: str = Field(
        description="calls, uses, invokes, runs, triggers, includes, forwards, renders, submits, "
        "links, reads, writes, contains or depends-on"
    )
    resolved: bool = True


class BatchJob(Fact):
    name: str
    scheduler: str = Field(description="autosys, jcl, cron, spring-batch")
    kind: str = Field("job", description="job, box (container, not counted), file-watcher")
    schedule: str | None = None
    box: str | None = None
    command: str | None = None
    machine: str | None = None
    condition: str | None = None
    component: str | None = None
    steps: list[str] = Field(default_factory=list)
    programs: list[str] = Field(default_factory=list)
    datasets: list[str] = Field(default_factory=list)
    predecessors: list[str] = Field(default_factory=list)
    complexity: str | None = None
    complexity_score: float | None = None


class Vulnerability(FactModel):
    id: str = Field(description="CVE, GHSA or OSV id")
    aliases: list[str] = Field(default_factory=list)
    severity: str = Field(description="critical, high, medium, low or unknown")
    cvss: float | None = None
    fixed_versions: list[str] = Field(default_factory=list)
    sources: list[str] = Field(default_factory=list, description="dependency-check, osv")
    description: str | None = None


class Recommendation(FactModel):
    action: str = Field(
        description="upgrade, use-bom, migrate-jakarta, replace, rewrite, remove or keep"
    )
    target: str | None = Field(None, description="Target coordinates or product")
    reason: str
    effort: str = Field("low", description="low, medium or high")
    recipe: str | None = Field(None, description="OpenRewrite recipe id, when one applies")
    verify: bool = Field(False, description="Target not confirmed by rule data; check it")
    rule: str | None = Field(None, description="Rule in dependency-upgrades.yaml")


class Library(Fact):
    group: str | None = None
    artifact: str
    version: str | None = None
    language: str = "java"
    source: str = Field(description="maven, gradle, ivy, jar, npm, js-file")
    sha1: str | None = None
    scope: str | None = None
    license: str | None = None
    declared: bool = True
    where_used: list[str] = Field(default_factory=list)
    cves: list[str] = Field(default_factory=list)
    vulnerabilities: list[Vulnerability] = Field(default_factory=list)
    max_severity: str | None = None
    eol: bool | None = None
    eol_date: str | None = None
    replacement: str | None = None
    recommended_version: str | None = None
    recommendation: Recommendation | None = None


class TechItem(Fact):
    name: str
    version: str | None = None
    category: str = Field(
        description="language, framework, library, database, runtime, build, scheduler, "
        "middleware, auth, upgrade-signal"
    )
    usages: int = 1
    eol: bool | None = None
    eol_date: str | None = None
    replacement: str | None = None


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
    remediation: str | None = None


class Metric(Fact):
    component: str
    loc_code: int = 0
    loc_comment: int = 0
    loc_blank: int = 0
    files: int = 0
    units: int = Field(0, description="Functions, methods, paragraphs and scripts measured")
    complexity_avg: float | None = Field(None, description="Mean cyclomatic complexity per unit")
    complexity_max: int | None = None
    complex_units: int = Field(0, description="Units above the high-complexity threshold")
    duplicated_lines: int = 0
    duplication_pct: float | None = None
    test_files: int = 0
    test_ratio: float | None = Field(None, description="Test files / code files")
    dead_code_candidates: int = 0
    fan_in_max: int = 0
    fan_out_max: int = 0
    complexity: str | None = Field(None, description="Band of the mean item score")
    complexity_score: float | None = None
    bands: dict[str, int] = Field(default_factory=dict, description="Items per band")


class CodeUnit(FactModel):
    """A function, method, COBOL paragraph or script, measured for complexity."""

    name: str
    kind: str = Field(description="method, function, paragraph, procedure or script")
    line: int
    end_line: int
    loc: int = Field(description="Non-blank lines")
    cyclomatic: int = Field(description="1 + decision points")


class CoverageStatus(StrEnum):
    PRESENT = "present"
    MISSING = "missing"


class InputCoverage(Fact):
    kind: str
    label: str
    status: CoverageStatus
    path: str
    file_count: int = 0


class UnparsedRegion(FactModel):
    file: str
    start: int
    end: int
    reason: str


class AnalyzerCoverage(FactModel):
    """How much of the files an analyzer handled it could actually parse."""

    files: int = 0
    lines: int = 0
    unparsed_lines: int = 0
    coverage_pct: float = 100.0
    regions: list[UnparsedRegion] = Field(
        default_factory=list, description="First 50 unparsed regions"
    )


class VulnerabilitySource(FactModel):
    """One vulnerability data source for this run (tool run or imported report)."""

    name: str = Field(description="dependency-check or osv-scanner")
    status: str = Field(description="ran, imported, missing or failed")
    detail: str | None = None
    database_date: str | None = None
    reports: list[str] = Field(default_factory=list)


class EffortLine(FactModel):
    component: str
    item: str = Field(description="service, screen, batch_job, sql, stored_procedure, ...")
    level: str = Field(description="Band, PG grade or upgrade action the weight is for")
    count: int
    low: float
    likely: float
    high: float


class EffortEstimate(FactModel):
    """``effort.json``: person-days from config weights. An estimate, labelled as such."""

    label: str = "estimate — weights from config"
    unit: str = "person-days"
    low: float = 0.0
    likely: float = 0.0
    high: float = 0.0
    by_component: dict[str, dict[str, float]] = Field(default_factory=dict)
    lines: list[EffortLine] = Field(default_factory=list)
    weights: dict[str, Any] = Field(default_factory=dict)


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
    analyzers: dict[str, AnalyzerCoverage] = Field(default_factory=dict)
    unresolved_references: int = 0
    vulnerability_sources: list[VulnerabilitySource] = Field(default_factory=list)


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
    "analyzer_coverage": AnalyzerCoverage,
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
    "vulnerability": Vulnerability,
    "recommendation": Recommendation,
    "effort": EffortEstimate,
}
