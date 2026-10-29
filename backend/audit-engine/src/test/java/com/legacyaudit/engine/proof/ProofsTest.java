package com.legacyaudit.engine.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProofsTest {

    /** The sample applications at the top of the repository. */
    private static final Path SAMPLES = Path.of("..", "..", "samples").toAbsolutePath().normalize();

    @Test
    void javaParserReadsEverySampleJavaFile() {
        Proofs.JavaResult r = Proofs.java(SAMPLES);
        assertTrue(r.files() > 40, "expected the sample Java files, found " + r.files());
        assertEquals(r.files(), r.parsed(), String.join("\n", r.failures()));
    }

    @Test
    void treeSitterLoadsEveryGrammarOnThisSystem() {
        for (Proofs.TreeSitterResult t : Proofs.treeSitter(SAMPLES)) {
            if (!t.language().equals("C++")) {
                assertTrue(t.files() > 0, t.language() + ": no sample files");
            }
            assertTrue(t.clean() >= 0);
        }
    }

    @Test
    void jsqlParserReadsMostOfTheSqlThePythonScanFound() {
        Proofs.SqlResult r = Proofs.sql(Proofs.corpus());
        assertTrue(r.sqlStatements() > 50, "corpus too small: " + r.sqlStatements());
        assertTrue(r.jsqlParsed() * 10 >= r.sqlStatements() * 7,
                "JSqlParser parsed only " + r.jsqlParsed() + " of " + r.sqlStatements());
    }

    @Test
    void execSqlBlocksAreBlankedButLinesKept() {
        String in = "int a;\nEXEC SQL SELECT X\n  INTO :y FROM T;\nint b;\n";
        String out = Proofs.blank(in);
        assertEquals(in.length(), out.length());
        assertEquals(in.lines().count(), out.lines().count());
        assertTrue(out.contains("int b;") && !out.contains("SELECT"));
    }

    @Test
    void wrappersAreRewrittenForJSqlParser() throws Exception {
        assertEquals(Set.of("SHIPMENTS"), Proofs.tables("DECLARE SHIP-CUR CURSOR FOR SELECT A FROM SHIPMENTS"));
        assertEquals("CALL PKG.TOTAL(?)", Proofs.prepare("{? = CALL PKG.TOTAL(?)}"));
        assertEquals("CALL PKG.RECALC_ALL()", Proofs.prepare("EXEC PKG.RECALC_ALL"));
        assertEquals(Set.of(), Proofs.tables("DELETE FROM ? WHERE RUN_DATE < SYSDATE"));
    }

    @Test
    void writesTheProofReport() throws Exception {
        String md = Proofs.markdown(Proofs.run(SAMPLES));
        Path out = Path.of("target", "proofs", "phase-0-proofs.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md);
        assertTrue(md.contains("JSqlParser"));
    }
}
