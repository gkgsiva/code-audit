package com.legacyaudit.engine.scan;

import static org.junit.jupiter.api.Assertions.fail;

import com.legacyaudit.engine.analyzers.SqlTools;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SQL helpers give what the Python tool's gave on every statement of the samples and of its
 * tests ({@code parity/sqltools.json.gz}, entries without {@code extra}). On the hand-written
 * statements ({@code extra}: every dialect, exotic syntax included) the table lists and the
 * parsed flag may differ in a few known cases, because the Python tool used another SQL parser
 * (sqlglot); the test keeps that number from growing.
 */
class SqlToolsParityTest {

    @Test
    void sqlHelpersMatchThePythonTool() throws Exception {
        List<Map<String, Object>> cases;
        try (InputStream in = new GZIPInputStream(getClass().getResourceAsStream("/parity/sqltools.json.gz"))) {
            cases = JsonMapper.builder().build().readValue(in, new TypeReference<List<Map<String, Object>>>() {});
        }
        List<String> problems = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            List<String> sink = Boolean.TRUE.equals(c.get("extra")) ? extra : problems;
            String sql = (String) c.get("sql");
            String dialect = (String) c.get("dialect");
            var t = SqlTools.parseTables(sql, dialect);
            check(sink, sql, dialect, "tables", c.get("tables"), t.tables());
            check(sink, sql, dialect, "procedures", c.get("procedures"), t.procedures());
            check(sink, sql, dialect, "parsed", c.get("parsed"), t.parsed());
            check(problems, sql, dialect, "type", c.get("type"), SqlTools.statementType(sql));
            check(problems, sql, dialect, "normalized", c.get("normalized"), SqlTools.normalize(sql));
            check(problems, sql, dialect, "fingerprint", c.get("fingerprint"), SqlTools.fingerprint(sql));
            check(problems, sql, dialect, "features", c.get("features"), SqlTools.vendorFeatures(sql));
            check(problems, sql, dialect, "sql_like", c.get("sql_like"), SqlTools.looksLikeSql(sql));
            check(problems, sql, dialect, "strict", c.get("strict"), SqlTools.looksLikeSql(sql, true));
            check(problems, sql, dialect, "hql_like", c.get("hql_like"), SqlTools.looksLikeHql(sql));
            check(problems, sql, dialect, "prepared", c.get("prepared"), SqlTools.prepare(sql));
            check(problems, sql, dialect, "prepared_binds", c.get("prepared_binds"), SqlTools.prepare(sql, true));
        }
        if (extra.size() > KNOWN_DIFFERENCES) {
            problems.add("hand-written statements: " + extra.size() + " differences, at most " + KNOWN_DIFFERENCES + " expected");
            problems.addAll(extra);
        }
        if (!problems.isEmpty()) {
            fail(problems.size() + " difference(s) in " + cases.size() + " statements\n" + String.join("\n", problems));
        }
    }

    /** Exotic syntax the two parsers read differently (see decision 210). */
    private static final int KNOWN_DIFFERENCES = 70;

    private static void check(List<String> problems, String sql, String dialect, String what, Object want, Object got) {
        if (!Objects.equals(want, got)) {
            problems.add(what + " [" + dialect + "] " + sql.replace("\n", " ") + "\n   python: " + want + "\n   java:   " + got);
        }
    }
}
