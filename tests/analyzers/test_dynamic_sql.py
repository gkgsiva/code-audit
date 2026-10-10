"""SQL built at run time is rebuilt from the code: Java, C (ODBC/OCI), Pro*C and COBOL."""

from __future__ import annotations

from legacy_audit.analyzers.c_cpp import CAnalyzer
from legacy_audit.analyzers.cobol import CobolAnalyzer
from legacy_audit.analyzers.dynsql import format_c, lit
from legacy_audit.analyzers.java import JavaAnalyzer
from legacy_audit.analyzers.proc import ProcAnalyzer
from legacy_audit.analyzers.sqltools import hole_kind
from legacy_audit.model import SqlStatement
from tests.analyzers.helpers import entities, run


def _by_type(facts) -> dict[str, SqlStatement]:  # type: ignore[no-untyped-def]
    return {s.type: s for s in entities(facts, "sql_statements")}


def test_hole_kinds() -> None:
    assert hole_kind("WHERE name = '") == "value"
    assert hole_kind("WHERE id = ") == "value"
    assert hole_kind("WHERE d > SYSDATE - ") == "value"
    assert hole_kind("WHERE id IN (1, ") == "value"
    assert hole_kind("SELECT * FROM ") == "structural"
    assert hole_kind("ORDER BY ") == "structural"
    assert hole_kind("WHERE f(a, ") == "structural"


def test_c_format_numbers_are_values() -> None:
    out = format_c(lit("SELECT a FROM %s WHERE n = %d AND s = '%s' %%", 1), [None, None, None], 1)
    assert out.text == "SELECT a FROM ? WHERE n = ? AND s = '?' %"
    assert (out.value_holes, out.structural_holes) == (2, 1)


# ---- Java ----------------------------------------------------------------------------------------
JAVA = """package a;
import java.sql.*;
public class Dao {
  private static final String BASE = "SELECT id, name FROM customers WHERE 1=1";
  public void search(Connection con, String name, String city, String sort, boolean active)
      throws Exception {
    StringBuilder sql = new StringBuilder(BASE);
    if (name != null) {
      sql.append(" AND name LIKE ?");
    }
    if (city != null) {
      sql.append(" AND city = '").append(city).append("'");
    }
    sql.append(active ? " AND status = 'A'" : " AND status <> 'A'");
    sql.append(" ORDER BY ").append(sort);
    PreparedStatement ps = con.prepareStatement(sql.toString());
  }
  public void count(Connection con, String table, int max) throws Exception {
    String q = "SELECT COUNT(*) FROM " + table;
    q += " WHERE ROWNUM <= " + max;
    con.createStatement().executeQuery(q);
  }
  public void fmt(Connection con, long id) throws Exception {
    String q = String.format("UPDATE orders SET status = '%s' WHERE id = %d", "X", id);
    con.createStatement().executeUpdate(q);
  }
  public void inList(Connection con, String ids) throws Exception {
    String q = "DELETE FROM orders WHERE id IN (" + ids + ")";
    con.createStatement().execute(q);
  }
  public String caption() { return "Select a customer from the list"; }
}
"""


def test_java_builder_with_conditional_parts_and_ternary() -> None:
    sts = entities(run(JavaAnalyzer(), "src/a/Dao.java", JAVA), "sql_statements")
    search = next(s for s in sts if "customers" in s.text)
    assert search.text == (
        "SELECT id, name FROM customers WHERE 1=1 AND name LIKE ? AND city = '?' "
        "AND status = 'A' ORDER BY ?"
    )
    assert search.dynamic and search.source == "jdbc"
    assert {"string-builder", "conditional", "ternary"} <= set(search.built_with)
    assert (search.value_holes, search.structural_holes) == (1, 1)
    assert search.optional_fragments == 3
    # without the optional parts, and the other ternary branch
    assert "SELECT id, name FROM customers WHERE 1=1 AND status = 'A' ORDER BY ?" in search.variants
    assert any("status <> 'A'" in v for v in search.variants)
    assert len({e.line for e in search.evidence}) > 3  # every fragment's line is evidence
    # the constant used as the builder's start is not a statement of its own
    assert sum("customers" in s.text for s in sts) == 1
    assert not any("Select a customer" in s.text for s in sts)


