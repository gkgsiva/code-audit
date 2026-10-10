"""Phase 3 dependency security: versions, EOL, upgrades, report import, SBOM, missing tools."""

from __future__ import annotations

import json
import os
import stat
import sys
from pathlib import Path

import pytest
from jsonschema import Draft7Validator
from referencing import Registry, Resource

from legacy_audit.config import DependencyCheckConfig, OsvScannerConfig, load_config
from legacy_audit.model import Library, TechItem, Vulnerability
from legacy_audit.rules.packs import load_pack
from legacy_audit.scan import run_scan
from legacy_audit.security import vulns
from legacy_audit.security.eol import apply_eol
from legacy_audit.security.jdk import parse_jdeprscan, parse_jdeps
from legacy_audit.security.sbom import build_sbom
from legacy_audit.security.upgrades import recommend
from legacy_audit.security.versions import compare, in_range, sort_versions
from tests.conftest import REPO, SAMPLES

DEP = load_pack("dependency").data
EOL = load_pack("eol").data
DC_REPORT = SAMPLES / "struts-oracle-app/audit-inputs/security/dependency-check-report.json"
OSV_REPORT = SAMPLES / "spring-mvc-batch-app/audit-inputs/security/osv-scanner.json"


def lib(artifact: str, version: str | None = None, group: str | None = None, **kw) -> Library:  # type: ignore[no-untyped-def]
    source = kw.pop("source", "maven")
    return Library(id=f"library:{group}:{artifact}:{version}", artifact=artifact,
                   version=version, group=group, source=source, **kw)  # fmt: skip


# ---- versions ----------------------------------------------------------------------------------
def test_version_order() -> None:
    assert sort_versions(["2.0", "1.2.17", "2.0-rc1", "1.2.9", "2.0.1", "2.0-beta1", "10.0"]) == [
        "1.2.9", "1.2.17", "2.0-beta1", "2.0-rc1", "2.0", "2.0.1", "10.0",
    ]  # fmt: skip
    assert compare("2.0", "2.0.0.RELEASE") == 0
    assert compare("4.3.11.Final", "5.4.24.Final") < 0
    assert compare("2.0-sp1", "2.0") > 0


def test_ranges() -> None:
    assert in_range("1.2.17", "<2") is True
    assert in_range("3.2", ">=3.0,<3.5") is True
    assert in_range("3.5", ">=3.0,<3.5") is False
    assert in_range(None, "<2") is None
    assert in_range(None, "*") is True


# ---- end of life ---------------------------------------------------------------------------------
def test_eol_libraries_and_tech() -> None:
    libs = [
        lib("struts", "1.2.9"),  # jar without a group still matches
        lib("log4j-core", "2.24.0", "org.apache.logging.log4j"),
        lib("spring-webmvc", "4.3.30.RELEASE", "org.springframework"),
        lib("spring-batch-core", "3.0.10.RELEASE", "org.springframework.batch"),
        lib("spring-webmvc", "6.2.0", "org.springframework"),
        lib("axis", None),
        lib("commons-lang", None),
    ]
    tech = [TechItem(id="t1", name="Java", version="1.8", category="language"),
            TechItem(id="t2", name="Java", version="21", category="language"),
            TechItem(id="t3", name="Struts 1", category="framework")]  # fmt: skip
    apply_eol(libs, tech, EOL)
    assert [x.eol for x in libs] == [True, False, True, True, False, True, None]
    assert libs[0].eol_date == "2013-04-05" and "Spring MVC" in (libs[0].replacement or "")
    assert "Batch" in (libs[3].replacement or "")  # the specific entry wins over Spring Framework
    assert [t.eol for t in tech] == [True, False, True]


# ---- recommendations ---------------------------------------------------------------------------
def test_recommendation_order() -> None:
    eol_jar = lib("log4j", "1.2.17", "log4j")
    apply_eol([eol_jar], [], EOL)
    r = recommend(eol_jar, DEP)
    assert (r.action, r.rule) == ("replace", "eol")
    assert r.recipe and "Log4j1ToLog4j2" in r.recipe

    spring = lib("spring-jdbc", "4.3.30.RELEASE", "org.springframework")
    apply_eol([spring], [], EOL)
    assert recommend(spring, DEP).action == "use-bom"

    servlet = lib("servlet-api", "2.5", "javax.servlet")
    r = recommend(servlet, DEP)
    assert r.action == "migrate-jakarta" and "jakarta.servlet-api" in (r.target or "")
    assert r.recipe and "Jakarta" in r.recipe

    assert recommend(lib("ojdbc8", "19.3", "com.oracle.database.jdbc"), DEP).target == (
        "org.postgresql:postgresql"
    )
    assert recommend(lib("jackson-databind", "2.9.0", "com.fasterxml.jackson.core"),
                     DEP).action == "use-bom"  # fmt: skip
    assert recommend(lib("slf4j-api", "1.7", None), DEP).action == "keep"  # group unknown

    angular = lib("angular", "1.5.8", language="javascript", source="js-file")
    apply_eol([angular], [], EOL)
    assert recommend(angular, DEP).action == "rewrite"
    assert recommend(lib("jquery", "3.7.1", language="javascript", source="npm"),
                     DEP).action == "remove"  # fmt: skip


