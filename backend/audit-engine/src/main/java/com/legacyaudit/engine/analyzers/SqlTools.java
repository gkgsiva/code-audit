package com.legacyaudit.engine.analyzers;

import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.Schemas;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL helpers shared by every analyzer that finds SQL: detection, normalization (for distinct
 * counting), statement type, tables (JSqlParser with a regex fallback) and vendor features.
 */
public final class SqlTools {

    private SqlTools() {}

    public static final int MAX_TEXT = 4000;

    // Statement shapes, not just a leading keyword, so UI text like "Select a customer" is not SQL.
    private static final Pattern SQL_SHAPE = Rx.of(
            "^\\s*\\(?\\s*("
            + "SELECT\\b[\\s\\S]+?\\bFROM\\b"
            + "|INSERT\\s+INTO\\b"
            + "|UPDATE\\s+[\\w.$#\"]+(\\s+\\w+)?\\s+SET\\b"
            + "|DELETE\\s+(FROM\\s+)?[\\w.$#\"]+"
            + "|MERGE\\s+INTO\\b"
            + "|WITH\\s+\\w+\\s+AS\\s*\\("
            + "|CALL\\s+[\\w.$#]+"
            + "|\\{\\s*(\\?\\s*=\\s*)?CALL\\b"
            + "|EXEC(UTE)?\\s+[\\w.$#]+"
            + "|(CREATE|ALTER|DROP|TRUNCATE)\\s+(OR\\s+REPLACE\\s+)?"
            + "(TABLE|VIEW|INDEX|SEQUENCE|PROCEDURE|FUNCTION|PACKAGE|TRIGGER|SYNONYM)\\b"
            + "|DECLARE\\s+[\\w-]+\\s+CURSOR\\b"
            + ")", Rx.I);

    private static final Pattern HQL_SHAPE = Rx.of(
            "^\\s*(FROM\\s+[A-Z]\\w*|SELECT\\s[\\s\\S]+?\\sFROM\\s+[A-Z]\\w*|UPDATE\\s+[A-Z]\\w*\\s|DELETE\\s+(FROM\\s+)?[A-Z]\\w*)", Rx.I);

    public static final Pattern STRING_LIT = Rx.of("'(?:[^']|'')*'");
    private static final Pattern NUMBER = Rx.of("(?<![\\w.$#:])\\d+(\\.\\d+)?(?![\\w.])");
    private static final Pattern BIND = Rx.of(
            "(\\?\\d*|:[A-Za-z_][\\w$#-]*(\\s*:\\s*[A-Za-z_][\\w-]*)?|:\\d+|#\\{[^}]*\\}|\\$\\{[^}]*\\}|#\\w+#)");
    public static final Pattern WS = Rx.of("\\s+");
    private static final Pattern COMMENTS = Rx.of("--[^\\n]*|/\\*[\\s\\S]*?\\*/");

    private record Feature(String name, Pattern rx) {}

    private static final List<Feature> VENDOR_FEATURES = List.of(
            new Feature("NVL", Rx.of("\\bNVL2?\\s*\\(", Rx.I)),
            new Feature("DECODE", Rx.of("\\bDECODE\\s*\\(", Rx.I)),
            new Feature("ROWNUM", Rx.of("\\bROWNUM\\b", Rx.I)),
            new Feature("CONNECT BY", Rx.of("\\bCONNECT\\s+BY\\b", Rx.I)),
            new Feature("OUTER JOIN (+)", Rx.of("\\(\\+\\)")),
            new Feature("SYSDATE", Rx.of("\\bSYSDATE\\b", Rx.I)),
            new Feature("DUAL", Rx.of("\\bFROM\\s+DUAL\\b", Rx.I)),
            new Feature("TO_CHAR/TO_DATE", Rx.of("\\bTO_(CHAR|DATE|NUMBER)\\s*\\(", Rx.I)),
            new Feature("ADD_MONTHS", Rx.of("\\bADD_MONTHS\\s*\\(", Rx.I)),
            new Feature("SEQUENCE.NEXTVAL", Rx.of("\\.\\s*NEXTVAL\\b", Rx.I)),
            new Feature("HINT", Rx.of("/\\*\\+")),
            new Feature("MERGE", Rx.of("^\\s*MERGE\\b", Rx.I)),
            new Feature("DB2 WITH UR", Rx.of("\\bWITH\\s+(UR|CS|RS|RR)\\s*$", Rx.I)),
            new Feature("DB2 FETCH FIRST", Rx.of("\\bFETCH\\s+FIRST\\b", Rx.I)),
            new Feature("CURRENT DATE/TIMESTAMP", Rx.of("\\bCURRENT\\s+(DATE|TIMESTAMP)\\b", Rx.I)),
            new Feature("TOP", Rx.of("^\\s*SELECT\\s+TOP\\s+\\d", Rx.I)));

