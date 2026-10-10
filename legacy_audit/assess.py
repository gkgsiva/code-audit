"""The assess stage: PostgreSQL grades, dependency security, rules, findings and effort.

It runs every scan after linking and metrics, on the linked facts and the cached per-file
results, so a changed weight, severity or disabled rule takes effect without re-analysing files.
"""

from __future__ import annotations

import logging
import threading
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from typing import Any

from legacy_audit.config import AuditConfig, LoadedConfig
from legacy_audit.metrics.compute import MetricsResult
from legacy_audit.metrics.effort import estimate
from legacy_audit.model import (
    Category,
    CoverageStatus,
    DbObject,
    EffortEstimate,
    Evidence,
    FileRecord,
    Finding,
    InputCoverage,
    Library,
    Origin,
    SqlStatement,
    TechItem,
    VulnerabilitySource,
    make_id,
)
from legacy_audit.parallel import worker_count
from legacy_audit.rules.engine import apply_config, evaluate
from legacy_audit.rules.packs import Pack, load_pack
from legacy_audit.rules.pg import grade_all
from legacy_audit.security import vulns as V
from legacy_audit.security.eol import apply_eol
from legacy_audit.security.jdk import run_jdk_tools
from legacy_audit.security.sbom import build_sbom
from legacy_audit.security.upgrades import apply_upgrades

log = logging.getLogger("legacy_audit")

SECURITY_INPUT = "security"
TOOL_LABELS = {V.DC: "OWASP Dependency-Check", V.OSV: "OSV-Scanner"}
SOURCE_LANGUAGES = {"java", "c", "cpp", "cobol", "proc", "procobol"}


@dataclass
class Assessment:
    findings: list[Finding]
    effort: EffortEstimate | None
    sbom: dict[str, Any]
    sources: list[VulnerabilitySource]
    coverage: list[InputCoverage]
    target_mapping: list[dict[str, Any]] = field(default_factory=list)
    unmatched_reports: int = 0


def team_dir(loaded: LoadedConfig) -> Path | None:
    d = loaded.config.rules.packs_dir
    return loaded.resolve(d) if d else None


def binaries(records: list[FileRecord]) -> list[dict[str, Any]]:
    """Compiled files (not archives), with ``has_source`` when a source file has the same stem."""
    stems = {
        PurePosixPath(r.path).stem.lower()
        for r in records
        if r.origin == Origin.SOURCE and r.category != Category.BINARY
    }
    out = []
    for r in records:
        if r.category == Category.BINARY and r.language not in ("archive",):
            out.append(
                {
                    "id": r.id,
                    "path": r.path,
                    "file": r.path,
                    "component": r.component,
                    "has_source": PurePosixPath(r.path).stem.lower() in stems,
                }
            )
    return out


def sql_distinct(statements: list[SqlStatement]) -> list[dict[str, Any]]:
    """One item per distinct statement text, located at its first occurrence."""
    groups: dict[tuple[str, str | None], list[SqlStatement]] = {}
    for st in statements:
        groups.setdefault((st.text, st.dialect), []).append(st)
    out = []
    for group in groups.values():
        first = group[0]
        item = first.model_dump(mode="json")
        item["occurrences"] = len(group)
        item["evidence"] = [e.model_dump(mode="json") for st in group for e in st.evidence][:5]
        out.append(item)
    return out


def _library_item(lib: Library, risky: list[str]) -> dict[str, Any]:
    item = lib.model_dump(mode="json")
    lic = (lib.license or "").upper()
    item["license_risky"] = any(r.upper() in lic for r in risky)
    item["vulnerability_count"] = len(lib.vulnerabilities)
    cves = lib.cves
    item["cves"] = ", ".join(cves[:5]) + (f" and {len(cves) - 5} more" if len(cves) > 5 else "")
    item["cve_list"] = cves
    return item


def _security_reports(inputs_dir: Path) -> dict[str, list[V.Report]]:
    found: dict[str, list[V.Report]] = {V.DC: [], V.OSV: []}
    folder = inputs_dir / SECURITY_INPUT
    if folder.is_dir():
        for p in sorted(folder.rglob("*.json")):
            display = f"audit-inputs/{SECURITY_INPUT}/{p.relative_to(folder).as_posix()}"
            report = V.load_report(p, display)
            if report is not None:
                found[report.tool].append(report)
    return found


