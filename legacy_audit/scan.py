"""The scan pipeline: sources -> inventory -> components -> overrides -> coverage -> export."""

from __future__ import annotations

import logging
import os
import shutil
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from legacy_audit import __version__
from legacy_audit.analyzers import Facts, version_key
from legacy_audit.assess import assess, team_dir
from legacy_audit.config import GitSource, LoadedConfig
from legacy_audit.enrich.status import carry_over
from legacy_audit.inventory.cache import CacheEntry, FileCache
from legacy_audit.inventory.components import detect_components, disk_reader
from legacy_audit.inventory.files import INVENTORY_VERSION, skip_by_extension
from legacy_audit.inventory.inputs import coverage_line, input_coverage
from legacy_audit.inventory.walker import (
    Filters,
    SkippedPath,
    WalkedFile,
    anchored_excludes,
    walk,
)
from legacy_audit.inventory.worker import Job, process, setup
from legacy_audit.linker import classify_components, link
from legacy_audit.metrics.compute import compute as compute_metrics
from legacy_audit.model import (
    AnalyzerCoverage,
    Evidence,
    FileRecord,
    Finding,
    Origin,
    RunInfo,
    Severity,
    UnparsedRegion,
    make_id,
)
from legacy_audit.overrides import apply_component_overrides, load_overrides
from legacy_audit.parallel import Cancelled, check, ordered_map, worker_count
from legacy_audit.rules.packs import rules_key, text_rules
from legacy_audit.source import resolve_sources
from legacy_audit.store.lock import ScanLock
from legacy_audit.store.runs import LATEST_FILE, new_run_id, runs_dir, set_latest
from legacy_audit.store.summary import build_summary
from legacy_audit.store.writer import FactSet, drop_duplicate_ids, write_facts, write_json
from legacy_audit.target import FILE as TARGET_FILE
from legacy_audit.target import info as target_info

log = logging.getLogger("legacy_audit")

INPUTS_ROOT = "audit-inputs"
COPILOT_PATHS = (  # installed by `init` (legacy_audit/copilot/install.py), never scanned
    ".github/agents",
    ".github/chatmodes",
    ".github/prompts",
    ".github/legacy-audit",
    ".github/copilot-instructions.md",
    ".github/copilot-instructions.md.new",
)

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


def _load_facts(path: str, payload: dict[str, Any]) -> list[tuple[str, str, Facts]]:
    return [(name, path, Facts.load(data)) for name, data in payload.get("facts", {}).items()]


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


def _stage(name: str, progress: Progress | None, cancel: threading.Event | None) -> None:
    check(cancel)
    if progress:
        progress(name, 0, 0)


def _skipped_findings(skipped: list[SkippedPath], multi: bool) -> list[Finding]:
    """Build output and precompiler-generated files the walker left out (decision 113)."""
    out = []
    for sk in skipped:
        path = f"{sk.root}/{sk.rel}" if multi else sk.rel
        what = f"{sk.files} file(s)" if sk.rel.endswith("/") else "the file"
        log.info("Left out %s: %s (%s)", path, sk.reason, what)
        out.append(
            Finding(
                id=make_id("finding", "build-output-skipped", path),
                rule_id="build-output-skipped",
                severity=Severity.INFO,
                category="coverage",
                file=path.rstrip("/"),
                message=f"Left out of the scan: {sk.reason}; {what}. Set "
                "inventory.skip_build_output: false in audit.yaml to scan it.",
                evidence=[Evidence(file=path.rstrip("/"))],
            )
        )
    return out