    public static final Pattern CALL = Rx.of("^\\s*\\{?\\s*(\\?\\s*=\\s*)?(CALL|EXEC(UTE)?)\\s+([\\w.$#]+)", Rx.I);
    private static final Pattern CURSOR = Rx.of("^\\s*DECLARE\\s+[\\w-]+\\s+CURSOR\\s+(WITH\\s+HOLD\\s+)?FOR\\s+", Rx.I);
    private static final Pattern SELECT_INTO = Rx.of("\\bINTO\\s+[^()]*?(?=\\bFROM\\b)", Rx.I | Pattern.DOTALL);
    private static final Pattern DB2_SPECIAL_REGISTER = Rx.of("\\bCURRENT\\s+(DATE|TIMESTAMP|TIME)\\b", Rx.I);
    private static final Pattern DB2_ISOLATION = Rx.of("\\bWITH\\s+(UR|CS|RS|RR)\\s*$", Rx.I);
    private static final Pattern REGEX_TABLES = Rx.of(
            "\\b(?:FROM|JOIN|INTO|UPDATE|TABLE|MERGE\\s+INTO|USING)\\s+([A-Za-z_][\\w$#]*(?:\\.[A-Za-z_][\\w$#]*)?)", Rx.I);
    public static final Set<String> NOT_TABLES = Set.of("SELECT", "DUAL", "SET", "VALUES", "WHERE", "TABLE", "LATERAL", "UNNEST", "THE");

    // Free-standing strings ("Select a customer from the list") must also carry SQL syntax.
    private static final Pattern SQL_SYNTAX = Rx.of("[*,=?()<>]|\\b(WHERE|JOIN|GROUP\\s+BY|ORDER\\s+BY|UNION)\\b", Rx.I);
    private static final Pattern UPPER_SELECT = Rx.of("^\\s*\\(?\\s*SELECT\\b[\\s\\S]+?\\bFROM\\b");
    private static final Pattern WORD = Rx.of("[A-Za-z]+");
    private static final Pattern MYBATIS_BIND = Rx.of("#\\{\\s*([A-Za-z_]\\w*)[^}]*\\}|#([A-Za-z_]\\w*)#");
    private static final Pattern COLON_BIND = Rx.of(":([A-Za-z][\\w-]*)");

    public static boolean looksLikeSql(String text) {
        return looksLikeSql(text, false);
    }

    /**
     * Statement shape; {@code strict} (strings with no SQL call site) also wants upper-case
     * SELECT/FROM or SQL punctuation, so UI captions are not counted.
     */
    public static boolean looksLikeSql(String text, boolean strict) {
        Matcher m = SQL_SHAPE.matcher(text);
        if (!m.lookingAt()) {
            return false;
        }
        if (strict && Rx.head(m.group(1), 6).toUpperCase(Locale.ROOT).equals("SELECT")) {
            return UPPER_SELECT.matcher(text).lookingAt() || SQL_SYNTAX.matcher(text).find();
        }
        return true;
    }

    public static boolean looksLikeHql(String text) {
        return HQL_SHAPE.matcher(text).lookingAt();
    }

    public static String stripComments(String sql) {
        return COMMENTS.matcher(sql).replaceAll(" ");
    }

    /** Whitespace collapsed, literals and bind variables replaced by {@code ?}, upper-cased. */
    public static String normalize(String sql) {
        String s = stripComments(sql);
        s = STRING_LIT.matcher(s).replaceAll("?");
        s = BIND.matcher(s).replaceAll("?");
        s = NUMBER.matcher(s).replaceAll("?");
        s = TextUtil.strip(TextUtil.rstrip(TextUtil.strip(WS.matcher(s).replaceAll(" ")), ";"));
        return s.toUpperCase(Locale.ROOT);
    }

