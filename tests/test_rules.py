"""Phase 3 rules: the engine, text patterns (with masking), PostgreSQL grading, config switches."""

from __future__ import annotations

from pathlib import Path

import pytest

from legacy_audit.analyzers.patterns import PatternsAnalyzer
from legacy_audit.config import load_config
from legacy_audit.model import DbObject, Evidence, PgGrade, SqlStatement
from legacy_audit.rules.engine import apply_config, evaluate, matches, render
from legacy_audit.rules.packs import Rule, load_pack, rules_key, text_rules
from legacy_audit.rules.pg import constructs, grade, grade_all
from legacy_audit.scan import run_scan
from tests.analyzers.helpers import config, run
from tests.conftest import write


# ---- engine ------------------------------------------------------------------------------------
def test_conditions() -> None:
    item = {"a": 3, "b": "Hello", "c": [1, 2], "d": None, "n": {"x": "y"}}
    assert matches(item, {"a": 3, "b": {"regex": "^H"}, "c": {"contains": 2}})
    assert matches(item, {"a": {"gte": 3, "lt": 4}, "n.x": "y", "d": {"exists": False}})
    assert matches(item, {"zz": {"empty": True}, "c": {"empty": False}, "b": {"in": ["Hello"]}})
    assert not matches(item, {"a": {"gt": 3}})
    assert not matches(item, {"b": {"not_in": ["Hello"]}})
    assert matches(item, {"b": {"ne": "x"}, "zz": {"ne": 1}})
    with pytest.raises(ValueError):
        matches(item, {"a": {"between": 1}})


def test_render_unknown_fields_and_lists() -> None:
    assert render("{a} {b} {missing} {n.x}", {"a": 1, "b": [1, 2], "n": {"x": "y"}}) == (
        "1 1, 2 ? y"
    )


def _rule(**kw):  # type: ignore[no-untyped-def]
    base = {"id": "r1", "severity": "medium", "category": "c", "message": "{name}",
            "match": {"entity": "things", "where": {"bad": True}}}  # fmt: skip
    return Rule.model_validate({**base, **kw})


def test_evaluate_and_apply_config() -> None:
    items = [
        {"id": "t1", "name": "one", "bad": True, "evidence": [{"file": "a.java", "line": 3}]},
        {"id": "t2", "name": "two", "bad": False},
        {"id": "t3", "name": "three", "bad": True, "level": "critical", "file": "b.c"},
    ]
    rules = [_rule(), _rule(id="r2", severity_field="level")]
    found = evaluate(rules, lambda name: items if name == "things" else [], [])
    assert sorted((f.rule_id, f.message) for f in found) == [
        ("r1", "one"), ("r1", "three"), ("r2", "one"), ("r2", "three"),
    ]  # fmt: skip
    assert {f.severity for f in found if f.rule_id == "r2"} == {"medium", "critical"}
    first = next(f for f in found if f.message == "one")
    assert (first.file, first.line) == ("a.java", 3)
    assert first.evidence == [Evidence(file="a.java", line=3)]

    cfg = config(rules={"disabled": ["r1"], "severity": {"r2": "low"}})
    out = apply_config(found + found, cfg)
    assert [(f.rule_id, f.severity) for f in out] == [("r2", "low"), ("r2", "low")]


def test_text_hits_become_findings() -> None:
    rule = Rule.model_validate({"id": "p", "severity": "low", "category": "c",
                                "message": "found {match}", "pattern": {"regex": "x"}})  # fmt: skip
    (f,) = evaluate([rule], lambda name: [], [("a.sh", "p", 4, "x=1")])
    assert (f.file, f.line, f.message) == ("a.sh", 4, "found x=1")


def test_packs_load_and_validate() -> None:
    risk = load_pack("risk")
    assert len(risk.rules) >= 15
    assert all(r.match or r.pattern for r in risk.rules)
    assert load_pack("postgresql").data["constructs"]
    assert load_pack("dependency").data["jakarta"]
    assert load_pack("eol").data["libraries"]
    assert load_pack("target-mapping").data["targets"]
    with pytest.raises(ValueError):
        Rule.model_validate({"id": "x", "severity": "huge", "category": "c", "message": "m"})