def _limit_findings(
    records: list[FileRecord], unreadable: list[tuple[str, str]], max_bytes: int
) -> list[Finding]:
    """Files the scan could not read in full: too large (inventoried, not analysed) or unreadable
    (permissions, path too long, vanished). Both are coverage gaps, never a failed scan."""
    out = []
    for rec in records:
        if rec.size > max_bytes and rec.category != "binary" and rec.language != "archive":
            out.append(
                Finding(
                    id=make_id("finding", "file-too-large", rec.path),
                    rule_id="file-too-large",
                    severity=Severity.LOW,
                    category="coverage",
                    file=rec.path,
                    message=f"{rec.size / 1048576:.1f} MB is above inventory.max_file_size_mb: "
                    "hashed and counted as a file, but not read for LOC or analysed.",
                    evidence=[Evidence(file=rec.path)],
                )
            )
    for path, why in unreadable:
        log.warning("Cannot read %s: %s", path, why)
        out.append(
            Finding(
                id=make_id("finding", "file-unreadable", path),
                rule_id="file-unreadable",
                severity=Severity.LOW,
                category="coverage",
                file=path,
                message=f"The file could not be read ({why}); it is not in the inventory.",
                evidence=[Evidence(file=path)],
            )
        )
    return out


def _previous_run(out_dir: Path) -> Path | None:
    latest = runs_dir(out_dir) / LATEST_FILE
    run_id = latest.read_text(encoding="utf-8").strip() if latest.exists() else ""
    return runs_dir(out_dir) / run_id if run_id else None


def _clean_interrupted(rd: Path) -> None:
    """Remove run folders an earlier scan left unfinished (no run.json); its cache is kept."""
    for d in sorted(rd.iterdir()):
        if d.is_dir() and not (d / "run.json").exists():
            log.info("Removing unfinished run %s", d.name)
            shutil.rmtree(d, ignore_errors=True)


def run_scan(
    loaded: LoadedConfig,
    *,
    full: bool = False,
    output_dir: Path | None = None,
    progress: Progress | None = None,
    cancel: threading.Event | None = None,
) -> ScanResult:
    """Scan into a new run folder. Raises ``ScanBusy`` when another scan holds the output
    folder and ``Cancelled`` when ``cancel`` is set (the unfinished run is removed)."""
    out_dir = (output_dir or loaded.output_dir).resolve()
    rd = runs_dir(out_dir)
    with ScanLock(rd):
        _clean_interrupted(rd)
        started = datetime.now(UTC)
        run_id = new_run_id(loaded.hash, started, rd)
        run_dir = rd / run_id
        (run_dir / "logs").mkdir(parents=True)

        handler = logging.FileHandler(run_dir / "logs" / "scan.log", encoding="utf-8")
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
        handler.addFilter(SecretFilter(_secrets(loaded)))
        log.addHandler(handler)
        prev_level = log.level
        log.setLevel(logging.INFO)
        cancelled = False
        try:
            return _run(loaded, out_dir, run_id, run_dir, started, full, progress, cancel)
        except (Cancelled, KeyboardInterrupt):
            cancelled = True
            raise
        except Exception as err:
            # The folder has no run.json, so it is not a run; its log stays until the next scan.
            log.error("Run %s failed: %s: %s", run_id, type(err).__name__, err)
            raise
        finally:
            log.removeHandler(handler)
            log.setLevel(prev_level)
            handler.close()
            if cancelled:
                shutil.rmtree(run_dir, ignore_errors=True)


