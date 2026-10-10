"""Phase 3 metrics: units and cyclomatic complexity, duplication, dead code, bands, effort."""

from __future__ import annotations

import json
from pathlib import Path

from legacy_audit.analyzers.metrics import MetricsAnalyzer
from legacy_audit.config import load_config
from legacy_audit.metrics.compute import band
from legacy_audit.metrics.tokens import windows
from legacy_audit.metrics.units import cobol_units, plsql_units, script_unit, tree_units
from legacy_audit.scan import run_scan
from tests.analyzers.helpers import config, run
from tests.conftest import write

JAVA = """package a;
public class Calc {
    public int sign(int x) {
        if (x > 0 && x < 100) { return 1; }
        else if (x < 0) { return -1; }
        return 0;
    }
    public void loop(int[] xs) {
        for (int x : xs) {
            switch (x) { case 1: break; case 2: break; default: break; }
        }
        try { run(); } catch (Exception e) { }
    }
    void run() {}
}
"""


def test_java_methods_and_cyclomatic() -> None:
    units = {u.name: u for u in tree_units(JAVA, "java")}
    assert units["sign"].cyclomatic == 4  # if, &&, else-if
    assert units["loop"].cyclomatic == 5  # for, 2 cases, catch
    assert units["run"].cyclomatic == 1
    assert units["sign"].line == 3 and units["sign"].kind == "method"


def test_c_functions() -> None:
    code = "int f(int a) {\n  while (a) { a--; }\n  return a ? 1 : 0;\n}\nvoid g(void) {}\n"
    units = {u.name: u for u in tree_units(code, "c")}
    assert units["f"].cyclomatic == 3
    assert units["g"].cyclomatic == 1


def test_cobol_paragraphs_ignore_end_if() -> None:
    lines = [
        "       PROCEDURE DIVISION.",
        "       MAIN-PARA.",
        "           IF WS-A = 1 AND WS-B = 2",
        "              PERFORM CALC-PARA UNTIL WS-EOF = 'Y'",
        "           END-IF.",
        "           STOP RUN.",
        "       CALC-PARA.",
        "           EVALUATE WS-X",
        "              WHEN 1 MOVE 1 TO WS-Y",
        "              WHEN OTHER MOVE 0 TO WS-Y",
        "           END-EVALUATE.",
    ]
    units = {u.name: u for u in cobol_units([ln[7:] for ln in lines])}  # code area only
    assert units["MAIN-PARA"].cyclomatic == 4  # IF, AND, UNTIL; END-IF is not an IF
    assert units["CALC-PARA"].cyclomatic == 2  # WHEN; WHEN OTHER does not count
    assert units["MAIN-PARA"].kind == "paragraph"


def test_plsql_procedures() -> None:
    code = """CREATE OR REPLACE PROCEDURE p_price(p IN NUMBER) AS
BEGIN
  IF p > 0 THEN NULL; ELSIF p < 0 THEN NULL; END IF;
  FOR r IN (SELECT 1 FROM dual) LOOP NULL; END LOOP;
END;
/
"""
    (unit,) = plsql_units(code)
    assert unit.cyclomatic == 4  # IF, ELSIF, LOOP


def test_script_is_one_unit() -> None:
    (unit,) = script_unit("if [ -f x ]; then\n  a && b\nfi\nfor i in 1 2; do :; done\n",
                          "shell", "run.sh")  # fmt: skip
    assert unit.cyclomatic == 4 and unit.kind == "script"


def test_duplicate_windows_match_across_files() -> None:
    body = " ".join(f"x{i} = y{i} + {i};" for i in range(40))
    a, b = windows(body, 50), windows("int z;\n" + body, 50)
    assert a and b
    assert {h for h, _, _ in a} & {h for h, _, _ in b}
    assert windows("short text", 50) == []


def test_metrics_analyzer_caches_units_and_windows() -> None:
    facts = run(MetricsAnalyzer(), "src/a/Calc.java", JAVA, cfg=config())
    assert {u.name for u in facts.units} >= {"sign", "loop", "run"}
    assert isinstance(facts.windows, list)


def test_band_thresholds() -> None:
    t = {"medium": 10, "complex": 25, "very_complex": 50}
    assert [band(s, t) for s in (0, 10, 24.9, 25, 50)] == [
        "Simple", "Medium", "Medium", "Complex", "Very complex",
    ]  # fmt: skip


def _app(tmp_path: Path) -> Path:
    app = tmp_path / "app"
    write(app / "audit.yaml", "application:\n  name: t\n")
    write(app / "pom.xml", "<project><artifactId>t</artifactId></project>\n")
    write(app / "src/main/java/a/Main.java",
          "package a;\npublic class Main {\n  public static void main(String[] a) {\n"
          "    new Used().go();\n  }\n}\n")  # fmt: skip
    write(app / "src/main/java/a/Used.java",
          "package a;\npublic class Used {\n  void go() { if (true) {} }\n}\n")  # fmt: skip
    write(app / "src/main/java/a/Orphan.java",
          "package a;\npublic class Orphan {\n  void never() {}\n}\n")  # fmt: skip
    return app


def test_dead_code_candidates(tmp_path: Path) -> None:
    result = run_scan(load_config(_app(tmp_path) / "audit.yaml"))
    dead = [f for f in result.facts["findings"] if f.rule_id == "dead-code-candidate"]  # type: ignore[attr-defined]
    assert [f.file for f in dead] == ["src/main/java/a/Orphan.java"]
    (metric,) = result.facts["metrics"]
    assert metric.dead_code_candidates == 1  # type: ignore[attr-defined]
    assert metric.units >= 3 and metric.test_files == 0  # type: ignore[attr-defined]


def test_changing_weights_rebands_without_reanalysis(sample_copy) -> None:  # type: ignore[no-untyped-def]
    app = sample_copy("spring-mvc-batch-app")
    first = run_scan(load_config(app / "audit.yaml"))
    cfg = app / "audit.yaml"
    cfg.write_text(
        cfg.read_text(encoding="utf-8")
        + "\ncomplexity:\n  weights: {loc: 1000}\neffort:\n  weights:\n"
        "    service: {very_complex: [20, 30, 50]}\n",
        encoding="utf-8",
    )
    second = run_scan(load_config(cfg))
    assert second.run.files_analyzed == 0  # bands and effort come from cached facts

    def bands(r):  # type: ignore[no-untyped-def]
        return sorted((s.name, s.complexity) for s in r.facts["services"])

    assert bands(first) != bands(second)

    def very(r):  # type: ignore[no-untyped-def]
        return sum(1 for _, b in bands(r) if b == "Very complex")

    assert very(second) > very(first)
    e1 = json.loads((first.run_dir / "data" / "effort.json").read_text(encoding="utf-8"))
    e2 = json.loads((second.run_dir / "data" / "effort.json").read_text(encoding="utf-8"))
    assert e2["likely"] > e1["likely"]
    assert e2["label"] == "estimate — weights from config"