def test_java_concatenation_format_and_in_list() -> None:
    sts = _by_type(run(JavaAnalyzer(), "src/a/Dao.java", JAVA))
    assert sts["SELECT"].text == "SELECT COUNT(*) FROM ? WHERE ROWNUM <= ?"
    assert (sts["SELECT"].value_holes, sts["SELECT"].structural_holes) == (1, 1)
    assert sts["UPDATE"].text == "UPDATE orders SET status = 'X' WHERE id = ?"
    assert "string-format" in sts["UPDATE"].built_with
    assert sts["DELETE"].text == "DELETE FROM orders WHERE id IN (?)"
    assert (sts["DELETE"].value_holes, sts["DELETE"].structural_holes) == (1, 0)


# ---- Pro*C ---------------------------------------------------------------------------------------
PC = r"""#include <stdio.h>
#define BASE_SQL "SELECT id, amount FROM payments WHERE 1=1"
EXEC SQL BEGIN DECLARE SECTION;
char dyn_sql[1024];
VARCHAR stmt[512];
EXEC SQL END DECLARE SECTION;
static int search(const char *merchant, int days, const char *sort, int open_only)
{
    strcpy(dyn_sql, BASE_SQL);
    if (merchant != NULL) {
        strcat(dyn_sql, " AND merchant_id = :1");
    }
    sprintf(dyn_sql + strlen(dyn_sql), " AND paid_on > SYSDATE - %d", days);
    strcat(dyn_sql, open_only ? " AND status = 'O'" : " AND status <> 'X'");
    strcat(dyn_sql, " ORDER BY ");
    strcat(dyn_sql, sort);
    EXEC SQL PREPARE s1 FROM :dyn_sql;
    strcpy((char *)stmt.arr, "UPDATE payments SET status = 'C' WHERE id = :1");
    stmt.len = strlen((char *)stmt.arr);
    EXEC SQL PREPARE s2 FROM :stmt;
    EXEC SQL EXECUTE IMMEDIATE :unknown_buf;
    return 0;
}
"""


def test_proc_prepare_from_buffer_is_rebuilt() -> None:
    sts = _by_type(run(ProcAnalyzer(), "src/pay.pc", PC))
    sel = sts["SELECT"]
    assert sel.text == (
        "SELECT id, amount FROM payments WHERE 1=1 AND merchant_id = :1 "
        "AND paid_on > SYSDATE - ? AND status = 'O' ORDER BY ?"
    )
    assert sel.line == 17 and sel.source == "embedded" and sel.dynamic
    assert {"prepare-from-variable", "conditional", "string-format", "ternary"} <= set(
        sel.built_with
    )
    assert (sel.value_holes, sel.structural_holes, sel.optional_fragments) == (1, 1, 1)
    assert sel.tables == ["PAYMENTS"]
    assert {2, 9, 11, 13, 17} <= {e.line for e in sel.evidence}
    # a VARCHAR host variable filled with one literal is fully known
    upd = sts["UPDATE"]
    assert upd.text == "UPDATE payments SET status = 'C' WHERE id = :1"
    assert not upd.dynamic and upd.structural_holes == 0
    # a buffer this file never fills stays unknown
    assert sts["DYNAMIC"].structural_holes == 1


# ---- C (ODBC / OCI) ------------------------------------------------------------------------------
C = r"""#include <sql.h>
static const char *REPORT = "SELECT region, SUM(amount) FROM sales GROUP BY region";
void run(SQLHSTMT h, const char *tbl, long id) {
    char q[256];
    snprintf(q, sizeof q, "DELETE FROM %s WHERE id = %ld", tbl, id);
    SQLExecDirect(h, (SQLCHAR *)q, SQL_NTS);
    OCIStmtPrepare(stmthp, errhp, (text *)"UPDATE t SET a = 1", 18, OCI_NTV_SYNTAX, OCI_DEFAULT);
    char q2[256] = "SELECT a FROM t";
    sprintf(q2, "%s WHERE b = '%s'", q2, tbl);
    db_exec(q2);
    SQLPrepare(h, (SQLCHAR *)getenv("X"), SQL_NTS);
}
"""


