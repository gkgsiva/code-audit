"""Undeclared dependencies: jars/wars/ears (manifest, pom.properties, file name), npm
``package.json`` (with the Node.js version it asks for), npm lock files (``package-lock.json``,
``npm-shrinkwrap.json``, ``yarn.lock``, ``pnpm-lock.yaml``: the exact version of every package,
direct or not), Bower ``bower.json`` and bundled JavaScript libraries.

Lock-file entries are ``source="npm-lock"``; the linker folds a ``package.json`` entry into the
lock file's exact version when both sit in one folder (``merge_npm_versions``). Packages only
the lock file lists are kept as ``declared=False``, ``scope="transitive"``, so a vulnerability
OSV-Scanner reports on one of them matches a library (decision 129)."""

from __future__ import annotations

import hashlib
import io
import json
import re
import zipfile
from typing import ClassVar

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import library, tech
from legacy_audit.analyzers.textutil import basename, split_lines
from legacy_audit.inventory.language import NPM_LOCK_FILES
from legacy_audit.model import FileRecord

_JAR_NAME = re.compile(
    r"^(?P<a>.+?)[-_](?P<v>\d+(?:[._]\d+)*(?:[.-][\w.]+?)?)\.(jar|war|ear|zip)$", re.I
)
_JS_NAME = re.compile(
    r"^(?P<a>jquery(?:[.-]ui)?|angular|bootstrap|prototype|scriptaculous|dojo|mootools|"
    r"underscore|backbone|ext-all|yui|knockout|moment|lodash|react|vue)[.-]v?(?P<v>\d+(?:\.\d+)+)",
    re.I,
)
_JS_HEADER = re.compile(
    r"(?P<a>jQuery UI|jQuery|AngularJS|Bootstrap|Prototype JavaScript framework|Dojo|"
    r"MooTools|Underscore\.js|Backbone\.js|Ext JS|Knockout|Moment\.js|Lodash)"
    r"[^\n\d]{0,20}v?(?P<v>\d+\.\d+(?:\.\d+)?)",
)
_ARCHIVES = (".jar", ".war", ".ear")
_NPM_SCOPES = ("dependencies", "devDependencies", "peerDependencies", "optionalDependencies")
_YARN_HEAD = re.compile(r'^"?((?:@[^@/\s"]+/)?[^@\s",]+)@')
_YARN_VERSION = re.compile(r'^\s+version:?\s+"?([^"\s]+)"?')
_PNPM_KEY = re.compile(r"^\s{2}'?/?((?:@[^@/\s]+/)?[^@/\s(']+)[@/]([0-9][^\s:('_]*)")
_NODE_VERSION = re.compile(r"(\d+(?:\.\d+){0,2})")


def npm_version(spec: str) -> str | None:
    """The version a ``package.json`` range starts from (``^17.3.0`` -> ``17.3.0``); None for
    tags, URLs, paths and ``*``."""
    m = re.match(r"^\s*(?:npm:[^@]+@)?[\^~=v>< ]*(\d+(?:\.[\dxX*]+)*(?:-[\w.]+)?)", spec)
    if not m:
        return None
    return re.sub(r"\.[xX*].*$", "", m.group(1))


def node_version(spec: str) -> str | None:
    """Major.minor of a Node.js engines range or ``.nvmrc`` (``>=18.17`` -> ``18.17``)."""
    m = _NODE_VERSION.search(spec or "")
    return m.group(1) if m else None


def _manifest(data: str) -> dict[str, str]:
    out: dict[str, str] = {}
    key = None
    for line in data.splitlines():
        if line.startswith(" ") and key:
            out[key] += line[1:]
            continue
        if ":" in line:
            key, value = line.split(":", 1)
            out[key.strip()] = value.strip()
    return out