def test_vulnerable_library_upgrades_to_lowest_fix() -> None:
    mybatis = lib("mybatis", "3.4.6", "org.mybatis", vulnerabilities=[
        Vulnerability(id="CVE-1", severity="high", fixed_versions=["3.5.6"]),
        Vulnerability(id="CVE-2", severity="medium", fixed_versions=["3.5.0", "3.5.9"]),
    ])  # fmt: skip
    r = recommend(mybatis, DEP)
    assert (r.action, r.target, r.verify) == ("upgrade", "org.mybatis:mybatis:3.5.6", False)
    unknown = lib("x", "1.0", "g", vulnerabilities=[Vulnerability(id="CVE-3", severity="low")])
    r = recommend(unknown, DEP)
    assert r.action == "upgrade" and r.target is None and r.verify


# ---- report import -------------------------------------------------------------------------------
def test_dependency_check_report() -> None:
    report = vulns.load_report(DC_REPORT, "dc.json")
    assert report and report.tool == "dependency-check"
    assert report.database_date == "2026-09-30"
    log4j = next(e for e in report.entries if e.file_name == "log4j-1.2.17.jar")
    assert [v.id for v in log4j.vulnerabilities][:1] == ["CVE-2019-17571"]
    cc = next(e for e in report.entries if "commons-collections" in (e.file_name or ""))
    assert cc.vulnerabilities[0].fixed_versions == ["3.2.2"]
    struts = next(e for e in report.entries if e.file_name == "struts-1.2.9.jar")
    v2 = next(v for v in struts.vulnerabilities if v.id == "CVE-2014-0114")
    assert (v2.cvss, v2.severity) == (7.5, "high")  # CVSS v2 only
    assert all(e.file_name != "ojdbc14.jar" for e in report.entries)  # no vulnerabilities


def test_osv_report() -> None:
    report = vulns.load_report(OSV_REPORT, "osv.json")
    assert report and report.tool == "osv-scanner"
    hib = next(e for e in report.entries if "hibernate-core" in e.purls[0])
    (v,) = hib.vulnerabilities
    assert v.aliases == ["CVE-2020-25638"] and v.cvss == 7.4 and v.severity == "high"
    assert v.fixed_versions == ["5.3.20.Final", "5.4.24.Final"]


def test_unknown_json_is_ignored(tmp_path: Path) -> None:
    p = tmp_path / "x.json"
    p.write_text('{"hello": 1}')
    assert vulns.load_report(p, "x.json") is None
    p.write_text("not json")
    assert vulns.load_report(p, "x.json") is None


def test_matching_by_sha1_purl_and_file_name_and_merging() -> None:
    by_sha1 = lib("axis", "1.4", sha1="5B4FF5E75BD75ED8F8E2BECAB121B4530E450B41")
    by_file = lib("struts", "1.2.9")
    by_purl = lib("log4j", "1.2.17", "log4j")
    dc = vulns.load_report(DC_REPORT, "dc.json")
    osv_entry = vulns.ReportEntry(sha1=None, purls=["pkg:maven/log4j/log4j@1.2.17"],
                                  file_name=None, vulnerabilities=[
        Vulnerability(id="GHSA-2qrg-x229-3v8q", aliases=["CVE-2019-17571"], severity="critical",
                      sources=["osv-scanner"], fixed_versions=["2.0"]),
    ])  # fmt: skip
    osv = vulns.Report(tool="osv-scanner", path="osv.json", entries=[osv_entry])
    assert dc
    unmatched = vulns.apply_reports([by_sha1, by_file, by_purl], [dc, osv])
    assert unmatched == 1  # commons-collections is not in this inventory
    assert by_sha1.cves == ["CVE-2018-8032", "CVE-2019-0227"]
    assert by_file.max_severity == "high"
    merged = next(v for v in by_purl.vulnerabilities if v.id == "CVE-2019-17571")
    assert merged.sources == ["dependency-check", "osv-scanner"]
    assert "GHSA-2qrg-x229-3v8q" in merged.aliases and "2.0" in merged.fixed_versions
    assert len(by_purl.vulnerabilities) == 3 and by_purl.max_severity == "critical"


# ---- SBOM ----------------------------------------------------------------------------------------
def _validator() -> Draft7Validator:
    folder = REPO / "schemas" / "cyclonedx"
    resources = []
    for name in ("bom-1.5.schema.json", "spdx.schema.json", "jsf-0.82.schema.json"):
        schema = json.loads((folder / name).read_text(encoding="utf-8"))
        resources.append((schema["$id"], Resource.from_contents(schema)))
    registry: Registry = Registry().with_resources(resources)
    bom = json.loads((folder / "bom-1.5.schema.json").read_text(encoding="utf-8"))
    return Draft7Validator(bom, registry=registry)


