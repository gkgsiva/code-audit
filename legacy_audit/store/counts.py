"""The headline counts (services, screens, SQL, integrations, jobs, libraries...), computed in one
place from the facts so the summary, the reports and the sample tests agree."""

from __future__ import annotations

from collections import Counter
from typing import Any

from legacy_audit.model import (
    BatchJob,
    CodeEdge,
    Component,
    DbObject,
    EntryPoint,
    FileRecord,
    Finding,
    Integration,
    Library,
    Screen,
    Service,
    SqlStatement,
    WebService,
    make_id,
)
from legacy_audit.store.writer import FactSet

PROCEDURE_TYPES = ("package", "procedure", "function")


def _sorted(c: Counter[str]) -> dict[str, int]:
    return {k: c[k] for k in sorted(c)}


RUN_EDGES = ("invokes", "runs", "triggers", "calls", "includes")


# Programs and entry points that start batch work (the same lists as the Batch pages).
PROGRAM_SERVICES = ("c-executable", "cobol-program")
PROGRAM_ENTRY = ("main", "scheduler", "spring-batch", "windows-service")
ONLINE_ROLES = {"web", "online-mainframe", "frontend", "api", "desktop"}


def _covered(facts: FactSet, runs: dict[str, list[str]], owner: dict[str, str]) -> set[str]:
    """Components a scheduler definition covers: a job is defined in it, or a job runs
    something in it (directly or through the scripts and programs it starts)."""
    jobs = [j for j in facts.get("batch_jobs", []) if isinstance(j, BatchJob)]
    covered = {j.component for j in jobs if j.component}
    seen = {j.id for j in jobs} | {make_id("service", "batch-job", j.id) for j in jobs}
    todo = list(seen)
    while todo:
        node = todo.pop()
        if node in owner:
            covered.add(owner[node])
        for t in runs.get(node, []):
            if t not in seen:
                seen.add(t)
                todo.append(t)
    return covered


def unscheduled_batch_entries(facts: FactSet) -> dict[str, list[dict[str, Any]]]:
    """Batch work no scheduler definition covers, per component (decisions 116 and 168).

    Each *entry point* of a batch component is one batch: a top-level shell script (no other
    script or program runs it), a C or COBOL batch program no script starts, a Java ``main`` (or
    Spring Batch job, Windows service) no script starts. What an entry point runs (helper scripts,
    the program, its SQL and properties) are its steps, not batches. A batch component with no
    entry point found is still one batch, unless an entry point of another component runs it
    (a Maven batch module started by a ksh in a ``scripts/`` folder): then it is a step of that
    batch. Returns component name -> entry points (empty list: the component is the batch)."""
    from legacy_audit.inventory.components import in_batch_folder, in_shared_folder

    comps = [c for c in facts.get("components", []) if isinstance(c, Component)]
    records = [r for r in facts.get("inventory", []) if isinstance(r, FileRecord) and r.component]
    owner: dict[str, str] = {}
    for kind in ("inventory", "services", "entry_points"):
        for f in facts.get(kind, []):
            comp = getattr(f, "component", None)
            if comp:
                owner[f.id] = comp
    runs: dict[str, list[str]] = {}
    called: set[str] = set()
    for e in facts.get("dependencies", []):
        if isinstance(e, CodeEdge) and e.resolved and e.kind in RUN_EDGES:
            runs.setdefault(e.source, []).append(e.target)
            called.add(e.target)
    covered = _covered(facts, runs, owner)
    roles = {c.name: {*c.roles, str(c.type)} for c in comps}
    batch = {c.name for c in comps if "batch" in roles[c.name] and c.name not in covered}
    file_id = {r.path: r.id for r in records}

    entries: dict[str, list[dict[str, Any]]] = {name: [] for name in batch}
    taken_files: set[str] = set()
    for r in sorted(records, key=lambda r: r.path):
        comp = str(r.component)
        if r.language != "shell" or comp not in batch or r.id in called:
            continue
        if roles[comp] & ONLINE_ROLES and not in_batch_folder(r.path):
            continue  # deploy and admin scripts of an online application
        if in_shared_folder(r.path):
            continue  # a helper the batches share
        entries[comp].append(
            {"id": r.id, "name": r.path.rsplit("/", 1)[-1], "kind": "script", "file": r.path,
             "line": None, "component": comp}
        )  # fmt: skip
        taken_files.add(r.path)
    services = [s for s in facts.get("services", []) if isinstance(s, Service)]
    # Files something runs: a program started by a script is a step, wherever the script is.
    file_of = {r.id: r.path for r in records}
    file_of.update({s.id: s.evidence[0].file for s in services if s.evidence})
    run_files = {file_of[t] for t in called if t in file_of}
    for svc in sorted(services, key=lambda s: s.name):
        comp = svc.component or ""
        if svc.kind not in PROGRAM_SERVICES or comp not in batch or svc.id in called:
            continue
        sfile = svc.evidence[0].file if svc.evidence else None
        if sfile and (sfile in taken_files or sfile in run_files):
            continue
        entries[comp].append(
            {"id": svc.id, "name": svc.name, "kind": "program", "file": sfile,
             "line": svc.evidence[0].line if svc.evidence else None, "component": comp,
             "service_id": svc.id}
        )  # fmt: skip
        if sfile:
            taken_files.add(sfile)
    eps = [e for e in facts.get("entry_points", []) if isinstance(e, EntryPoint)]
    for ep in sorted(eps, key=lambda e: e.name):
        comp = ep.component or ""
        if ep.kind not in PROGRAM_ENTRY or comp not in batch or ep.id in called:
            continue
        if ep.kind == "main" and "desktop" in roles[comp]:
            continue  # the main() of a desktop application opens its windows
        if ep.file in taken_files or ep.file in run_files:
            continue
        entries[comp].append(
            {"id": ep.id, "name": ep.name.rsplit(".", 1)[-1], "kind": "program", "file": ep.file,
             "line": ep.line, "component": comp}
        )  # fmt: skip
        taken_files.add(ep.file)

    # Components an entry point of another component reaches are steps of that batch.
    reached: set[str] = set()
    for comp, items in entries.items():
        seen = {i["id"] for i in items} | {
            file_id[i["file"]] for i in items if i["file"] in file_id
        }
        todo = list(seen)
        while todo:
            node = todo.pop()
            other = owner.get(node)
            if other and other != comp:
                reached.add(other)
            for t in runs.get(node, []):
                if t not in seen:
                    seen.add(t)
                    todo.append(t)
    return {name: items for name, items in sorted(entries.items()) if items or name not in reached}


