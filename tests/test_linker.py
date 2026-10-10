"""Linker: by-name references become edges, facts from many files merge, counting rules apply."""

from __future__ import annotations

from pathlib import Path

from legacy_audit.analyzers import AnalyzerContext, Facts, registered
from legacy_audit.linker import LinkResult, link
from legacy_audit.linker.aggregate import merge_integrations, merge_libraries, merge_tech
from legacy_audit.model import CodeEdge, Evidence, Integration, Library, Service, TechItem, make_id
from tests.analyzers.helpers import config, record


def estate(files: dict[str, str], copybook_paths: tuple[str, ...] = ()) -> LinkResult:
    records = [record(path, text=text) for path, text in files.items()]
    facts: list[Facts] = []
    for rec in records:
        ctx = AnalyzerContext(config=config(), abs_path=Path(rec.path), text=files[rec.path])
        facts.extend(a.analyze(rec, ctx) for a in registered() if a.supports(rec))
    return link(records, facts, copybook_paths)


def _edges(result: LinkResult) -> set[tuple[str, str, str, bool]]:
    names: dict[str, str] = {}
    for entity in ("services", "batch_jobs", "screens"):
        for f in result.entities.get(entity, []):
            names[f.id] = f.name  # type: ignore[attr-defined]

    def label(x: str) -> str:
        return names.get(x, x.removeprefix("file:"))

    return {
        (e.kind, label(e.source), label(e.target), e.resolved)
        for e in result.entities["dependencies"]
        if isinstance(e, CodeEdge)
    }


SETTLE_C = """#include <cmqc.h>
int main(void)
{
    MQOD od;
    strncpy(od.ObjectName, "SETTLE.OUT", 48);
    MQPUT(0, &od, 0, 0, 0, 0, 0, 0);
    return 0;
}
"""
READER_C = SETTLE_C.replace("MQPUT(0, &od, 0, 0, 0, 0, 0, 0)", "MQGET(0, &od, 0, 0, 0, 0, 0, 0, 0)")
RUN_KSH = """#!/bin/ksh
/opt/settle/bin/settle_send
./purge_logs.sh
CFTUTIL SEND PART=BANK1, IDF=SETTLE, FNAME=/data/out/settle.dat
sqlplus -s / <<EOF
UPDATE settle_ctl SET status = 'S';
EOF
"""
JIL = """insert_job: STL_RUN  job_type: CMD
command: /opt/settle/scripts/run_settle.ksh
machine: srv1
"""
CFT = """CFTPART ID=BANK1, NSPART=BANKONE
CFTTCP ID=BANK1, HOST=cft.bank1.example
CFTSEND ID=SETTLE, FNAME=/data/out/settle.dat
CFTSEND ID=UNUSED, FNAME=/data/out/unused.dat
"""
MQSC = "DEFINE QLOCAL('SETTLE.OUT')\nDEFINE QLOCAL('SETTLE.SPARE')\n"


def test_jobs_scripts_executables_queues_and_transfers_link_up() -> None:
    result = estate(
        {
            "settle/src/settle_send.c": SETTLE_C,
            "settle/src/settle_read.c": READER_C,
            "settle/scripts/run_settle.ksh": RUN_KSH,
            "audit-inputs/autosys/settle.jil": JIL,
            "audit-inputs/cft/settle.cfg": CFT,
            "audit-inputs/mq/qm.mqsc": MQSC,
        }
    )
    edges = _edges(result)
    assert ("invokes", "STL_RUN", "settle/scripts/run_settle.ksh", True) in edges
    assert ("invokes", "settle/scripts/run_settle.ksh", "settle_send", True) in edges
    # A script that is not in the estate is reported once, as an unresolved edge and a finding.
    assert ("invokes", "settle/scripts/run_settle.ksh", "unresolved:purge_logs.sh", False) in edges
    assert result.unresolved == 1
    (finding,) = [f for f in result.entities["findings"] if f.rule_id == "unresolved-reference"]  # type: ignore[attr-defined]
    assert finding.severity == "info" and "purge_logs.sh" in finding.message

    services = {s.name: s for s in result.entities["services"] if isinstance(s, Service)}
    assert {n: s.kind for n, s in services.items()} == {
        "STL_RUN": "batch-job",
        "settle_read": "c-executable",
        "settle_send": "c-executable",
    }
    # The job reaches the table its script updates.
    assert services["STL_RUN"].tables == ["SETTLE_CTL"]

    ints = {
        (i.type, i.target): i for i in result.entities["integrations"] if isinstance(i, Integration)
    }
    # Producer and consumer of one queue make it two-way; a defined, unused queue stays "defined".
    assert ints["MQ", "SETTLE.OUT"].direction == "both"
    assert ints["MQ", "SETTLE.OUT"].usages == 2
    spare = ints["MQ", "SETTLE.SPARE"]
    assert (spare.direction, spare.usages) == ("defined", 0)
    # The exported CFT flow folds into the script's use of the same IDF; partner details join it.
    settle = ints["CFT", "BANK1/SETTLE"]
    assert settle.details["defined_by_export"] == "true"
    assert (settle.details["host"], settle.details["nspart"]) == ("cft.bank1.example", "BANKONE")
    assert ("CFT", "*/SETTLE") not in ints
    assert ints["CFT", "*/UNUSED"].usages == 0


