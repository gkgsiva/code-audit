"""Group the components found by the folder and build-file rules (decision 176).

``inventory.component_grouping: auto`` (the default) runs two steps on the candidates of
``legacy_audit.inventory.components``; ``module`` keeps one component per module and folder.

1. Maven / Gradle (the pom decides): every module that runs on its own is a *deployable* (a
   ``war``, ``ear``, ``rar`` or ``sar``, a Spring Boot or other runnable jar, a module whose files
   make it web, batch, desktop, front end or API). A library module (a plain ``jar`` or ``ejb``)
   goes into the deployable that uses it, read from the ``<dependencies>`` of the poms
   (``project(':core')`` in a ``build.gradle``), directly or through other libraries. A library
   used by several deployables, or by none, stays a component of its own.
2. Part folders (technology decides): in a Maven / Gradle group folder, the folders beside the
   modules (``config/``, ``scripts/``, ``ops/batch/...``) are one component
   ``<group>-scripts-config``. Without a build file, part folders found side by side are grouped
   by technology: COBOL and BMS (with their copybooks) are ``cobol-online``, JCL and PROCLIB
   ``jcl-jobs``, SQL folders ``database``, the batches of one batch folder and what they share
   one component named after the batch folder, configuration and utility scripts
   ``shared-scripts-config``. Configuration and scripts beside exactly one application go into it.

Each grouped component lists the folders it is made of in ``parts``. The batch count does not
change: it is one batch per entry point whatever component holds it (decision 168).
"""

from __future__ import annotations

import posixpath
from collections import defaultdict
from pathlib import PurePosixPath
from typing import TYPE_CHECKING

from legacy_audit.model import Category, ComponentType, FileRecord

if TYPE_CHECKING:
    from legacy_audit.inventory.components import Candidate

GROUPED = "grouped"  # Component.detected_by of a component made of several part folders

DEPLOY_PACKAGING = {"war", "ear", "rar", "sar", "har", "jboss-sar", "app", "spring-boot"}
RUNS_ON_ITS_OWN = {
    ComponentType.WEB,
    ComponentType.BATCH,
    ComponentType.DESKTOP,
    ComponentType.FRONTEND,
    ComponentType.API,
    ComponentType.ONLINE_MAINFRAME,
}
MAINFRAME_ONLINE = {"cobol", "bms", "pli", "assembler", "asm", "mfs"}
_SUPPORT_CATS = {Category.SCRIPT, Category.CONFIG}
_NOT_TECH = {Category.DOC, Category.OTHER, Category.BUILD, Category.BINARY}

Members = dict[int, list[FileRecord]]


def group_candidates(
    candidates: list[Candidate], members: Members, group_names: dict[str, dict[str, str]]
) -> list[Candidate]:
    """The candidates after grouping; ``members`` is updated for the new and grown ones."""
    live = [c for c in candidates if members.get(id(c))]
    live = _fold_libraries(live, members)
    return _group_parts(live, members, group_names)


# ---- 1. library modules into their deployable ---------------------------------------------------


def _deployable(c: Candidate, files: list[FileRecord]) -> bool:
    from legacy_audit.inventory.components import infer_type

    if (c.packaging or "") in DEPLOY_PACKAGING or c.runnable:
        return True
    return infer_type(files, c.path) in RUNS_ON_ITS_OWN


def _fold_libraries(cands: list[Candidate], members: Members) -> list[Candidate]:
    by_root: dict[str, list[Candidate]] = defaultdict(list)
    for c in cands:
        if c.detected_by == "build-file" and c.jvm_module:
            by_root[c.root].append(c)
    gone: set[int] = set()
    for mods in by_root.values():
        by_artifact: dict[str | None, Candidate] = {c.artifact: c for c in mods if c.artifact}
        by_path = {c.path: c for c in mods}

        def uses(
            c: Candidate,
            mods: list[Candidate] = mods,
            by_artifact: dict[str | None, Candidate] = by_artifact,
            by_path: dict[str, Candidate] = by_path,
        ) -> list[Candidate]:
            out: list[Candidate] = []
            for dep in c.deps:
                hit = by_artifact.get(dep) or by_path.get(dep)
                if hit is None:  # a Gradle project path: 'core' for the folder apps/core
                    hit = next((m for m in mods if m.path.endswith("/" + dep)), None)
                if hit is not None and hit is not c and hit not in out:
                    out.append(hit)
            return out

        deploy = {id(c) for c in mods if _deployable(c, members[id(c)])}
        users: dict[int, set[int]] = defaultdict(set)
        for d in mods:
            if id(d) not in deploy:
                continue
            todo, seen = uses(d), {id(d)}
            while todo:
                lib = todo.pop()
                if id(lib) in seen or id(lib) in deploy:
                    continue
                seen.add(id(lib))
                users[id(lib)].add(id(d))
                todo.extend(uses(lib))
        owner = {id(c): c for c in mods}
        for lib in sorted(mods, key=lambda c: c.path):
            target = users.get(id(lib), set())
            if id(lib) in deploy or len(target) != 1:
                continue  # shared by several deployables, or used by none
            d = owner[next(iter(target))]
            if not d.parts:
                d.parts = [d.path]
            d.parts.append(lib.path)
            d.build_files = sorted({*d.build_files, *lib.build_files})
            members[id(d)].extend(members.pop(id(lib)))
            gone.add(id(lib))
    return [c for c in cands if id(c) not in gone]


