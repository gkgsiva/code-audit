package com.legacyaudit.engine.pg;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import com.legacyaudit.engine.rules.Packs;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converts every statement the Python tool converted to PostgreSQL (parity/pg.json.gz: SQL,
 * dialect, the text or the error) and counts how many come out the same. Differences are
 * written to target/pg-corpus-diff.txt.
 */
class PgCorpusTest {

    /** Statements that must match; raise them as the converter improves, never lower them. */
    private static final int CORPUS_FLOOR = 1457;
    private static final int EXTRA_FLOOR = 1674;

    /** {@code mvn test -Dtest=PgCorpusTest -Dpg.sql="..." [-Dpg.dialect=oracle]}: print one conversion. */
    @Test
    void one() {
        String sql = System.getProperty("pg.sql");
        if (sql != null) {
            try {
                System.out.println("pg: " + Transpiler.toPostgres(sql, System.getProperty("pg.dialect")));
            } catch (Transpiler.Failure e) {
                System.out.println("pg: !" + e.kind());
            }
        }
    }

    @Test
    void matchesRecordedConversions() throws Exception {
        check("pg", CORPUS_FLOOR);
    }

    /**
     * Extra statements written for the converter (functions, dates, casts, paging, DML forms),
     * each in every dialect; recorded from the Python tool like the corpus.
     */
    @Test
    void matchesRecordedExtraConversions() throws Exception {
        check("pg-extra", EXTRA_FLOOR);
    }

    private void check(String name, int floor) throws Exception {
        JsonNode cases;
        try (InputStream in = new GZIPInputStream(getClass().getResourceAsStream("/parity/" + name + ".json.gz"))) {
            cases = JsonMapper.builder().build().readTree(in);
        }
        int same = 0;
        int graded = 0;
        StringBuilder diff = new StringBuilder();
        List<Grader.Construct> rules = Grader.constructs(Packs.load("postgresql", null).data);
        for (JsonNode c : cases) {
            String sql = c.get("sql").asString();
            String dialect = c.get("dialect").isNull() ? null : c.get("dialect").asString();
            String want = c.get("error").isNull() ? c.get("transpiled").asString() : "!" + c.get("error").asString();
            String got;
            try {
                got = Transpiler.toPostgres(sql, dialect);
            } catch (Transpiler.Failure e) {
                got = "!" + e.kind();
            } catch (RuntimeException e) {
                got = "!!" + e;
            }
            Grader.TextGrade g = Grader.gradeText(sql, dialect, rules);
            List<String> reasons = new ArrayList<>();
            c.get("reasons").forEach(r -> reasons.add(r.asString()));
            String pgText = c.get("pg_text").isNull() ? null : c.get("pg_text").asString();
            if (g.grade().equals(c.get("grade").asString()) && g.reasons().equals(reasons) && Objects.equals(g.pgText(), pgText)) {
                graded++;
            } else {
                diff.append(dialect).append(" | ").append(sql.replace('\n', ' ')).append("\n  want grade ").append(c.get("grade").asString())
                        .append(" ").append(reasons).append(" ").append(pgText).append("\n  got  grade ").append(g.grade()).append(" ")
                        .append(g.reasons()).append(" ").append(g.pgText()).append("\n");
            }
            if (want.equals(got)) {
                same++;
            } else {
                diff.append(dialect).append(" | ").append(sql.replace('\n', ' ')).append("\n  want ").append(want).append("\n  got  ")
                        .append(got).append("\n");
            }
        }
        Files.writeString(Path.of("target/" + name + "-diff.txt"), diff.toString(), StandardCharsets.UTF_8);
        System.out.println(name + ": " + same + " of " + cases.size() + " match, " + graded + " graded the same");
        assertTrue(same >= floor, name + ": " + same + " of " + cases.size() + " match, below " + floor);
        assertTrue(graded >= floor, name + ": " + graded + " of " + cases.size() + " graded the same, below " + floor);
    }
}
