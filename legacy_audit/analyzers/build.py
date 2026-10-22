"""Build files: Maven, Gradle (best effort), Ant/Ivy and Makefiles.

Outputs declared ``Library`` facts and ``TechItem``s (Java version, build tool, Spring Boot).
Maven versions inherited from a parent pom (properties, dependencyManagement) are resolved by
the linker from the ``maven-*`` symbols declared here.
"""

from __future__ import annotations

import json
import re
import xml.etree.ElementTree as ET
from typing import ClassVar

from legacy_audit.analyzers.base import AnalyzerContext, Facts
from legacy_audit.analyzers.textutil import attrs, child_text, line_of, parse_xml, split_lines
from legacy_audit.model import Evidence, FileRecord, Library, TechItem, make_id

_PROP = re.compile(r"\$\{([^}]+)\}")


def tech(name: str, version: str | None, category: str, file: str, line: int | None) -> TechItem:
    return TechItem(
        id=make_id("tech", name, version or ""),
        name=name,
        version=version,
        category=category,
        evidence=[Evidence(file=file, line=line)],
    )


def library(
    *,
    group: str | None,
    artifact: str,
    version: str | None,
    source: str,
    file: str,
    line: int | None,
    declared: bool = True,
    scope: str | None = None,
    language: str = "java",
    sha1: str | None = None,
) -> Library:
    return Library(
        id=make_id("library", language, group or "", artifact, version or ""),
        group=group,
        artifact=artifact,
        version=version,
        source=source,
        declared=declared,
        scope=scope,
        language=language,
        sha1=sha1,
        where_used=[file],
        evidence=[Evidence(file=file, line=line)],
    )


def _resolve(value: str | None, props: dict[str, str], depth: int = 0) -> str | None:
    if value is None or depth > 5:
        return value
    out = _PROP.sub(lambda m: props.get(m.group(1), m.group(0)), value.strip())
    return _resolve(out, props, depth + 1) if out != value and "${" in out else out


