"""Generate a synthetic legacy estate of roughly ``target_loc`` lines (Java/Struts/JSP, SQL,
shell, C, Pro*C, COBOL, JCL, config) for the stress test. Not committed: built in a temp folder.

    python -m tests.stress_tree <folder> [target_loc]
"""

from __future__ import annotations

import sys
from pathlib import Path

JAVA_METHODS = 12


def _java(pkg: str, name: str, i: int) -> str:
    body = []
    for m in range(JAVA_METHODS):
        body.append(
            f"""
    public java.util.List<String> find{m}(java.sql.Connection c, String key) throws Exception {{
        String sql = "SELECT id, name, NVL(status, 'N') FROM table_{i % 40} t, ref_{m} r "
                   + "WHERE t.id = r.id(+) AND t.code = ? AND ROWNUM < 100";
        java.sql.PreparedStatement ps = c.prepareStatement(sql);
        ps.setString(1, key);
        java.util.List<String> out = new java.util.ArrayList<String>();
        java.sql.ResultSet rs = ps.executeQuery();
        while (rs.next()) {{
            out.add(rs.getString(2));
        }}
        rs.close();
        ps.close();
        return out;
    }}"""
        )
    return f"""package {pkg};

import java.sql.Connection;
import org.apache.log4j.Logger;

/** Generated class {i}. */
public class {name} {{
    private static final Logger LOG = Logger.getLogger({name}.class);
    private static final String ENDPOINT = "http://svc{i % 7}.acme.local:8080/api/v1";
{"".join(body)}
}}
"""


def _jsp(i: int) -> str:
    rows = "\n".join(
        f'  <tr><td><input type="text" name="f{k}"/></td><td>${{row.c{k}}}</td></tr>'
        for k in range(20)
    )
    return f"""<%@ page contentType="text/html" %>
<%@ include file="/jsp/common/header.jspf" %>
<html><body>
<form action="/page{i}.do" method="post">
<table>
{rows}
</table>
<input type="submit" value="Save"/>
</form>
</body></html>
"""


def _cobol(i: int) -> str:
    paras = []
    for p in range(25):
        paras.append(
            f"""       PARA-{p:03d}.
           MOVE SPACES TO WS-AREA
           EXEC SQL
               SELECT COL_{p} INTO :WS-COL
                 FROM TAB_{i % 30}
                WHERE KEY_ID = :WS-KEY
           END-EXEC
           IF WS-COL = SPACES
              PERFORM PARA-{(p + 1) % 25:03d}
           END-IF."""
        )
    return f"""       IDENTIFICATION DIVISION.
       PROGRAM-ID. PGM{i:04d}.
       ENVIRONMENT DIVISION.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  WS-AREA                PIC X(80).
       01  WS-COL                 PIC X(20).
       01  WS-KEY                 PIC 9(8).
           COPY CPY{i % 10:03d}.
           EXEC SQL INCLUDE SQLCA END-EXEC.
       PROCEDURE DIVISION.
{chr(10).join(paras)}
           GOBACK.
"""


def _c(i: int) -> str:
    funcs = "\n".join(
        f"""static int step_{k}(const char *in, char *out)
{{
    int n = 0;
    while (in[n] != '\\0') {{
        out[n] = in[n] == ';' ? ',' : in[n];
        n++;
    }}
    out[n] = '\\0';
    return n;
}}
"""
        for k in range(15)
    )
    return f"""#include <stdio.h>
#include <string.h>
#include "common_{i % 5}.h"

{funcs}
int main(int argc, char **argv)
{{
    char buf[256];
    FILE *f = fopen("/data/in/file_{i}.dat", "r");
    step_0(argc > 1 ? argv[1] : "", buf);
    system("/opt/app/scripts/job_{i % 20}.sh");
    return f == NULL;
}}
"""


def _shell(i: int) -> str:
    lines = [f"#!/bin/ksh\n# job {i}\nBIN=/opt/app/bin\n"]
    for k in range(10):
        lines.append(f"$BIN/prog_{(i + k) % 40} --step {k} || exit {k + 1}\n")
    lines.append(
        f"""sqlplus -s $DB_USER/$DB_PASS@APP <<EOF
UPDATE batch_ctl SET status = 'DONE' WHERE job_id = {i};
EXIT
EOF
CFTUTIL SEND PART=PARTNER{i % 3}, IDF=FLOW{i}, FNAME=/data/out/f{i}.dat
"""
    )
    return "".join(lines)


def _sql(i: int) -> str:
    stmts = "\n".join(
        f"SELECT a.id, b.name FROM tab_{i}_{k} a JOIN ref_{k} b ON b.id = a.ref_id "
        f"WHERE a.created < SYSDATE - {k};\n"
        f"UPDATE tab_{i}_{k} SET flag = DECODE(flag, 'Y', 'N', 'Y') WHERE id = :{k};\n"
        for k in range(30)
    )
    return f"-- generated script {i}\n{stmts}"


def generate(root: Path, target_loc: int = 200_000) -> int:
    """Write the tree; return the number of lines written."""
    total = 0
    i = 0
    root.mkdir(parents=True, exist_ok=True)
    (root / "web/WebContent/jsp/common").mkdir(parents=True, exist_ok=True)
    (root / "web/WebContent/jsp/common/header.jspf").write_text("<div>header</div>\n")
    (root / "web/build.xml").write_text('<project name="web"><javac source="1.6"/></project>\n')
    (root / "batch").mkdir(parents=True, exist_ok=True)
    (root / "batch/Makefile").write_text("all: prog\n\tcc -o prog prog.c\n")
    writers = [
        (lambda n: f"web/src/com/acme/gen/p{n % 20}/Gen{n}.java",
         lambda n: _java(f"com.acme.gen.p{n % 20}", f"Gen{n}", n)),
        (lambda n: f"web/WebContent/jsp/page{n}.jsp", _jsp),
        (lambda n: f"host/cobol/PGM{n:04d}.cbl", _cobol),
        (lambda n: f"batch/src/prog_{n}.c", _c),
        (lambda n: f"batch/scripts/job_{n}.sh", _shell),
        (lambda n: f"db/sql/script_{n}.sql", _sql),
    ]  # fmt: skip
    while total < target_loc:
        for path_of, content_of in writers:
            text = content_of(i)
            p = root / path_of(i)
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text, encoding="utf-8")
            total += text.count("\n")
        i += 1
    return total


if __name__ == "__main__":
    n = generate(Path(sys.argv[1]), int(sys.argv[2]) if len(sys.argv) > 2 else 200_000)
    print(f"{n} lines")
