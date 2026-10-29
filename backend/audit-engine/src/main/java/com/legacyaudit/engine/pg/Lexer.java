package com.legacyaudit.engine.pg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Splits a statement into tokens the way the Python tool's SQL library (sqlglot) did for each
 * source dialect: quoting, comments, multi-word keywords and which characters end a name.
 */
final class Lexer {

    enum T { VAR, IDENT, STRING, NATIONAL, HEX, NUMBER, SYM, EOF }

    static final class Tok {
        final T type;
        final String text;
        /** Upper-case text of a word, or of a multi-word keyword with single spaces ("GROUP BY"). */
        final String up;
        final int start;
        final int end;
        /** True when whitespace (or a comment) comes right before this token. */
        final boolean spaceBefore;

        Tok(T type, String text, String up, int start, int end, boolean spaceBefore) {
            this.type = type;
            this.text = text;
            this.up = up;
            this.start = start;
            this.end = end;
            this.spaceBefore = spaceBefore;
        }

        boolean is(String word) {
            return (type == T.VAR || type == T.SYM) && up.equals(word);
        }

        @Override
        public String toString() {
            return type + ":" + text;
        }
    }

    private static final String SINGLE = "!\"#%&'()*+,-./:;<=>?@[\\]^`{|}~";
    private static final List<String> OPERATORS = List.of(
            "!~*", "->>", "@>", "<@", "<=>", "#>>", "<->", "~~~", "~~*", "-|-", "?::",
            "::", "||", "|>", ">=", "<=", "<>", "!=", ":=", "->", "=>", "#>", "&&", "??", "~~", "~*", "!~", "&<", "&>", "==");
    private static final Set<String> PG_ONLY = Set.of("@>", "<@", "!~", "!~*");
    private static final Map<String, List<String>> MULTI = Map.of(
            "GROUP", List.of("BY"), "ORDER", List.of("BY"), "PARTITION", List.of("BY"), "CONNECT", List.of("BY"),
            "CLUSTER", List.of("BY"), "DISTRIBUTE", List.of("BY"), "SORT", List.of("BY"), "PARTITIONED", List.of("BY"));
    private static final List<List<String>> MULTI_WORDS = List.of(
            List.of("GROUPING", "SETS"), List.of("PRIMARY", "KEY"), List.of("FOREIGN", "KEY"), List.of("SIMILAR", "TO"),
            List.of("SQL", "SECURITY"), List.of("DOUBLE", "PRECISION"), List.of("CHAR", "VARYING"), List.of("CHARACTER", "VARYING"));

    private final Dialect d;
    private final String sql;
    private int pos;
    private final List<Tok> out = new ArrayList<>();
    private boolean space;

    private Lexer(Dialect d, String sql) {
        this.d = d;
        this.sql = sql;
    }

    static List<Tok> lex(Dialect d, String sql) {
        Lexer l = new Lexer(d, sql);
        l.run();
        return l.out;
    }

    private char at(int i) {
        return i < sql.length() ? sql.charAt(i) : '\0';
    }

    private boolean single(char c) {
        if (c == '$') {
            return d == Dialect.POSTGRES;
        }
        return SINGLE.indexOf(c) >= 0;
    }

    private boolean varSingle(char c) {
        return switch (d) {
            case ORACLE, TSQL -> c == '$' || c == '@' || c == '#';
            case POSTGRES -> c == '$';
            default -> false;
        };
    }

    private void add(T type, String text, int start) {
        String up = type == T.VAR || type == T.SYM ? text.toUpperCase(Locale.ROOT) : text;
        out.add(new Tok(type, text, up, start, pos, space));
        space = false;
    }