def jar_identity(name: str, data: bytes) -> tuple[str | None, str, str | None]:
    """(group, artifact, version) from pom.properties, the manifest, then the file name."""
    group = version = None
    artifact = ""
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as zf:
            names = zf.namelist()
            poms = [n for n in names if re.match(r"META-INF/maven/[^/]+/[^/]+/pom\.properties$", n)]
            if len(poms) == 1:
                props = dict(
                    line.split("=", 1)
                    for line in zf.read(poms[0]).decode("utf-8", "replace").splitlines()
                    if "=" in line and not line.startswith("#")
                )
                if props.get("artifactId"):
                    return props.get("groupId"), props["artifactId"], props.get("version")
            if "META-INF/MANIFEST.MF" in names:
                mf = _manifest(zf.read("META-INF/MANIFEST.MF").decode("utf-8", "replace"))
                version = mf.get("Implementation-Version") or mf.get("Bundle-Version")
                group = mf.get("Implementation-Vendor-Id")
                sym = mf.get("Bundle-SymbolicName", "").split(";")[0]
                if sym and "." in sym:
                    group = group or sym.rsplit(".", 1)[0]
    except (zipfile.BadZipFile, OSError, KeyError):
        pass
    m = _JAR_NAME.match(name)
    if m:
        artifact = m.group("a")
        version = version or m.group("v")
    else:
        artifact = name.rsplit(".", 1)[0]
    return group, artifact, version