    public static String fingerprint(String sql) {
        return sha256(normalize(sql)).substring(0, 16);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String statementType(String sql) {
        String s = TextUtil.lstrip(stripComments(sql), " \t\r\n({");
        if (CALL.matcher(sql).lookingAt()) {
            return "CALL";
        }
        if (CURSOR.matcher(s).lookingAt()) {
            return "SELECT";
        }
        Matcher word = WORD.matcher(s);
        if (!word.lookingAt()) {
            return "UNKNOWN";
        }
        String w = word.group().toUpperCase(Locale.ROOT);
        return switch (w) {
            case "WITH" -> "SELECT";
            case "CREATE", "ALTER", "DROP", "TRUNCATE", "GRANT", "REVOKE", "COMMENT" -> "DDL";
            case "FROM" -> "SELECT"; // HQL
            default -> w;
        };
    }

    public static List<String> vendorFeatures(String sql) {
        List<String> out = new ArrayList<>();
        for (Feature f : VENDOR_FEATURES) {
            if (f.rx().matcher(sql).find()) {
                out.add(f.name());
            }
        }
        return out;
    }

    public static String prepare(String sql) {
        return prepare(sql, false);
    }

    /**
     * Statement text a parser can read: no comments, no cursor declaration or embedded
     * {@code INTO :host} list, DB2 isolation clauses dropped and special registers in standard
     * form. Bind variables become {@code ?} unless {@code keepBinds} (then {@code :WS-NAME}
     * becomes {@code :WS_NAME}).
     */
    public static String prepare(String sql, boolean keepBinds) {
        String s = stripComments(sql);
        s = CURSOR.matcher(s).replaceAll("");
        if (keepBinds) {
            s = TextUtil.sub(MYBATIS_BIND, s, m -> ":" + (m.group(1) != null ? m.group(1) : m.group(2)));
            s = TextUtil.sub(COLON_BIND, s, m -> ":" + m.group(1).replace("-", "_"));
        } else {
            s = BIND.matcher(s).replaceAll("?");
        }
        s = statementType(s).equals("SELECT") ? SELECT_INTO.matcher(s).replaceAll(" ") : s;
        s = DB2_ISOLATION.matcher(TextUtil.rstrip(TextUtil.strip(s), ";")).replaceAll("");
        return TextUtil.sub(DB2_SPECIAL_REGISTER, s, m -> "CURRENT_" + m.group(1).toUpperCase(Locale.ROOT));
    }

    /** Tables, procedures and whether the statement was parsed. */
    public record Tables(List<String> tables, List<String> procedures, boolean parsed) {}

    public static Tables parseTables(String sql, String dialect) {
        Matcher m = CALL.matcher(sql);
        if (m.lookingAt()) {
            return new Tables(List.of(), List.of(m.group(4).toUpperCase(Locale.ROOT)), true);
        }
        String s = prepare(sql);
        List<String> parsed = SqlParse.tables(s, dialect);
        if (parsed != null) {
            return new Tables(parsed, List.of(), true);
        }
        TreeSet<String> tables = new TreeSet<>();
        Matcher t = REGEX_TABLES.matcher(s);
        while (t.find()) {
            if (!t.group(1).contains("?")) {
                tables.add(t.group(1).toUpperCase(Locale.ROOT));
            }
        }
        tables.removeAll(NOT_TABLES);
        return new Tables(new ArrayList<>(tables), List.of(), false);
    }

    // What comes just before a hole decides its kind: a value (bind-able) or part of the statement.
    private static final Pattern VALUE_BEFORE = Rx.of(
            "(?:'|=|<>|!=|<|>|\\s[-+/]|\\bLIKE|\\bBETWEEN|\\bBETWEEN\\s+\\S+\\s+AND|\\bLIMIT|\\bOFFSET|\\bTOP)\\s*$", Rx.I);
    private static final Pattern LIST_OPEN = Rx.of("\\b(?:IN|VALUES)\\s*\\($", Rx.I);

    /**
     * Kind of a run-time piece of SQL text, from the text just before it: "value" (after an
     * operator, inside quotes, in {@code IN (...)}/{@code VALUES (...)}) or "structural".
     */
    public static String holeKind(String before) {
        String text = TextUtil.rstrip(before);
        if (TextUtil.count(text, '\'') % 2 == 1 || VALUE_BEFORE.matcher(text).find()) {
            return "value";
        }
        int depth = 0;
        for (int i = text.length() - 1; i >= 0; i--) {
            char ch = text.charAt(i);
            if (ch == ')') {
                depth++;
            } else if (ch == '(') {
                if (depth == 0) {
                    return LIST_OPEN.matcher(text.substring(0, i + 1)).find() ? "value" : "structural";
                }
                depth--;
            }
        }
        return "structural";
    }

    public static Doc sqlFact(String file, int line, String text, String source, String dialect) {
        return sqlFact(file, line, text, source, dialect, false, false);
    }

    public static Doc sqlFact(String file, int line, String text, String source, String dialect, boolean dynamic, boolean hql) {
        String stype = statementType(text);
        Tables t = hql ? new Tables(hqlEntities(text), List.of(), true) : parseTables(text, dialect);
        String fp = fingerprint(text);
        return Doc.of(Schemas.SQL_STATEMENT,
                "id", Fact.makeId("sql", file, line, fp),
                "file", file,
                "line", (long) line,
                "type", stype,
                "source", source,
                "text", Rx.head(TextUtil.strip(text), MAX_TEXT),
                "tables", new ArrayList<>(t.tables()),
                "procedures", new ArrayList<>(t.procedures()),
                "dialect", hql ? "hql" : dialect,
                "dynamic", dynamic || text.contains("${"),
                "parsed", t.parsed(),
                "fingerprint", fp,
                "normalized", Rx.head(normalize(text), MAX_TEXT),
                "vendor_features", hql ? new ArrayList<>() : new ArrayList<>(vendorFeatures(text)),
                "evidence", new ArrayList<>(List.of(new Evidence(file, line, Rx.head(WS.matcher(TextUtil.strip(text)).replaceAll(" "), 120)))));
    }

    private static final Pattern HQL_ENTITY = Rx.of("\\b(?:FROM|JOIN|UPDATE|INTO)\\s+([A-Z]\\w*(?:\\.\\w+)*)", Rx.I);

    private static List<String> hqlEntities(String text) {
        TreeSet<String> out = new TreeSet<>(PyJson_ORDER);
        Matcher m = HQL_ENTITY.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        out.remove("SELECT");
        return new ArrayList<>(out);
    }

    /** Python's string order (by code point). */
    public static final java.util.Comparator<String> PyJson_ORDER = com.legacyaudit.model.json.PyJson::compareCodePoints;

    private static final Pattern PLSQL_START = Rx.of(
            "^\\s*(CREATE\\s+(OR\\s+REPLACE\\s+)?(PACKAGE|PROCEDURE|FUNCTION|TRIGGER|TYPE)\\b|DECLARE\\b|BEGIN\\b)", Rx.I);
    private static final Pattern SQLPLUS = Rx.of(
            "^\\s*(SET|SPOOL|PROMPT|WHENEVER|EXIT|QUIT|COLUMN|COL|TTITLE|BTITLE|BREAK|DEFINE|UNDEFINE|@@?|START|SHOW|CONNECT|REM(ARK)?)\\b",
            Rx.I);
    private static final Pattern LINE_COMMENT = Rx.of("--.*$");

    /** A statement of a script and its 1-based first line. */
    public record Statement(int line, String text) {}

    /**
     * Splits a SQL script into statements: {@code ;}, a lone {@code /} after PL/SQL blocks,
     * quotes and comments; lines starting with SQL*Plus commands are skipped.
     */
    public static List<Statement> splitStatements(String text) {
        List<Statement> out = new ArrayList<>();
        List<String> buf = new ArrayList<>();
        int start = 0;
        boolean inPlsql = false;
        boolean inComment = false;
        int no = 0;
        for (String line : TextUtil.splitLines(text)) {
            no++;
            String stripped = TextUtil.strip(line);
            if (buf.isEmpty()) {
                if (inComment) {
                    if (stripped.contains("*/")) {
                        inComment = false;
                    }
                    continue;
                }
                if (stripped.isEmpty() || stripped.startsWith("--")) {
                    continue;
                }
                if (stripped.startsWith("/*") && !stripped.contains("*/")) {
                    inComment = true;
                    continue;
                }
                if (stripped.startsWith("/*") && stripped.endsWith("*/")) {
                    continue;
                }
                if (stripped.equals("/") || SQLPLUS.matcher(line).lookingAt()) {
                    continue;
                }
                start = no;
                inPlsql = PLSQL_START.matcher(line).lookingAt();
            }
            if (inPlsql) {
                if (stripped.equals("/")) {
                    out.add(new Statement(start, String.join("\n", buf)));
                    buf = new ArrayList<>();
                    continue;
                }
                buf.add(line);
                continue;
            }
            buf.add(line);
            String code = STRING_LIT.matcher(LINE_COMMENT.matcher(line).replaceAll("")).replaceAll("''");
            if (TextUtil.rstrip(code).endsWith(";")) {
                out.add(new Statement(start, TextUtil.rstrip(TextUtil.rstrip(String.join("\n", buf)), ";")));
                buf = new ArrayList<>();
            }
        }
        if (!buf.isEmpty() && !TextUtil.strip(String.join("\n", buf)).isEmpty()) {
            out.add(new Statement(start, TextUtil.rstrip(TextUtil.rstrip(String.join("\n", buf)), ";")));
        }
        return out;
    }
}