    private void run() {
        while (pos < sql.length()) {
            char c = sql.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
                space = true;
                continue;
            }
            if (comment()) {
                space = true;
                continue;
            }
            int start = pos;
            if (c >= '0' && c <= '9') {
                number();
                continue;
            }
            if (identifierQuote(c)) {
                continue;
            }
            if (string()) {
                continue;
            }
            if (d == Dialect.ORACLE && sql.startsWith("(+)", pos)) {
                pos += 3;
                add(T.SYM, "(+)", start);
                continue;
            }
            if (d == Dialect.ORACLE && sql.startsWith("^=", pos)) {
                pos += 2;
                add(T.SYM, "<>", start);
                continue;
            }
            if (single(c)) {
                String op = null;
                for (String o : OPERATORS) {
                    if (sql.startsWith(o, pos) && (d == Dialect.POSTGRES || !PG_ONLY.contains(o))) {
                        op = o;
                        break;
                    }
                }
                if (op != null) {
                    pos += op.length();
                    add(T.SYM, op, start);
                } else {
                    pos++;
                    add(T.SYM, String.valueOf(c), start);
                }
                continue;
            }
            var();
        }
        out.add(new Tok(T.EOF, "", "", pos, pos, space));
    }

    private boolean comment() {
        if (sql.startsWith("--", pos) || (d == Dialect.MYSQL && at(pos) == '#')) {
            while (pos < sql.length() && sql.charAt(pos) != '\n' && sql.charAt(pos) != '\r') {
                pos++;
            }
            return true;
        }
        if (sql.startsWith("/*", pos)) {
            int depth = 1;
            pos += 2;
            boolean nested = d != Dialect.ORACLE;
            while (pos < sql.length()) {
                if (sql.startsWith("*/", pos)) {
                    depth--;
                    pos += 2;
                    if (depth == 0) {
                        return true;
                    }
                    continue;
                }
                if (nested && sql.startsWith("/*", pos)) {
                    depth++;
                    pos += 2;
                    continue;
                }
                pos++;
            }
            return true;
        }
        return false;
    }

    private void number() {
        int start = pos;
        boolean decimal = false;
        int scientific = 0;
        pos++;
        while (true) {
            char p = at(pos);
            if (p >= '0' && p <= '9') {
                pos++;
            } else if (p == '.' && !decimal) {
                decimal = true;
                pos++;
            } else if ((p == '-' || p == '+') && scientific == 1) {
                char n = at(pos + 1);
                if (n >= '0' && n <= '9') {
                    scientific++;
                    pos++;
                } else {
                    break;
                }
            } else if ((p == 'e' || p == 'E') && scientific == 0) {
                scientific++;
                pos++;
            } else {
                break;
            }
        }
        add(T.NUMBER, sql.substring(start, pos), start);
    }

    private boolean identifierQuote(char c) {
        char end;
        if (c == '"' && d != Dialect.MYSQL) {
            end = '"';
        } else if (c == '[' && d == Dialect.TSQL) {
            end = ']';
        } else if (c == '`' && d == Dialect.MYSQL) {
            end = '`';
        } else {
            return false;
        }
        int start = pos;
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= sql.length()) {
                throw new Transpiler.Failure("TokenError");
            }
            char ch = sql.charAt(pos);
            if (ch == end) {
                if (at(pos + 1) == end) {
                    sb.append(end);
                    pos += 2;
                    continue;
                }
                pos++;
                break;
            }
            sb.append(ch);
            pos++;
        }
        add(T.IDENT, sb.toString(), start);
        return true;
    }

    private boolean string() {
        int start = pos;
        char c = at(pos);
        char n = at(pos + 1);
        T type = T.STRING;
        int skip;
        char quote;
        if (c == '\'' || (c == '"' && (d == Dialect.MYSQL || d == Dialect.TSQL))) {
            skip = 1;
            quote = c;
        } else if ((c == 'N' || c == 'n') && (n == '\'' || (n == '"' && (d == Dialect.MYSQL || d == Dialect.TSQL)))) {
            skip = 2;
            quote = n;
            type = T.NATIONAL;
        } else if ((c == 'X' || c == 'x') && n == '\'' && (d == Dialect.MYSQL || d == Dialect.POSTGRES)) {
            skip = 2;
            quote = '\'';
            type = T.HEX;
        } else if ((c == 'q' || c == 'Q') && n == '\'' && d == Dialect.ORACLE && pos + 2 < sql.length()) {
            char open = sql.charAt(pos + 2);
            char close = switch (open) {
                case '[' -> ']';
                case '{' -> '}';
                case '(' -> ')';
                case '<' -> '>';
                default -> open;
            };
            int endAt = sql.indexOf(close + "'", pos + 3);
            if (endAt < 0) {
                throw new Transpiler.Failure("TokenError");
            }
            String text = sql.substring(pos + 3, endAt);
            pos = endAt + 2;
            add(T.STRING, text, start);
            return true;
        } else {
            return false;
        }
        pos += skip;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= sql.length()) {
                throw new Transpiler.Failure("TokenError");
            }
            char ch = sql.charAt(pos);
            if (d == Dialect.MYSQL && ch == '\\' && pos + 1 < sql.length()) {
                sb.append(sql.charAt(pos + 1));
                pos += 2;
                continue;
            }
            if (ch == quote) {
                if (at(pos + 1) == quote) {
                    sb.append(quote);
                    pos += 2;
                    continue;
                }
                pos++;
                break;
            }
            sb.append(ch);
            pos++;
        }
        add(type, sb.toString(), start);
        return true;
    }

    private void var() {
        int start = pos;
        pos++;
        while (pos < sql.length()) {
            char p = sql.charAt(pos);
            if (Character.isWhitespace(p)) {
                break;
            }
            if (!varSingle(p) && single(p)) {
                break;
            }
            pos++;
        }
        String word = sql.substring(start, pos);
        String up = word.toUpperCase(Locale.ROOT);
        // multi-word keywords: GROUP BY, ORDER BY, ... (any whitespace between the words)
        List<String> next = MULTI.get(up);
        if (next != null && !(d == Dialect.ORACLE && up.equals("ORDER") && peekWord(pos).equals("SIBLINGS"))) {
            int after = skipSpace(pos);
            String w = peekWord(after);
            if (after > pos && next.contains(w)) {
                pos = after + w.length();
                add(T.VAR, up + " " + w, start);
                return;
            }
        }
        for (List<String> pair : MULTI_WORDS) {
            if (pair.get(0).equals(up)) {
                int after = skipSpace(pos);
                String w = peekWord(after);
                if (after > pos && w.equals(pair.get(1))) {
                    pos = after + w.length();
                    add(T.VAR, up + " " + w, start);
                    return;
                }
            }
        }
        add(T.VAR, word, start);
    }

    private int skipSpace(int i) {
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        return i;
    }

    private String peekWord(int i) {
        int j = i;
        while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
            j++;
        }
        return sql.substring(i, j).toUpperCase(Locale.ROOT);
    }

    static final Set<String> COMMANDS = Set.of("CALL", "EXPLAIN", "OPTIMIZE", "PREPARE", "VACUUM", "EXECUTE", "FETCH", "SHOW", "RENAME");
}