class DepsAnalyzer:
    name: ClassVar[str] = "deps"
    version: ClassVar[str] = "2"  # 2: npm lock files, bower.json, Node.js version

    def supports(self, file: FileRecord) -> bool:
        p = file.path.lower()
        name = p.rsplit("/", 1)[-1]
        return (
            p.endswith(_ARCHIVES)
            or name in ("package.json", "bower.json", ".nvmrc")
            or name in NPM_LOCK_FILES
            or (file.language == "javascript" and "node_modules" not in p)
        )

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        facts = Facts()
        p = file.path.lower()
        if p.endswith(_ARCHIVES):
            data = ctx.read_bytes()
            sha1 = hashlib.sha1(data).hexdigest()
            group, artifact, version = jar_identity(basename(file.path), data)
            facts.add(
                "libraries",
                library(
                    group=group,
                    artifact=artifact,
                    version=version,
                    source="jar",
                    file=file.path,
                    line=None,
                    declared=False,
                    sha1=sha1,
                ),
            )
            if p.endswith((".war", ".ear")):
                self._nested(file.path, data, facts)
            return facts
        text = ctx.text or ""
        facts.coverage.lines = text.count("\n") + 1
        name = p.rsplit("/", 1)[-1]
        if name == ".nvmrc":
            ver = node_version(text.strip())
            if ver:
                facts.add("tech_stack", tech("Node.js", ver, "runtime", file.path, 1))
            return facts
        if name in NPM_LOCK_FILES:
            self._lock(file.path, name, text, facts)
            return facts
        if name in ("package.json", "bower.json"):
            try:
                pkg = json.loads(text)
            except json.JSONDecodeError as err:
                facts.coverage.gap(1, facts.coverage.lines, f"JSON parse error: {err}")
                return facts
            if not isinstance(pkg, dict):
                return facts
            lines = split_lines(text)
            source = "npm" if name == "package.json" else "bower"
            for scope in _NPM_SCOPES:
                deps = pkg.get(scope)
                if not isinstance(deps, dict):
                    continue
                for dep, ver in deps.items():
                    line = next((i for i, ln in enumerate(lines, 1) if f'"{dep}"' in ln), None)
                    facts.add(
                        "libraries",
                        library(
                            group=None,
                            artifact=dep,
                            version=npm_version(str(ver)),
                            source=source,
                            file=file.path,
                            line=line,
                            scope=scope,
                            language="javascript",
                        ),
                    )
            engines = pkg.get("engines")
            if isinstance(engines, dict) and isinstance(engines.get("node"), str):
                ver = node_version(engines["node"])
                line = next((i for i, ln in enumerate(lines, 1) if '"node"' in ln), None)
                facts.add("tech_stack", tech("Node.js", ver, "runtime", file.path, line))
            return facts
        # A bundled JavaScript library: file name or header comment.
        m = _JS_NAME.match(basename(file.path))
        h = _JS_HEADER.search(text[:600]) if text.lstrip().startswith(("/*", "//")) else None
        if m or h:
            artifact = (m.group("a") if m else h.group("a")).lower().replace(" ", "-")  # type: ignore[union-attr]
            artifact = artifact.replace("javascript-framework", "").strip("-.")
            version = (m.group("v") if m else None) or (h.group("v") if h else None)
            facts.add(
                "libraries",
                library(
                    group=None,
                    artifact=artifact,
                    version=version,
                    source="js-file",
                    file=file.path,
                    line=1,
                    declared=False,
                    language="javascript",
                ),
            )
        return facts

    def _lock(self, path: str, name: str, text: str, facts: Facts) -> None:
        """Exact versions from an npm, Yarn or pnpm lock file."""
        found: dict[tuple[str, str], bool] = {}  # (package, version) -> dev only
        if name.endswith(".json"):
            try:
                data = json.loads(text)
            except json.JSONDecodeError as err:
                facts.coverage.gap(1, facts.coverage.lines, f"JSON parse error: {err}")
                return
            if not isinstance(data, dict):
                return
            packages = data.get("packages")
            if isinstance(packages, dict):  # lockfileVersion 2 and 3
                for key, info in packages.items():
                    if not key or "node_modules/" not in key or not isinstance(info, dict):
                        continue
                    pkg = key.rsplit("node_modules/", 1)[-1]
                    ver = info.get("version")
                    if isinstance(ver, str):
                        found.setdefault((pkg, ver), bool(info.get("dev")))
            else:  # lockfileVersion 1: nested "dependencies"
                stack = [data.get("dependencies")]
                while stack:
                    deps = stack.pop()
                    if not isinstance(deps, dict):
                        continue
                    for pkg, info in deps.items():
                        if not isinstance(info, dict):
                            continue
                        ver = info.get("version")
                        if isinstance(ver, str):
                            found.setdefault((pkg, ver), bool(info.get("dev")))
                        stack.append(info.get("dependencies"))
        elif name == "yarn.lock":
            current: list[str] = []
            for line in split_lines(text):
                if line and not line.startswith((" ", "#")):
                    current = [m.group(1) for part in line.rstrip(":").split(",")
                               if (m := _YARN_HEAD.match(part.strip()))]  # fmt: skip
                    continue
                m = _YARN_VERSION.match(line)
                if m and current:
                    for pkg in current:
                        found.setdefault((pkg, m.group(1)), False)
                    current = []
        else:  # pnpm-lock.yaml: "  /name@1.2.3:" or "  name@1.2.3:" under packages
            section = ""
            for line in split_lines(text):
                if line and not line.startswith(" "):
                    section = line.rstrip(":").strip()
                    continue
                if section not in ("packages", "snapshots"):
                    continue
                m = _PNPM_KEY.match(line)
                if m and line.startswith("  ") and not line.startswith("   "):
                    found.setdefault((m.group(1), m.group(2)), False)
        for (pkg, ver), dev in sorted(found.items()):
            facts.add(
                "libraries",
                library(
                    group=None,
                    artifact=pkg,
                    version=ver,
                    source="npm-lock",
                    file=path,
                    line=None,
                    declared=False,
                    scope="devDependencies" if dev else "transitive",
                    language="javascript",
                ),
            )

    def _nested(self, path: str, data: bytes, facts: Facts) -> None:
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as zf:
                for n in zf.namelist():
                    if n.lower().endswith(".jar") and ("lib/" in n.lower()):
                        inner = zf.read(n)
                        g, a, v = jar_identity(basename(n), inner)
                        lib = library(
                            group=g,
                            artifact=a,
                            version=v,
                            source="jar",
                            file=f"{path}!/{n}",
                            line=None,
                            declared=False,
                            sha1=hashlib.sha1(inner).hexdigest(),
                        )
                        facts.add("libraries", lib)
        except (zipfile.BadZipFile, OSError):
            facts.coverage.gap(0, 0, "archive could not be opened")
