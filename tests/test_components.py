from legacy_audit.config import ComponentSpec
from legacy_audit.inventory.components import detect_components
from legacy_audit.model import Category, FileRecord, make_id


def rec(path: str, lang: str = "java", cat: Category = Category.SOURCE, root: str = "r"):
    return FileRecord(
        id=make_id("file", root, path),
        path=path,
        root=root,
        language=lang,
        category=cat,
        size=1,
        sha256="x",
        loc_code=10,
    )


def names(components):
    return {c.name: (c.type, c.files, c.detected_by) for c in components}


def test_maven_multi_module():
    files = [
        rec("pom.xml", "xml", Category.BUILD),
        rec("core/pom.xml", "xml", Category.BUILD),
        rec("core/src/main/java/A.java"),
        rec("web/pom.xml", "xml", Category.BUILD),
        rec("web/src/main/webapp/WEB-INF/web.xml", "xml", Category.CONFIG),
        rec("web/src/main/webapp/index.jsp", "jsp", Category.SCREEN),
        rec("docs/notes.md", "markdown", Category.DOC),
    ]
    comps, mapping = detect_components(files, ["r"], [])
    # The top pom (not read here) holds the other modules and no code: a group (decision 166);
    # it and docs/ (no code, script, SQL or configuration) belong to no component.
    assert names(comps) == {
        "core": ("library", 2, "build-file"),
        "web": ("web", 3, "build-file"),
    }
    assert mapping[files[2].id] == "core"
    assert files[0].id not in mapping and files[6].id not in mapping
    assert {c.name: c.build_group for c in comps} == {"core": ["r"], "web": ["r"]}


def test_top_folders_without_build_files():
    files = [
        rec("batch/run.ksh", "shell", Category.SCRIPT),
        rec("batch/jobs/NIGHTLY.jcl", "jcl", Category.JOB),
        rec("db/schema.sql", "sql", Category.SQL),
        rec("cprog/Makefile", "make", Category.BUILD),
        rec("cprog/main.c", "c"),
        rec("README.txt", "text", Category.DOC),
    ]
    comps, _ = detect_components(files, ["r"], [])
    assert names(comps) == {
        "r": ("unknown", 1, "root"),
        "batch": ("batch", 2, "folder"),
        "cprog": ("library", 2, "build-file"),
        "db": ("db", 1, "folder"),
    }


def test_manual_components_win():
    files = [rec("pom.xml", "xml", Category.BUILD), rec("src/a/A.java"), rec("src/b/B.java")]
    specs = [ComponentSpec(name="alpha", path="src/a", type="batch")]
    comps, mapping = detect_components(files, ["r"], specs)
    assert names(comps)["alpha"] == ("batch", 1, "config")
    assert mapping[files[1].id] == "alpha"
    assert mapping[files[2].id] == "r"


def test_multiple_roots_prefix_paths():
    files = [
        rec("one/build.xml", "xml", Category.BUILD, root="one"),
        rec("one/src/A.java", root="one"),
        rec("two/pom.xml", "xml", Category.BUILD, root="two"),
    ]
    comps, _ = detect_components(files, ["one", "two"], [])
    assert {c.name for c in comps} == {"one", "two"}
    assert next(c for c in comps if c.name == "one").build_files == ["one/build.xml"]


def test_component_ids_are_stable():
    files = [rec("pom.xml", "xml", Category.BUILD), rec("A.java")]
    a, _ = detect_components(files, ["r"], [])
    b, _ = detect_components(list(reversed(files)), ["r"], [])
    assert [c.id for c in a] == [c.id for c in b]