def test_team_pack_replaces_rule_by_id(tmp_path: Path) -> None:
    write(
        tmp_path / "risk-rules.yaml",
        """
rules:
  - id: system-exit
    severity: low
    category: team
    message: team version
    pattern: {regex: 'System\\.exit', languages: [java]}
  - id: team-only
    severity: info
    category: team
    message: added
    pattern: {regex: 'TODO'}
""",
    )
    rules = {r.id: r for r in load_pack("risk", tmp_path).rules}
    assert rules["system-exit"].category == "team"
    assert "team-only" in rules
    assert rules_key(text_rules(["risk"], tmp_path)) != rules_key(text_rules(["risk"], None))


# ---- patterns ----------------------------------------------------------------------------------
JAVA = """package a;
import sun.misc.BASE64Encoder;
public class X {
  private static final String URL = "jdbc:oracle:thin:scott/tiger@10.1.2.3:1521:ORCL";
  private String password = "s3cret!";
  String u = "http://pricing.acme.local:8080/svc";
  String ns = "http://java.sun.com/jsp/jstl/core";
  void f() throws Exception { System.exit(1); Runtime.getRuntime().exec("ls");
    javax.xml.parsers.DocumentBuilderFactory.newInstance(); new java.io.File("/data/in/x.dat"); }
}
"""


def test_java_patterns_and_masking() -> None:
    hits = run(PatternsAnalyzer(), "src/a/X.java", JAVA).hits
    rules = {h[0] for h in hits}
    assert {"hardcoded-endpoint", "hardcoded-credential", "system-exit", "platform-process-java",
            "jdk-internal-import", "xxe-parser", "filesystem-path"} <= rules  # fmt: skip
    text = " ".join(str(h[2]) for h in hits)
    assert "tiger" not in text and "s3cret" not in text
    assert "****" in text
    assert "java.sun.com" not in text  # XML namespaces are not endpoints


def test_shell_credentials_are_masked() -> None:
    sh = (
        "#!/bin/sh\nDB_PASSWORD=tiger123\nsqlplus -s scott/tiger@ORCL <<END\nEND\n"
        "ftp 192.168.1.20\n"
    )
    hits = run(PatternsAnalyzer(), "run.sh", sh).hits
    assert {"hardcoded-credential-script", "hardcoded-ip"} <= {h[0] for h in hits}
    assert not any("tiger" in str(h[2]) for h in hits)


def test_hardened_parser_is_not_flagged() -> None:
    java = JAVA.replace("newInstance();", "newInstance(); f.setFeature(\"http://apache.org/xml/"
                        "features/disallow-doctype-decl\", true);")  # fmt: skip
    assert "xxe-parser" not in {h[0] for h in run(PatternsAnalyzer(), "src/a/X.java", java).hits}


# ---- PostgreSQL grading ------------------------------------------------------------------------
RULES = constructs(load_pack("postgresql").data)


def _st(text: str, **kw) -> SqlStatement:  # type: ignore[no-untyped-def]
    base = {"id": "s", "text": text, "type": text.split()[0].upper(), "source": "jdbc",
            "dialect": "oracle", "parsed": True, "file": "a.java", "line": 1}  # fmt: skip
    return SqlStatement.model_validate({**base, **kw})


@pytest.mark.parametrize(
    ("text", "expected", "reason"),
    [
        ("SELECT id, name FROM orders WHERE id = ?", "auto", None),
        ("SELECT NVL(name, 'x') FROM orders", "review", "COALESCE"),
        ("SELECT id FROM orders WHERE ROWNUM < 10", "review", "ROWNUM"),
        ("SELECT a.id FROM a, b WHERE a.id = b.id(+)", "review", "outer join"),
        ("SELECT DBMS_RANDOM.VALUE FROM dual", "manual", "DBMS_"),
        ("SELECT seq_order.NEXTVAL FROM dual", "review", "nextval"),
    ],
)
def test_grades(text: str, expected: str, reason: str | None) -> None:
    g, why, pg_text = grade(_st(text), RULES)
    assert g == expected
    if reason:
        assert why and reason.lower() in why.lower()
    if g == "auto":
        assert pg_text and "orders" in pg_text


