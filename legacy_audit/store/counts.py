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


def unscheduled_batches(facts: FactSet) -> list[str]:
    """Components doing batch work that no scheduler definition covers (decision 116). Each is
    one batch, however many scripts and programs it holds: its ksh scripts are parts of it. A
    component is covered when a job is defined in it or a job runs something in it (directly
    or through the scripts and programs it starts)."""
    jobs = [j for j in facts.get("batch_jobs", []) if isinstance(j, BatchJob)]
    covered = {j.component for j in jobs if j.component}
    owner: dict[str, str] = {}
    for kind in ("inventory", "services", "entry_points"):
        for f in facts.get(kind, []):
            comp = getattr(f, "component", None)
            if comp:
                owner[f.id] = comp
    outgoing: dict[str, list[str]] = {}
    for e in facts.get("dependencies", []):
        if isinstance(e, CodeEdge) and e.resolved and e.kind in RUN_EDGES:
            outgoing.setdefault(e.source, []).append(e.target)
    seen = {j.id for j in jobs} | {make_id("service", "batch-job", j.id) for j in jobs}
    todo = list(seen)
    while todo:
        node = todo.pop()
        if node in owner:
            covered.add(owner[node])
        for t in outgoing.get(node, []):
            if t not in seen:
                seen.add(t)
                todo.append(t)
    return sorted(
        c.name
        for c in facts.get("components", [])
        if isinstance(c, Component) and "batch" in c.roles and c.name not in covered
    )


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
    unscheduled = unscheduled_batches(facts)
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
            "total": len(counted_jobs) + len(unscheduled),
            "scheduled": len(counted_jobs),
            "unscheduled": len(unscheduled),
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