def test_application_folders_hold_their_parts():
    """Without build files, src/WebContent/ksh/properties are parts of one component."""
    files = [
        rec("apps/orders/src/com/acme/A.java"),
        rec("apps/orders/WebContent/WEB-INF/web.xml", "xml", Category.CONFIG),
        rec("apps/orders/WebContent/a.jsp", "jsp", Category.SCREEN),
        rec("apps/orders/conf/app.properties", "properties", Category.CONFIG),
        rec("apps/billing/java/com/acme/Run.java"),
        rec("apps/billing/ksh/run.ksh", "shell", Category.SCRIPT),
        rec("apps/billing/properties/b.properties", "properties", Category.CONFIG),
        rec("config/common.properties", "properties", Category.CONFIG),
        rec("config/log4j.xml", "xml", Category.CONFIG),
        rec("scripts/deploy.sh", "shell", Category.SCRIPT),
    ]
    comps, mapping = detect_components(files, ["r"], [])
    assert names(comps) == {
        "orders": ("web", 4, "folder"),
        "billing": ("library", 3, "folder"),  # the linker makes it batch from its main/ksh
        "config": ("config", 2, "folder"),
        "scripts": ("script", 1, "folder"),
    }
    assert {c.name: c.composition for c in comps}["billing"] == {
        "config": 1, "script": 1, "source": 1
    }  # fmt: skip
    assert mapping[files[5].id] == "billing"  # the ksh belongs to its component


def test_root_application_keeps_its_folders_together():
    files = [
        rec("src/com/acme/A.java"),
        rec("WebContent/a.jsp", "jsp", Category.SCREEN),
        rec("properties/app.properties", "properties", Category.CONFIG),
        rec("batch/nightly.ksh", "shell", Category.SCRIPT),
        rec("reports/src/R.java"),  # an application of its own, nested
    ]
    comps, _ = detect_components(files, ["r"], [])
    assert names(comps) == {"r": ("web", 4, "root"), "reports": ("library", 1, "folder")}


def test_batch_folder_names_and_project_files():
    files = [
        rec("settle/Makefile.aix", "make", Category.BUILD),
        rec("settle/src/s.pc", "proc"),
        rec("eod-batch/run_eod.ksh", "shell", Category.SCRIPT),
        rec("eod-batch/eod.properties", "properties", Category.CONFIG),
        rec("ui/.project", "xml", Category.CONFIG),
        rec("ui/src/U.java"),
    ]
    comps, _ = detect_components(files, ["r"], [])
    assert names(comps) == {
        "settle": ("library", 2, "build-file"),
        "eod-batch": ("batch", 2, "folder"),
        "ui": ("library", 2, "build-file"),
    }


def _pom(artifact: str, packaging: str = "jar", modules: str = "") -> str:
    return (
        '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
        f"<artifactId>{artifact}</artifactId><packaging>{packaging}</packaging>{modules}"
        "</project>"
    )


def _reader(texts: dict[str, str]):
    return lambda root, rel: texts.get(rel)


def test_maven_parents_are_groups_and_modules_components():
    files = [
        rec("pom.xml", "xml", Category.BUILD),
        rec("app/pom.xml", "xml", Category.BUILD),
        rec("app/src/main/java/A.java"),
        rec("batches/pom.xml", "xml", Category.BUILD),
        rec("batches/eod/pom.xml", "xml", Category.BUILD),
        rec("batches/eod/src/main/java/Eod.java"),
        rec("tools/pom.xml", "xml", Category.BUILD),
        rec("tools/src/main/java/T.java"),
        rec("config/app.properties", "properties", Category.CONFIG),
        rec("scripts/run_eod.ksh", "shell", Category.SCRIPT),
        rec("README.md", "markdown", Category.DOC),
    ]
    texts = {
        "pom.xml": _pom(
            "acme-parent",
            "pom",
            "<modules><module>app</module><module>batches/pom.xml</module></modules>"
            "<profiles><profile><modules><module>./tools</module></modules></profile></profiles>",
        ),
        "app/pom.xml": _pom("acme-app", "war"),
        "batches/pom.xml": _pom("acme-batches", "pom", "<modules><module>eod</module></modules>"),
        "batches/eod/pom.xml": _pom("acme-eod"),
        "tools/pom.xml": _pom("acme-tools"),
    }
    comps, mapping = detect_components(files, ["r"], [], _reader(texts))
    assert names(comps) == {
        "app": ("web", 2, "build-file"),  # war packaging
        "eod": ("batch", 2, "build-file"),  # it sits in batches/
        "tools": ("library", 2, "build-file"),
        "config": ("config", 1, "folder"),
        "scripts": ("script", 1, "folder"),
    }
    by = {c.name: c for c in comps}
    assert by["eod"].build_group == ["acme-parent", "acme-batches"]
    assert by["scripts"].build_group == ["acme-parent"]
    assert (by["app"].artifact, by["app"].packaging) == ("acme-app", "war")
    for f in (files[0], files[3], files[10]):  # the parent poms and the README
        assert f.id not in mapping