def test_c_api_calls_and_buffers() -> None:
    sts = entities(run(CAnalyzer(), "src/rep.c", C), "sql_statements")
    by_line = {s.line: s for s in sts}
    delete = by_line[6]
    assert (delete.source, delete.text) == ("odbc", "DELETE FROM ? WHERE id = ?")
    assert (delete.value_holes, delete.structural_holes) == (1, 1)
    assert delete.tables == []  # a hole is not a table
    assert (by_line[7].source, by_line[7].text, by_line[7].dynamic) == (
        "oci", "UPDATE t SET a = 1", False,
    )  # fmt: skip
    assert by_line[11].type == "DYNAMIC" and by_line[11].structural_holes == 1
    # buffers and constants that look like SQL but reach the database through a wrapper
    strings = {s.text: s for s in sts if s.source == "c-string"}
    assert "SELECT region, SUM(amount) FROM sales GROUP BY region" in strings
    appended = strings["SELECT a FROM t WHERE b = '?'"]
    assert appended.value_holes == 1 and appended.dynamic


# ---- COBOL ---------------------------------------------------------------------------------------
COB = """       IDENTIFICATION DIVISION.
       PROGRAM-ID. DYNRPT.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-BASE      PIC X(60) VALUE
           'SELECT ORD_ID, ORD_AMOUNT FROM ORDERS WHERE 1=1'.
       01  WS-REGION    PIC X(4).
       01  WS-SORT-COL  PIC X(20).
       01  WS-SQL.
           49 WS-SQL-LEN  PIC S9(4) COMP.
           49 WS-SQL-TEXT PIC X(500).
       01  WS-UPD       PIC X(200).
       PROCEDURE DIVISION.
       MAIN-PARA.
           MOVE WS-BASE TO WS-SQL-TEXT
           IF WS-REGION NOT = SPACES
              STRING WS-SQL-TEXT DELIMITED BY '  '
                     ' AND REGION = ''' WS-REGION ''''
                     DELIMITED BY SIZE INTO WS-SQL-TEXT
              END-STRING
           END-IF
           STRING WS-SQL-TEXT DELIMITED BY '  '
                  ' AND STATUS = ?' ' ORDER BY ' WS-SORT-COL
                  DELIMITED BY SIZE INTO WS-SQL-TEXT.
           EXEC SQL PREPARE S1 FROM :WS-SQL END-EXEC.
           MOVE 'DELETE FROM ORDER_HIST WHERE ORD_STATUS = ''X'''
             TO WS-UPD.
           EXEC SQL EXECUTE IMMEDIATE :WS-UPD END-EXEC.
           STOP RUN.
"""


def test_cobol_move_and_string_into_prepare() -> None:
    sts = _by_type(run(CobolAnalyzer(), "cobol/DYNRPT.cbl", COB))
    sel = sts["SELECT"]
    assert sel.text == (
        "SELECT ORD_ID, ORD_AMOUNT FROM ORDERS WHERE 1=1 AND REGION = '?' AND STATUS = ? ORDER BY ?"
    )
    assert sel.line == 25 and sel.dynamic
    assert {"prepare-from-variable", "string-statement", "conditional"} <= set(sel.built_with)
    assert (sel.value_holes, sel.structural_holes) == (1, 1)
    assert sel.variants == [
        "SELECT ORD_ID, ORD_AMOUNT FROM ORDERS WHERE 1=1 AND STATUS = ? ORDER BY ?"
    ]
    assert {5, 15, 17, 22} <= {e.line for e in sel.evidence}
    dele = sts["DELETE"]
    assert dele.text == "DELETE FROM ORDER_HIST WHERE ORD_STATUS = 'X'" and not dele.dynamic
    assert "DYNAMIC" not in sts