def unscheduled_batches(facts: FactSet) -> list[str]:
    """Batch components no scheduler definition covers (see ``unscheduled_batch_entries``)."""
    return sorted(unscheduled_batch_entries(facts))


def unscheduled_count(entries: dict[str, list[dict[str, Any]]]) -> int:
    """One batch per entry point; a batch component with none found is one batch."""
    return sum(max(1, len(items)) for items in entries.values())


def counts(facts: FactSet) -> dict[str, Any]:
    services = [s for s in facts.get("services", []) if isinstance(s, Service)]
    screens = [s for s in facts.get("screens", []) if isinstance(s, Screen)]
    sql = [s for s in facts.get("sql_statements", []) if isinstance(s, SqlStatement)]
    objects = [o for o in facts.get("db_objects", []) if isinstance(o, DbObject)]
    integrations = [i for i in facts.get("integrations", []) if isinstance(i, Integration)]
    jobs = [j for j in facts.get("batch_jobs", []) if isinstance(j, BatchJob)]
    web = [w for w in facts.get("web_services", []) if isinstance(w, WebService)]
    libs = [lib for lib in facts.get("libraries", []) if isinstance(lib, Library)]
    entries = [e for e in facts.get("entry_points", []) if isinstance(e, EntryPoint)]
    edges = [e for e in facts.get("dependencies", []) if isinstance(e, CodeEdge)]
    findings = [f for f in facts.get("findings", []) if isinstance(f, Finding)]
    real_screens = [s for s in screens if not s.partial]
    counted_jobs = [j for j in jobs if j.kind != "box"]
    unscheduled_entries = unscheduled_batch_entries(facts)
    unscheduled = unscheduled_count(unscheduled_entries)
    return {
        "services": {"total": len(services), "by_kind": _sorted(Counter(s.kind for s in services))},
        "screens": {
            "total": len(real_screens),
            "partials": len(screens) - len(real_screens),
            "by_technology": _sorted(Counter(s.technology for s in real_screens)),
        },
        "sql": {
            "total": len(sql),
            "distinct": len({s.fingerprint for s in sql}),
            "by_type": _sorted(Counter(s.type for s in sql)),
            "by_source": _sorted(Counter(s.source for s in sql)),
            "dynamic": sum(1 for s in sql if s.dynamic),
            "not_parsed": sum(1 for s in sql if not s.parsed),
        },
        "stored_procedures": sum(1 for o in objects if o.object_type in PROCEDURE_TYPES),
        "db_objects": _sorted(Counter(o.object_type for o in objects)),
        "integrations": {
            "total": len(integrations),
            "by_type": _sorted(Counter(i.type for i in integrations)),
        },
        "web_services": {
            "total": len(web),
            "offered": sum(1 for w in web if w.side == "offered"),
            "called": sum(1 for w in web if w.side == "called"),
            "by_style": _sorted(Counter(f"{w.style} {w.side}" for w in web)),
            "operations": sum(len(w.operations) for w in web),
        },
        "batch_jobs": {
            "total": len(counted_jobs) + unscheduled,
            "scheduled": len(counted_jobs),
            "unscheduled": unscheduled,
            "boxes": len(jobs) - len(counted_jobs),
            "by_scheduler": _sorted(Counter(j.scheduler for j in counted_jobs)),
        },
        "libraries": {
            "total": len(libs),
            "declared": sum(1 for lib in libs if lib.declared),
            "undeclared": sum(1 for lib in libs if not lib.declared),
        },
        "entry_points": _sorted(Counter(e.kind for e in entries)),
        "dependencies": {
            "resolved": sum(1 for e in edges if e.resolved),
            "unresolved": sum(1 for e in edges if not e.resolved),
        },
        "findings": _sorted(Counter(f.rule_id for f in findings)),
        # Phase 3: grades, bands, dependency security.
        "findings_by_severity": _sorted(Counter(str(f.severity) for f in findings)),
        "pg_grades": _sorted(Counter(str(s.pg_grade) for s in sql if s.pg_grade)),
        "complexity": {
            "services": _sorted(Counter(s.complexity for s in services if s.complexity)),
            "screens": _sorted(Counter(s.complexity for s in real_screens if s.complexity)),
            "batch_jobs": _sorted(Counter(j.complexity for j in counted_jobs if j.complexity)),
        },
        "vulnerable_libraries": {
            "total": sum(1 for lib in libs if lib.cves),
            "by_severity": _sorted(Counter(lib.max_severity for lib in libs if lib.max_severity)),
            "vulnerabilities": len({c for lib in libs for c in lib.cves}),
        },
        "eol_libraries": sum(1 for lib in libs if lib.eol),
        "recommendations": _sorted(
            Counter(lib.recommendation.action for lib in libs if lib.recommendation)
        ),
    }
