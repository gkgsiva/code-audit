"""The headline counts (services, screens, SQL, integrations, jobs, libraries...), computed in one
place from the facts so the summary, the reports and the sample tests agree."""

from __future__ import annotations

from collections import Counter
from typing import Any

from legacy_audit.model import (
    BatchJob,
    CodeEdge,
    DbObject,
    EntryPoint,
    Finding,
    Integration,
    Library,
    Screen,
    Service,
    SqlStatement,
)
from legacy_audit.store.writer import FactSet

PROCEDURE_TYPES = ("package", "procedure", "function")


def _sorted(c: Counter[str]) -> dict[str, int]:
    return {k: c[k] for k in sorted(c)}


def counts(facts: FactSet) -> dict[str, Any]:
    services = [s for s in facts.get("services", []) if isinstance(s, Service)]
    screens = [s for s in facts.get("screens", []) if isinstance(s, Screen)]
    sql = [s for s in facts.get("sql_statements", []) if isinstance(s, SqlStatement)]
    objects = [o for o in facts.get("db_objects", []) if isinstance(o, DbObject)]
    integrations = [i for i in facts.get("integrations", []) if isinstance(i, Integration)]
    jobs = [j for j in facts.get("batch_jobs", []) if isinstance(j, BatchJob)]
    libs = [lib for lib in facts.get("libraries", []) if isinstance(lib, Library)]
    entries = [e for e in facts.get("entry_points", []) if isinstance(e, EntryPoint)]
    edges = [e for e in facts.get("dependencies", []) if isinstance(e, CodeEdge)]
    findings = [f for f in facts.get("findings", []) if isinstance(f, Finding)]
    real_screens = [s for s in screens if not s.partial]
    counted_jobs = [j for j in jobs if j.kind != "box"]
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
        "batch_jobs": {
            "total": len(counted_jobs),
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
    }