@pytest.mark.parametrize("sample", ["struts-oracle-app", "spring-mvc-batch-app"])
def test_sbom_validates_against_cyclonedx_schema(sample: str, sample_copy, monkeypatch) -> None:  # type: ignore[no-untyped-def]
    monkeypatch.setattr(vulns, "find_tool", lambda configured, names: None)
    result = run_scan(load_config(sample_copy(sample) / "audit.yaml"))
    sbom = json.loads((result.run_dir / "data" / "sbom.cdx.json").read_text(encoding="utf-8"))
    errors = sorted(_validator().iter_errors(sbom), key=str)
    assert not errors, errors[0].message
    assert sbom["specVersion"] == "1.5"
    assert len(sbom["components"]) == len(result.facts["libraries"])
    assert sbom["vulnerabilities"]
    refs = {c["bom-ref"] for c in sbom["components"]}
    assert all(a["ref"] in refs for v in sbom["vulnerabilities"] for a in v["affects"])


def test_sbom_scopes_and_hashes() -> None:
    libs = [lib("junit", "4.12", "junit", scope="test"),
            lib("servlet-api", "2.5", "javax.servlet", scope="provided", sha1="ab" * 20),
            lib("x", None, sha1="not-a-sha")]  # fmt: skip
    sbom = build_sbom("app", "run1", "2026-10-10T00:00:00+00:00", libs)
    comps = sbom["components"]
    assert [c["scope"] for c in comps] == ["excluded", "optional", "required"]
    assert comps[1]["hashes"] == [{"alg": "SHA-1", "content": "ab" * 20}]
    assert "hashes" not in comps[2] and "purl" not in comps[2]
    assert comps[0]["purl"] == "pkg:maven/junit/junit@4.12"
    assert not list(_validator().iter_errors(sbom))


# ---- tools: missing, failing, running ------------------------------------------------------------
def test_missing_dependency_check_is_a_coverage_gap(sample_copy, monkeypatch) -> None:  # type: ignore[no-untyped-def]
    app = sample_copy("spring-mvc-batch-app")
    monkeypatch.setenv("PATH", str(app / "no-tools-here"))  # nothing on PATH
    result = run_scan(load_config(app / "audit.yaml"))
    cov = {c.kind: c for c in result.run.coverage}
    assert cov["dependency-check"].status == "missing"
    assert "not installed" in cov["dependency-check"].label
    assert cov["osv-scanner"].status == "present"  # the imported report still counts
    sources = {s.name: s.status for s in result.run.vulnerability_sources}
    assert sources == {"dependency-check": "missing", "osv-scanner": "imported"}
    run_json = json.loads((result.run_dir / "run.json").read_text())
    assert run_json["vulnerability_sources"]


def test_configured_path_that_does_not_exist() -> None:
    cfg = DependencyCheckConfig(path=str(Path("missing") / "dependency-check.sh"))
    report, source = vulns.run_dependency_check(cfg, [], Path("unused"), "app")
    assert report is None and source.status == "missing"


@pytest.mark.skipif(sys.platform == "win32", reason="uses a POSIX script as the fake tool")
def test_running_tools(tmp_path: Path) -> None:
    fake = tmp_path / "dependency-check.sh"
    fake.write_text(
        "#!/bin/sh\n"
        'while [ $# -gt 0 ]; do [ "$1" = "--out" ] && out="$2"; shift; done\n'
        f'cp "{DC_REPORT}" "$out/dependency-check-report.json"\n'
    )
    fake.chmod(fake.stat().st_mode | stat.S_IEXEC)
    report, source = vulns.run_dependency_check(
        DependencyCheckConfig(path=str(fake)), [tmp_path], tmp_path / "out", "app"
    )
    assert report and source.status == "ran" and source.database_date == "2026-09-30"

    broken = tmp_path / "osv-scanner"
    broken.write_text("#!/bin/sh\necho 'no database' >&2\nexit 127\n")
    broken.chmod(broken.stat().st_mode | stat.S_IEXEC)
    report, source = vulns.run_osv_scanner(
        OsvScannerConfig(path=str(broken)), [tmp_path], tmp_path / "out2"
    )
    assert report is None and source.status == "failed"
    assert "no database" in (source.detail or "")
    assert os.access(fake, os.X_OK)


# ---- jdeps / jdeprscan parsing -------------------------------------------------------------------
def test_jdk_tool_output_parsing() -> None:
    line = "   org.apache.axis.{}   -> sun.misc.BASE64Encoder   JDK internal API (JDK removed)"
    jdeps = "axis-1.4.jar -> JDK removed internal API\naxis-1.4.jar -> java.base\n" + "\n".join(
        line.format(c) for c in ("Foo", "Bar")
    )
    items = parse_jdeps(jdeps, "lib/axis-1.4.jar")
    assert [(i["tool"], i["api"], i["name"]) for i in items] == [
        ("jdeps", "sun.misc.BASE64Encoder", "axis-1.4.jar")
    ]
    out = (
        "Jar file lib/x.jar:\n"
        "class a/B uses deprecated method java/lang/Thread::stop()V (forRemoval=true)\n"
    )
    (item,) = parse_jdeprscan(out, "lib/x.jar")
    assert item["class"] == "a.B" and item["api"].startswith("java/lang/Thread::stop")
