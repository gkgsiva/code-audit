"""Cross-file metrics, recomputed every run from cached per-file results: duplication,
fan-in/fan-out, complexity scores and bands, dead-code candidates and per-component metrics.

Score of an item = sum over features of ``weight x feature / scale`` (``complexity`` in
``audit.yaml``). Features:

- services: ``loc`` and ``cyclomatic`` of the code the service runs (its handler, or its whole
  code file, plus code files reached within two hops over calls/uses/invokes/runs/triggers/
  includes edges), ``tables`` and ``integrations`` it reaches, ``vendor_sql`` (statements with
  vendor-specific features in that code) and ``fan_out``;
- screens: ``loc`` of the page, ``fields`` and ``fan_out``;
- batch jobs: the score of the job's ``batch-job`` service;
- components: the mean score of their services and screens.
"""

from __future__ import annotations

from collections import defaultdict
from collections.abc import Iterable
from dataclasses import dataclass, field
from typing import Any

from legacy_audit.config import AuditConfig
from legacy_audit.model import (
    BatchJob,
    Category,
    CodeEdge,
    CodeUnit,
    Component,
    EntryPoint,
    Fact,
    FileRecord,
    Metric,
    Screen,
    Service,
    SqlStatement,
    make_id,
)

BANDS = ("Simple", "Medium", "Complex", "Very complex")
FOOTPRINT_EDGES = {"calls", "uses", "invokes", "runs", "triggers", "includes"}
FOOTPRINT_DEPTH = 2
# Files that can be dead code; screens, SQL and JavaScript are judged elsewhere or not at all.
DEAD_CODE_LANGUAGES = {"java", "c", "cpp", "proc", "cobol", "procobol", "shell", "perl", "python"}
CODE_LANGUAGES = DEAD_CODE_LANGUAGES | {
    "jsp",
    "sql",
    "plsql",
    "javascript",
    "typescript",
    "batch",
    "csharp",
    "vb6",
    "vbnet",
}


def band(score: float, thresholds: dict[str, float]) -> str:
    if score >= thresholds["very_complex"]:
        return BANDS[3]
    if score >= thresholds["complex"]:
        return BANDS[2]
    if score >= thresholds["medium"]:
        return BANDS[1]
    return BANDS[0]


def score(features: dict[str, float], cfg: AuditConfig) -> float:
    w, scale = cfg.complexity.weights, cfg.complexity.scale
    total = sum(w.get(k, 0.0) * v / (scale.get(k) or 1.0) for k, v in features.items())
    return round(total, 2)


@dataclass
class MetricsResult:
    metrics: list[Metric]
    units: list[dict[str, Any]] = field(default_factory=list)  # every unit, with file/component
    dead_code: list[dict[str, Any]] = field(default_factory=list)
    duplicated: dict[str, int] = field(default_factory=dict)  # path -> duplicated lines
    features: dict[str, dict[str, float]] = field(default_factory=dict)  # item id -> features


def _duplicated_lines(windows: dict[str, list[list[int]]]) -> dict[str, set[int]]:
    seen: dict[int, list[tuple[str, int, int]]] = defaultdict(list)
    for path, items in windows.items():
        for h, start, end in items:
            seen[h].append((path, start, end))
    marked: dict[str, set[int]] = defaultdict(set)
    for places in seen.values():
        if len(places) < 2:
            continue
        # Overlapping windows of one repetitive block in the same place are not clones.
        if len({(p, s) for p, s, _ in places}) < 2:
            continue
        for path, start, end in places:
            marked[path].update(range(start, end + 1))
    return marked


class _Graph:
    def __init__(self, edges: Iterable[CodeEdge]) -> None:
        self.out: dict[str, list[CodeEdge]] = defaultdict(list)
        self.into: dict[str, list[CodeEdge]] = defaultdict(list)
        for e in edges:
            if e.resolved:
                self.out[e.source].append(e)
                self.into[e.target].append(e)

    def reach(self, starts: Iterable[str], kinds: set[str] | None, depth: int | None) -> set[str]:
        seen = set(starts)
        frontier = list(seen)
        level = 0
        while frontier and (depth is None or level < depth):
            nxt = []
            for node in frontier:
                for e in self.out.get(node, []):
                    if (kinds is None or e.kind in kinds) and e.target not in seen:
                        seen.add(e.target)
                        nxt.append(e.target)
            frontier, level = nxt, level + 1
        return seen


