"""C/C++ (functions, main, MQI, files, system calls) and Pro*C (embedded SQL then C)."""

from __future__ import annotations

from legacy_audit.analyzers.c_cpp import CAnalyzer
from legacy_audit.analyzers.cobol import CobolAnalyzer
from legacy_audit.analyzers.embedded import extract
from legacy_audit.analyzers.proc import ProcAnalyzer
from tests.analyzers.helpers import entities, run

C = """#include <stdio.h>
#include <cmqc.h>
#include "util.h"

static void put_msg(MQHCONN h, char *msg)
{
    MQOD od = {MQOD_DEFAULT};
    MQLONG cc, rc;
    strncpy(od.ObjectName, "SETTLE.OUT", MQ_Q_NAME_LENGTH);
    MQOPEN(h, &od, MQOO_OUTPUT, &h, &cc, &rc);
    MQPUT(h, &od, NULL, NULL, 10, msg, &cc, &rc);
}

int main(int argc, char **argv)
{
    MQHCONN h;
    MQLONG cc, rc;
    FILE *f = fopen("/data/out/settle.dat", "w");
    MQCONN("QMSETTLE", &h, &cc, &rc);
    put_msg(h, "x");
    log_line("sent");
    system("CFTUTIL SEND PART=BANK1, IDF=SETTLE");
    system("/opt/app/scripts/archive.sh");
    return 0;
}
"""


def test_c_executable_and_integrations() -> None:
    facts = run(CAnalyzer(), "src/send.c", C)
    (svc,) = entities(facts, "services")
    assert (svc.name, svc.kind, svc.handler) == ("send", "c-executable", "src/send.c#main")
    (entry,) = entities(facts, "entry_points")
    assert (entry.kind, entry.line) == ("main", 14)
    ints = {(i.type, i.target): i for i in entities(facts, "integrations")}
    assert set(ints) == {
        ("FILE", "/data/out/settle.dat"),
        ("MQ-QMGR", "QMSETTLE"),
        ("MQ", "SETTLE.OUT"),
        ("CFT", "BANK1/SETTLE"),
    }
    assert ints["MQ", "SETTLE.OUT"].producers == ["src/send.c"]
    assert ints["FILE", "/data/out/settle.dat"].direction == "outbound"
    reported = {(r.kind, r.target) for r in facts.refs if r.report_unresolved}
    # System headers are not dependencies; the local header and the script are.
    assert reported == {("includes", "util.h"), ("invokes", "archive.sh")}
    assert {s.name for s in facts.symbols if s.kind == "c-function"} == {"put_msg", "main"}


def test_c_without_main_is_not_a_service() -> None:
    facts = run(CAnalyzer(), "src/util.c", "int add(int a, int b) { return a + b; }\n")
    assert not entities(facts, "services")
    assert [s.name for s in facts.symbols] == ["add"]


PROC = """#include <stdio.h>
EXEC SQL INCLUDE SQLCA;
int load(char *key)
{
    EXEC SQL BEGIN DECLARE SECTION;
    char v_name[30];
    char stmt[200];
    EXEC SQL END DECLARE SECTION;
    EXEC SQL DECLARE c1 CURSOR FOR SELECT name FROM settle_in WHERE batch = :key;
    EXEC SQL OPEN c1;
    EXEC SQL FETCH c1 INTO :v_name;
    EXEC SQL INSERT INTO settle_out (name) VALUES (:v_name);
    EXEC SQL PREPARE s1 FROM :stmt;
    EXEC SQL EXECUTE
      BEGIN
        settle_pkg.close_batch(:key);
      END;
    END-EXEC;
    EXEC SQL COMMIT;
    return 0;
}
int main(void) { return load("A"); }
"""


def test_proc_embedded_sql_and_c_structure() -> None:
    facts = run(ProcAnalyzer(), "src/load.pc", PROC)
    stmts = [
        (s.line, s.type, s.tables, s.procedures, s.dynamic)
        for s in entities(facts, "sql_statements")
    ]
    assert stmts == [
        (9, "SELECT", ["SETTLE_IN"], [], False),  # cursor declaration counts as its SELECT
        (12, "INSERT", ["SETTLE_OUT"], [], False),
        (13, "DYNAMIC", [], [], True),
        (16, "CALL", [], ["SETTLE_PKG.CLOSE_BATCH"], False),
    ]
    assert all(s.source == "embedded" for s in entities(facts, "sql_statements"))
    assert [s.name for s in entities(facts, "services")] == ["load"]
    assert not facts.coverage.unparsed  # the C parser never sees EXEC SQL


def test_embedded_cobol_sql_ignores_sequence_and_identification_columns() -> None:
    text = (
        "000100 IDENTIFICATION DIVISION.                                         ORDR0001\n"
        "000200 PROGRAM-ID. ORDQRY.                                              ORDR0002\n"
        "000300 PROCEDURE DIVISION.                                              ORDR0003\n"
        "000400     EXEC SQL                                                     ORDR0004\n"
        "000500*        old version of the query                                 ORDR0005\n"
        "000600         SELECT NAME INTO :WS-NAME FROM CUSTOMER                  ORDR0006\n"
        "000700     END-EXEC.                                                    ORDR0007\n"
    )
    facts = run(CobolAnalyzer(), "cobol/ORDQRY.cbl", text)
    (stmt,) = entities(facts, "sql_statements")
    assert stmt.text.split() == ["SELECT", "NAME", "INTO", ":WS-NAME", "FROM", "CUSTOMER"]
    assert (stmt.line, stmt.tables, stmt.parsed) == (4, ["CUSTOMER"], True)


def test_embedded_c_include_and_whenever() -> None:
    text = "EXEC SQL INCLUDE settle_types.h;\nEXEC SQL WHENEVER SQLERROR GOTO err;\n"
    result = extract(text, file="a.pc", dialect="oracle", cobol=False)
    assert result.includes == [(1, "settle_types.h")]
    assert not result.statements and not result.unparsed