def _vulnerabilities(
    cfg: AuditConfig,
    inputs_dir: Path,
    scan_dirs: list[Path],
    run_dir: Path,
    libraries: list[Library],
) -> tuple[list[VulnerabilitySource], list[InputCoverage], int]:
    imported = _security_reports(inputs_dir)
    sources: list[VulnerabilitySource] = []
    reports: list[V.Report] = []
    sec = cfg.security
    out_dir = run_dir / "security"
    for tool in (V.DC, V.OSV):
        if imported[tool]:
            dates = [r.database_date for r in imported[tool] if r.database_date]
            sources.append(
                VulnerabilitySource(
                    name=tool,
                    status="imported",
                    database_date=max(dates) if dates else None,
                    reports=[r.path for r in imported[tool]],
                )
            )
            reports += imported[tool]
            continue
        enabled = sec.dependency_check.enabled if tool == V.DC else sec.osv_scanner.enabled
        if not enabled:
            sources.append(VulnerabilitySource(name=tool, status="disabled"))
            continue
        if not libraries:
            sources.append(
                VulnerabilitySource(name=tool, status="skipped", detail="no libraries found")
            )
            continue
        if tool == V.DC:
            report, source = V.run_dependency_check(
                sec.dependency_check, scan_dirs, out_dir, cfg.application.name
            )
        else:
            report, source = V.run_osv_scanner(sec.osv_scanner, scan_dirs, out_dir)
        sources.append(source)
        if report is not None:
            reports.append(report)
    unmatched = V.apply_reports(libraries, reports)

    coverage = []
    for s in sources:
        if s.status == "skipped":
            continue
        ok = s.status in ("ran", "imported")
        detail = s.detail or s.status
        coverage.append(
            InputCoverage(
                id=make_id("input_coverage", s.name),
                kind=s.name,
                label=TOOL_LABELS[s.name] + ("" if ok else f" ({detail})"),
                status=CoverageStatus.PRESENT if ok else CoverageStatus.MISSING,
                path=", ".join(s.reports) if s.reports else s.name,
                file_count=len(s.reports),
                evidence=[Evidence(file=r) for r in s.reports if r.startswith("audit-inputs/")],
            )
        )
    any_ok = any(s.status in ("ran", "imported") for s in sources)
    if libraries and not any_ok:
        log.warning("Vulnerability check not done: no Dependency-Check or OSV-Scanner results")
    return sources, coverage, unmatched


def assess(
    loaded: LoadedConfig,
    run_id: str,
    timestamp: str,
    run_dir: Path,
    records: list[FileRecord],
    entities: dict[str, list[Any]],
    measured: MetricsResult,
    hits: list[tuple[str, str, int, str]],
    scan_dirs: list[Path],
    abs_paths: dict[str, Path],
    cancel: threading.Event | None = None,
) -> Assessment:
    cfg = loaded.config
    tdir = team_dir(loaded)
    packs: dict[str, Pack] = {name: load_pack(name, tdir) for name in cfg.rules.enabled}

    statements = [s for s in entities.get("sql_statements", []) if isinstance(s, SqlStatement)]
    objects = [o for o in entities.get("db_objects", []) if isinstance(o, DbObject)]
    grade_all(
        statements,
        objects,
        load_pack("postgresql", tdir).data,
        workers=worker_count(cfg.inventory.workers),
        cache_file=run_dir.parent.parent / "cache" / "grade-cache.sqlite",
        cancel=cancel,
    )

    libraries = [lib for lib in entities.get("libraries", []) if isinstance(lib, Library)]
    tech = [t for t in entities.get("tech_stack", []) if isinstance(t, TechItem)]
    apply_eol(libraries, tech, load_pack("eol", tdir).data)
    sources, coverage, unmatched = _vulnerabilities(
        cfg, loaded.inputs_dir, scan_dirs, run_dir, libraries
    )
    dependency = load_pack("dependency", tdir).data
    apply_upgrades(libraries, dependency)
    risky = [str(x) for x in dependency.get("licenses", {}).get("risky", [])]

    jars = sorted(
        (r.path, abs_paths[r.path])
        for r in records
        if r.language == "archive" and r.path.lower().endswith(".jar") and r.path in abs_paths
    )
    jdk_items, jdk_status = run_jdk_tools(cfg.security.jdk_tools, jars)
    if jdk_status != "disabled":
        log.info("JDK tools (jdeps/jdeprscan): %s, %d items", jdk_status, len(jdk_items))

    sbom = build_sbom(cfg.application.name, run_id, timestamp, libraries)

    cache: dict[str, list[dict[str, Any]]] = {}
    extra: dict[str, Callable[[], list[dict[str, Any]]]] = {
        "units": lambda: measured.units,
        "dead_code": lambda: measured.dead_code,
        "binaries": lambda: binaries(records),
        "sql_distinct": lambda: sql_distinct(statements),
        "jdk_findings": lambda: jdk_items,
        "libraries": lambda: [_library_item(lib, risky) for lib in libraries],
        "metrics": lambda: [m.model_dump(mode="json") for m in measured.metrics],
        "inventory": lambda: [r.model_dump(mode="json") for r in records],
    }

    def collection(name: str) -> list[dict[str, Any]]:
        if name not in cache:
            if name in extra:
                cache[name] = extra[name]()
            else:
                cache[name] = [
                    e.model_dump(mode="json") if hasattr(e, "model_dump") else e
                    for e in entities.get(name, [])
                ]
        return cache[name]

    rules = [r for pack in packs.values() for r in pack.rules]
    raised = [f for f in entities.get("findings", []) if isinstance(f, Finding)]
    findings = apply_config([*raised, *evaluate(rules, collection, hits)], cfg)

    component_of = {r.path: r.component for r in records}
    effort = estimate(cfg, entities, component_of) if cfg.effort.enabled else None
    mapping = load_pack("target-mapping", tdir).data.get("targets", [])
    return Assessment(
        findings=findings,
        effort=effort,
        sbom=sbom,
        sources=sources,
        coverage=coverage,
        target_mapping=mapping,
        unmatched_reports=unmatched,
    )
