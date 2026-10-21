"""Merging per-file facts into one fact set: the same integration, library, tech item, batch job
or database object found in several files becomes one fact with all its evidence."""

from __future__ import annotations

import re
from collections.abc import Iterable
from typing import TypeVar

from legacy_audit.analyzers.integrations import consolidation_key, describe, merge_directions
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
            _queue_direction(it, it.id in defined)
    merged = _consolidate(_merge_cft(out))
    for it in merged:
        it.purpose, it.description = describe(it)
    return merged


def _queue_direction(it: Integration, defined: bool) -> None:
    if it.producers and it.consumers:
        it.direction = "both"
    elif it.producers:
        it.direction = "outbound"
    elif it.consumers:
        it.direction = "inbound"
    else:
        it.direction = "defined" if defined else "unknown"


def _consolidate(items: list[Integration]) -> list[Integration]:
    """One row per interface: usages of the same target found with different directions,
    spellings (host case, default port, trailing slash, ``queue:///``), or as HTTP and SOAP fold
    into one integration whose direction is the union (inbound + outbound = both)."""
    groups: dict[tuple[str, str], list[tuple[Integration, str]]] = {}
    for it in items:
        family, key, display = consolidation_key(it.type, it.target)
        groups.setdefault((family, key), []).append((it, display))
    out: list[Integration] = []
    for members in groups.values():
        # The most detailed spelling names the row (QM1@host:1414 over QM1); then most used.
        members.sort(key=lambda m: (-len(m[1]), -m[0].usages, m[1], m[0].id))
        head, display = members[0]
        merged = head.model_copy(deep=True)
        merged.target = display
        if len(members) > 1:
            directions = {m.direction for m, _ in members}
            for m, _ in members[1:]:
                merged.usages += m.usages
                merged.source_files = _union(merged.source_files, m.source_files)
                merged.producers = _union(merged.producers, m.producers)
                merged.consumers = _union(merged.consumers, m.consumers)
                merged.evidence = _evidence(merged.evidence, m.evidence)
                merged.protocol = merged.protocol or m.protocol
                for k, v in m.details.items():
                    merged.details.setdefault(k, v)
            if any(m.type == "SOAP" for m, _ in members):
                merged.type = "SOAP"
            if merged.type in ("MQ", "JMS") and (merged.producers or merged.consumers):
                _queue_direction(merged, False)
            else:
                merged.direction = merge_directions(directions)
        out.append(merged)
    return out


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


def _folder(path: str) -> str:
    return path.rsplit("/", 1)[0] if "/" in path else ""


def merge_npm_versions(items: Iterable[Library]) -> list[Library]:
    """A package that ``package.json`` declares and a lock file in the same folder pins is one
    library at the lock file's exact version, declared, with the ``package.json`` scope."""
    libs = list(items)
    locked: dict[tuple[str, str], list[Library]] = {}
    for lib in libs:
        if lib.source == "npm-lock":
            for f in lib.where_used:
                locked.setdefault((_folder(f), lib.artifact), []).append(lib)
    out: list[Library] = []
    for lib in libs:
        if lib.source == "npm" and lib.where_used:
            cands = locked.get((_folder(lib.where_used[0]), lib.artifact), [])
            # Several versions installed (nested copies): the one of the declared major.
            major = (lib.version or "").split(".", 1)[0]
            hit = next(
                (c for c in cands if (c.version or "").split(".", 1)[0] == major),
                cands[0] if cands else None,
            )
            if hit is not None:
                hit.declared = True
                hit.source = "npm"
                hit.scope = lib.scope
                hit.where_used = _union(lib.where_used, hit.where_used)
                hit.evidence = _evidence(lib.evidence, hit.evidence)
                continue
        out.append(lib)
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
    (re.compile(r"^spring(-(core|context|beans|web|webmvc|jdbc|jms|tx|orm|aop))?$"),
     "Spring Framework", "framework"),
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
    # Embedded or vendor server libraries in a Maven/Gradle build.
    (re.compile(r"^tomcat-embed-core$"), "Apache Tomcat (embedded)", "app-server"),
    (re.compile(r"^(jetty-server|jetty-webapp)$"), "Eclipse Jetty", "app-server"),
    (re.compile(r"^undertow-core$"), "Undertow", "app-server"),
    (re.compile(r"^(weblogic|wlfullclient|wlthint3client|weblogic-server-pom)$"),
     "Oracle WebLogic Server", "app-server"),
    (re.compile(r"^(was_public|com\.ibm\.ws\.webservices\.thinclient.*|com\.ibm\.websphere\..*)$"),
     "IBM WebSphere Application Server", "app-server"),
    (re.compile(r"^(jboss-as-[a-z-]+|jboss-client)$"), "JBoss AS", "app-server"),
    (re.compile(r"^wildfly-[a-z-]+$"), "WildFly", "app-server"),
]  # fmt: skip

