"""Merging per-file facts into one fact set: the same integration, library, tech item, batch job
or database object found in several files becomes one fact with all its evidence."""

from __future__ import annotations

import re
from collections.abc import Iterable
from typing import TypeVar

from legacy_audit.model import (
    BatchJob,
    DbObject,
    Evidence,
    Fact,
    Integration,
    Library,
    TechItem,
    make_id,
)

MAX_EVIDENCE = 50
F = TypeVar("F", bound=Fact)


def _union(a: list[str], b: Iterable[str]) -> list[str]:
    seen = set(a)
    out = list(a)
    for x in b:
        if x not in seen:
            seen.add(x)
            out.append(x)
    return out


def _evidence(a: list[Evidence], b: list[Evidence]) -> list[Evidence]:
    keys = {(e.file, e.line) for e in a}
    out = list(a)
    for e in b:
        if len(out) >= MAX_EVIDENCE:
            break
        if (e.file, e.line) not in keys:
            keys.add((e.file, e.line))
            out.append(e)
    return out


def dedupe(items: Iterable[F]) -> list[F]:
    """Keep the first fact per id, adding later evidence to it."""
    by_id: dict[str, F] = {}
    for item in items:
        prev = by_id.get(item.id)
        if prev is None:
            by_id[item.id] = item
        else:
            prev.evidence = _evidence(prev.evidence, item.evidence)
    return list(by_id.values())


def merge_integrations(items: Iterable[Integration]) -> list[Integration]:
    by_id: dict[str, Integration] = {}
    defined: set[str] = set()
    for it in items:
        if it.direction == "defined":
            defined.add(it.id)
            it = it.model_copy(update={"usages": 0})  # a definition is not a use
        prev = by_id.get(it.id)
        if prev is None:
            by_id[it.id] = it.model_copy(deep=True)
            continue
        prev.usages += it.usages
        prev.source_files = _union(prev.source_files, it.source_files)
        prev.producers = _union(prev.producers, it.producers)
        prev.consumers = _union(prev.consumers, it.consumers)
        prev.evidence = _evidence(prev.evidence, it.evidence)
        prev.protocol = prev.protocol or it.protocol
        for k, v in it.details.items():
            prev.details.setdefault(k, v)
    out = list(by_id.values())
    for it in out:
        if it.type in ("MQ", "JMS"):
            if it.producers and it.consumers:
                it.direction = "both"
            elif it.producers:
                it.direction = "outbound"
            elif it.consumers:
                it.direction = "inbound"
            else:
                it.direction = "defined" if it.id in defined else "unknown"
    return _merge_cft(out)


def _merge_cft(items: list[Integration]) -> list[Integration]:
    """CFT flows from a configuration export (target ``*/IDF``) fold into the script usages of
    the same IDF and direction; unused exported flows are kept on their own."""
    used: dict[tuple[str, str], list[Integration]] = {}
    for it in items:
        if it.type == "CFT" and not it.target.startswith("*/"):
            used.setdefault((it.details.get("idf", ""), it.direction), []).append(it)
    out: list[Integration] = []
    for it in items:
        if it.type == "CFT" and it.target.startswith("*/"):
            matches = used.get((it.target[2:], it.direction))
            if matches:
                for m in matches:
                    m.evidence = _evidence(m.evidence, it.evidence)
                    m.details["defined_by_export"] = "true"
                    if it.details.get("file") and "file" not in m.details:
                        m.details["file"] = it.details["file"]
                continue
            it.usages = 0  # defined only
        out.append(it)
    return out


def merge_libraries(items: Iterable[Library]) -> list[Library]:
    by_id: dict[str, Library] = {}
    for lib in items:
        prev = by_id.get(lib.id)
        if prev is None:
            by_id[lib.id] = lib.model_copy(deep=True)
            continue
        prev.declared = prev.declared or lib.declared
        prev.where_used = _union(prev.where_used, lib.where_used)
        prev.evidence = _evidence(prev.evidence, lib.evidence)
        prev.sha1 = prev.sha1 or lib.sha1
        prev.scope = prev.scope or lib.scope
        if prev.source != lib.source and lib.declared and not prev.declared:
            prev.source = lib.source
    # Jars named only by file (no group) fold into a declared library with the same name.
    named: dict[tuple[str, str, str | None], list[Library]] = {}
    for lib in by_id.values():
        if lib.group:
            named.setdefault((lib.language, lib.artifact, lib.version), []).append(lib)
    out: list[Library] = []
    for lib in by_id.values():
        if lib.group is None:
            targets = named.get((lib.language, lib.artifact, lib.version))
            if targets:
                t = sorted(targets, key=lambda x: x.group or "")[0]
                t.where_used = _union(t.where_used, lib.where_used)
                t.evidence = _evidence(t.evidence, lib.evidence)
                t.sha1 = t.sha1 or lib.sha1
                continue
        out.append(lib)
    return out


