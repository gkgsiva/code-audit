"""COBOL programs and copybooks, CICS, BMS maps and JCL."""

from __future__ import annotations

from legacy_audit.analyzers.cobol import CobolAnalyzer, source_lines
from legacy_audit.analyzers.jcl import JclAnalyzer
from tests.analyzers.helpers import entities, run

ORDINQ = """       IDENTIFICATION DIVISION.
       PROGRAM-ID. ORDINQ.
      * Order inquiry transaction
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT ORDFILE ASSIGN TO ORDIN.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-PGM                 PIC X(8) VALUE 'DATECONV'.
           COPY ORDREC.
           EXEC SQL INCLUDE CUSTDCL END-EXEC.
       PROCEDURE DIVISION.
           EXEC CICS RECEIVE MAP('ORDMAP1') MAPSET('ORDMS1') END-EXEC
           EXEC SQL
               SELECT CUST_NAME INTO :WS-NAME
                 FROM CUSTOMER
                WHERE CUST_ID = :WS-ID
           END-EXEC
           CALL WS-PGM USING WS-DATE
           CALL 'ORDVAL' USING WS-ORDER
           EXEC CICS LINK PROGRAM('ORDAUD') END-EXEC
           EXEC CICS SEND MAP('ORDMAP1') MAPSET('ORDMS1') END-EXEC
           EXEC CICS RETURN TRANSID('ORDI') END-EXEC.
"""


def test_cics_program_service_sql_calls_and_maps() -> None:
    facts = run(CobolAnalyzer(), "cobol/ORDINQ.cbl", ORDINQ)
    (svc,) = entities(facts, "services")
    assert (svc.name, svc.kind, svc.handler) == ("ORDI", "cics-transaction", "ORDINQ")
    (sql,) = entities(facts, "sql_statements")
    assert (sql.line, sql.type, sql.dialect, sql.tables) == (15, "SELECT", "db2", ["CUSTOMER"])
    (file_io,) = entities(facts, "integrations")
    assert (file_io.type, file_io.target) == ("FILE", "DD:ORDIN")
    refs = {(r.kind, r.target, r.data.get("dynamic")) for r in facts.refs}
    assert refs == {
        ("includes", "ORDREC", None),
        ("includes", "CUSTDCL", None),
        ("calls", "DATECONV", "true"),  # resolved from the VALUE of WS-PGM
        ("calls", "ORDVAL", "false"),
        ("calls", "ORDAUD", None),
        ("reads", "ORDMAP1", None),
        ("forwards", "ORDMAP1", None),
    }
    assert {t.name for t in entities(facts, "tech_stack")} == {"COBOL", "DB2", "CICS"}


def test_batch_program_and_copybook() -> None:
    prog = (
        "       IDENTIFICATION DIVISION.\n       PROGRAM-ID. ORDBATCH.\n"
        "       PROCEDURE DIVISION.\n       MAIN-PARA.\n           GOBACK.\n"
    )
    facts = run(CobolAnalyzer(), "cobol/ORDBATCH.cbl", prog)
    (svc,) = entities(facts, "services")
    assert (svc.name, svc.kind) == ("ORDBATCH", "cobol-program")
    copybook = run(
        CobolAnalyzer(), "copy/ORDREC.cpy", "       01  ORD-REC.\n           05 ORD-ID PIC 9(8).\n"
    )
    assert not entities(copybook, "services")
    assert [(s.kind, s.name) for s in copybook.symbols] == [("cobol-copybook", "ORDREC")]


def test_source_lines_fixed_and_free_format() -> None:
    fixed = (
        "000100 IDENTIFICATION DIVISION.                                         ABC00001\n"
        "000200* comment\n"
    )
    assert [ln.strip() for ln in source_lines(fixed)] == ["IDENTIFICATION DIVISION.", ""]
    free = ">>SOURCE FORMAT FREE\nIDENTIFICATION DIVISION. *> trailing\n* old\n"
    assert source_lines(free)[1:] == ["IDENTIFICATION DIVISION. ", ""]


BMS = """ORDMS1   DFHMSD TYPE=&SYSPARM,MODE=INOUT,LANG=COBOL,                       X
               TIOAPFX=YES
ORDMAP1  DFHMDI SIZE=(24,80),LINE=1,COLUMN=1
         DFHMDF POS=(1,1),LENGTH=20,INITIAL='ORDER INQUIRY'
ORDNO    DFHMDF POS=(3,1),LENGTH=10,ATTRB=(UNPROT,IC)
CUSTNM   DFHMDF POS=(4,1),LENGTH=30,ATTRB=PROT
ORDMAP2  DFHMDI SIZE=(24,80)
STATUS   DFHMDF POS=(1,1),LENGTH=10
         DFHMSD TYPE=FINAL
         END
"""


def test_bms_maps_are_screens_with_named_fields() -> None:
    facts = run(CobolAnalyzer(), "bms/ORDMS1.bms", BMS)
    screens = [(s.name, s.technology, s.fields_count) for s in entities(facts, "screens")]
    assert screens == [("ORDMAP1", "BMS", 2), ("ORDMAP2", "BMS", 1)]
    assert {(s.kind, s.name, s.data["mapset"]) for s in facts.symbols} == {
        ("bms-map", "ORDMAP1", "ORDMS1"),
        ("bms-map", "ORDMAP2", "ORDMS1"),
    }


JCL = """//ORDNIGHT JOB (ACCT),'NIGHTLY',CLASS=A
//* nightly order batch
//STEP01   EXEC PGM=ORDBATCH
//ORDIN    DD DSN=PROD.ORDERS.DAILY,DISP=SHR
//STEP02   EXEC PGM=IEBGENER
//SYSUT1   DD DSN=PROD.ORDERS.DAILY,DISP=SHR
//STEP03   EXEC ORDRPTP
//STEP04   EXEC PGM=IKJEFT01
//SYSTSIN  DD *
  DSN SYSTEM(DB2P)
  RUN PROGRAM(ORDARCH) PLAN(ORDPLAN)
/*
"""


def test_jcl_job_steps_programs_and_datasets() -> None:
    facts = run(JclAnalyzer(), "jcl/ORDNIGHT.jcl", JCL)
    (job,) = entities(facts, "batch_jobs")
    assert (job.name, job.scheduler) == ("ORDNIGHT", "jcl")
    assert job.steps == ["STEP01", "STEP02", "STEP03", "STEP04"]
    assert job.programs == ["ORDBATCH", "IEBGENER", "IKJEFT01", "ORDARCH"]
    assert job.datasets == ["PROD.ORDERS.DAILY"]
    # Utilities are listed as programs but not linked; the DB2 batch program is.
    runs = {(r.target, tuple(r.target_kinds)) for r in facts.refs}
    assert runs == {
        ("ORDBATCH", ("cobol-program", "c-executable", "file-name")),
        ("ORDRPTP", ("jcl-proc",)),
        ("ORDARCH", ("cobol-program", "c-executable")),
    }


def test_jcl_procedure_member() -> None:
    proc = "//ORDRPTP  PROC\n//RPT      EXEC PGM=ORDRPT\n//         PEND\n"
    facts = run(JclAnalyzer(), "proclib/ORDRPTP.jcl", proc)
    assert not entities(facts, "batch_jobs")
    assert ("jcl-proc", "ORDRPTP") in {(s.kind, s.name) for s in facts.symbols}
    assert [r.target for r in facts.refs] == ["ORDRPT"]