# npm packages (language javascript): frameworks and runtimes of Angular and Node.js code.
_NPM_TECH: list[tuple[re.Pattern[str], str, str]] = [
    (re.compile(r"^@angular/core$"), "Angular", "framework"),
    (re.compile(r"^angular$"), "AngularJS", "framework"),
    (re.compile(r"^@uirouter/angularjs$|^angular-ui-router$"), "AngularJS UI-Router", "library"),
    (re.compile(r"^@angular/material$"), "Angular Material", "library"),
    (re.compile(r"^rxjs$"), "RxJS", "library"),
    (re.compile(r"^@ngrx/store$"), "NgRx", "library"),
    (re.compile(r"^typescript$"), "TypeScript", "language"),
    (re.compile(r"^react$"), "React", "framework"),
    (re.compile(r"^vue$"), "Vue.js", "framework"),
    (re.compile(r"^jquery$"), "jQuery", "library"),
    (re.compile(r"^bootstrap$"), "Bootstrap", "library"),
    (re.compile(r"^express$"), "Express", "framework"),
    (re.compile(r"^@nestjs/core$"), "NestJS", "framework"),
    (re.compile(r"^fastify$"), "Fastify", "framework"),
    (re.compile(r"^koa$"), "Koa", "framework"),
    (re.compile(r"^@hapi/hapi$|^hapi$"), "hapi", "framework"),
    (re.compile(r"^typeorm$"), "TypeORM", "framework"),
    (re.compile(r"^(prisma|@prisma/client)$"), "Prisma", "framework"),
    (re.compile(r"^sequelize$"), "Sequelize", "framework"),
    (re.compile(r"^knex$"), "Knex.js", "library"),
    (re.compile(r"^mongoose$"), "Mongoose", "framework"),
    (re.compile(r"^(pg|pg-promise|postgres)$"), "PostgreSQL", "database"),
    (re.compile(r"^(oracledb|node-oracledb)$"), "Oracle", "database"),
    (re.compile(r"^(mysql|mysql2)$"), "MySQL", "database"),
    (re.compile(r"^(mssql|tedious)$"), "SQL Server", "database"),
    (re.compile(r"^(ibm_db|ibm_db2)$"), "DB2", "database"),
    (re.compile(r"^(mongodb)$"), "MongoDB", "database"),
    (re.compile(r"^(redis|ioredis)$"), "Redis", "database"),
    (re.compile(r"^node-cron$"), "node-cron", "scheduler"),
    (re.compile(r"^cron$"), "cron (npm)", "scheduler"),
    (re.compile(r"^node-schedule$"), "node-schedule", "scheduler"),
    (re.compile(r"^@nestjs/schedule$"), "NestJS Schedule", "scheduler"),
    (re.compile(r"^agenda$"), "Agenda", "scheduler"),
    (re.compile(r"^(bull|bullmq)$"), "Bull queue", "scheduler"),
    (re.compile(r"^kafkajs$"), "Kafka client (kafkajs)", "middleware"),
    (re.compile(r"^amqplib$"), "AMQP client (amqplib)", "middleware"),
    (re.compile(r"^ibmmq$"), "IBM MQ client", "middleware"),
    (re.compile(r"^passport$"), "Passport", "auth"),
    (re.compile(r"^@okta/okta-(auth-js|angular|react)$"), "Okta SDK", "auth"),
    (re.compile(r"^keycloak-(js|angular)$"), "Keycloak adapter", "auth"),
    (re.compile(r"^angular-oauth2-oidc$"), "angular-oauth2-oidc", "auth"),
    (re.compile(r"^@azure/msal-(angular|browser|node)$"), "Microsoft MSAL", "auth"),
    (re.compile(r"^jsonwebtoken$"), "jsonwebtoken (JWT)", "auth"),
    (re.compile(r"^(soap|strong-soap)$"), "SOAP client (Node.js)", "library"),
    (re.compile(r"^(@nrwl/workspace|nx)$"), "Nx", "build"),
    (re.compile(r"^webpack$"), "webpack", "build"),
    (re.compile(r"^gulp$"), "gulp", "build"),
    (re.compile(r"^grunt$"), "Grunt", "build"),
]  # fmt: skip