def test_nvl_is_converted_by_sqlglot() -> None:
    g, _, pg_text = grade(_st("SELECT NVL(name, 'x') FROM orders"), RULES)
    assert g == "review" and pg_text and "COALESCE" in pg_text.upper()


def test_dynamic_unparsed_spring_data_and_hql() -> None:
    assert grade(_st("SELECT 1", type="DYNAMIC"), RULES)[0] == PgGrade.MANUAL
    assert grade(_st("SELEC garbage", parsed=False), RULES)[0] == PgGrade.MANUAL
    assert grade(_st("findByName", source="spring-data", type="SELECT"), RULES)[0] == "auto"
    assert grade(_st("from Order o where o.id = :id", dialect="hql", type="SELECT"),
                 RULES)[0] == "auto"  # fmt: skip


def test_dynamic_sql_grades() -> None:
    known = _st("SELECT id FROM orders WHERE 1=1 AND name = ?", dynamic=True, fragments=3,
                optional_fragments=1)  # fmt: skip
    g, why, _ = grade(known, RULES)
    assert g == "review" and why and "3 fragment(s), 1 optional" in why
    hole = _st("SELECT id FROM orders ORDER BY ?", dynamic=True, structural_holes=1)
    g, why, _ = grade(hole, RULES)
    assert g == "manual" and why and "run time (1 place" in why
    # the worst variant decides the grade
    variant = _st("SELECT id FROM orders WHERE 1=1", dynamic=True,
                  variants=["SELECT id FROM orders WHERE ROWNUM < 5"])  # fmt: skip
    g, why, _ = grade(variant, RULES)
    assert g == "review" and why and "2 variants" in why


def test_db2_and_stored_objects() -> None:
    st = _st("SELECT A FROM T FETCH FIRST 5 ROWS ONLY WITH UR", dialect="db2")
    g, why, _ = grade(st, RULES)
    assert g == "review" and "isolation" in (why or "")
    proc = DbObject(id="o", name="PKG", object_type="package")
    table = DbObject(id="t", name="T", object_type="table")
    grade_all([st], [proc, table], load_pack("postgresql").data)
    assert proc.pg_grade == "manual" and table.pg_grade is None
    assert st.pg_grade == "review"


# ---- config switches without re-analysis ---------------------------------------------------------
def test_disable_and_reweight_rules_on_rerun(sample_copy) -> None:  # type: ignore[no-untyped-def]
    app = sample_copy("struts-oracle-app")
    first = run_scan(load_config(app / "audit.yaml"))
    rules = {f.rule_id for f in first.facts["findings"]}  # type: ignore[attr-defined]
    assert {"hardcoded-endpoint", "eol-library", "sql-concatenation"} <= rules
    cfg = app / "audit.yaml"
    cfg.write_text(
        cfg.read_text(encoding="utf-8")
        + "\nrules:\n  disabled: [hardcoded-endpoint]\n  severity: {eol-library: low}\n",
        encoding="utf-8",
    )
    second = run_scan(load_config(cfg))
    assert second.run.files_analyzed == 0
    found = second.facts["findings"]
    assert not [f for f in found if f.rule_id == "hardcoded-endpoint"]  # type: ignore[attr-defined]
    assert {f.severity for f in found if f.rule_id == "eol-library"} == {"low"}  # type: ignore[attr-defined]


def test_findings_never_contain_scanned_secrets(sample_copy) -> None:  # type: ignore[no-untyped-def]
    app = sample_copy("struts-oracle-app")
    result = run_scan(load_config(app / "audit.yaml"))
    out = "".join(
        p.read_text(encoding="utf-8", errors="replace")
        for p in result.run_dir.rglob("*")
        if p.is_file() and p.suffix in (".json", ".csv", ".log")
    )
    assert "Orders2009!" not in out  # db.password in conf/orders-app.properties
