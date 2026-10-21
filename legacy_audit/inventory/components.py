"""Default component detection from build files and folder layout.

Rules, per source root:
1. Every folder holding a build or project file (pom.xml, build.xml, build.gradle, Makefile,
   an Eclipse .project, CMakeLists.txt, a Visual Studio project, package.json, angular.json,
   bower.json, ...) is a component root.
2. If the source root itself has no build file, the folder layout decides (decision 114):
   - folders named like the *parts* of an application (src, WebContent, conf, properties,
     scripts, ksh, sql, jcl, ...) are never components on their own while they sit next to
     code: an application folder with ``src/`` and ``WebContent/`` is one component holding
     its Java, its JSPs, its scripts and its properties;
   - a folder holding an application's code folders (src, java, WebContent, WebRoot, webapp,
     ...) is one component; application folders nested in it become components of their own;
   - a folder holding only other application folders (no files of its own, no part folders)
     is a container, not a component: each folder under it is looked at the same way;
   - at a root that is not itself an application, every top-level folder is a component,
     including part folders (a shared ``config/`` becomes a config component); loose files go
     to a component named after the root.
3. Components listed in ``audit.yaml`` take precedence over 1 and 2 for the files under them.
A file belongs to the deepest component root above it.
"""

from __future__ import annotations

from collections import Counter, defaultdict
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
    Category.CONFIG,
}


# Files that make their folder a component root, besides BUILD_FILES.
PROJECT_FILES = {
    ".project", "cmakelists.txt", "configure.ac", "configure.in", "imakefile", "bower.json",
}  # fmt: skip
PROJECT_SUFFIXES = (".vcxproj", ".vcproj", ".dsp", ".vbp", ".csproj", ".vbproj")

# Folder names that hold an application's code: their parent is the application.
CODE_FOLDERS = {
    "src", "source", "sources", "java", "main", "webcontent", "webroot", "webapp", "web-inf",
    "javasource", "javasrc",
}  # fmt: skip
# Folder names that are a part of whatever application they sit in (never a component on their
# own next to code). Java package roots are here too, for sources kept without a src/ folder.
PART_FOLDERS = CODE_FOLDERS | {
    "web", "jsp", "jsps", "pages", "html", "resources", "res", "conf", "config", "configs",
    "cfg", "configuration", "properties", "props", "prop", "param", "params", "parm", "etc",
    "env", "bin", "scripts", "script", "shell", "ksh", "sh", "lib", "libs", "include", "inc",
    "includes", "sql", "ddl", "plsql", "db", "database", "jcl", "proclib", "proc", "procs",
    "cobol", "cbl", "cpy", "copy", "copybook", "copybooks", "bms", "maps", "ctl", "control",
    "data", "logs", "log", "test", "tests", "doc", "docs", "meta-inf", "images", "img", "css",
    "js", "static", "templates", "tmp", "temp", "jobs", "job", "cron", "batch-scripts",
    "com", "org", "net", "fr", "de", "it", "uk", "edu", "gov", "io",
    "forms", "modules", "classes", "usercontrols", "controls", "designers", "views",
    "controllers", "models", "viewmodels", "app_code", "app_data", "app_start",
}  # fmt: skip
_MAX_CONTAINER_DEPTH = 3


def is_project_file(name: str) -> bool:
    low = name.lower()
    return (
        low in BUILD_FILES
        or low in PROJECT_FILES
        or low.startswith("makefile.")
        or low.endswith(PROJECT_SUFFIXES)
    )


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


# Folder names that say "batch" (a script under one of them is batch work, not a utility).
BATCH_FOLDERS = {
    "batch", "batchs", "batches", "jobs", "job", "cron", "sched", "scheduler", "nightly", "eod",
    "daily", "monthly", "weekly",
}  # fmt: skip


def in_batch_folder(path: str) -> bool:
    parts = [p.lower() for p in PurePosixPath(path).parts[:-1]]
    return any(p in BATCH_FOLDERS or p.endswith(("-batch", "_batch")) for p in parts)