ORDINQ = """       IDENTIFICATION DIVISION.
       PROGRAM-ID. ORDINQ.
       PROCEDURE DIVISION.
           CALL 'ORDVAL' USING WS-ORDER
           EXEC CICS SEND MAP('ORDMAP1') MAPSET('ORDMS1') END-EXEC
           EXEC CICS RETURN TRANSID('ORDI') END-EXEC.
"""
ORDVAL = """       IDENTIFICATION DIVISION.
       PROGRAM-ID. ORDVAL.
       PROCEDURE DIVISION.
           GOBACK.
"""
ORDBATCH = ORDVAL.replace("ORDVAL", "ORDBATCH")
BMS = """ORDMS1   DFHMSD TYPE=&SYSPARM,MODE=INOUT,LANG=COBOL
ORDMAP1  DFHMDI SIZE=(24,80)
ORDNO    DFHMDF POS=(3,1),LENGTH=10
         DFHMSD TYPE=FINAL
"""
JCL = "//ORDNIGHT JOB (ACCT),'NIGHTLY'\n//STEP01   EXEC PGM=ORDBATCH\n"


def test_called_cobol_subprograms_are_not_services() -> None:
    result = estate(
        {
            "host/cobol/ORDINQ.cbl": ORDINQ,
            "host/cobol/ORDVAL.cbl": ORDVAL,
            "host/cobol/ORDBATCH.cbl": ORDBATCH,
            "host/bms/ORDMS1.bms": BMS,
            "host/jcl/ORDNIGHT.jcl": JCL,
        }
    )
    services = sorted(f"{s.kind}: {s.name}" for s in result.entities["services"])  # type: ignore[attr-defined]
    # ORDVAL is only CALLed, so it is part of ORDI; ORDBATCH runs from JCL, so it is a service.
    assert services == ["batch-job: ORDNIGHT", "cics-transaction: ORDI", "cobol-program: ORDBATCH"]
    edges = _edges(result)
    assert ("forwards", "host/cobol/ORDINQ.cbl", "ORDMAP1", True) in edges
    assert ("runs", "ORDNIGHT", "ORDBATCH", True) in edges
    # The call to the pruned sub-program points at its file, not at a service that is gone.
    assert ("calls", "host/cobol/ORDINQ.cbl", "host/cobol/ORDVAL.cbl", True) in edges
    assert result.unresolved == 0


def test_copybook_paths_are_searched_first() -> None:
    copy = "WORKING-STORAGE SECTION.\n           COPY ORDREC."
    prog = ORDVAL.replace("PROCEDURE DIVISION.", copy)
    files = {
        "host/cobol/ORDVAL.cbl": prog,
        "host/copy/ORDREC.cpy": "       01  ORD-REC PIC X.\n",
        "host/zlib/ORDREC.cpy": "       01  ORD-REC PIC X(2).\n",
    }
    default = _edges(estate(files))
    assert ("includes", "host/cobol/ORDVAL.cbl", "host/copy/ORDREC.cpy", True) in default
    configured = _edges(estate(files, copybook_paths=("zlib",)))
    assert ("includes", "host/cobol/ORDVAL.cbl", "host/zlib/ORDREC.cpy", True) in configured


