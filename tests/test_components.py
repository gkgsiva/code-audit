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
    assert names(comps) == {
        "r": ("unknown", 2, "build-file"),
        "core": ("library", 2, "build-file"),
        "web": ("web", 3, "build-file"),
    }
    assert mapping[files[2].id] == "core"


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
