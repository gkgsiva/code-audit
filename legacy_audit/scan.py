"""The scan pipeline: sources -> inventory -> components -> overrides -> coverage -> export."""

from __future__ import annotations

import logging
import os
import time
from collections import defaultdict
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from legacy_audit import __version__
from legacy_audit.analyzers import Analyzer, AnalyzerContext, Facts, registered, version_key
from legacy_audit.config import GitSource, LoadedConfig
from legacy_audit.inventory.cache import CacheEntry, FileCache
from legacy_audit.inventory.components import detect_components
from legacy_audit.inventory.files import INVENTORY_VERSION, analyze_file, skip_by_extension
from legacy_audit.inventory.inputs import coverage_line, input_coverage
from legacy_audit.inventory.walker import Filters, WalkedFile, anchored_excludes, walk
from legacy_audit.linker import classify_components, link
from legacy_audit.model import (
    AnalyzerCoverage,
    Category,
    Component,
    Evidence,
    FileRecord,
    Finding,
    Metric,
    Origin,
    RunInfo,
    Severity,
    UnparsedRegion,
    make_id,
)
from legacy_audit.overrides import apply_component_overrides, load_overrides
from legacy_audit.source import resolve_sources
from legacy_audit.store.runs import new_run_id, runs_dir, set_latest
from legacy_audit.store.summary import build_summary
from legacy_audit.store.writer import FactSet, write_facts, write_json

log = logging.getLogger("legacy_audit")

INPUTS_ROOT = "audit-inputs"
COPILOT_PATHS = (".github/agents", ".github/prompts", ".github/copilot-instructions.md")

Progress = Callable[[str, int, int], None]


@dataclass
class ScanResult:
    run_dir: Path
    run: RunInfo
    facts: FactSet


class SecretFilter(logging.Filter):
    """Masks secret values in log records (defence in depth; secrets are never logged)."""

    def __init__(self, secrets: list[str]) -> None:
        super().__init__()
        self.secrets = [s for s in secrets if s]

    def filter(self, record: logging.LogRecord) -> bool:
        if self.secrets:
            msg = record.getMessage()
            for s in self.secrets:
                msg = msg.replace(s, "****")
            record.msg, record.args = msg, None
        return True


def _secrets(loaded: LoadedConfig) -> list[str]:
    src = loaded.config.source
    if not isinstance(src, GitSource):
        return []
    return [os.environ.get(r.token_env, "") for r in src.repos if r.token_env]


def _metrics(records: list[FileRecord], components: list[Component]) -> list[Metric]:
    members: dict[str, list[FileRecord]] = defaultdict(list)
    for r in records:
        if r.component:
            members[r.component].append(r)
    out = []
    for c in components:
        files = members.get(c.name, [])
        out.append(
            Metric(
                id=make_id("metric", c.id),
                component=c.name,
                files=len(files),
                loc_code=sum(f.loc_code for f in files),
                loc_comment=sum(f.loc_comment for f in files),
                loc_blank=sum(f.loc_blank for f in files),
                test_files=sum(1 for f in files if f.category == Category.TEST),
                evidence=c.evidence,
            )
        )
    return out


def _load_facts(path: str, payload: dict[str, Any]) -> list[tuple[str, str, Facts]]:
    return [(name, path, Facts.load(data)) for name, data in payload.get("facts", {}).items()]


def _analyze(a: Analyzer, rec: FileRecord, ctx: AnalyzerContext, text: str | None) -> Facts:
    """Run one analyzer; a crash is a coverage gap and a finding, never a failed scan."""
    try:
        return a.analyze(rec, ctx)
    except Exception as err:
        log.warning("Analyzer %s failed on %s: %s: %s", a.name, rec.path, type(err).__name__, err)
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


def _analyzer_coverage(file_facts: list[tuple[str, str, Facts]]) -> dict[str, AnalyzerCoverage]:
    out: dict[str, AnalyzerCoverage] = {}
    for name, path, facts in file_facts:
        cov = out.setdefault(name, AnalyzerCoverage())
        cov.files += 1
        lines = max(facts.coverage.lines, 0)
        cov.lines += lines
        covered: set[int] = set()
        for u in facts.coverage.unparsed:
            covered.update(range(max(u.start, 1), min(u.end, lines) + 1))
            if len(cov.regions) < 50:
                cov.regions.append(
                    UnparsedRegion(file=path, start=u.start, end=u.end, reason=u.reason)
                )
        cov.unparsed_lines += len(covered)
    for cov in out.values():
        if cov.lines:
            cov.coverage_pct = round(100.0 * (cov.lines - cov.unparsed_lines) / cov.lines, 1)
    return dict(sorted(out.items()))