# NuGet packages (language dotnet): .NET frameworks and libraries, with their versions.
_NUGET_TECH: list[tuple[re.Pattern[str], str, str]] = [
    (re.compile(r"^microsoft\.entityframeworkcore(\.(sqlserver|sqlite|relational|cosmos))?$"), "Entity Framework Core", "framework"),
    (re.compile(r"^entityframework$"), "Entity Framework", "framework"),
    (re.compile(r"^dapper$"), "Dapper", "library"),
    (re.compile(r"^nhibernate$"), "NHibernate", "framework"),
    (re.compile(r"^microsoft\.aspnet\.mvc$"), "ASP.NET MVC", "framework"),
    (re.compile(r"^microsoft\.aspnet\.webapi(\.core)?$"), "ASP.NET Web API", "framework"),
    (re.compile(r"^hangfire(\.core)?$"), "Hangfire", "scheduler"),
    (re.compile(r"^quartz$"), "Quartz.NET", "scheduler"),
    (re.compile(r"^log4net$"), "log4net", "library"),
    (re.compile(r"^nlog$"), "NLog", "library"),
    (re.compile(r"^serilog$"), "Serilog", "library"),
    (re.compile(r"^newtonsoft\.json$"), "Json.NET", "library"),
    (re.compile(r"^automapper$"), "AutoMapper", "library"),
    (re.compile(r"^(oracle\.manageddataaccess(\.core)?|oracle\.dataaccess)$"), "Oracle .NET driver", "database"),
    (re.compile(r"^(system|microsoft)\.data\.sqlclient$"), "SQL Server .NET driver", "database"),
    (re.compile(r"^(npgsql|npgsql\.entityframeworkcore\.postgresql)$"), "PostgreSQL .NET driver (Npgsql)", "database"),
    (re.compile(r"^(mysql\.data|mysqlconnector)$"), "MySQL .NET driver", "database"),
    (re.compile(r"^ibm\.data\.db2(\.core)?$"), "DB2 .NET driver", "database"),
    (re.compile(r"^ibmmqdotnet$|^ibmxmsdotnetclient$"), "IBM MQ client", "middleware"),
    (re.compile(r"^rabbitmq\.client$"), "RabbitMQ client", "middleware"),
    (re.compile(r"^confluent\.kafka$"), "Kafka client (Confluent)", "middleware"),
    (re.compile(r"^microsoft\.identity\.client$"), "Microsoft MSAL", "auth"),
    (re.compile(r"^okta\."), "Okta SDK", "auth"),
    (re.compile(r"^devexpress\.|^telerik\.|^infragistics"), "Commercial UI controls", "library"),
    (re.compile(r"^crystaldecisions\."), "Crystal Reports", "library"),
]  # fmt: skip


def techs_from_libraries(libs: Iterable[Library]) -> list[TechItem]:
    out: list[TechItem] = []
    for lib in libs:
        if lib.language in ("javascript", "dotnet") and lib.scope == "transitive":
            continue  # what the application uses directly, not what its packages pull in
        if lib.source == "com":
            continue  # VB6 references are mapped by the analyzer
        rules = {"javascript": _NPM_TECH, "dotnet": _NUGET_TECH}.get(lib.language, _LIB_TECH)
        for rx, name, category in rules:
            if rx.match(lib.artifact.lower()):
                out.append(TechItem(id=make_id("tech", name, lib.version or ""), name=name,
                                    version=lib.version, category=category,
                                    evidence=list(lib.evidence[:1])))  # fmt: skip
                break
    return out


_FOLD_CATEGORIES = {"language", "runtime", "app-server", "web-server"}


def _version_key(v: str) -> tuple[int, ...]:
    return tuple(int(x) for x in re.findall(r"\d+", v)[:4])


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
        if not t.version and targets and (len(targets) == 1 or t.category in _FOLD_CATEGORIES):
            # A mention without a version is the same product: it goes with the version found
            # (with the newest one when several are, for runtimes and front-end frameworks).
            target = max(targets, key=lambda x: _version_key(x.version or ""))
            target.usages += t.usages
            target.evidence = _evidence(target.evidence, t.evidence)
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
