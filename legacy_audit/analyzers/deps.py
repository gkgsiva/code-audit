"""Undeclared dependencies: jars/wars/ears (manifest, pom.properties, file name), npm
``package.json`` and bundled JavaScript libraries."""

from __future__ import annotations

import hashlib
import io
import json
import re
import zipfile
from typing import ClassVar

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.build import library
from legacy_audit.analyzers.textutil import basename
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
    version: ClassVar[str] = "1"

    def supports(self, file: FileRecord) -> bool:
        p = file.path.lower()
        return (
            p.endswith(_ARCHIVES)
            or p.endswith("/package.json")
            or p == "package.json"
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
        if p.endswith("package.json"):
            try:
                pkg = json.loads(text)
            except json.JSONDecodeError as err:
                facts.coverage.gap(1, facts.coverage.lines, f"JSON parse error: {err}")
                return facts
            for scope in ("dependencies", "devDependencies"):
                for name, ver in (pkg.get(scope) or {}).items():
                    facts.add(
                        "libraries",
                        library(
                            group=None,
                            artifact=name,
                            version=str(ver).lstrip("^~=") or None,
                            source="npm",
                            file=file.path,
                            line=None,
                            scope=scope,
                            language="javascript",
                        ),
                    )
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
