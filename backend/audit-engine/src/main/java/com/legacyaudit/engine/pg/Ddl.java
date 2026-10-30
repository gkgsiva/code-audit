package com.legacyaudit.engine.pg;

import com.legacyaudit.engine.pg.Lexer.T;
import com.legacyaudit.engine.pg.Lexer.Tok;
import java.util.List;

/**
 * DDL and session statements. The SQL library rewrote only a few of them (index columns, views
 * and tables built from a query, {@code ALTER TABLE ... ADD}); the rest it passed through as a
 * command: the first word, then the rest of the text as written.
 */
final class Ddl {

    private Ddl() {}

    static Node statement(Parser p, String sql) {
        Tok first = p.tok();
        switch (first.up) {
            case "CREATE" -> {
                Node n = create(p, sql);
                if (n != null) {
                    return n;
                }
            }
            case "ALTER" -> {
                Node n = alter(p, sql);
                if (n != null) {
                    return n;
                }
            }
            case "LOCK" -> {
                if (p.dialect() != Dialect.POSTGRES) {
                    throw new Parser.ParseFail();
                }
            }
            default -> {
                // passed through as written
            }
        }
        return command(p, sql);
    }

    /** The first word and the rest of the statement as written (sqlglot's Command). */
    private static Node command(Parser p, String sql) {
        p.seek(0);
        Tok kw = p.tok();
        p.advance();
        int start = p.tok().start;
        while (p.tok().type != T.EOF && !p.tok().is(";")) {
            p.advance();
        }
        int end = p.tok().type == T.EOF ? sql.length() : p.tok().start;
        String rest = start >= end ? "" : sql.substring(start, end).strip();
        return Node.of("command", "this", kw.up, "rest", rest);
    }

    private static String text(String sql, int from, int to) {
        return sql.substring(from, to).strip().replaceAll("\\s+", " ");
    }

    private static Node create(Parser p, String sql) {
        int save = p.pos();
        p.advance();
        boolean orReplace = p.acceptSeq("OR", "REPLACE");
        boolean unique = p.accept("UNIQUE");
        if (p.accept("INDEX")) {
            Node name = p.identifier();
            if (!p.accept("ON")) {
                p.seek(save);
                return null;
            }
            Node table = p.tableTarget();
            if (table == null || !p.accept("(")) {
                p.seek(save);
                return null;
            }
            List<Node> cols = p.csv(p::ordered);
            p.require(")");
            if (p.tok().type != T.EOF && !p.tok().is(";")) {
                p.seek(save);
                return null;
            }
            String head = "CREATE " + (unique ? "UNIQUE " : "") + "INDEX " + (name != null ? Writer.sqlOf(p.dialect(), name) + " " : "")
                    + "ON " + Writer.sqlOf(p.dialect(), table) + "(";
            StringBuilder sb = new StringBuilder(head);
            for (int k = 0; k < cols.size(); k++) {
                sb.append(k > 0 ? ", " : "").append(Writer.sqlOf(p.dialect(), cols.get(k)));
            }
            return Node.of("ddl", "text", sb.append(")").toString());
        }
        boolean view = p.accept("VIEW");
        boolean table = !view && p.accept("TABLE");
        if (!view && !table) {
            p.seek(save);
            return null;
        }
        Node name = p.tableTarget();
        if (name == null) {
            p.seek(save);
            return null;
        }
        String kind = view ? "VIEW" : "TABLE";
        String prefix = "CREATE " + (orReplace ? "OR REPLACE " : "") + kind + " ";
        if (p.accept("AS")) {
            Node q = p.query(true);
            if (p.tok().type != T.EOF && !p.tok().is(";")) {
                p.seek(save);
                return null;
            }
            return Node.of("ddl", "text", prefix + Writer.sqlOf(p.dialect(), name) + " AS " + Writer.sqlOf(p.dialect(), q));
        }
        p.seek(save);
        return null;
    }

    private static Node alter(Parser p, String sql) {
        int save = p.pos();
        p.advance();
        if (!p.accept("TABLE")) {
            p.seek(save);
            return null;
        }
        Node table = p.tableTarget();
        if (table == null || !p.sees("ADD")) {
            p.seek(save);
            return null;
        }
        p.advance();
        if (p.sees("CONSTRAINT") || p.sees("PRIMARY KEY") || p.sees("FOREIGN KEY") || p.sees("UNIQUE") || p.sees("CHECK")
                || p.sees("(")) {
            p.seek(save);
            return null;
        }
        p.accept("COLUMN");
        Node col = p.identifier();
        if (col == null) {
            p.seek(save);
            return null;
        }
        Node type = p.dataType();
        StringBuilder sb = new StringBuilder("ALTER TABLE ").append(Writer.sqlOf(p.dialect(), table)).append(" ADD COLUMN ")
                .append(Writer.sqlOf(p.dialect(), col)).append(" ").append(Writer.sqlOf(p.dialect(), type));
        int afterType = p.tok().start;
        while (p.tok().type != T.EOF && !p.tok().is(";")) {
            p.advance();
        }
        int end = p.tok().type == T.EOF ? sql.length() : p.tok().start;
        if (afterType < end) {
            sb.append(" ").append(text(sql, afterType, end).toUpperCase(java.util.Locale.ROOT));
        }
        return Node.of("ddl", "text", sb.toString());
    }
}
