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
3. Maven and Gradle decide when they are there (decision 166): a ``pom.xml`` with
   ``<packaging>pom</packaging>`` (a parent or aggregator) and a Gradle root with
   ``settings.gradle`` are *groups*, not components; each module they list is a component. What
   sits beside the modules in a group folder (``scripts/``, ``config/``, ``sql/``) follows rule
   2, so a shared ``config/`` is a config component; leftovers with no code, script, SQL or
   configuration (the parent pom itself, a README) belong to no component.
4. A batch folder (``batch``, ``batches``, ``jobs``, ``cron``, ...) holding two or more batch
   folders is split: each is a batch component, and what they share (``common/``, ``lib/``,
   ``config/``, loose files) is one component of its own (decision 167).
5. Components listed in ``audit.yaml`` take precedence over 1 to 4 for the files under them.
A file belongs to the deepest component root above it.
"""

from __future__ import annotations

import logging
import posixpath
import re
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath

from legacy_audit.analyzers.textutil import child_text, parse_xml, text_of
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
    "settings.gradle.kts",
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


log = logging.getLogger(__name__)

# Reads a file of a source root: (root name, path relative to the root) -> text, or None.
ReadText = Callable[[str, str], str | None]


@dataclass
class Candidate:
    root: str
    path: str  # '' = source root
    detected_by: str
    name: str | None = None
    type: ComponentType | None = None
    build_files: list[str] = field(default_factory=list)
    artifact: str | None = None
    packaging: str | None = None
    in_group: bool = False  # found by the folder rules inside a Maven/Gradle group folder


@dataclass
class BuildInfo:
    """What a folder's ``pom.xml`` or ``settings.gradle`` says about its modules."""

    artifact: str | None = None
    packaging: str | None = None
    modules: list[str] = field(default_factory=list)  # folders, relative to the source root
    gradle_root: bool = False


_GRADLE_INCLUDE = re.compile(r"^\s*include(?:Flat)?\b\s*\(?(.*)$", re.M)
_GRADLE_PROJECT_DIR = re.compile(
    r"project\(\s*['\"]:?([^'\"]+)['\"]\s*\)\.projectDir\s*=\s*"
    r"(?:new\s+File\(\s*(?:settingsDir|rootDir)\s*,\s*|file\(\s*)['\"]([^'\"]+)['\"]"
)
_QUOTED = re.compile(r"['\"]([^'\"]+)['\"]")


def _module_dir(base: str, module: str) -> str | None:
    """A module path as written in a pom (``core``, ``../shared``, ``core/pom.xml``) as a
    folder relative to the source root, or None when it points outside the root."""
    module = module.strip().replace("\\", "/")
    if not module:
        return None
    if module.lower().endswith(".xml"):
        module = posixpath.dirname(module)
    joined = posixpath.normpath(posixpath.join(base or ".", module or "."))
    if joined == ".":
        return ""
    if joined.startswith("../") or joined == ".." or joined.startswith("/"):
        return None
    return joined


def read_pom(base: str, text: str) -> BuildInfo:
    """artifactId, packaging and modules (``<modules>`` and those of every profile)."""
    root = parse_xml(text)
    modules: list[str] = []
    for el in [*root.findall("modules/module"), *root.findall("profiles/profile/modules/module")]:
        d = _module_dir(base, text_of(el) or "")
        if d is not None and d != base and d not in modules:
            modules.append(d)
    packaging = (child_text(root, "packaging") or "jar").strip().lower() or "jar"
    return BuildInfo(child_text(root, "artifactId"), packaging, modules)


def read_gradle_settings(base: str, text: str) -> BuildInfo:
    """Projects a ``settings.gradle`` includes (``include 'core', ':batch:billing'``)."""
    dirs: dict[str, str] = {}
    for m in _GRADLE_PROJECT_DIR.finditer(text):
        dirs[m.group(1).replace(":", "/")] = m.group(2)
    modules: list[str] = []
    for m in _GRADLE_INCLUDE.finditer(text):
        for name in _QUOTED.findall(m.group(1)):
            key = name.strip(":").replace(":", "/")
            d = _module_dir(base, dirs.get(key, key))
            if d is not None and d != base and d not in modules:
                modules.append(d)
    name = re.search(r"rootProject\.name\s*=\s*['\"]([^'\"]+)['\"]", text)
    return BuildInfo(name.group(1) if name else None, None, modules, gradle_root=True)


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
# Folders of a batch folder that hold what its batches share, not a batch of their own.
SHARED_FOLDERS = {
    "common", "commun", "commons", "shared", "share", "util", "utils", "utilities", "tools",
    "functions", "fonctions", "lib", "libs", "include", "includes", "inc", "env", "conf",
    "config", "configs", "cfg", "properties", "props", "param", "params", "parm", "etc",
}  # fmt: skip


