"""The linker: runs after every file has been analysed (or reloaded from the cache).

1. Merge per-file facts (integrations, libraries, tech, jobs, DB objects).
2. Resolve by-name references against symbols and file paths into ``CodeEdge``s; references
   that should resolve but do not become ``unresolved-reference`` findings.
3. Cross-link: screens reached from services (partials vs screens), SQL to tables (CRUD) and
   procedures, services to the tables and integrations they reach, batch jobs to services,
   COBOL sub-programs pruned from the service count, facts to components, component types.

Everything here is recomputed on every run, so new inputs (an Autosys export, a DDL dump) link
to source files whose analysis came from the cache.
"""

from __future__ import annotations

import posixpath
import re
from collections import defaultdict, deque
from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass, field
from typing import Any, TypeVar

from legacy_audit.analyzers.base import Facts, Ref, Symbol
from legacy_audit.linker.aggregate import (
    dedupe,
    merge_batch_jobs,
    merge_db_objects,
    merge_integrations,
    merge_libraries,
    merge_tech,
    techs_from_libraries,
)
from legacy_audit.linker.maven import resolve_versions
from legacy_audit.model import (
    BatchJob,
    CodeEdge,
    Component,
    ComponentType,
    DbObject,
    EntryPoint,
    Evidence,
    Fact,
    FileRecord,
    Finding,
    Integration,
    Library,
    Screen,
    Service,
    Severity,
    SqlStatement,
    TechItem,
    make_id,
)

NAV_EDGES = {"forwards", "renders", "submits", "links"}
REACH_EDGES = {"calls", "uses", "invokes", "runs", "triggers", "includes"}
PAGE_TECH = {"jsp", "jsf", "html", "velocity", "freemarker"}
WEB_SERVICE_KINDS = {
    "struts-action",
    "spring-handler",
    "servlet",
    "soap-operation",
    "rest-operation",
    "ejb-method",
    "webflow",
    "remoting",
}
VIEW_EXTENSIONS = (".jsp", ".jspx", ".xhtml", ".html", ".vm", ".ftl")
REACH_DEPTH = 4
F = TypeVar("F", bound=Fact)


@dataclass
class LinkResult:
    entities: dict[str, list[Fact]]
    unresolved: int = 0
    edges: int = 0
    notes: list[str] = field(default_factory=list)


@dataclass
class _Index:
    records: dict[str, FileRecord]  # path -> record
    suffix: dict[str, list[str]]  # path suffix (at '/' boundaries), lower case -> paths
    basename: dict[str, list[str]]  # lower-case file name -> paths
    stem: dict[str, list[str]]  # lower-case name without extension -> paths
    symbols: dict[tuple[str, str], list[Symbol]]
    symbols_ci: dict[tuple[str, str], list[Symbol]]
    pages: dict[str, str]  # path -> screen id
    urls: dict[str, list[str]]  # service url -> service ids
    resolvers: list[Symbol]


def _build_index(
    records: list[FileRecord], symbols: list[Symbol], services: list[Service]
) -> _Index:
    by_path = {r.path: r for r in records}
    suffix: dict[str, list[str]] = defaultdict(list)
    basename: dict[str, list[str]] = defaultdict(list)
    stem: dict[str, list[str]] = defaultdict(list)
    for p in by_path:
        parts = p.lower().split("/")
        for i in range(len(parts)):
            suffix["/".join(parts[i:])].append(p)
        basename[parts[-1]].append(p)
        stem[parts[-1].rsplit(".", 1)[0]].append(p)
    sym: dict[tuple[str, str], list[Symbol]] = defaultdict(list)
    sym_ci: dict[tuple[str, str], list[Symbol]] = defaultdict(list)
    pages: dict[str, str] = {}
    for s in symbols:
        sym[(s.kind, s.name)].append(s)
        sym_ci[(s.kind, s.name.lower())].append(s)
        if s.kind == "page" and s.owner:
            pages[s.file] = s.owner
    urls: dict[str, list[str]] = defaultdict(list)
    for svc in services:
        if svc.url:
            urls[svc.url].append(svc.id)
    resolvers = [s for s in symbols if s.kind == "view-resolver"]
    return _Index(by_path, suffix, basename, stem, sym, sym_ci, pages, urls, resolvers)


