"""build and deps analyzers."""

from __future__ import annotations

import io
import zipfile
from pathlib import Path

from legacy_audit.analyzers.build import BuildAnalyzer
from legacy_audit.analyzers.deps import DepsAnalyzer
from tests.analyzers.helpers import entities, run

POM = """<project xmlns="http://maven.apache.org/POM/4.0.0">
  <parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1</version></parent>
  <artifactId>app</artifactId>
  <properties><commons.version>3.2.1</commons.version><java.version>1.8</java.version></properties>
  <dependencies>
    <dependency><groupId>commons-collections</groupId><artifactId>commons-collections</artifactId>
      <version>${commons.version}</version></dependency>
    <dependency><groupId>org.springframework</groupId><artifactId>spring-core</artifactId></dependency>
    <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.12</version>
      <scope>test</scope></dependency>
  </dependencies>
</project>
"""


def test_maven_dependencies_properties_and_unresolved_versions() -> None:
    facts = run(BuildAnalyzer(), "app/pom.xml", POM)
    libs = {(lib.artifact, lib.version, lib.scope) for lib in entities(facts, "libraries")}
    assert ("commons-collections", "3.2.1", "compile") in libs
    assert ("junit", "4.12", "test") in libs
    assert ("spring-core", None, "compile") in libs  # resolved from the parent by the linker
    assert any(s.kind == "maven-unresolved" for s in facts.symbols)
    assert any(s.kind == "maven-pom" and s.data["parent"] == "com.acme:parent" for s in facts.symbols)
    java = [t for t in entities(facts, "tech_stack") if t.name == "Java"]
    assert java and java[0].version == "1.8"


def test_makefile_executables_and_proc() -> None:
    text = "CC = gcc\nall: loader\nloader: loader.o\n\t$(CC) -o loader loader.o\n%.c: %.pc\n\tproc iname=$<\n"
    facts = run(BuildAnalyzer(), "batch/Makefile", text)
    assert {s.name for s in facts.symbols if s.kind == "c-executable"} == {"loader"}
    names = {t.name for t in entities(facts, "tech_stack")}
    assert "Oracle Pro*C" in names and "C compiler (gcc)" in names


def test_ant_java_version() -> None:
    facts = run(BuildAnalyzer(), "build.xml", '<project><javac source="1.5" target="1.5"/></project>')
    assert [(t.name, t.version) for t in entities(facts, "tech_stack") if t.name == "Java"] == [("Java", "1.5")]


def _jar(tmp_path: Path, name: str, pom_props: str | None, manifest: str = "") -> Path:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n" + manifest)
        if pom_props:
            z.writestr("META-INF/maven/g/a/pom.properties", pom_props)
    p = tmp_path / name
    p.write_bytes(buf.getvalue())
    return p


def test_jar_identity_from_pom_properties_then_file_name(tmp_path: Path) -> None:
    jar = _jar(tmp_path, "whatever.jar", "groupId=log4j\nartifactId=log4j\nversion=1.2.17\n",
               "Implementation-Version: 9.9\n")  # pom.properties wins over the manifest
    facts = run(DepsAnalyzer(), "WEB-INF/lib/whatever.jar", "", language="archive", abs_path=jar)
    (lib,) = entities(facts, "libraries")
    assert (lib.group, lib.artifact, lib.version, lib.declared) == ("log4j", "log4j", "1.2.17", False)
    assert lib.sha1 and len(lib.sha1) == 40

    jar = _jar(tmp_path, "struts-1.2.9.jar", None)
    facts = run(DepsAnalyzer(), "WEB-INF/lib/struts-1.2.9.jar", "", language="archive", abs_path=jar)
    (lib,) = entities(facts, "libraries")
    assert (lib.group, lib.artifact, lib.version) == (None, "struts", "1.2.9")

    jar = _jar(tmp_path, "ojdbc14.jar", None, "Implementation-Version: 10.2.0.4.0\n")
    facts = run(DepsAnalyzer(), "WEB-INF/lib/ojdbc14.jar", "", language="archive", abs_path=jar)
    (lib,) = entities(facts, "libraries")
    assert (lib.artifact, lib.version) == ("ojdbc14", "10.2.0.4.0")


def test_bundled_javascript_library() -> None:
    facts = run(DepsAnalyzer(), "web/js/jquery-1.4.2.min.js", "/*! jQuery v1.4.2 */\n")
    (lib,) = entities(facts, "libraries")
    assert (lib.artifact.lower(), lib.version, lib.language) == ("jquery", "1.4.2", "javascript")