class BuildAnalyzer:
    name: ClassVar[str] = "build"
    version: ClassVar[str] = "1"

    def supports(self, file: FileRecord) -> bool:
        name = file.path.rsplit("/", 1)[-1].lower()
        return (
            name in ("pom.xml", "build.xml", "ivy.xml")
            or (file.language in ("make", "groovy") and file.category == "build")
            or name.endswith(".gradle.kts")
        )

    def analyze(self, file: FileRecord, ctx: AnalyzerContext) -> Facts:
        facts = Facts()
        text = ctx.text or ""
        facts.coverage.lines = text.count("\n") + 1
        name = file.path.rsplit("/", 1)[-1].lower()
        try:
            if name == "pom.xml":
                self._maven(file.path, parse_xml(text), facts)
            elif name == "build.xml":
                self._ant(file.path, parse_xml(text), facts)
            elif name == "ivy.xml":
                self._ivy(file.path, parse_xml(text), facts)
            elif file.language == "make":
                self._make(file.path, text, facts)
            else:
                self._gradle(file.path, text, facts)
        except ET.ParseError as err:
            facts.coverage.gap(1, facts.coverage.lines, f"XML parse error: {err}")
        return facts

    # ---- Maven ------------------------------------------------------------------------------
    def _maven(self, path: str, root: ET.Element, facts: Facts) -> None:
        parent = root.find("parent")
        p_group = child_text(parent, "groupId") if parent is not None else None
        p_artifact = child_text(parent, "artifactId") if parent is not None else None
        p_version = child_text(parent, "version") if parent is not None else None
        group = child_text(root, "groupId") or p_group
        artifact = child_text(root, "artifactId") or ""
        version = child_text(root, "version") or p_version
        props: dict[str, str] = {}
        props_el = root.find("properties")
        if props_el is not None:
            for p in props_el:
                props[p.tag] = (p.text or "").strip()
        props.update(
            {
                "project.groupId": group or "",
                "project.artifactId": artifact,
                "project.version": version or "",
                "pom.version": version or "",
                "project.parent.version": p_version or "",
            }
        )
        coord = f"{group}:{artifact}"
        facts.symbol(
            "maven-pom",
            coord,
            path,
            line_of(root),
            data={
                "parent": f"{p_group}:{p_artifact}" if p_artifact else "",
                "version": version or "",
                "properties": json.dumps(props, sort_keys=True),
            },
        )
        facts.add("tech_stack", tech("Maven", None, "build", path, line_of(root)))

        managed: dict[str, str] = {}
        dm = root.find("dependencyManagement/dependencies")
        if dm is not None:
            for d in dm.findall("dependency"):
                g, a = child_text(d, "groupId"), child_text(d, "artifactId")
                v = _resolve(child_text(d, "version"), props)
                if g and a and v:
                    managed[f"{_resolve(g, props)}:{a}"] = v
                    facts.symbol(
                        "maven-managed",
                        f"{coord}|{_resolve(g, props)}:{a}",
                        path,
                        line_of(d),
                        data={"version": v},
                    )
                if child_text(d, "scope") == "import":
                    facts.add(
                        "libraries",
                        library(
                            group=_resolve(g, props),
                            artifact=a or "",
                            version=v,
                            source="maven",
                            file=path,
                            line=line_of(d),
                            scope="import",
                        ),
                    )

        deps = root.find("dependencies")
        for d in deps.findall("dependency") if deps is not None else []:
            g = _resolve(child_text(d, "groupId"), props)
            a = child_text(d, "artifactId") or ""
            v = _resolve(child_text(d, "version"), props)
            if v is None:
                v = managed.get(f"{g}:{a}")
            lib = library(
                group=g,
                artifact=a,
                version=v,
                source="maven",
                file=path,
                line=line_of(d),
                scope=child_text(d, "scope") or "compile",
            )
            if v is None or "${" in v:
                # Resolved later from the parent pom chain.
                facts.symbol(
                    "maven-unresolved",
                    lib.id,
                    path,
                    line_of(d),
                    data={"pom": coord, "key": f"{g}:{a}", "raw": v or ""},
                )
            facts.add("libraries", lib)

        # Java version and Spring Boot.
        java = None
        for key in ("maven.compiler.release", "maven.compiler.source", "java.version"):
            if props.get(key):
                java = _resolve(props[key], props)
                break
        for plugin in root.iter("plugin"):
            if child_text(plugin, "artifactId") == "maven-compiler-plugin":
                conf = plugin.find("configuration")
                if conf is not None:
                    java = (
                        _resolve(child_text(conf, "release") or child_text(conf, "source"), props)
                        or java
                    )
        if java and "${" not in java:
            facts.add("tech_stack", tech("Java", java, "language", path, line_of(root)))
        if p_artifact == "spring-boot-starter-parent":
            facts.add(
                "tech_stack",
                tech(
                    "Spring Boot",
                    p_version,
                    "framework",
                    path,
                    line_of(parent) if parent is not None else None,
                ),
            )

    # ---- Ant / Ivy ----------------------------------------------------------------------------
    def _ant(self, path: str, root: ET.Element, facts: Facts) -> None:
        if root.tag != "project":
            return
        facts.add("tech_stack", tech("Ant", None, "build", path, line_of(root)))
        props = {
            a["name"]: a["value"]
            for el in root.iter("property")
            if "name" in (a := attrs(el)) and "value" in a
        }
        for javac in root.iter("javac"):
            a = attrs(javac)
            v = _resolve(a.get("source") or a.get("target") or a.get("release"), props)
            if v and "${" not in v:
                facts.add("tech_stack", tech("Java", v, "language", path, line_of(javac)))
                break

    def _ivy(self, path: str, root: ET.Element, facts: Facts) -> None:
        for d in root.iter("dependency"):
            a = attrs(d)
            if a.get("name"):
                facts.add(
                    "libraries",
                    library(
                        group=a.get("org"),
                        artifact=a["name"],
                        version=a.get("rev"),
                        source="ivy",
                        file=path,
                        line=line_of(d),
                        scope=a.get("conf"),
                    ),
                )

    # ---- Gradle -------------------------------------------------------------------------------
    _GRADLE_DEP = re.compile(
        r"^\s*(implementation|api|compile|compileOnly|runtime|runtimeOnly|testImplementation|"
        r"testCompile|providedCompile|providedRuntime)\s*\(?\s*['\"]([^:'\"]+):([^:'\"]+):([^'\"@]+)['\"]",
        re.M,
    )
    _GRADLE_MAP = re.compile(
        r"^\s*(\w+)\s*\(?\s*group\s*:\s*['\"]([^'\"]+)['\"]\s*,\s*name\s*:\s*['\"]([^'\"]+)['\"]"
        r"\s*,\s*version\s*:\s*['\"]([^'\"]+)['\"]",
        re.M,
    )
    _GRADLE_JAVA = re.compile(
        r"sourceCompatibility\s*=\s*['\"]?(?:JavaVersion\.VERSION_)?([\d._]+)"
    )

    def _gradle(self, path: str, text: str, facts: Facts) -> None:
        facts.add("tech_stack", tech("Gradle", None, "build", path, 1))
        for rx in (self._GRADLE_DEP, self._GRADLE_MAP):
            for m in rx.finditer(text):
                line = text.count("\n", 0, m.start()) + 1
                facts.add(
                    "libraries",
                    library(
                        group=m.group(2),
                        artifact=m.group(3),
                        version=m.group(4),
                        source="gradle",
                        file=path,
                        line=line,
                        scope=m.group(1),
                    ),
                )
        jm = self._GRADLE_JAVA.search(text)
        if jm:
            facts.add(
                "tech_stack",
                tech(
                    "Java",
                    jm.group(1).replace("_", "."),
                    "language",
                    path,
                    text.count("\n", 0, jm.start()) + 1,
                ),
            )

    # ---- Make ---------------------------------------------------------------------------------
    _TARGET = re.compile(r"^([A-Za-z0-9_.$(){}/-]+)\s*:(?!=)\s*(.*)$")
    _OUT = re.compile(r"-o\s+(\$\(\w+\)|[\w./-]+)")

    def _make(self, path: str, text: str, facts: Facts) -> None:
        facts.add("tech_stack", tech("Make", None, "build", path, 1))
        variables: dict[str, str] = {}
        for no, line in enumerate(split_lines(text), 1):
            if line.startswith("\t") or line.lstrip().startswith("#"):
                cmd = line.strip()
                if re.match(r"(\$\(PROC\)|proc)\s", cmd):
                    facts.add("tech_stack", tech("Oracle Pro*C", None, "build", path, no))
                for m in self._OUT.finditer(cmd):
                    out = m.group(1)
                    if out.startswith("$("):
                        out = variables.get(out[2:-1], "")
                    if out and not out.endswith(".o") and "$" not in out:
                        facts.symbol("c-executable", out.rsplit("/", 1)[-1], path, no)
                continue
            var = re.match(r"^\s*(\w+)\s*[:+?]?=\s*(.*)$", line)
            if var:
                variables[var.group(1)] = var.group(2).strip()
                if var.group(1) in ("CC", "CXX"):
                    facts.add(
                        "tech_stack",
                        tech(f"C compiler ({var.group(2).split()[0]})", None, "build", path, no),
                    )
                continue
            tm = self._TARGET.match(line)
            if tm:
                target = tm.group(1)
                if target.startswith(".") or target in ("all", "clean", "install", "test"):
                    continue
                if "$(" in target:
                    target = variables.get(target.strip("$()"), "")
                if target and not target.endswith((".o", ".c", ".h")) and "%" not in target:
                    facts.symbol("c-executable", target.rsplit("/", 1)[-1], path, no)