def compute(
    cfg: AuditConfig,
    records: list[FileRecord],
    components: list[Component],
    entities: dict[str, list[Fact]],
    units: dict[str, list[CodeUnit]],
    windows: dict[str, list[list[int]]],
) -> MetricsResult:
    """Fill complexity, scores and fan-in/out on services, screens and batch jobs (in place)
    and return per-component metrics plus the inputs of metric-based rules."""
    by_path = {r.path: r for r in records}
    by_id = {r.id: r for r in records}
    services = [s for s in entities.get("services", []) if isinstance(s, Service)]
    screens = [s for s in entities.get("screens", []) if isinstance(s, Screen)]
    jobs = [j for j in entities.get("batch_jobs", []) if isinstance(j, BatchJob)]
    sql = [s for s in entities.get("sql_statements", []) if isinstance(s, SqlStatement)]
    entries = [e for e in entities.get("entry_points", []) if isinstance(e, EntryPoint)]
    graph = _Graph(e for e in entities.get("dependencies", []) if isinstance(e, CodeEdge))
    thresholds = cfg.complexity.thresholds
    result = MetricsResult(metrics=[])

    vendor_sql: dict[str, int] = defaultdict(int)
    for s in sql:
        if s.vendor_features:
            vendor_sql[s.file] += 1
    services_per_file: dict[str, int] = defaultdict(int)
    for svc in services:
        if svc.file:
            services_per_file[svc.file] += 1
    job_of_service = {make_id("service", "batch-job", j.id): j for j in jobs}

    def is_code(path: str | None) -> bool:
        rec = by_path.get(path or "")
        return rec is not None and rec.language in CODE_LANGUAGES

    def file_node(path: str | None) -> str | None:
        rec = by_path.get(path or "")
        return rec.id if rec else None

    def unit_cc(path: str) -> int:
        return sum(u.cyclomatic for u in units.get(path, []))

    # ---- services ------------------------------------------------------------------------
    for svc in services:
        nodes = [svc.id]
        job = job_of_service.get(svc.id)
        if job is not None:
            nodes.append(job.id)
        own_file = svc.file if is_code(svc.file) else None
        own_units = units.get(own_file or "", [])
        shared = own_file is not None and services_per_file[own_file] > 1
        if shared and svc.handler:
            method = svc.handler.rsplit(".", 1)[-1].rsplit("#", 1)[-1]
            own_units = [u for u in own_units if u.name == method]
        fnode = file_node(own_file)
        starts = nodes + ([fnode] if fnode else [])
        reached = graph.reach(starts, FOOTPRINT_EDGES, FOOTPRINT_DEPTH)
        files = {
            by_id[n].path for n in reached if n in by_id and by_id[n].language in CODE_LANGUAGES
        }
        files.discard(own_file or "")
        own_loc = sum(u.loc for u in own_units) if own_units else 0
        if own_file and not own_units and not shared:
            own_loc = by_path[own_file].loc_code
        out_nodes = list(nodes) + ([fnode] if fnode and not shared else [])
        fan_out = {e.target for n in out_nodes for e in graph.out.get(n, [])}
        fan_in = {e.source for n in nodes for e in graph.into.get(n, [])}
        svc.fan_out, svc.fan_in = len(fan_out), len(fan_in)
        features = {
            "loc": float(own_loc + sum(by_path[p].loc_code for p in files)),
            "cyclomatic": float(sum(u.cyclomatic for u in own_units) + sum(map(unit_cc, files))),
            "tables": float(len(svc.tables)),
            "integrations": float(len(svc.external_calls)),
            "vendor_sql": float(
                (vendor_sql.get(own_file, 0) if own_file and not shared else 0)
                + sum(vendor_sql.get(p, 0) for p in files)
            ),
            "fan_out": float(len(fan_out)),
        }
        svc.complexity_score = score(features, cfg)
        svc.complexity = band(svc.complexity_score, thresholds)
        result.features[svc.id] = features
        if job is not None:
            job.complexity_score, job.complexity = svc.complexity_score, svc.complexity

    # ---- screens --------------------------------------------------------------------------
    screens_per_file: dict[str, int] = defaultdict(int)
    for sc in screens:
        if sc.file:
            screens_per_file[sc.file] += 1
    for sc in screens:
        if sc.partial:
            continue
        rec = by_path.get(sc.file or "")
        per = screens_per_file[sc.file] if sc.file else 1
        nodes = [sc.id] + ([rec.id] if rec and per == 1 else [])
        fan_out = {e.target for n in nodes for e in graph.out.get(n, [])}
        features = {
            "loc": round(rec.loc_code / per, 1) if rec else 0.0,
            "fields": float(sc.fields_count),
            "fan_out": float(len(fan_out)),
        }
        sc.complexity_score = score(features, cfg)
        sc.complexity = band(sc.complexity_score, thresholds)
        result.features[sc.id] = features

    # ---- duplication -----------------------------------------------------------------------
    marked = _duplicated_lines(windows)
    result.duplicated = {
        p: min(len(lines), by_path[p].loc_code) for p, lines in marked.items() if p in by_path
    }

    # ---- dead-code candidates --------------------------------------------------------------
    roots: set[str] = {s.id for s in services} | {j.id for j in jobs}
    roots |= {s.id for s in screens if not s.partial}
    for path in [s.file for s in services] + [s.file for s in screens] + [e.file for e in entries]:
        node = file_node(path)
        if node:
            roots.add(node)
    for r in records:
        if r.language not in CODE_LANGUAGES or r.category in (Category.CONFIG, Category.BUILD):
            roots.add(r.id)
    # The scripts nothing calls in a batch component are where its batch starts (decision 116).
    batch_comps = {c.name for c in components if "batch" in c.roles}
    called = {e.target for edges in graph.out.values() for e in edges}
    for r in records:
        if r.category == Category.SCRIPT and r.component in batch_comps and r.id not in called:
            roots.add(r.id)
    live = graph.reach(roots, None, None)
    for r in records:
        if (
            r.origin == "source"
            and r.language in DEAD_CODE_LANGUAGES
            and r.category not in (Category.TEST, Category.GENERATED)
            and r.id not in live
        ):
            result.dead_code.append(
                {"file": r.path, "component": r.component, "language": r.language}
            )

    # ---- units list for rules --------------------------------------------------------------
    for path in sorted(units):
        rec = by_path.get(path)
        if rec is None or rec.category in (Category.TEST, Category.GENERATED):
            continue
        for u in units[path]:
            result.units.append(
                {
                    **u.model_dump(),
                    "file": path,
                    "component": rec.component,
                    "complex": u.cyclomatic >= cfg.complexity.high_unit,
                }
            )

    # ---- per component ---------------------------------------------------------------------
    members: dict[str, list[FileRecord]] = defaultdict(list)
    for r in records:
        if r.component and r.origin == "source":
            members[r.component].append(r)
    dead_per: dict[str, int] = defaultdict(int)
    for d in result.dead_code:
        dead_per[d["component"] or ""] += 1
    items_per: dict[str, list[tuple[float, str]]] = defaultdict(list)
    for svc in services:
        if svc.component and svc.complexity_score is not None and svc.complexity:
            items_per[svc.component].append((svc.complexity_score, svc.complexity))
    for sc in screens:
        if sc.component and sc.complexity_score is not None and sc.complexity:
            items_per[sc.component].append((sc.complexity_score, sc.complexity))
    for c in components:
        comp_files = members.get(c.name, [])
        code_files = [
            f for f in comp_files if f.language in CODE_LANGUAGES and f.category != "test"
        ]
        comp_units = [u for f in comp_files if f.category != "test" for u in units.get(f.path, [])]
        cc = [u.cyclomatic for u in comp_units]
        loc_code = sum(f.loc_code for f in comp_files)
        dup = sum(result.duplicated.get(f.path, 0) for f in comp_files)
        tests = sum(1 for f in comp_files if f.category == Category.TEST)
        items = items_per.get(c.name, [])
        mean = round(sum(s for s, _ in items) / len(items), 2) if items else None
        comp_services = [s for s in services if s.component == c.name]
        bands: dict[str, int] = defaultdict(int)
        for _, b in items:
            bands[b] += 1
        result.metrics.append(
            Metric(
                id=make_id("metric", c.id),
                component=c.name,
                files=len(comp_files),
                loc_code=loc_code,
                loc_comment=sum(f.loc_comment for f in comp_files),
                loc_blank=sum(f.loc_blank for f in comp_files),
                units=len(cc),
                complexity_avg=round(sum(cc) / len(cc), 2) if cc else None,
                complexity_max=max(cc) if cc else None,
                complex_units=sum(1 for v in cc if v >= cfg.complexity.high_unit),
                duplicated_lines=dup,
                duplication_pct=round(100.0 * dup / loc_code, 1) if loc_code else None,
                test_files=tests,
                test_ratio=round(tests / len(code_files), 3) if code_files else None,
                dead_code_candidates=dead_per.get(c.name, 0),
                fan_in_max=max((s.fan_in for s in comp_services), default=0),
                fan_out_max=max((s.fan_out for s in comp_services), default=0),
                complexity=band(mean, thresholds) if mean is not None else None,
                complexity_score=mean,
                bands={b: bands[b] for b in BANDS if bands.get(b)},
                evidence=c.evidence,
            )
        )
    return result