def in_batch_folder(path: str) -> bool:
    parts = [p.lower() for p in PurePosixPath(path).parts[:-1]]
    return any(p in BATCH_FOLDERS or p.endswith(("-batch", "_batch")) for p in parts)


def in_shared_folder(path: str) -> bool:
    """A file of the shared part of a batch folder (``batch/common/functions.ksh``): a helper
    of the batches, not batch work of its own."""
    parts = [p.lower() for p in PurePosixPath(path).parts[:-1]]
    return len(parts) >= 2 and parts[-1] in SHARED_FOLDERS and parts[-2] in BATCH_FOLDERS


# Component.detected_by of the shared part of a split batch folder.
BATCH_SHARED = "batch-shared"


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

    def batch_kids(folder: str) -> list[str]:
        """The batches of a batch folder holding several (rule 4), or [] when it is one."""
        if name(folder) not in BATCH_FOLDERS or has_code(folder):
            return []
        kids = [
            k
            for k in sorted(children.get(folder, ()))
            if name(k) not in PART_FOLDERS and name(k) not in SHARED_FOLDERS
        ]
        return kids if len(kids) >= 2 else []

    def component(folder: str, depth: int) -> None:
        kids = children.get(folder, set())
        batches = batch_kids(folder)
        if batches:
            out.append(Candidate(root, folder, BATCH_SHARED))  # what the batches share
            for kid in batches:
                out.append(Candidate(root, kid, "folder"))
                nested_apps(kid)
            return
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
        if (
            folder not in direct
            and len(kids) == 1
            and depth < _MAX_CONTAINER_DEPTH
            and batch_kids(next(iter(kids)))
        ):  # a wrapper folder around a batch folder of several batches (ops/batch/...)
            component(next(iter(kids)), depth + 1)
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


def _build_info(
    root: str, build_dirs: dict[str, list[str]], read: ReadText | None
) -> dict[str, BuildInfo]:
    """Per build folder, what its pom.xml / settings.gradle says (when it can be read)."""
    out: dict[str, BuildInfo] = {}
    if read is None:
        return out
    for d, files in build_dirs.items():
        for rel in files:
            low = PurePosixPath(rel).name.lower()
            if low not in ("pom.xml", "settings.gradle", "settings.gradle.kts"):
                continue
            text = read(root, rel)
            if text is None:
                continue
            try:
                info = read_pom(d, text) if low == "pom.xml" else read_gradle_settings(d, text)
            except ET.ParseError as err:
                log.info("Components: %s/%s not read (%s)", root, rel, err)
                continue
            if low == "pom.xml" or d not in out:
                if d in out and out[d].gradle_root:  # a folder with both: keep both lists
                    info.modules = list(dict.fromkeys([*info.modules, *out[d].modules]))
                out[d] = info
            else:
                out[d].modules = list(dict.fromkeys([*out[d].modules, *info.modules]))
    return out


def _groups(
    rels: list[str], build_dirs: dict[str, list[str]], info: dict[str, BuildInfo]
) -> set[str]:
    """Build folders that are Maven parents / aggregators or Gradle roots: groups of modules,
    not components (rule 3). A pom that could not be read is a group when other build folders
    sit under it and it has no code folder of its own."""
    first: dict[str, set[str]] = defaultdict(set)  # folder -> names of its direct sub-folders
    for rel in rels:
        parts = PurePosixPath(rel).parts[:-1]
        for n in range(len(parts)):
            first["/".join(parts[:n])].add(parts[n].lower())
    out: set[str] = set()
    for d, files in build_dirs.items():
        names = {PurePosixPath(f).name.lower() for f in files}
        has_code = bool(first.get(d, set()) & CODE_FOLDERS)
        bi = info.get(d)
        if bi is not None and bi.packaging is not None:
            if bi.packaging == "pom":
                out.add(d)
        elif bi is not None and bi.gradle_root:
            if bi.modules and not has_code:
                out.add(d)
        elif "pom.xml" in names or names & {"settings.gradle", "settings.gradle.kts"}:
            nested = any(o != d and _under(o, d) for o in build_dirs)
            if nested and not has_code:
                out.add(d)
    return out