# ---- 2. part folders by technology -------------------------------------------------------------


def _family(c: Candidate, files: list[FileRecord]) -> tuple[str, str]:
    from legacy_audit.inventory.components import BATCH_SHARED, in_batch_folder

    if c.batch_kid:
        return "batch", _parent(c.path)
    if c.detected_by == BATCH_SHARED:
        return "batch", c.path
    tech = [f for f in files if f.category not in _NOT_TECH and f.category != Category.TEST]
    cats = {f.category for f in tech}
    langs = {f.language for f in tech}
    if langs & MAINFRAME_ONLINE:
        return "cobol", ""
    if Category.JOB in cats and cats <= {Category.JOB, Category.CONFIG}:
        return "jcl", ""
    if cats and cats <= {Category.SQL}:
        return "db", ""
    if cats <= _SUPPORT_CATS:
        if any(f.category == Category.SCRIPT and in_batch_folder(f.path) for f in tech):
            return "batch", ""
        return "support", ""
    main = max(sorted(langs), key=lambda lang: sum(f.loc_code for f in tech if f.language == lang))
    return "code", main


def _has_tech(files: list[FileRecord]) -> bool:
    return any(f.category not in _NOT_TECH for f in files)


def _parent(path: str) -> str:
    parent = PurePosixPath(path).parent.as_posix()
    return "" if parent == "." else parent


def _common(paths: list[str]) -> str:
    split = [p.split("/") if p else [] for p in paths]
    out: list[str] = []
    for parts in zip(*split, strict=False):
        if len(set(parts)) != 1:
            break
        out.append(parts[0])
    return posixpath.join(*out) if out else ""


def _is_part(c: Candidate) -> bool:
    from legacy_audit.inventory.components import BATCH_SHARED, PART_FOLDERS

    if c.app or c.detected_by in ("build-file", "config"):
        return False
    if c.in_group:
        return True
    name = PurePosixPath(c.path).name.lower()
    return c.batch_kid or c.detected_by in (BATCH_SHARED, "root") or name in PART_FOLDERS


def _name(family: tuple[str, str], files: list[FileRecord]) -> str:
    kind, extra = family
    if kind == "cobol":
        return "cobol-online" if any(f.language == "bms" for f in files) else "cobol-programs"
    if kind == "batch":
        return PurePosixPath(extra).name or "batch"
    return {"jcl": "jcl-jobs", "db": "database", "support": "shared-scripts-config"}.get(
        kind, f"{extra}-code"
    )


def _group_parts(
    cands: list[Candidate], members: Members, group_names: dict[str, dict[str, str]]
) -> list[Candidate]:
    from legacy_audit.inventory.components import Candidate

    levels: dict[tuple[str, str], list[Candidate]] = defaultdict(list)
    for c in cands:
        if c.detected_by != "config":
            levels[(c.root, c.level)].append(c)
    out = [c for c in cands if c.detected_by == "config"]
    for (root, level), items in levels.items():
        in_group = level in group_names.get(root, {})
        # A part with no code, script, SQL or configuration (a parent pom, a README) is left
        # alone: it belongs to no component (rule 3).
        parts = [c for c in items if _is_part(c) and _has_tech(members[id(c)])]
        others = [c for c in items if c not in parts]
        buckets: dict[tuple[str, str], list[Candidate]] = defaultdict(list)
        if in_group:
            if parts:
                buckets[("group", level)] = parts
        else:
            for c in parts:
                buckets[_family(c, members[id(c)])].append(c)
        grouped: list[Candidate] = []
        for fam, bucket in buckets.items():
            if len(bucket) == 1:
                grouped.append(bucket[0])
                continue
            files = [f for c in bucket for f in members.pop(id(c))]
            if fam[0] == "group":
                gname = group_names.get(root, {}).get(level) or PurePosixPath(level).name or root
                name = f"{gname}-scripts-config"
            else:
                name = _name(fam, files)
            paths = sorted(c.path for c in bucket)
            merged = Candidate(
                root, _common(paths), GROUPED, name=name, in_group=in_group, level=level,
                parts=paths,
            )  # fmt: skip
            members[id(merged)] = files
            grouped.append(merged)
        # Configuration and scripts beside exactly one application belong to it.
        if len(others) == 1:
            app = others[0]
            for c in list(grouped):
                if _family(c, members[id(c)])[0] != "support" or c.batch_kid:
                    continue
                if not app.parts:
                    app.parts = [app.path]
                app.parts.extend(c.parts or [c.path])
                members[id(app)].extend(members.pop(id(c)))
                grouped.remove(c)
        out.extend(others)
        out.extend(grouped)
    return out
