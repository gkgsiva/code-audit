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