def _file_id(rec: FileRecord) -> str:
    return rec.id


class _Linker:
    def __init__(self, records: list[FileRecord], facts: Iterable[Facts]) -> None:
        self.records = records
        self.by_path = {r.path: r for r in records}
        self.entities: dict[str, list[Fact]] = defaultdict(list)
        self.symbols: list[Symbol] = []
        self.refs: list[Ref] = []
        for f in facts:
            for entity, items in f.entities.items():
                self.entities[entity].extend(items)
            self.symbols.extend(f.symbols)
            self.refs.extend(f.refs)
        self.edges: dict[str, CodeEdge] = {}
        self.findings: list[Finding] = []
        self.unresolved = 0
        self._jobs: list[BatchJob] = []
        self.ix = _build_index(records, [], [])

    # ---- helpers ------------------------------------------------------------------------------
    def comp(self, path: str | None) -> str | None:
        rec = self.by_path.get(path or "")
        return rec.component if rec else None

    def _pick(self, paths: list[str], near: str) -> str | None:
        """Best candidate for a reference made in file ``near``: same component first, then
        the longest shared folder prefix, then alphabetical."""
        if not paths:
            return None
        if len(paths) == 1:
            return paths[0]
        comp = self.comp(near)
        near_dir = near.split("/")

        def score(p: str) -> tuple[int, int, str]:
            same = 1 if comp is not None and self.comp(p) == comp else 0
            common = 0
            for a, b in zip(p.split("/"), near_dir, strict=False):
                if a != b:
                    break
                common += 1
            return (-same, -common, p)

        return sorted(paths, key=score)[0]

    def _path(self, target: str, near: str) -> str | None:
        t = posixpath.normpath(target.split("?", 1)[0].split("#", 1)[0]).lstrip("/").lower()
        if not t or t == ".":
            return None
        return self._pick(self.ix.suffix.get(t, []), near)

    def _node_for_file(self, path: str, page: bool = False) -> str:
        if page and path in self.ix.pages:
            return self.ix.pages[path]
        return _file_id(self.by_path[path])

    def _node_for_symbol(self, s: Symbol) -> str:
        if s.owner:
            return s.owner
        if s.kind == "tiles-definition":
            return f"tiles:{s.name}"
        rec = self.by_path.get(s.file)
        return _file_id(rec) if rec else f"file:{s.file}"

    def _symbol(self, kind: str, name: str, near: str) -> Symbol | None:
        cands = self.ix.symbols.get((kind, name)) or self.ix.symbols_ci.get((kind, name.lower()))
        if not cands:
            return None
        best = self._pick([c.file for c in cands], near)
        return next(c for c in cands if c.file == best)

    def _source(self, ref: Ref) -> str:
        src = ref.source
        kind, _, rest = src.partition(":")
        if kind == "file":
            rec = self.by_path.get(rest)
            return _file_id(rec) if rec else src
        if kind == "class":
            s = self._symbol("java-class", rest, ref.file)
            if s:
                return self._node_for_symbol(s)
            rec = self.by_path.get(ref.file)
            return _file_id(rec) if rec else src
        if kind == "bean":
            s = self._symbol("service", src, ref.file)
            if s and s.owner:
                return s.owner
            rec = self.by_path.get(ref.file)
            return _file_id(rec) if rec else src
        if kind in ("service", "screen", "batch_job"):
            return rest
        return src

    def _resolve(self, ref: Ref) -> str | None:
        target = ref.target
        for kind in ref.target_kinds:
            node: str | None = None
            if kind == "path":
                p = self._path(target, ref.file)
                node = self._node_for_file(p, page=True) if p else None
            elif kind == "file-name":
                p = self._pick(self.ix.basename.get(target.lower(), []), ref.file)
                node = self._node_for_file(p) if p else None
            elif kind == "view":
                node = self._view(target, ref.file)
            elif kind == "url":
                ids = self.ix.urls.get(target) or self.ix.urls.get(target + ".do")
                node = ids[0] if ids else None
            else:
                s = self._symbol(kind, target, ref.file)
                node = self._node_for_symbol(s) if s else None
            if node:
                return node
        if "file-name" in ref.target_kinds:  # last resort: same name, any extension
            p = self._pick(self.ix.stem.get(target.lower(), []), ref.file)
            if p:
                return self._node_for_file(p)
        return None

    def _view(self, view: str, near: str) -> str | None:
        for r in self.ix.resolvers:
            if r.data.get("tiles"):
                s = self._symbol("tiles-definition", view, near)
                if s:
                    return self._node_for_symbol(s)
                continue
            p = self._path(f"{r.data.get('prefix', '')}{view}{r.data.get('suffix', '')}", near)
            if p:
                return self._node_for_file(p, page=True)
        s = self._symbol("tiles-definition", view, near)
        if s:
            return self._node_for_symbol(s)
        if view.lower().endswith(VIEW_EXTENSIONS):
            p = self._path(view, near)
            return self._node_for_file(p, page=True) if p else None
        for ext in VIEW_EXTENSIONS:
            p = self._path(view + ext, near)
            if p:
                return self._node_for_file(p, page=True)
        return None

    def _edge(self, source: str, target: str, kind: str, ref: Ref, resolved: bool = True) -> None:
        eid = make_id("edge", kind, source, target)
        ev = Evidence(file=ref.file, line=ref.line)
        prev = self.edges.get(eid)
        if prev is None:
            self.edges[eid] = CodeEdge(
                id=eid, source=source, target=target, kind=kind, resolved=resolved, evidence=[ev]
            )
        elif len(prev.evidence) < 20 and ev not in prev.evidence:
            prev.evidence.append(ev)

    # ---- main ---------------------------------------------------------------------------------
    def run(self) -> LinkResult:
        ent = self.entities
        libraries = resolve_versions(list(_typed(ent, "libraries", Library)), self.symbols)
        libraries = merge_libraries(libraries)
        tech = merge_tech([*_typed(ent, "tech_stack", TechItem), *techs_from_libraries(libraries)])
        integrations = merge_integrations(_typed(ent, "integrations", Integration))
        jobs = merge_batch_jobs(_typed(ent, "batch_jobs", BatchJob))
        db_objects = merge_db_objects(_typed(ent, "db_objects", DbObject))
        services = dedupe(_typed(ent, "services", Service))
        screens = dedupe(_typed(ent, "screens", Screen))
        entry_points = dedupe(_typed(ent, "entry_points", EntryPoint))
        sql = dedupe(_typed(ent, "sql_statements", SqlStatement))
        self.findings = dedupe(_typed(ent, "findings", Finding))

        # Batch jobs are services (boxes are containers; file watchers only wait).
        job_service: dict[str, str] = {}
        for job in jobs:
            if job.kind in ("box", "file-watcher"):
                continue
            sid = make_id("service", "batch-job", job.id)
            job_service[sid] = job.id
            services.append(
                Service(
                    id=sid,
                    name=job.name,
                    kind="batch-job",
                    file=job.evidence[0].file if job.evidence else None,
                    handler=job.command or ",".join(job.programs) or None,
                    evidence=list(job.evidence[:1]),
                )
            )

        self.ix = _build_index(self.records, self.symbols, services)
        self._link_refs()
        edges = list(self.edges.values())

        incoming: dict[str, list[CodeEdge]] = defaultdict(list)
        outgoing: dict[str, list[CodeEdge]] = defaultdict(list)
        for e in edges:
            if e.resolved:
                incoming[e.target].append(e)
                outgoing[e.source].append(e)

        screens = self._classify_screens(screens, services, incoming, outgoing)
        services = self._prune_cobol(services, incoming)
        db_objects = self._link_sql(sql, db_objects)
        self._job_components(jobs, outgoing)
        self._reach(services, job_service, sql, integrations, outgoing)
        self._components(services, screens, sql, jobs, entry_points, integrations)

        out: dict[str, list[Fact]] = {
            "entry_points": _sorted(entry_points, lambda x: (x.file, x.line or 0, x.name)),
            "services": _sorted(services, lambda x: (x.kind, x.name, x.file or "")),
            "screens": _sorted(screens, lambda x: (x.partial, x.file or "", x.name)),
            "sql_statements": _sorted(sql, lambda x: (x.file, x.line, x.fingerprint)),
            "db_objects": _sorted(db_objects, lambda x: (x.object_type, x.name)),
            "integrations": _sorted(integrations, lambda x: (x.type, x.target, x.direction)),
            "dependencies": _sorted(edges, lambda x: (x.kind, x.source, x.target)),
            "batch_jobs": _sorted(jobs, lambda x: (x.scheduler, x.name)),
            "libraries": _sorted(
                libraries, lambda x: (x.language, x.group or "", x.artifact, x.version or "")
            ),
            "tech_stack": list(tech),
            "findings": _sorted(self.findings, lambda x: (x.rule_id, x.file or "", x.line or 0)),
        }
        return LinkResult(out, unresolved=self.unresolved, edges=len(edges))

    def _link_refs(self) -> None:
        for ref in self.refs:
            source = self._source(ref)
            target = self._resolve(ref)
            if target is not None:
                if ref.data.get("reverse") == "true":
                    self._edge(target, source, ref.kind, ref)
                else:
                    self._edge(source, target, ref.kind, ref)
                continue
            if not ref.report_unresolved:
                continue
            self.unresolved += 1
            self._edge(source, f"unresolved:{ref.target}", ref.kind, ref, resolved=False)
            self.findings.append(
                Finding(
                    id=make_id(
                        "finding", "unresolved-reference", ref.file, ref.line, ref.kind, ref.target
                    ),
                    rule_id="unresolved-reference",
                    severity=Severity.INFO,
                    category="linking",
                    file=ref.file,
                    line=ref.line,
                    message=f"{ref.source_label} {ref.kind} '{ref.target}', which was not found "
                    f"in the scanned files (looked for: {', '.join(ref.target_kinds)})",
                    evidence=[Evidence(file=ref.file, line=ref.line)],
                )
            )

    # ---- screens ------------------------------------------------------------------------------
    def _classify_screens(
        self,
        screens: list[Screen],
        services: list[Service],
        incoming: dict[str, list[CodeEdge]],
        outgoing: dict[str, list[CodeEdge]],
    ) -> list[Screen]:
        service_ids = {s.id for s in services}

        def navigated(node: str) -> list[str]:
            return [e.source for e in incoming.get(node, []) if e.kind in NAV_EDGES]

        # Tiles definitions: the pages a navigated definition puts in, when no other
        # definition uses them (layouts and headers are shared, so they stay partials).
        tiles_sources: dict[str, list[str]] = {}  # page id -> sources navigating to its def
        included_by_defs: dict[str, set[str]] = defaultdict(set)
        for node, edges in outgoing.items():
            if node.startswith("tiles:"):
                for e in edges:
                    if e.kind == "includes":
                        included_by_defs[e.target].add(node)
        for page, defs in included_by_defs.items():
            if len(defs) == 1:
                d = next(iter(defs))
                srcs = navigated(d)
                if srcs:
                    tiles_sources[page] = srcs

        by_service: dict[str, list[str]] = defaultdict(list)
        out: list[Screen] = []
        for scr in screens:
            if scr.technology not in PAGE_TECH:
                srcs = navigated(scr.id)
                scr.targeted_by = sorted(set(srcs))
                for s in srcs:
                    if s in service_ids:
                        by_service[s].append(scr.id)
                out.append(scr)
                continue
            srcs = navigated(scr.id) + tiles_sources.get(scr.id, [])
            included = any(e.kind == "includes" for e in incoming.get(scr.id, []))
            if scr.partial or (not srcs and included):
                scr.partial = True
                scr.targeted_by = sorted({e.source for e in incoming.get(scr.id, [])})
                out.append(scr)
                continue
            if not srcs and scr.technology == "html":
                continue  # a static HTML page nothing points to is not a screen
            scr.targeted_by = sorted(set(srcs))
            for s in srcs:
                if s in service_ids:
                    by_service[s].append(scr.id)
            out.append(scr)
        for svc in services:
            if svc.id in by_service:
                svc.screens = sorted(set(by_service[svc.id]))
        return out

    def _prune_cobol(
        self, services: list[Service], incoming: dict[str, list[CodeEdge]]
    ) -> list[Service]:
        """A COBOL program that is only CALLed / LINKed by other programs is a sub-program,
        not a service; programs run by JCL or a scheduler, or owning a CICS transaction, stay."""
        out = []
        for svc in services:
            if svc.kind in ("cobol-program", "cics-transaction"):
                kinds = {e.kind for e in incoming.get(svc.id, [])}
                own_transaction = svc.kind == "cics-transaction" and svc.name != svc.handler
                if "calls" in kinds and "runs" not in kinds and not own_transaction:
                    continue
            out.append(svc)
        return out

    # ---- SQL ----------------------------------------------------------------------------------
    def _link_sql(self, sql: list[SqlStatement], objects: list[DbObject]) -> list[DbObject]:
        by_name: dict[str, DbObject] = {}
        by_short: dict[str, list[DbObject]] = defaultdict(list)
        for o in objects:
            if o.object_type in ("table", "view", "synonym", "sequence"):
                by_name[o.name.upper()] = o
                by_short[o.name.upper().rsplit(".", 1)[-1]].append(o)
        entities = {
            s.name: s.data.get("table", s.name).upper()
            for s in self.symbols
            if s.kind == "jpa-entity"
        }
        procs: dict[str, DbObject] = {}
        for o in objects:
            if o.object_type in ("package", "procedure", "function"):
                procs[o.name.upper()] = o
                procs[o.name.upper().rsplit(".", 1)[-1]] = o

        def table(name: str) -> DbObject:
            key = name.upper()
            if key in by_name:
                return by_name[key]
            short = by_short.get(key.rsplit(".", 1)[-1], [])
            if len(short) == 1:
                return short[0]
            obj = DbObject(id=make_id("db_object", "table", key), name=key, object_type="table")
            by_name[key] = obj
            by_short[key.rsplit(".", 1)[-1]].append(obj)
            objects.append(obj)
            return obj

        for st in sql:
            if st.dialect == "hql":
                st.tables = sorted(
                    {
                        entities.get(t.rsplit(".", 1)[-1], entities.get(t, t.upper()))
                        for t in st.tables
                    }
                )
            target = _dml_target(st)
            for t in st.tables:
                obj = table(t)
                if not obj.evidence:
                    obj.evidence = [Evidence(file=st.file, line=st.line)]
                if st.id not in obj.referenced_by:
                    obj.referenced_by.append(st.id)
                ops = _crud(st.type, t, target)
                for op in ops:
                    obj.crud.setdefault(op, [])
                    if st.id not in obj.crud[op]:
                        obj.crud[op].append(st.id)
            for p in st.procedures:
                key = p.upper()
                proc = (
                    procs.get(key)
                    or procs.get(key.split(".", 1)[0])
                    or procs.get(key.rsplit(".", 1)[-1])
                )
                if proc is not None and st.id not in proc.referenced_by:
                    proc.referenced_by.append(st.id)
        return objects

    # ---- reach: what a service touches -------------------------------------------------------
    def _node_files(
        self, services: list[Service], job_service: dict[str, str], jobs_by_id: dict[str, BatchJob]
    ) -> dict[str, str]:
        out: dict[str, str] = {r.id: r.path for r in self.records}
        for svc in services:
            if svc.file:
                out[svc.id] = svc.file
        for jid, job in jobs_by_id.items():
            if job.evidence:
                out[jid] = job.evidence[0].file
        return out

    def _reach(
        self,
        services: list[Service],
        job_service: dict[str, str],
        sql: list[SqlStatement],
        integrations: list[Integration],
        outgoing: dict[str, list[CodeEdge]],
    ) -> None:
        sql_by_file: dict[str, set[str]] = defaultdict(set)
        for st in sql:
            sql_by_file[st.file].update(st.tables)
        integ_by_file: dict[str, set[str]] = defaultdict(set)
        for it in integrations:
            for f in it.source_files:
                integ_by_file[f].add(f"{it.type} {it.target}")
        jobs_by_id = {j.id: j for j in self._jobs}
        node_file = self._node_files(services, job_service, jobs_by_id)
        for svc in services:
            start = job_service.get(svc.id, svc.id)
            files: set[str] = set()
            if svc.file and svc.kind != "batch-job":
                files.add(svc.file)
            seen = {start}
            queue = deque([(start, 0)])
            while queue:
                node, depth = queue.popleft()
                if node != start and node in node_file:
                    files.add(node_file[node])
                if depth >= REACH_DEPTH:
                    continue
                for e in outgoing.get(node, []):
                    if e.kind in REACH_EDGES and e.target not in seen:
                        seen.add(e.target)
                        queue.append((e.target, depth + 1))
            tables: set[str] = set()
            calls: set[str] = set()
            for f in files:
                tables |= sql_by_file.get(f, set())
                calls |= integ_by_file.get(f, set())
            svc.tables = sorted(tables)
            svc.external_calls = sorted(calls)

    def _job_components(self, jobs: list[BatchJob], outgoing: dict[str, list[CodeEdge]]) -> None:
        """Jobs defined outside the source tree (JIL exports) belong to the component of what
        they run."""
        self._jobs = jobs
        file_comp = {r.id: r.component for r in self.records}
        for job in jobs:
            comp = self.comp(job.evidence[0].file if job.evidence else None)
            if comp is None:
                for e in outgoing.get(job.id, []):
                    if e.kind in ("invokes", "runs", "triggers"):
                        comp = file_comp.get(e.target) or self._owner_component(e.target)
                        if comp:
                            break
            job.component = comp

    def _owner_component(self, node: str) -> str | None:
        for s in self.symbols:
            if s.owner == node:
                return self.comp(s.file)
        return None

    # ---- components ---------------------------------------------------------------------------
    def _components(
        self,
        services: list[Service],
        screens: list[Screen],
        sql: list[SqlStatement],
        jobs: list[BatchJob],
        entry_points: list[EntryPoint],
        integrations: list[Integration],
    ) -> None:
        for svc in services:
            if svc.kind != "batch-job":
                svc.component = self.comp(svc.file)
        job_comp = {make_id("service", "batch-job", j.id): j.component for j in jobs}
        for svc in services:
            if svc.kind == "batch-job":
                svc.component = job_comp.get(svc.id)
        for scr in screens:
            scr.component = self.comp(scr.file)
        for st in sql:
            st.component = self.comp(st.file)
        for ep in entry_points:
            ep.component = self.comp(ep.file)
        for it in integrations:
            it.components = sorted({c for f in it.source_files if (c := self.comp(f))})