def test_pom_with_code_is_a_module_even_with_modules_under_it():
    files = [
        rec("pom.xml", "xml", Category.BUILD),
        rec("src/main/java/A.java"),
        rec("sub/pom.xml", "xml", Category.BUILD),
        rec("sub/src/main/java/B.java"),
    ]
    texts = {"pom.xml": _pom("root-app", "jar", "<modules><module>sub</module></modules>")}
    comps, _ = detect_components(files, ["r"], [], _reader(texts))
    assert names(comps) == {
        "r": ("library", 2, "build-file"),
        "sub": ("library", 2, "build-file"),
    }


def test_read_pom_module_paths():
    from legacy_audit.inventory.components import read_pom

    info = read_pom(
        "parent",
        _pom(
            "p",
            "POM",
            "<modules><module>a</module><module>../shared</module><module>../../out</module>"
            "<module>b\\pom.xml</module></modules>",
        ),
    )
    assert info.packaging == "pom"
    assert info.modules == ["parent/a", "shared", "parent/b"]  # ../../out leaves the root


def test_gradle_settings_include_projects():
    files = [
        rec("settings.gradle", "groovy", Category.BUILD),
        rec("build.gradle", "groovy", Category.BUILD),
        rec("core/src/main/java/A.java"),  # a Gradle project needs no build file of its own
        rec("batch/billing/build.gradle", "groovy", Category.BUILD),
        rec("batch/billing/src/main/java/B.java"),
        rec("legacy-web/src/main/webapp/a.jsp", "jsp", Category.SCREEN),
    ]
    texts = {
        "settings.gradle": "rootProject.name = 'acme'\ninclude 'core', ':batch:billing'\n"
        "include(':web')\nproject(':web').projectDir = file('legacy-web')\n",
    }
    comps, _ = detect_components(files, ["r"], [], _reader(texts))
    assert names(comps) == {
        "core": ("library", 1, "build-file"),
        "billing": ("batch", 2, "build-file"),  # it sits in batch/
        "legacy-web": ("web", 1, "build-file"),
    }
    assert {c.name: c.build_group for c in comps}["billing"] == ["acme"]


def test_batch_folder_with_several_batches_is_split():
    files = [
        rec("ops/batch/env.properties", "properties", Category.CONFIG),
        rec("ops/batch/common/functions.ksh", "shell", Category.SCRIPT),
        rec("ops/batch/purge/purge.ksh", "shell", Category.SCRIPT),
        rec("ops/batch/purge/purge.sql", "sql", Category.SQL),
        rec("ops/batch/archive/archive.ksh", "shell", Category.SCRIPT),
        rec("ops/batch/eod/ksh/run_eod.ksh", "shell", Category.SCRIPT),
        rec("ops/batch/eod/sql/eod.sql", "sql", Category.SQL),
        rec("web/src/A.java"),
    ]
    comps, mapping = detect_components(files, ["r"], [])
    assert names(comps) == {
        "batch": ("script", 2, "batch-shared"),  # what the batches share, never a batch
        "purge": ("batch", 2, "folder"),
        "archive": ("batch", 1, "folder"),
        "eod": ("batch", 2, "folder"),  # ksh/ and sql/ are parts of the eod batch
        "web": ("library", 1, "folder"),
    }
    assert mapping[files[1].id] == "batch"


def test_batch_folder_holding_one_batch_is_not_split():
    files = [
        rec("batch/ksh/run.ksh", "shell", Category.SCRIPT),
        rec("batch/java/Run.java"),
        rec("batch/properties/run.properties", "properties", Category.CONFIG),
        rec("web/src/A.java"),
    ]
    comps, _ = detect_components(files, ["r"], [])
    assert names(comps)["batch"] == ("batch", 3, "folder")