# Framework and product names derived from library coordinates.
_LIB_TECH: list[tuple[re.Pattern[str], str, str]] = [
    (re.compile(r"^(struts|struts-core)$"), "Struts 1", "framework"),
    (re.compile(r"^struts2-core$"), "Struts 2", "framework"),
    (re.compile(r"^spring(-core)?$"), "Spring Framework", "framework"),
    (re.compile(r"^spring-boot$"), "Spring Boot", "framework"),
    (re.compile(r"^spring-batch-core$"), "Spring Batch", "framework"),
    (re.compile(r"^spring-security-core$"), "Spring Security", "auth"),
    (re.compile(r"^spring-webflow$"), "Spring Web Flow", "framework"),
    (re.compile(r"^(hibernate|hibernate3|hibernate-core)$"), "Hibernate", "framework"),
    (re.compile(r"^log4j$"), "Log4j 1", "library"),
    (re.compile(r"^log4j-core$"), "Log4j 2", "library"),
    (re.compile(r"^logback-classic$"), "Logback", "library"),
    (re.compile(r"^axis$"), "Apache Axis", "framework"),
    (re.compile(r"^axis2(-kernel)?$"), "Apache Axis2", "framework"),
    (re.compile(r"^cxf-core$"), "Apache CXF", "framework"),
    (re.compile(r"^mybatis$"), "MyBatis", "framework"),
    (re.compile(r"^(ibatis|ibatis-sqlmap|ibatis2-sqlmap)$"), "iBATIS", "framework"),
    (re.compile(r"^ojdbc\d*$"), "Oracle JDBC driver", "database"),
    (re.compile(r"^db2jcc\d*$"), "DB2 JDBC driver", "database"),
    (re.compile(r"^(com\.ibm\.mq(\.allclient|\.jakarta\.client)?|mq|mqjms|com\.ibm\.mqjms)$"),
     "IBM MQ client", "middleware"),
    (re.compile(r"^quartz$"), "Quartz", "scheduler"),
    (re.compile(r"^(jsf-api|myfaces-api|jakarta\.faces-api)$"), "JSF", "framework"),
    (re.compile(r"^jquery$"), "jQuery", "library"),
    (re.compile(r"^jquery[.-]ui$"), "jQuery UI", "library"),
    (re.compile(r"^angular$"), "AngularJS", "framework"),
    (re.compile(r"^bootstrap$"), "Bootstrap", "library"),
]  # fmt: skip


def techs_from_libraries(libs: Iterable[Library]) -> list[TechItem]:
    out: list[TechItem] = []
    for lib in libs:
        for rx, name, category in _LIB_TECH:
            if rx.match(lib.artifact.lower()):
                out.append(TechItem(id=make_id("tech", name, lib.version or ""), name=name,
                                    version=lib.version, category=category,
                                    evidence=list(lib.evidence[:1])))  # fmt: skip
                break
    return out


def merge_tech(items: Iterable[TechItem]) -> list[TechItem]:
    by_id: dict[str, TechItem] = {}
    for t in items:
        prev = by_id.get(t.id)
        if prev is None:
            by_id[t.id] = t.model_copy(deep=True)
            continue
        prev.usages += t.usages
        prev.evidence = _evidence(prev.evidence, t.evidence)
    versions: dict[str, list[TechItem]] = {}
    for t in by_id.values():
        if t.version:
            versions.setdefault(t.name, []).append(t)
    out = []
    for t in by_id.values():
        targets = versions.get(t.name)
        if not t.version and targets and len(targets) == 1:
            targets[0].usages += t.usages
            targets[0].evidence = _evidence(targets[0].evidence, t.evidence)
            continue
        out.append(t)
    return sorted(out, key=lambda t: (t.category, t.name.lower(), t.version or ""))


def merge_batch_jobs(items: Iterable[BatchJob]) -> list[BatchJob]:
    """The same job in several files (e.g. JIL exports): the later definition wins, evidence
    is kept from all of them."""
    by_id: dict[str, BatchJob] = {}
    for job in items:
        prev = by_id.get(job.id)
        if prev is not None:
            job = job.model_copy(update={"evidence": _evidence(prev.evidence, job.evidence)})
        by_id[job.id] = job
    return list(by_id.values())


def merge_db_objects(items: Iterable[DbObject]) -> list[DbObject]:
    """Package spec and body are one object; definitions found twice keep all evidence."""
    by_id: dict[str, DbObject] = {}
    for obj in items:
        prev = by_id.get(obj.id)
        if prev is None:
            by_id[obj.id] = obj.model_copy(deep=True)
            continue
        prev.members = sorted(set(prev.members) | set(obj.members))
        prev.evidence = _evidence(prev.evidence, obj.evidence)
        prev.defined_in = prev.defined_in or obj.defined_in
    return list(by_id.values())
