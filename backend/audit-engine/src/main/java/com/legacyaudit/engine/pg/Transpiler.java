package com.legacyaudit.engine.pg;

import com.legacyaudit.engine.analyzers.SqlTools;
import java.util.regex.Pattern;

/**
 * SQL in a source dialect to PostgreSQL text, the way the Python tool converted it with its SQL
 * library (sqlglot): what the library could not read or convert fails here with the same kind
 * of error (ParseError, TokenError, UnsupportedError), so the grade stays the same.
 */
public final class Transpiler {

    private static final Pattern PSYCOPG = Pattern.compile("%s\\b");

    /** A statement that could not be converted; {@link #kind} is the Python error's class name. */
    public static class Failure extends RuntimeException {
        private final String kind;

        public Failure(String kind) {
            super(kind, null, false, false);
            this.kind = kind;
        }

        public String kind() {
            return kind;
        }
    }

    private Transpiler() {}

    /** PostgreSQL text of a statement (binds as JDBC {@code ?}, at most 4000 characters). */
    public static String toPostgres(String text, String dialect) {
        Dialect d = Dialect.of(dialect);
        String out;
        try {
            out = Writer.sqlOf(d, Parser.parse(d, SqlTools.prepare(text)));
        } catch (StackOverflowError e) {
            throw new Failure("RecursionError");
        }
        if (out.isBlank()) {
            throw new Failure("ValueError");
        }
        out = PSYCOPG.matcher(out).replaceAll("?");
        int n = out.codePointCount(0, out.length());
        return n <= SqlTools.MAX_TEXT ? out : out.substring(0, out.offsetByCodePoints(0, SqlTools.MAX_TEXT));
    }
}