def _ev(file: str) -> list[Evidence]:
    return [Evidence(file=file, line=1)]


def test_merge_integrations_definitions_are_not_usages() -> None:
    qid = make_id("integration", "MQ", "Q1")
    defined = Integration(
        id=qid, type="MQ", target="Q1", direction="defined", usages=1,
        source_files=["a.mqsc"], evidence=_ev("a.mqsc"),
    )  # fmt: skip
    used = Integration(
        id=qid, type="MQ", target="Q1", direction="unknown", usages=1,
        source_files=["b.c"], consumers=["b.c"], evidence=_ev("b.c"),
    )  # fmt: skip
    (merged,) = merge_integrations([defined, used])
    assert (merged.direction, merged.usages) == ("inbound", 1)
    assert merged.source_files == ["a.mqsc", "b.c"]


def test_merge_libraries_folds_file_named_jars_into_declared_ones() -> None:
    declared = Library(
        id="library:a", language="java", group="commons-io", artifact="commons-io",
        version="2.4", declared=True, source="maven", evidence=_ev("pom.xml"),
    )  # fmt: skip
    jar = Library(
        id="library:b", language="java", artifact="commons-io", version="2.4", declared=False,
        source="jar", where_used=["WEB-INF/lib"], evidence=_ev("WEB-INF/lib/commons-io-2.4.jar"),
    )  # fmt: skip
    other = Library(
        id="library:c", language="java", artifact="commons-io", version="1.3", declared=False,
        source="jar", evidence=_ev("lib/commons-io-1.3.jar"),
    )  # fmt: skip
    merged = merge_libraries([declared, jar, other])
    assert [(lib.artifact, lib.version, lib.declared) for lib in merged] == [
        ("commons-io", "2.4", True),
        ("commons-io", "1.3", False),
    ]
    assert merged[0].where_used == ["WEB-INF/lib"]


def test_merge_tech_folds_versionless_into_the_only_version() -> None:
    def t(name: str, version: str | None) -> TechItem:
        return TechItem(
            id=make_id("tech", name, version or ""), name=name, version=version,
            category="framework", evidence=_ev("x"),
        )  # fmt: skip

    merged = merge_tech(
        [
            t("Struts 1", None),
            t("Struts 1", "1.3.10"),
            t("Spring Framework", None),
            t("Spring Framework", "3.2"),
            t("Spring Framework", "4.3"),
        ]
    )
    assert sorted((m.name, m.version or "") for m in merged) == [
        ("Spring Framework", ""),  # two versions: no way to choose
        ("Spring Framework", "3.2"),
        ("Spring Framework", "4.3"),
        ("Struts 1", "1.3.10"),
    ]


PARENT_POM = """<project>
  <groupId>com.acme</groupId><artifactId>parent</artifactId><version>1</version>
  <packaging>pom</packaging>
  <properties><spring.version>3.2.18.RELEASE</spring.version></properties>
  <dependencyManagement><dependencies>
    <dependency><groupId>log4j</groupId><artifactId>log4j</artifactId><version>1.2.17</version></dependency>
  </dependencies></dependencyManagement>
  <modules><module>web</module></modules>
</project>
"""
CHILD_POM = """<project>
  <parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1</version></parent>
  <artifactId>web</artifactId>
  <dependencies>
    <dependency><groupId>org.springframework</groupId><artifactId>spring-webmvc</artifactId>
      <version>${spring.version}</version></dependency>
    <dependency><groupId>log4j</groupId><artifactId>log4j</artifactId></dependency>
    <dependency><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1</version></dependency>
  </dependencies>
</project>
"""


def test_maven_versions_resolve_through_the_parent_chain() -> None:
    result = estate({"pom.xml": PARENT_POM, "web/pom.xml": CHILD_POM})
    libs = {(lib.artifact, lib.version) for lib in result.entities["libraries"]}  # type: ignore[attr-defined]
    # Internal modules are not third-party libraries.
    assert libs == {("spring-webmvc", "3.2.18.RELEASE"), ("log4j", "1.2.17")}
    tech = {(t.name, t.version) for t in result.entities["tech_stack"]}  # type: ignore[attr-defined]
    assert {("Spring Framework", "3.2.18.RELEASE"), ("Log4j 1", "1.2.17")} <= tech