def _run(
    loaded: LoadedConfig,
    out_dir: Path,
    run_id: str,
    run_dir: Path,
    started: datetime,
    full: bool,
    progress: Progress | None,
    cancel: threading.Event | None,
) -> ScanResult:
    cfg = loaded.config
    unreadable: list[tuple[str, str]] = []  # (path, why)
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
    left_out: list[SkippedPath] = []
    for root in roots:
        filters = Filters.build(
            cfg.include,
            exclude,
            cfg.inventory.use_default_excludes,
            anchored_excludes(root.path, tool_owned),
            cfg.inventory.skip_build_output,
        )
        for wf in walk(root.name, root.path, filters, root.subpaths, left_out):
            walked.append((wf, f"{root.name}/{wf.rel}" if multi else wf.rel, Origin.SOURCE))
    inputs_dir = loaded.inputs_dir
    if inputs_dir.is_dir():
        for wf in walk(INPUTS_ROOT, inputs_dir, Filters.build(["**"], [])):
            walked.append((wf, f"{INPUTS_ROOT}/{wf.rel}", Origin.INPUT))

    # Settings that change per-file results are part of the cache key.
    inv = cfg.inventory
    patterns = text_rules(cfg.rules.enabled, team_dir(loaded))
    cache_version = (
        f"{version_key(INVENTORY_VERSION)};binaries={inv.include_binaries};"
        f"max_mb={inv.max_file_size_mb};dup={cfg.metrics.duplication_tokens};"
        f"rules={rules_key(patterns)}"
    )
    cache = FileCache(out_dir / "cache" / "file-cache.sqlite", cache_version)
    max_bytes = int(cfg.inventory.max_file_size_mb * 1024 * 1024)
    # Pass 1 (this process): what the cache already holds. Pass 2 (worker pool): the rest.
    slots: list[dict[str, Any] | None] = []  # cache payload per walked file, in walk order
    todo: list[tuple[int, Job, CacheEntry | None]] = []
    analyzed = cached = skipped = 0
    total = len(walked)
    for n, (wf, path, origin) in enumerate(walked, 1):
        check(cancel)
        if progress:
            progress("inventory", n, total)
        slots.append(None)
        if skip_by_extension(wf.rel, cfg.inventory.include_binaries):
            skipped += 1
            continue
        try:
            st = wf.abs_path.stat()
        except OSError as err:
            unreadable.append((path, f"{type(err).__name__}: {err.strerror or err}"))
            continue
        entry = None if full else cache.get(wf.root, path)
        if entry and entry.size == st.st_size and entry.mtime_ns == st.st_mtime_ns:
            cached += 1
            skipped += entry.payload.get("record") is None
            slots[-1] = entry.payload
            continue
        todo.append((n - 1, Job(str(wf.abs_path), path, wf.root, origin.value), entry))

    workers = worker_count(cfg.inventory.workers)
    log.info("Analysing %d file(s) with %d worker(s)", len(todo), workers)
    results = ordered_map(
        process,
        [job for _, job, _ in todo],
        workers,
        initializer=setup,
        initargs=(cfg, patterns, max_bytes),
        cancel=cancel,
    )
    for k, ((slot, job, entry), done) in enumerate(zip(todo, results, strict=True), 1):
        if progress:
            progress("analysis", k, len(todo))
        for w in done.warnings:
            log.warning(w)
        if done.error is not None:
            unreadable.append((job.path, done.error))
            continue
        if entry and done.sha256 and entry.sha256 == done.sha256:
            # Touched but unchanged: refresh the stat fields, keep the cached analysis.
            cached += 1
            entry.size, entry.mtime_ns = done.size, done.mtime_ns
            cache.put(job.root, job.path, entry)
            slots[slot] = entry.payload
            continue
        analyzed += 1
        cache.put(
            job.root,
            job.path,
            CacheEntry(done.size, done.mtime_ns, done.sha256, cache.version, done.payload),
        )
        slots[slot] = done.payload
        if done.payload["record"] is None:
            skipped += 1
        if k % 500 == 0:
            cache.commit()  # an interrupted run keeps what it analysed (the next run resumes)

    records: list[FileRecord] = []
    file_facts: list[tuple[str, str, Facts]] = []  # (analyzer, path, facts)
    for (_, path, _), payload in zip(walked, slots, strict=True):
        if payload is None:
            continue
        if payload.get("record") is None:  # binary skipped by content
            continue
        records.append(FileRecord.model_validate(payload["record"]))
        file_facts.extend(_load_facts(path, payload))
    removed = cache.prune()
    cache.close()
    limits = _limit_findings(records, unreadable, max_bytes) + _skipped_findings(left_out, multi)
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
    _stage("components", progress, cancel)
    t = time.perf_counter()
    source_records = [r for r in records if r.origin == Origin.SOURCE]
    components, file_to_comp = detect_components(
        source_records,
        [r.name for r in roots],
        cfg.components,
        disk_reader({r.name: r.path for r in roots}),
        cfg.inventory.component_grouping,
    )
    for rec in source_records:
        rec.component = file_to_comp.get(rec.id)
    override_report: dict[str, Any] = {}
    components = apply_component_overrides(
        overrides, source_records, components, override_report, warnings
    )
    timings["components"] = round(time.perf_counter() - t, 3)

    # ---- Linking and classification -----------------------------------------------------------
    _stage("link", progress, cancel)
    t = time.perf_counter()
    linked = link(
        records,
        (f for _, _, f in file_facts),
        cfg.cobol.copybook_paths,
        cfg.integrations.group_by_service,
    )
    linked.entities.setdefault("findings", []).extend(limits)
    fixed_types = set(overrides.components.type) | {c.name for c in cfg.components if c.type}
    classify_components(components, linked.entities, source_records, fixed_types)
    timings["link"] = round(time.perf_counter() - t, 3)
    warnings.extend(linked.warnings)
    log.info("Linked: %d edges, %d unresolved references", linked.edges, linked.unresolved)

    # ---- Metrics ------------------------------------------------------------------------------
    _stage("metrics", progress, cancel)
    t = time.perf_counter()
    units = {path: f.units for name, path, f in file_facts if name == "metrics"}
    windows = {path: f.windows for name, path, f in file_facts if name == "metrics"}
    measured = compute_metrics(cfg, source_records, components, linked.entities, units, windows)
    timings["metrics"] = round(time.perf_counter() - t, 3)

    # ---- Assess: PostgreSQL grades, dependency security, rules, effort --------------------------
    _stage("assess", progress, cancel)
    t = time.perf_counter()
    hits = [
        (path, str(h[0]), int(h[1]), str(h[2]))
        for name, path, f in file_facts
        if name == "patterns"
        for h in f.hits
    ]
    assessed = assess(
        loaded,
        run_id,
        started.isoformat(timespec="seconds"),
        run_dir,
        records,
        linked.entities,
        measured,
        hits,
        [r.path for r in roots],
        {path: wf.abs_path for wf, path, _ in walked},
        cancel,
    )
    timings["assess"] = round(time.perf_counter() - t, 3)
    log.info("Assessed: %d findings", len(assessed.findings))
    if assessed.unmatched_reports:
        log.info(
            "Vulnerability reports: %d entries match no library in the inventory (transitive?)",
            assessed.unmatched_reports,
        )
    for s in assessed.sources:
        log.info(
            "Vulnerability source %s: %s%s", s.name, s.status, f" ({s.detail})" if s.detail else ""
        )

    # ---- Coverage, export ---------------------------------------------------------------------
    coverage = input_coverage(inputs_dir, INPUTS_ROOT) + assessed.coverage
    for c in coverage:
        log.info("Input coverage: %s", coverage_line(c))
    records.sort(key=lambda r: (r.origin != Origin.SOURCE, r.path))
    facts: FactSet = {
        "inventory": records,
        "components": components,
        **linked.entities,
        "metrics": measured.metrics,
        "findings": assessed.findings,
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
        vulnerability_sources=assessed.sources,
        target=target_info(cfg.target),
    )
    _stage("export", progress, cancel)
    t = time.perf_counter()
    data_dir = run_dir / "data"
    facts, dup_warnings = drop_duplicate_ids(facts)
    warnings.extend(dup_warnings)
    run.warnings = warnings
    write_facts(data_dir, facts)
    write_json(data_dir / "sbom.cdx.json", assessed.sbom)
    if assessed.effort is not None:
        write_json(data_dir / "effort.json", assessed.effort.model_dump(mode="json"))
    write_json(data_dir / "target-mapping.json", assessed.target_mapping)
    write_json(data_dir / TARGET_FILE, run.target)
    timings["export"] = round(time.perf_counter() - t, 3)
    run.timings = timings
    run.finished_at = datetime.now(UTC).isoformat(timespec="seconds")
    write_json(run_dir / "run.json", run.model_dump(mode="json"))
    effort = assessed.effort.model_dump(mode="json") if assessed.effort else None
    write_json(data_dir / "audit-summary.json", build_summary(run, facts, effort=effort))
    carry_over(_previous_run(out_dir), run_dir)
    set_latest(out_dir, run_id)
    for w in warnings:
        log.warning(w)
    log.info("Run %s written to %s", run_id, run_dir)
    return ScanResult(run_dir, run, facts)
