"""Default component detection from build files and folder layout.

Rules, per source root:
1. Every folder holding a build file (pom.xml, build.xml, build.gradle, Makefile, ...) is a
   component root.
2. If the source root itself has no build file, every top-level folder with files not covered
   by rule 1 becomes a component; loose files at the root go to a component named after the root.
3. Components listed in ``audit.yaml`` take precedence over 1 and 2 for the files under them.
A file belongs to the deepest component root above it.
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import PurePosixPath

from legacy_audit.config import ComponentSpec
from legacy_audit.inventory.language import BUILD_FILES
from legacy_audit.model import Category, Component, ComponentType, Evidence, FileRecord, make_id

_TECH_CATEGORIES = {
    Category.SOURCE,
    Category.SCREEN,
    Category.SCRIPT,
    Category.SQL,
    Category.JOB,
    Category.TEST,
}


@dataclass
class Candidate:
    root: str
    path: str  # '' = source root
    detected_by: str
    name: str | None = None
    type: ComponentType | None = None
    build_files: list[str] = field(default_factory=list)


def _parent(path: str) -> str:
    parent = PurePosixPath(path).parent.as_posix()
    return "" if parent == "." else parent


def _under(path: str, folder: str) -> bool:
    return folder == "" or path == folder or path.startswith(folder + "/")


def _rel_in_root(rec: FileRecord, multi_root: bool) -> str:
    """File path relative to its own root (strip the root prefix added for multi-root runs)."""
    if multi_root and rec.path.startswith(rec.root + "/"):
        return rec.path[len(rec.root) + 1 :]
    return rec.path


def infer_type(files: list[FileRecord]) -> ComponentType:
    langs = {f.language for f in files}
    cats = {f.category for f in files}
    if any(f.path.lower().endswith("web-inf/web.xml") for f in files) or (
        Category.SCREEN in cats and langs & {"jsp", "html", "bms", "velocity", "freemarker"}
    ):
        return ComponentType.WEB
    if Category.JOB in cats:
        return ComponentType.BATCH
    if Category.SOURCE in cats:
        return ComponentType.LIBRARY
    if Category.SCRIPT in cats:
        return ComponentType.SCRIPT
    if Category.SQL in cats:
        return ComponentType.DB
    return ComponentType.UNKNOWN


def detect_components(
    records: list[FileRecord],
    root_names: list[str],
    specs: list[ComponentSpec],
) -> tuple[list[Component], dict[str, str]]:
    """Return components and a map file id -> component name."""
    multi = len(root_names) > 1
    by_root: dict[str, list[FileRecord]] = defaultdict(list)
    for r in records:
        by_root[r.root].append(r)

    candidates: list[Candidate] = []
    manual: list[Candidate] = []
    for spec in specs:
        root = spec.root or root_names[0]
        manual.append(Candidate(root, spec.path, "config", spec.name, spec.type))

    for root in root_names:
        files = by_root.get(root, [])
        rels = [_rel_in_root(f, multi) for f in files]
        build_dirs: dict[str, list[str]] = defaultdict(list)
        for rel in rels:
            if PurePosixPath(rel).name.lower() in BUILD_FILES:
                build_dirs[_parent(rel)].append(rel)
        for d, bfiles in build_dirs.items():
            candidates.append(Candidate(root, d, "build-file", build_files=sorted(bfiles)))
        if "" not in build_dirs:
            tops = set()
            for rel in rels:
                parts = rel.split("/")
                if len(parts) > 1 and not any(_under(rel, d) for d in build_dirs if d):
                    tops.add(parts[0])
            candidates += [Candidate(root, t, "folder") for t in sorted(tops)]
            candidates.append(Candidate(root, "", "root"))

    members: dict[int, list[FileRecord]] = defaultdict(list)
    for root, files in by_root.items():
        root_manual = [c for c in manual if c.root == root]
        root_auto = [c for c in candidates if c.root == root]
        for f in files:
            rel = _rel_in_root(f, multi)
            best: Candidate | None = None
            for pool in (root_manual, root_auto):
                hits = [c for c in pool if _under(rel, c.path)]
                if hits:
                    best = max(hits, key=lambda c: len(c.path))
                    break
            if best is not None:
                members[id(best)].append(f)

    components: list[Component] = []
    taken: set[str] = set()
    file_to_name: dict[str, str] = {}
    for cand in [*manual, *sorted(candidates, key=lambda c: (c.root, c.path))]:
        files = members.get(id(cand), [])
        if not files:
            continue
        name = cand.name or (PurePosixPath(cand.path).name if cand.path else cand.root)
        if name in taken:
            name = f"{cand.root}-{cand.path.replace('/', '-')}" if cand.path else cand.root
        n = 2
        base = name
        while name in taken:
            name, n = f"{base}-{n}", n + 1
        taken.add(name)
        ctype = ComponentType(cand.type) if cand.type else infer_type(files)
        tech = sorted({f.language for f in files if f.category in _TECH_CATEGORIES})
        evidence_files = cand.build_files or [files[0].path]
        if multi:
            evidence_files = [f"{cand.root}/{p}" for p in cand.build_files] or [files[0].path]
        components.append(
            Component(
                id=make_id("component", cand.root, cand.path, cand.detected_by),
                name=name,
                type=ctype,
                root=cand.root,
                path=cand.path,
                build_files=evidence_files if cand.build_files else [],
                technology=tech,
                files=len(files),
                loc_code=sum(f.loc_code for f in files),
                detected_by=cand.detected_by,
                evidence=[Evidence(file=p) for p in evidence_files],
            )
        )
        for f in files:
            file_to_name[f.id] = name
    return components, file_to_name