def _typed(entities: dict[str, list[Fact]], name: str, model: type[F]) -> list[F]:
    return [f for f in entities.get(name, []) if isinstance(f, model)]


def _sorted(items: Sequence[F], key: Callable[[F], Any]) -> list[Fact]:
    ordered: list[F] = sorted(items, key=key)
    return list(ordered)


_TARGET_RX = re.compile(
    r"^\s*(?:INSERT\s+(?:/\*.*?\*/\s*)?INTO|UPDATE|DELETE\s+(?:FROM\s+)?|MERGE\s+INTO)\s+([\w.$#\"]+)",
    re.I | re.S,
)


def _dml_target(st: SqlStatement) -> str | None:
    m = _TARGET_RX.match(st.text)
    return m.group(1).replace('"', "").upper() if m else None


def _crud(stype: str, table: str, target: str | None) -> list[str]:
    is_target = target is not None and (
        table.upper() == target or table.upper().rsplit(".", 1)[-1] == target.rsplit(".", 1)[-1]
    )
    if stype == "INSERT":
        return ["C"] if is_target else ["R"]
    if stype == "UPDATE":
        return ["U"] if is_target else ["R"]
    if stype == "DELETE":
        return ["D"] if is_target else ["R"]
    if stype == "MERGE":
        return ["C", "U"] if is_target else ["R"]
    return ["R"]