def run_scan(
    loaded: LoadedConfig,
    *,
    full: bool = False,
    output_dir: Path | None = None,
    progress: Progress | None = None,
) -> ScanResult:
    out_dir = (output_dir or loaded.output_dir).resolve()
    started = datetime.now(UTC)
    rd = runs_dir(out_dir)
    rd.mkdir(parents=True, exist_ok=True)
    run_id = new_run_id(loaded.hash, started, rd)
    run_dir = rd / run_id
    (run_dir / "logs").mkdir(parents=True)

    handler = logging.FileHandler(run_dir / "logs" / "scan.log", encoding="utf-8")
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
    handler.addFilter(SecretFilter(_secrets(loaded)))
    log.addHandler(handler)
    prev_level = log.level
    log.setLevel(logging.INFO)
    try:
        return _run(loaded, out_dir, run_id, run_dir, started, full, progress)
    finally:
        log.removeHandler(handler)
        log.setLevel(prev_level)
        handler.close()


def _run(
    loaded: LoadedConfig,
    out_dir: Path,
    run_id: str,
    run_dir: Path,
    started: datetime,
    full: bool,
    progress: Progress | None,
) -> ScanResult:
    cfg = loaded.config
    timings: dict[str, float] = {}
    warnings: list[str] = []
    log.info("Run %s for '%s' (legacy-audit %s)", run_id, cfg.application.name, __version__)

    t = time.perf_counter()
    overrides = load_overrides(loaded.overrides_path)
    roots = resolve_sources(loaded)
    timings["sources"] = round(time.perf_counter() - t, 3)
    for r in roots:
        log.info("Source %s: %s%s", r.name, r.path, f" @ {r.commit}" if r.commit else "")

    # ---- Inventory -------------------------------------------------------------------------
    t = time.perf_counter()
    tool_owned = [
        out_dir,
        loaded.inputs_dir,
        loaded.path,
        loaded.overrides_path,
        *(loaded.base_dir / p for p in COPILOT_PATHS),
    ]
    exclude = [*cfg.exclude, *overrides.files.exclude, *(f"!{p}" for p in overrides.files.include)]
    multi = len(roots) > 1
    walked: list[tuple[WalkedFile, str, Origin]] = []
    for root in roots:
        filters = Filters.build(
            cfg.include,
            exclude,
            cfg.inventory.use_default_excludes,
            anchored_excludes(root.path, tool_owned),
        )
        for wf in walk(root.name, root.path, filters, root.subpaths):
            walked.append((wf, f"{root.name}/{wf.rel}" if multi else wf.rel, Origin.SOURCE))
    inputs_dir = loaded.inputs_dir
    if inputs_dir.is_dir():
        for wf in walk(INPUTS_ROOT, inputs_dir, Filters.build(["**"], [])):
            walked.append((wf, f"{INPUTS_ROOT}/{wf.rel}", Origin.INPUT))

    # Settings that change per-file results are part of the cache key.
    inv = cfg.inventory
    cache_version = (
        f"{version_key(INVENTORY_VERSION)};binaries={inv.include_binaries};"
        f"max_mb={inv.max_file_size_mb}"
    )
    cache = FileCache(out_dir / "cache" / "file-cache.sqlite", cache_version)
    analyzers = registered()
    max_bytes = int(cfg.inventory.max_file_size_mb * 1024 * 1024)
    records: list[FileRecord] = []
    file_facts: list[tuple[str, str, Facts]] = []  # (analyzer, path, facts)
    analyzed = cached = skipped = 0
    total = len(walked)
    for n, (wf, path, origin) in enumerate(walked, 1):
        if progress:
            progress("inventory", n, total)
        if skip_by_extension(wf.rel, cfg.inventory.include_binaries):
            skipped += 1
            continue
        root_key = wf.root
        st = wf.abs_path.stat()
        entry = None if full else cache.get(root_key, path)
        if entry and entry.size == st.st_size and entry.mtime_ns == st.st_mtime_ns:
            rec_data = entry.payload.get("record")
            cached += 1
            if rec_data is None:
                skipped += 1
            else:
                records.append(FileRecord.model_validate(rec_data))
                file_facts.extend(_load_facts(path, entry.payload))
            continue
        result = analyze_file(
            wf.abs_path, path, root_key, origin, cfg.inventory.include_binaries, max_bytes
        )
        rec = result.record
        if entry and rec and entry.sha256 == rec.sha256:
            # Touched but unchanged: refresh the stat fields, keep the cached analysis.
            cached += 1
            entry.size, entry.mtime_ns = st.st_size, st.st_mtime_ns
            cache.put(root_key, path, entry)
            records.append(FileRecord.model_validate(entry.payload["record"]))
            file_facts.extend(_load_facts(path, entry.payload))
            continue
        analyzed += 1
        per_analyzer: dict[str, Facts] = {}
        if rec is not None and (result.text is not None or rec.language == "archive"):
            ctx = AnalyzerContext(config=cfg, abs_path=wf.abs_path, text=result.text)
            for a in analyzers:
                if a.supports(rec):
                    per_analyzer[a.name] = _analyze(a, rec, ctx, result.text)
        if rec is None:
            skipped += 1
        cache.put(
            root_key,
            path,
            CacheEntry(
                size=st.st_size,
                mtime_ns=st.st_mtime_ns,
                sha256=rec.sha256 if rec else "",
                version=cache.version,
                payload={
                    "record": rec.model_dump(mode="json") if rec else None,
                    "facts": {name: f.dump() for name, f in per_analyzer.items()},
                },
            ),
        )
        if rec is not None:
            records.append(rec)
            file_facts.extend((name, path, f) for name, f in per_analyzer.items())
    removed = cache.prune()
    cache.close()
    timings["inventory"] = round(time.perf_counter() - t, 3)
    log.info(
        "Inventory: %d files, %d analyzed, %d from cache, %d skipped binaries, %d removed",
        len(records),
        analyzed,
        cached,
        skipped,
        removed,
    )

    # ---- Components and overrides -------------------------------------------------------------
    t = time.perf_counter()
    source_records = [r for r in records if r.origin == Origin.SOURCE]
    components, file_to_comp = detect_components(
        source_records, [r.name for r in roots], cfg.components
    )
    for rec in source_records:
        rec.component = file_to_comp.get(rec.id)
    override_report: dict[str, Any] = {}
    components = apply_component_overrides(
        overrides, source_records, components, override_report, warnings
    )
    timings["components"] = round(time.perf_counter() - t, 3)

    # ---- Linking and classification -----------------------------------------------------------
    t = time.perf_counter()
    linked = link(records, (f for _, _, f in file_facts), cfg.cobol.copybook_paths)
    fixed_types = set(overrides.components.type) | {c.name for c in cfg.components if c.type}
    classify_components(components, linked.entities, source_records, fixed_types)
    timings["link"] = round(time.perf_counter() - t, 3)
    log.info("Linked: %d edges, %d unresolved references", linked.edges, linked.unresolved)

    # ---- Coverage, metrics, export --------------------------------------------------------------
    coverage = input_coverage(inputs_dir, INPUTS_ROOT)
    for c in coverage:
        log.info("Input coverage: %s", coverage_line(c))
    records.sort(key=lambda r: (r.origin != Origin.SOURCE, r.path))
    facts: FactSet = {
        "inventory": records,
        "components": components,
        **linked.entities,
        "metrics": _metrics(source_records, components),
        "input_coverage": coverage,
    }

    run = RunInfo(
        run_id=run_id,
        tool_version=__version__,
        started_at=started.isoformat(timespec="seconds"),
        config_path=loaded.path.as_posix(),
        config_hash=loaded.hash,
        application=cfg.application.name,
        sources=[r.info() for r in roots],
        full_scan=full,
        files_total=len(records),
        files_analyzed=analyzed,
        files_cached=cached,
        files_removed=removed,
        coverage=coverage,
        overrides=override_report,
        counting_rules=cfg.counting,
        warnings=warnings,
        analyzers=_analyzer_coverage(file_facts),
        unresolved_references=linked.unresolved,
    )
    t = time.perf_counter()
    data_dir = run_dir / "data"
    write_facts(data_dir, facts)
    timings["export"] = round(time.perf_counter() - t, 3)
    run.timings = timings
    run.finished_at = datetime.now(UTC).isoformat(timespec="seconds")
    write_json(run_dir / "run.json", run.model_dump(mode="json"))
    write_json(data_dir / "audit-summary.json", build_summary(run, facts))
    set_latest(out_dir, run_id)
    for w in warnings:
        log.warning(w)
    log.info("Run %s written to %s", run_id, run_dir)
    return ScanResult(run_dir, run, facts)