def detect_components(
    records: list[FileRecord],
    root_names: list[str],
    specs: list[ComponentSpec],
    read: ReadText | None = None,
) -> tuple[list[Component], dict[str, str]]:
    """Return components and a map file id -> component name. ``read`` gives the text of a
    build file (pom.xml, settings.gradle) so Maven and Gradle can say which folders are modules
    and which are parents; without it the folder layout decides."""
    multi = len(root_names) > 1
    by_root: dict[str, list[FileRecord]] = defaultdict(list)
    for r in records:
        by_root[r.root].append(r)

    candidates: list[Candidate] = []
    manual: list[Candidate] = []
    groups: dict[str, dict[str, str]] = {}  # root -> group folder -> group name
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
        info = _build_info(root, build_dirs, read)
        present = {_parent(rel) for rel in rels} | {
            p for rel in rels for p in [str(x) for x in PurePosixPath(rel).parents][:-1]
        }
        for bi in list(info.values()):
            for m in bi.modules:  # a Gradle project needs no build file of its own
                if m not in build_dirs and m in present:
                    build_dirs[m] = []
        group_dirs = _groups(rels, build_dirs, info)
        groups[root] = {
            g: (info[g].artifact if g in info and info[g].artifact else None)
            or (PurePosixPath(g).name if g else root)
            for g in group_dirs
        }
        for d, bfiles in build_dirs.items():
            if d in group_dirs:
                continue
            mod = info.get(d)
            candidates.append(
                Candidate(
                    root,
                    d,
                    "build-file",
                    build_files=sorted(bfiles),
                    artifact=mod.artifact if mod else None,
                    packaging=mod.packaging if mod else None,
                )
            )
        layout_roots = sorted(group_dirs) + ([""] if "" not in build_dirs else [])
        for g in layout_roots:
            cut = len(g) + 1 if g else 0
            sub_rels = [rel[cut:] for rel in rels if g == "" or rel.startswith(g + "/")]
            sub_build = {d[cut:] for d in build_dirs if d != g and _under(d, g)}
            for c in _layout_candidates(root, sub_rels, sub_build):
                c.path = f"{g}/{c.path}" if g and c.path else (g or c.path)
                c.in_group = g in group_dirs
                candidates.append(c)

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
    seen_paths: set[tuple[str, str]] = set()
    for cand in [*manual, *sorted(candidates, key=lambda c: (c.root, c.path))]:
        files = members.get(id(cand), [])
        if not files:
            continue
        if cand.in_group and not any(f.category in _TECH_CATEGORIES for f in files):
            continue  # a parent pom, a README: part of the group, not a component (rule 3)
        if cand.detected_by != "config":
            if (cand.root, cand.path) in seen_paths:
                continue
            seen_paths.add((cand.root, cand.path))
        name = cand.name or (PurePosixPath(cand.path).name if cand.path else cand.root)
        if name in taken:
            name = f"{cand.root}-{cand.path.replace('/', '-')}" if cand.path else cand.root
        n = 2
        base = name
        while name in taken:
            name, n = f"{base}-{n}", n + 1
        taken.add(name)
        if cand.type:
            ctype = ComponentType(cand.type)
        elif cand.detected_by == BATCH_SHARED:
            ctype = _shared_type(files)
        else:
            ctype = infer_type(files, cand.path)
            if cand.packaging == "war" and ctype in (ComponentType.LIBRARY, ComponentType.UNKNOWN):
                ctype = ComponentType.WEB
        tech = sorted({f.language for f in files if f.category in _TECH_CATEGORIES})
        evidence_files = cand.build_files or [files[0].path]
        if multi:
            evidence_files = [f"{cand.root}/{p}" for p in cand.build_files] or [files[0].path]
        group_names = groups.get(cand.root, {})
        chain = sorted((g for g in group_names if g != cand.path and _under(cand.path, g)), key=len)
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
                build_group=[group_names[g] for g in chain],
                artifact=cand.artifact,
                packaging=cand.packaging,
                evidence=[Evidence(file=p) for p in evidence_files],
            )
        )
        for f in files:
            file_to_name[f.id] = name
    return components, file_to_name


def _shared_type(files: list[FileRecord]) -> ComponentType:
    """The shared part of a batch folder: configuration, helper scripts or code, never a batch
    of its own (its batches are the folders beside it)."""
    cats = {f.category for f in files}
    if Category.SOURCE in cats:
        return ComponentType.LIBRARY
    if Category.SCRIPT in cats:
        return ComponentType.SCRIPT
    if Category.SQL in cats:
        return ComponentType.DB
    if Category.CONFIG in cats:
        return ComponentType.CONFIG
    return ComponentType.UNKNOWN


def disk_reader(roots: dict[str, Path], max_bytes: int = 2_000_000) -> ReadText:
    """A ``ReadText`` over source roots on disk (build files only; never written to)."""

    def read(root: str, rel: str) -> str | None:
        base = roots.get(root)
        if base is None:
            return None
        path = base / rel
        try:
            if path.stat().st_size > max_bytes:
                return None
            return path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            return None

    return read