def classify_components(
    components: list[Component],
    entities: dict[str, list[Fact]],
    records: list[FileRecord],
    fixed: set[str],
) -> None:
    """Refine each component's type from what was found in it (overridden types are kept)."""
    services: dict[str, set[str]] = defaultdict(set)
    for s in entities.get("services", []):
        if isinstance(s, Service) and s.component:
            services[s.component].add(s.kind)
    screens: dict[str, set[str]] = defaultdict(set)
    for sc in entities.get("screens", []):
        if isinstance(sc, Screen) and sc.component and not sc.partial:
            screens[sc.component].add(sc.technology)
    jobs: set[str] = {
        j.component
        for j in entities.get("batch_jobs", [])
        if isinstance(j, BatchJob) and j.component
    }
    entries: dict[str, set[str]] = defaultdict(set)
    for ep in entities.get("entry_points", []):
        if isinstance(ep, EntryPoint) and ep.component:
            entries[ep.component].add(ep.kind)
    cft: set[str] = set()
    for it in entities.get("integrations", []):
        if isinstance(it, Integration) and it.type in ("CFT", "FTP", "SFTP", "SCP"):
            cft.update(it.components)
    cats: dict[str, set[str]] = defaultdict(set)
    for r in records:
        if r.component:
            cats[r.component].add(str(r.category))
    for c in components:
        names = [
            str(ep.name)
            for ep in entities.get("entry_points", [])
            if isinstance(ep, EntryPoint) and ep.component == c.name
        ]
        c.entry_points = sorted(set(names))
        if c.name in fixed:
            continue
        kinds = services.get(c.name, set())
        techs = screens.get(c.name, set())
        if kinds & WEB_SERVICE_KINDS or techs & PAGE_TECH:
            c.type = ComponentType.WEB
        elif "cics-transaction" in kinds or "BMS" in techs:
            c.type = ComponentType.ONLINE_MAINFRAME
        elif (
            c.name in jobs
            or kinds & {"cobol-program", "c-executable", "batch-job"}
            or entries.get(c.name, set()) & {"main", "scheduler", "spring-batch"}
        ):
            c.type = ComponentType.BATCH
        elif c.name in cft and cats.get(c.name, set()) <= {
            "script",
            "config",
            "doc",
            "other",
            "job",
        }:
            c.type = ComponentType.CFT
        # Otherwise the inventory-based type from phase 1 stands.


def link(records: list[FileRecord], facts: Iterable[Facts]) -> LinkResult:
    return _Linker(records, facts).run()