def infer_type(files: list[FileRecord], name: str = "") -> ComponentType:
    """The type from the inventory alone; the linker refines it from what was found (services,
    screens, jobs). Mixed components take the type of their most specific part: Java + JSP is
    web; Java or C + ksh + properties is batch when a script or folder says so."""
    langs = {f.language for f in files}
    cats = {f.category for f in files}
    names = {f.path.rsplit("/", 1)[-1].lower() for f in files}
    java_web = any(f.path.lower().endswith("web-inf/web.xml") for f in files) or (
        Category.SCREEN in cats
        and langs & {"jsp", "bms", "velocity", "freemarker", "aspx", "razor"}
    )
    if not java_web and _desktop(files, langs):
        return ComponentType.DESKTOP  # VB6, Windows Forms, WPF, Java Swing/JavaFX
    script_langs = langs & {"typescript", "javascript"}
    if not java_web and (
        "angular.json" in names or (script_langs and Category.SCREEN in cats and "html" in langs)
    ):
        return ComponentType.FRONTEND  # Angular, AngularJS or another single-page app
    if java_web or (Category.SCREEN in cats and "html" in langs):
        return ComponentType.WEB
    batchy = in_batch_folder(name + "/x") or any(
        f.category == Category.SCRIPT and in_batch_folder(f.path) for f in files
    )
    if Category.JOB in cats or (batchy and cats & {Category.SCRIPT, Category.SOURCE}):
        return ComponentType.BATCH
    if Category.SOURCE in cats:
        return ComponentType.LIBRARY
    if Category.SCRIPT in cats:
        return ComponentType.SCRIPT
    if Category.SQL in cats:
        return ComponentType.DB
    if Category.CONFIG in cats:
        return ComponentType.CONFIG
    return ComponentType.UNKNOWN


def _desktop(files: list[FileRecord], langs: set[str]) -> bool:
    """A desktop application by its files: VB6 forms, Windows Forms designer files, WPF
    windows. Java Swing is only seen in the code, so the linker decides it."""
    for f in files:
        low = f.path.lower()
        if f.category == Category.TEST:
            continue
        if low.endswith(".frm") and f.language == "vb6":
            return True
        if low.endswith((".designer.cs", ".designer.vb")) and not low.endswith(
            (
                "resources.designer.cs",
                "settings.designer.cs",
                "resources.designer.vb",
                "settings.designer.vb",
            )
        ):
            return True
        if f.language == "xaml" and not low.endswith(("app.xaml", "application.xaml")):
            return True
    return False


def composition(files: list[FileRecord]) -> dict[str, int]:
    counts = Counter(str(f.category) for f in files)
    return {k: counts[k] for k in sorted(counts)}


def _layout_candidates(root: str, rels: list[str], build_dirs: set[str]) -> list[Candidate]:
    """Rule 2: components of a root without a build file of its own, from the folder layout."""
    children: dict[str, set[str]] = defaultdict(set)
    direct: set[str] = set()  # folders holding files directly
    for rel in rels:
        if any(_under(rel, d) for d in build_dirs if d):
            continue
        parent = _parent(rel)
        direct.add(parent)
        while parent:
            up = _parent(parent)
            children[up].add(parent)
            parent = up

    def name(folder: str) -> str:
        return PurePosixPath(folder).name.lower()

    def has_code(folder: str) -> bool:
        return any(name(k) in CODE_FOLDERS for k in children.get(folder, ()))

    out: list[Candidate] = []

    def nested_apps(folder: str) -> None:
        """Application folders inside an application become components of their own."""
        for kid in sorted(children.get(folder, ())):
            if name(kid) in PART_FOLDERS:
                continue
            if has_code(kid):
                out.append(Candidate(root, kid, "folder"))
            nested_apps(kid)

    def component(folder: str, depth: int) -> None:
        kids = children.get(folder, set())
        container = (
            not has_code(folder)
            and folder not in direct
            and len(kids) >= 2
            and not any(name(k) in PART_FOLDERS for k in kids)
            and depth < _MAX_CONTAINER_DEPTH
        )
        if container:
            for kid in sorted(kids):
                component(kid, depth + 1)
            return
        out.append(Candidate(root, folder, "folder"))
        nested_apps(folder)

    if has_code(""):
        out.append(Candidate(root, "", "root"))
        nested_apps("")
    else:
        for top in sorted(children.get("", ())):
            component(top, 1)
        out.append(Candidate(root, "", "root"))
    return out


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
            if is_project_file(PurePosixPath(rel).name):
                build_dirs[_parent(rel)].append(rel)
        for d, bfiles in build_dirs.items():
            candidates.append(Candidate(root, d, "build-file", build_files=sorted(bfiles)))
        if "" not in build_dirs:
            candidates += _layout_candidates(root, rels, set(build_dirs))

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
        ctype = ComponentType(cand.type) if cand.type else infer_type(files, cand.path)
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
                roles=[str(ctype)] if ctype != ComponentType.UNKNOWN else [],
                composition=composition(files),
                files=len(files),
                loc_code=sum(f.loc_code for f in files),
                detected_by=cand.detected_by,
                evidence=[Evidence(file=p) for p in evidence_files],
            )
        )
        for f in files:
            file_to_name[f.id] = name
    return components, file_to_name
