"""SQL scripts (DDL objects, PL/SQL bodies, DML) and shell scripts (calls, sqlplus, CFT, FTP)."""

from __future__ import annotations

from legacy_audit.analyzers.shell import ShellAnalyzer
from legacy_audit.analyzers.sql import SqlAnalyzer
from legacy_audit.analyzers.sqltools import looks_like_sql
from tests.analyzers.helpers import entities, run

SQL = """-- order maintenance
CREATE TABLE orders (id NUMBER PRIMARY KEY, status VARCHAR2(1));
CREATE OR REPLACE VIEW open_orders AS SELECT * FROM orders WHERE status = 'O';
CREATE OR REPLACE PROCEDURE close_order(p_id NUMBER) AS
BEGIN
  UPDATE orders SET status = 'C' WHERE id = p_id;
  INSERT INTO order_audit (id, action) VALUES (p_id, 'CLOSE');
END;
/
SELECT o.id, NVL(o.status, 'N') FROM orders o WHERE o.id(+) = 1;
DELETE FROM orders WHERE status = 'X';
"""


def test_sql_script_objects_and_statements() -> None:
    facts = run(SqlAnalyzer(), "db/orders.sql", SQL)
    objects = {(o.name, o.object_type) for o in entities(facts, "db_objects")}
    assert objects == {("ORDERS", "table"), ("OPEN_ORDERS", "view"), ("CLOSE_ORDER", "procedure")}
    stmts = [(s.line, s.type, s.source, s.tables) for s in entities(facts, "sql_statements")]
    assert stmts == [
        (6, "UPDATE", "plsql", ["ORDERS"]),
        (7, "INSERT", "plsql", ["ORDER_AUDIT"]),
        (10, "SELECT", "sql-file", ["ORDERS"]),
        (11, "DELETE", "sql-file", ["ORDERS"]),
    ]
    select = entities(facts, "sql_statements")[2]
    assert select.vendor_features == ["NVL", "OUTER JOIN (+)"]
    assert select.normalized == "SELECT O.ID, NVL(O.STATUS, ?) FROM ORDERS O WHERE O.ID(+) = ?"
    assert {s.kind for s in facts.symbols} == {"db-table", "db-view", "db-procedure"}
    assert not facts.coverage.unparsed


def test_sql_shape_rejects_ui_text() -> None:
    assert looks_like_sql("select id from t")
    assert looks_like_sql("SELECT name FROM customer")
    assert looks_like_sql("Select a, b from t")
    assert not looks_like_sql("Select a customer from the list", strict=True)
    assert looks_like_sql("SELECT name FROM customer", strict=True)
    assert not looks_like_sql("Update your details")
    assert not looks_like_sql("Delete")


SHELL = """#!/bin/ksh
. /opt/app/env.sh
BIN=/opt/app/bin
$BIN/settle_load -d $DATE 2>&1 | tee log
./archive.sh || exit 2
java -cp app.jar com.acme.batch.Main
sqlplus -s $DB_USER/$DB_PASS@ORCL <<SQL
UPDATE batch_ctl SET status = 'DONE' WHERE job = 'X';
EXIT
SQL
CFTUTIL SEND PART=BANK1, IDF=SETTLE, FNAME=/data/out/settle.dat
ftp -n ftp.bank.example <<FTP
put file.dat
FTP
"""


def test_shell_calls_sql_and_transfers() -> None:
    facts = run(ShellAnalyzer(), "scripts/run.ksh", SHELL)
    refs = {(r.kind, r.target, r.line, r.report_unresolved) for r in facts.refs}
    assert refs == {
        ("invokes", "env.sh", 2, True),
        # Called through a variable path: resolved if found, never reported as missing.
        ("invokes", "settle_load", 4, False),
        ("invokes", "archive.sh", 5, True),
        ("invokes", "com.acme.batch.Main", 6, True),
    }
    (sql,) = entities(facts, "sql_statements")
    assert (sql.line, sql.type, sql.source, sql.tables) == (8, "UPDATE", "script", ["BATCH_CTL"])
    ints = {(i.type, i.target, i.direction) for i in entities(facts, "integrations")}
    assert ints == {("CFT", "BANK1/SETTLE", "outbound"), ("FTP", "ftp.bank.example", "outbound")}
    # sqlplus credentials are variables here; nothing secret may leak either way.
    assert "DB_PASS" not in str([s.text for s in entities(facts, "sql_statements")])


def test_shell_redirection_is_not_a_separator() -> None:
    facts = run(ShellAnalyzer(), "a.sh", "#!/bin/sh\nrun_me.sh 2>&1 >/dev/null\n")
    assert [r.target for r in facts.refs] == ["run_me.sh"]
