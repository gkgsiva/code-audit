package com.legacyaudit.engine.pg;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes a parsed statement as PostgreSQL, the way the Python tool's SQL library generated it:
 * keyword case, {@code AS} before aliases, function and type names mapped from the source
 * dialect ({@code functions.tsv}, {@code types.tsv}, generated from that library) and the
 * rewrites it made (NVL to COALESCE, DECODE to CASE, TOP to LIMIT, ...).
 */
final class Writer {

    private static final Map<String, String> FUNCTIONS = new HashMap<>();
    private static final Map<String, String[]> TYPES = new HashMap<>();
    private static final Pattern INTERVAL_STRING = Pattern.compile("\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z]+)\\s*");
    private static final Pattern ARG = Pattern.compile("'X([1-8])'|\\b([xX])([1-8])\\b");
    private static final Set<String> TEMPORAL = Set.of("DATE", "TIMESTAMP", "TIMESTAMPTZ", "TIME", "DATETIME", "TIMESTAMPLTZ",
            "DATETIME2", "SMALLDATETIME", "DATETIMEOFFSET");
    private static final Set<String> NUMERIC = Set.of("INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT", "DECIMAL", "NUMERIC",
            "NUMBER", "FLOAT", "DOUBLE", "REAL", "DOUBLE PRECISION", "MONEY");

    static {
        load("functions.tsv.gz", (cols) -> FUNCTIONS.put(cols[0] + "|" + cols[1], cols[2]));
        load("types.tsv", (cols) -> TYPES.put(cols[0] + "|" + cols[1], new String[] {cols[2], cols[3]}));
    }

    private static void load(String name, java.util.function.Consumer<String[]> row) {
        try (InputStream raw = Writer.class.getResourceAsStream(name);
                InputStream in = name.endsWith(".gz") ? new java.util.zip.GZIPInputStream(raw) : raw;
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) {
                    row.accept(line.split("\t", -1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final Dialect d;

    private Writer(Dialect d) {
        this.d = d;
    }

    static String sqlOf(Dialect d, Node n) {
        return new Writer(d).sql(n);
    }

    private static String key(Dialect d) {
        return switch (d) {
            case DEFAULT -> "*";
            case ORACLE -> "oracle";
            case TSQL -> "tsql";
            case MYSQL -> "mysql";
            case POSTGRES -> "postgres";
        };
    }

    private String fn(String nameArgs) {
        String v = FUNCTIONS.get(key(d) + "|" + nameArgs);
        if (v == null && d != Dialect.DEFAULT) {
            v = FUNCTIONS.get("*|" + nameArgs);
        } else if ("-".equals(v)) {
            v = null;
        }
        return v;
    }

    private String[] type(String name) {
        String[] v = TYPES.get(key(d) + "|" + name);
        if (v == null) {
            v = TYPES.get("*|" + name);
        }
        return v == null || v[0].equals("-") ? null : v;
    }

    // ---- helpers -------------------------------------------------------------------------------

    private String list(List<Node> nodes) {
        StringBuilder sb = new StringBuilder();
        for (Node n : nodes) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(sql(n));
        }
        return sb.toString();
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static String ident(Node id) {
        if (id.is("ph")) {
            return "%s";
        }
        if (id.is("param")) {
            return "$" + id.s("name");
        }
        String name = id.s("name");
        if (id.b("quoted")) {
            return "\"" + name.replace("\"", "\"\"") + "\"";
        }
        return name;
    }

    static Failure unsupported() {
        return new Failure("UnsupportedError");
    }

    private static final class Failure extends Transpiler.Failure {
        Failure(String kind) {
            super(kind);
        }
    }

    // ---- dispatch ------------------------------------------------------------------------------

    String sql(Node n) {
        if (n == null) {
            return "";
        }
        return switch (n.kind) {
            case "command" -> n.s("rest").isEmpty() ? n.s("this") : n.s("this") + " " + n.s("rest");
            case "raw" -> n.s("text");
            case "select" -> select(n);
            case "union" -> union(n);
            case "values" -> values(n) + modifiers(n);
            case "sub" -> withClause(n) + "(" + sql(n.n("q")) + ")" + modifiers(n);
            case "tsub" -> "(" + sql(n.n("q")) + ")" + tableAlias(n);
            case "tparen" -> "(" + sql(n.n("t")) + joins(n.l("joins")) + ")" + tableAlias(n);
            case "lateral" -> "LATERAL " + sql(n.n("t")) + tableAlias(n);
            case "tfunc" -> sql(n.n("f")) + tableAlias(n);
            case "table" -> table(n);
            case "join" -> join(n);
            case "insert" -> insert(n);
            case "update" -> update(n);
            case "delete" -> delete(n);
            case "merge" -> merge(n);
            case "bin" -> binary(n);
            case "not" -> not(n);
            case "neg" -> "-" + sql(n.n("x"));
            case "bitnot" -> "~" + sql(n.n("x"));
            case "paren" -> "(" + sql(n.n("x")) + ")";
            case "tuple" -> "(" + list(n.l("items")) + ")";
            case "in" -> in(n);
            case "between" -> sql(n.n("x")) + " BETWEEN " + sql(n.n("lo")) + " AND " + sql(n.n("hi"));
            case "like" -> like(n, n.b("negated"));
            case "json" -> json(n);
            case "nextval" -> "NEXT VALUE FOR " + sql(n.n("seq"));
            case "is" -> sql(n.n("x")) + " IS " + sql(n.n("r"));
            case "isnotnull" -> sql(n.n("x")) + " IS NOT NULL";
            case "multiinsert" -> multiInsert(n);
            case "nullsafeeq" -> sql(n.n("l")) + " IS NOT DISTINCT FROM " + sql(n.n("r"));
            case "nullsafeneq" -> sql(n.n("l")) + " IS DISTINCT FROM " + sql(n.n("r"));
            case "exists" -> "EXISTS(" + sql(n.n("q")) + ")";
            case "any" -> anySql(n);
            case "array" -> "ARRAY[" + list(n.l("items")) + "]";
            case "convert" -> convert(n);
            case "prior" -> "PRIOR " + sql(n.n("x"));
            case "case" -> caseSql(n);
            case "cast" -> "CAST(" + sql(n.n("x")) + " AS " + typeSql(n.n("type")) + ")";
            case "castswap" -> "CAST(" + sql(n.n("a")) + " AS " + sql(n.n("b")) + ")";
            case "type" -> typeSql(n);
            case "func" -> func(n);
            case "dotfunc" -> sql(n.n("x")) + "." + func(n.n("f"));
            case "dot" -> sql(n.n("x")) + "." + sql(n.n("part"));
            case "window" -> windowSpec(n);
            case "ordered" -> ordered(n);
            case "interval" -> interval(n);
            case "typedlit" -> typedLiteral(n);
            case "extract" -> "EXTRACT(" + n.s("part") + " FROM " + sql(n.n("x")) + ")";
            case "substring" -> substring(n.n("x"), n.n("start"), n.n("len"));
            case "trim" -> trim(n);
            case "position" -> "POSITION(" + sql(n.n("sub")) + " IN " + sql(n.n("x")) + ")";
            case "col" -> column(n);
            case "id" -> ident(n);
            case "star" -> "*";
            case "str" -> quote(n.s("v"));
            case "nstr" -> "N" + quote(n.s("v"));
            case "hex" -> "x" + quote(n.s("v"));
            case "num" -> n.s("v");
            case "null" -> "NULL";
            case "true" -> "TRUE";
            case "false" -> "FALSE";
            case "ph" -> "%s";
            case "param" -> "$" + n.s("name");
            case "colonp" -> "%(" + n.s("name") + ")s";
            case "sessionvar" -> switch (d) {
                case MYSQL -> "@@" + n.s("name");
                case POSTGRES -> " @@ " + n.s("name");
                default -> "$$" + n.s("name");
            };
            case "concatlit" -> concatLiterals(n);
            case "alias" -> sql(n.n("x")) + " AS " + ident(n.n("name"));
            case "aliases" -> sql(n.n("x")) + " AS (" + identList(n.l("names")) + ")";
            case "kwarg" -> sql(n.n("k")) + " := " + sql(n.n("v"));
            case "argwords" -> argWords(n);
            case "joinmark" -> throw unsupported();
            case "index" -> index(n);
            case "collate" -> sql(n.n("x")) + " COLLATE " + sql(n.n("c"));
            case "grouping" -> grouping(n);
            case "cte" -> cte(n);
            case "ddl" -> n.s("text");
            default -> throw new IllegalStateException("no writer for " + n.kind);
        };
    }

    private String identList(List<Node> ids) {
        StringBuilder sb = new StringBuilder();
        for (Node id : ids) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(ident(id));
        }
        return sb.toString();
    }

    // ---- queries -------------------------------------------------------------------------------

    private String withClause(Node n) {
        List<Node> ctes = n.l("with");
        if (ctes.isEmpty()) {
            return "";
        }
        return "WITH " + (n.b("recursive") ? "RECURSIVE " : "") + list(ctes) + " ";
    }

    private String cte(Node n) {
        String cols = n.has("cols") ? "(" + identList(n.l("cols")) + ")" : "";
        return ident(n.n("name")) + cols + " AS (" + sql(n.n("q")) + ")";
    }

    private String select(Node s) {
        if (s.has("qualify")) {
            throw unsupported();
        }
        StringBuilder sb = new StringBuilder(withClause(s));
        sb.append("SELECT");
        for (Object m : s.has("modifiers") ? (List<?>) s.get("modifiers") : List.of()) {
            sb.append(" ").append(m);
        }
        if (s.has("distinctOn")) {
            sb.append(" DISTINCT ON (").append(list(s.l("distinctOn"))).append(")");
        } else if (s.b("distinct")) {
            sb.append(" DISTINCT");
        }
        if (!s.l("exprs").isEmpty()) {
            sb.append(" ").append(list(s.l("exprs")));
        }
        if (s.has("into")) {
            sb.append(" INTO ").append(sql(s.n("into")));
        }
        if (s.has("from")) {
            sb.append(" FROM ").append(sql(s.n("from")));
            sb.append(joins(s.l("joins")));
        }
        if (s.has("where")) {
            sb.append(" WHERE ").append(sql(s.n("where")));
        }
        if (s.has("startWith")) {
            sb.append(" START WITH ").append(sql(s.n("startWith")));
        }
        if (s.has("connectBy")) {
            sb.append(" CONNECT BY").append(s.b("nocycle") ? " NOCYCLE" : "").append(" ").append(sql(s.n("connectBy")));
        }
        if (s.b("groupAll")) {
            sb.append(" GROUP BY ALL");
        } else if (s.has("group")) {
            sb.append(" GROUP BY ").append(list(s.l("group")));
            if (s.b("withRollup")) {
                sb.append(" WITH ROLLUP");
            }
        }
        if (s.has("having")) {
            sb.append(" HAVING ").append(sql(s.n("having")));
        }
        if (s.has("windows")) {
            StringBuilder w = new StringBuilder();
            for (Node nw : s.l("windows")) {
                if (w.length() > 0) {
                    w.append(", ");
                }
                w.append(ident(nw.n("name"))).append(" AS (").append(windowSpec(nw.n("spec"))).append(")");
            }
            sb.append(" WINDOW ").append(w);
        }
        sb.append(modifiers(s));
        return sb.toString();
    }

    private String joins(List<Node> joins) {
        StringBuilder sb = new StringBuilder();
        for (Node j : joins) {
            sb.append(join(j));
        }
        return sb.toString();
    }

    private String join(Node j) {
        if (",".equals(j.s("kind"))) {
            return ", " + sql(j.n("t"));
        }
        StringBuilder sb = new StringBuilder(" ");
        if (j.b("natural")) {
            sb.append("NATURAL ");
        }
        if (j.s("side") != null) {
            sb.append(j.s("side")).append(" ");
        }
        String kind = j.s("kind");
        if (kind != null) {
            if (kind.equals("SEMI") || kind.equals("ANTI")) {
                kind = j.s("side") != null ? kind : kind;
            }
            sb.append(kind).append(" ");
        }
        sb.append("JOIN ").append(sql(j.n("t")));
        if (j.has("on")) {
            sb.append(" ON ").append(sql(j.n("on")));
        } else if (j.has("using")) {
            sb.append(" USING (").append(identList(j.l("using"))).append(")");
        }
        return sb.toString();
    }

    private String union(Node n) {
        String op = n.s("op");
        Object distinct = n.get("distinct");
        String kw = op;
        if (Boolean.FALSE.equals(distinct)) {
            kw += " ALL";
        }
        return withClause(n) + sql(n.n("l")) + " " + kw + " " + sql(n.n("r")) + modifiers(n);
    }

    private String values(Node n) {
        return "VALUES " + list(n.l("rows"));
    }

    private String modifiers(Node q) {
        StringBuilder sb = new StringBuilder();
        if (q.has("order")) {
            sb.append(q.b("siblings") ? " ORDER SIBLINGS BY " : " ORDER BY ").append(list(q.l("order")));
        }
        Node fetch = q.n("fetch");
        if (q.has("limit") && fetch == null) {
            sb.append(" LIMIT ").append(sql(q.n("limit")));
            if (q.b("limitPercent")) {
                sb.append(" PERCENT");
            }
            if (q.has("offset")) {
                sb.append(" OFFSET ").append(sql(q.n("offset")));
            }
        } else {
            if (q.has("offset")) {
                sb.append(" OFFSET ").append(sql(q.n("offset")));
            }
            if (fetch != null) {
                sb.append(" FETCH ").append(fetch.s("kind"));
                if (fetch.has("count")) {
                    sb.append(" ").append(sql(fetch.n("count")));
                }
                if (fetch.b("percent")) {
                    sb.append(" PERCENT");
                }
                sb.append(" ROWS ").append(fetch.s("with"));
            }
        }
        if (q.has("lock")) {
            Node lock = q.n("lock");
            sb.append(" ").append(lock.s("text"));
            if (lock.has("of")) {
                sb.append(" OF ").append(list(lock.l("of")));
            }
            if (lock.s("wait") != null) {
                String wait = lock.s("wait");
                if (wait.startsWith("WAIT")) {
                    throw unsupported();
                }
                sb.append(" ").append(wait);
            }
        }
        if (q.b("readOnly")) {
            throw unsupported();
        }
        return sb.toString();
    }

    private String ordered(Node o) {
        StringBuilder sb = new StringBuilder(sql(o.n("x")));
        Object desc = o.get("desc");
        boolean isDesc = Boolean.TRUE.equals(desc);
        boolean nullsFirst = o.b("nullsFirst");
        if (isDesc) {
            sb.append(" DESC");
        } else if (Boolean.FALSE.equals(desc)) {
            sb.append(" ASC");
        }
        // PostgreSQL sorts NULLs as larger than any value: say so only where that differs
        if (nullsFirst && !isDesc) {
            sb.append(" NULLS FIRST");
        } else if (!nullsFirst && isDesc) {
            sb.append(" NULLS LAST");
        }
        return sb.toString();
    }

    private String grouping(Node g) {
        return g.s("kind") + " (" + list(g.l("items")) + ")";
    }

    // ---- tables --------------------------------------------------------------------------------

    private String table(Node t) {
        StringBuilder sb = new StringBuilder();
        for (Node p : t.l("parts")) {
            if (sb.length() > 0) {
                sb.append(".");
            }
            sb.append(ident(p));
        }
        if (t.has("partition")) {
            sb.append(" PARTITION(").append(identList(t.l("partition"))).append(")");
        }
        if (t.has("asOf")) {
            Node a = t.n("asOf");
            sb.append(" FOR ").append(a.s("kind")).append(" AS OF ").append(sql(a.n("x")));
        }
        sb.append(tableAlias(t));
        if (t.b("pivot")) {
            throw unsupported();
        }
        if (t.has("sample")) {
            sb.append(" TABLESAMPLE (").append(sql(t.n("sample"))).append(")");
        }
        return sb.toString();
    }

    private String tableAlias(Node t) {
        Node a = t.n("alias");
        if (a == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" AS");
        if (a.has("name")) {
            sb.append(" ").append(ident(a.n("name")));
        }
        if (a.has("cols")) {
            sb.append(a.has("name") ? "" : " ").append("(").append(identList(a.l("cols"))).append(")");
        }
        return sb.toString();
    }

    // ---- DML -----------------------------------------------------------------------------------

    private String insert(Node n) {
        StringBuilder sb = new StringBuilder(withClause(n));
        sb.append(n.b("replace") ? "REPLACE " : "INSERT ").append(n.b("ignore") ? "IGNORE " : "")
                .append(n.b("overwrite") ? "OVERWRITE TABLE " : "INTO ").append(sql(n.n("table")));
        if (n.has("cols")) {
            sb.append(" (").append(list(n.l("cols"))).append(")");
        }
        if (n.has("values")) {
            sb.append(" ").append(values(n.n("values")));
        } else if (n.b("defaultValues")) {
            sb.append(" DEFAULT VALUES");
        } else {
            sb.append(" ").append(sql(n.n("q")));
        }
        if (n.has("dup")) {
            sb.append(" ON DUPLICATE KEY UPDATE SET ").append(list(n.l("dup")));
        }
        if (n.has("conflict")) {
            Node c = n.n("conflict");
            sb.append(" ON CONFLICT");
            if (c.has("cols")) {
                sb.append("(").append(list(c.l("cols"))).append(")");
            } else if (c.has("constraint")) {
                sb.append(" ON CONSTRAINT ").append(ident(c.n("constraint")));
            }
            if (c.has("where")) {
                sb.append(" WHERE ").append(sql(c.n("where")));
            }
            if (c.b("nothing")) {
                sb.append(" DO NOTHING");
            } else {
                sb.append(" DO UPDATE SET ").append(list(c.l("sets")));
                if (c.has("updateWhere")) {
                    sb.append(" WHERE ").append(sql(c.n("updateWhere")));
                }
            }
        }
        returning(n, sb);
        return sb.toString();
    }

    private String multiInsert(Node n) {
        StringBuilder sb = new StringBuilder("INSERT ").append(n.s("kind"));
        for (Node into : n.l("intos")) {
            if (into.has("when")) {
                sb.append(" WHEN ").append(sql(into.n("when"))).append(" THEN");
            } else if (into.b("else")) {
                sb.append(" ELSE");
            }
            sb.append(" INTO ").append(sql(into.n("table")));
            if (into.has("cols")) {
                sb.append(" (").append(list(into.l("cols"))).append(")");
            }
            if (into.has("values")) {
                sb.append(" ").append(values(into.n("values")));
            }
        }
        return sb.append(" ").append(sql(n.n("q"))).toString();
    }

    private void returning(Node n, StringBuilder sb) {
        if (n.has("returning")) {
            sb.append(" RETURNING ").append(list(n.l("returning")));
        }
    }

    private String update(Node n) {
        StringBuilder sb = new StringBuilder(withClause(n));
        sb.append("UPDATE ").append(sql(n.n("table"))).append(joins(n.l("joins")));
        sb.append(" SET");
        if (!n.l("sets").isEmpty()) {
            sb.append(" ").append(list(n.l("sets")));
        }
        if (n.has("from")) {
            sb.append(" FROM ").append(sql(n.n("from"))).append(joins(n.l("fromJoins")));
        }
        if (n.has("where")) {
            sb.append(" WHERE ").append(sql(n.n("where")));
        }
        returning(n, sb);
        sb.append(modifiers(n));
        return sb.toString();
    }

    private String delete(Node n) {
        StringBuilder sb = new StringBuilder(withClause(n));
        sb.append("DELETE ");
        if (n.has("targets")) {
            sb.append(list(n.l("targets"))).append(" ");
        }
        sb.append(n.b("from") ? "FROM " : "").append(sql(n.n("table"))).append(joins(n.l("joins")));
        if (n.has("using")) {
            sb.append(" USING ").append(sql(n.n("using"))).append(joins(n.l("usingJoins")));
        }
        if (n.has("where")) {
            sb.append(" WHERE ").append(sql(n.n("where")));
        }
        returning(n, sb);
        return sb.toString();
    }

    private String merge(Node n) {
        StringBuilder sb = new StringBuilder(withClause(n));
        sb.append("MERGE INTO ").append(sql(n.n("target"))).append(" USING ").append(sql(n.n("source")));
        sb.append(" ON ").append(sql(n.n("on")));
        for (Node w : n.l("whens")) {
            sb.append(" WHEN ").append(w.b("not") ? "NOT " : "").append("MATCHED");
            if (w.s("by") != null) {
                sb.append(" BY ").append(w.s("by"));
            }
            if (w.has("cond")) {
                sb.append(" AND ").append(sql(w.n("cond")));
            }
            sb.append(" THEN ");
            Node a = w.n("action");
            switch (a.kind) {
                case "mupdate" -> {
                    StringBuilder sets = new StringBuilder();
                    for (Node set : a.l("sets")) {
                        if (sets.length() > 0) {
                            sets.append(", ");
                        }
                        Node left = set.n("l");
                        if (left.is("col") && left.l("parts").size() > 1) {
                            List<Node> parts = left.l("parts");
                            left = Node.of("col", "parts", new ArrayList<>(List.of(parts.get(parts.size() - 1))));
                        }
                        sets.append(sql(left)).append(" = ").append(sql(set.n("r")));
                    }
                    sb.append("UPDATE SET ").append(sets);
                    if (a.has("where")) {
                        sb.append(" WHERE ").append(sql(a.n("where")));
                    }
                    if (a.has("deleteWhere")) {
                        sb.append(" DELETE WHERE ").append(sql(a.n("deleteWhere")));
                    }
                }
                case "minsert" -> {
                    sb.append("INSERT");
                    if (a.has("cols")) {
                        sb.append(" (").append(list(a.l("cols"))).append(")");
                    }
                    if (a.has("values")) {
                        sb.append(" ").append(values(a.n("values")));
                    }
                    if (a.has("where")) {
                        sb.append(" WHERE ").append(sql(a.n("where")));
                    }
                }
                default -> sb.append("DELETE");
            }
        }
        return sb.toString();
    }

    // ---- expressions ---------------------------------------------------------------------------

    private String anySql(Node n) {
        Node x = n.n("x");
        String kind = n.s("kind").equals("SOME") ? "ANY" : n.s("kind");
        if (n.s("kind").equals("SOME") || x.is("paren")) {
            return kind + (x.is("paren") ? "(" + sql(x.n("x")) + ")" : sql(x));
        }
        return kind + " " + sql(x);
    }

    /** SQL Server CONVERT(type, value[, style]): a cast, or TO_CHAR/TO_DATE with the style's format. */
    private String convert(Node n) {
        Node type = n.n("type");
        String tname = type.s("name");
        List<?> params = (List<?>) type.get("params");
        boolean textType = Set.of("VARCHAR", "NVARCHAR", "CHAR", "NCHAR").contains(tname);
        if (textType && (params == null || params.isEmpty())) {
            type = Node.of("type", "name", tname, "params", new ArrayList<>(List.of("30")), "text", type.s("text"));
        }
        Node style = n.n("style");
        String x = sql(n.n("x"));
        if (style != null && style.is("num") && style.s("v").matches("\\d+")) {
            String fmt = Formats.tsqlStyle(style.s("v"));
            if (fmt == null) {
                throw unsupported();
            }
            String pg = quote(Formats.fromStrftime(fmt));
            switch (tname) {
                case "DATE" -> {
                    return "TO_DATE(" + x + ", " + pg + ")";
                }
                case "DATETIME", "DATETIME2" -> {
                    return "TO_TIMESTAMP(" + x + ", " + pg + ")";
                }
                case "TEXT" -> {
                    return "TO_CHAR(" + x + ", " + pg + ")";
                }
                default -> {
                    if (textType) {
                        return "CAST(TO_CHAR(" + x + ", " + pg + ") AS " + typeSql(type) + ")";
                    }
                }
            }
        }
        return "CAST(" + x + " AS " + typeSql(type) + ")";
    }

    private static final Set<String> REAL_TYPES = Set.of("BIGDECIMAL", "DECFLOAT", "DECIMAL", "DOUBLE", "FLOAT", "MONEY",
            "SMALLMONEY", "NUMERIC", "NUMBER", "REAL", "DOUBLE PRECISION", "FLOAT4", "FLOAT8", "BINARY_DOUBLE", "BINARY_FLOAT");

    private static boolean realCast(Node n) {
        return n.is("cast") && REAL_TYPES.contains(n.n("type").s("name"));
    }

    private String binary(Node n) {
        String op = n.s("op");
        if (op.equals("/") && d != Dialect.TSQL && d != Dialect.POSTGRES) {
            // PostgreSQL divides integers as integers; the source did not
            Node ln = n.n("l");
            Node rn = n.n("r");
            String l = realCast(ln) || realCast(rn) ? sql(ln) : "CAST(" + sql(ln) + " AS DOUBLE PRECISION)";
            String r = d == Dialect.MYSQL ? "NULLIF(" + sql(rn) + ", 0)" : sql(rn);
            return l + " / " + r;
        }
        String l = sql(n.n("l"));
        String r = sql(n.n("r"));
        return switch (op) {
            case "<=>" -> l + " IS NOT DISTINCT FROM " + r;
            case "XOR" -> "(" + l + " AND (NOT " + r + ")) OR ((NOT " + l + ") AND " + r + ")";
            case "^" -> l + " # " + r;
            default -> l + " " + op + " " + r;
        };
    }

    private String not(Node n) {
        return "NOT " + sql(n.n("x"));
    }

    /** x -> 'path' / x ->> 'path': kept in PostgreSQL, else JSON_EXTRACT_PATH[_TEXT]. */
    private String json(Node n) {
        String x = sql(n.n("x"));
        Node path = n.n("path");
        if (d == Dialect.POSTGRES) {
            return x + " " + n.s("op") + " " + sql(path);
        }
        String fn = n.s("op").equals("->>") ? "JSON_EXTRACT_PATH_TEXT" : "JSON_EXTRACT_PATH";
        List<String> parts = null;
        if (path.is("str")) {
            String v = path.s("v");
            parts = v.startsWith("$") ? jsonPath(v) : List.of(v);
        }
        if (parts == null) {
            return fn + "(" + x + ", " + sql(path) + ")";
        }
        StringBuilder sb = new StringBuilder(fn).append("(").append(x);
        for (String part : parts) {
            sb.append(", ").append(quote(part));
        }
        return sb.append(")").toString();
    }

    private String like(Node n, boolean negate) {
        String op = n.s("op");
        String x = sql(n.n("x"));
        String p = sql(n.n("p"));
        String out = switch (op) {
            case "LIKE", "ILIKE" -> x + (negate ? " NOT " : " ") + op + " " + p;
            case "SIMILAR TO" -> x + (negate ? " NOT" : "") + " SIMILAR TO " + p;
            case "RLIKE", "REGEXP", "~" -> (negate ? "NOT " : "") + x + " ~ " + p;
            case "~*" -> (negate ? "NOT " : "") + x + " ~* " + p;
            case "!~" -> (negate ? "" : "NOT ") + x + " ~ " + p;
            case "!~*" -> (negate ? "" : "NOT ") + x + " ~* " + p;
            default -> (negate ? "NOT " : "") + x + " " + op + " " + p;
        };
        if (n.has("esc")) {
            out += " ESCAPE " + sql(n.n("esc"));
        }
        return out;
    }

    private String in(Node n) {
        String x = sql(n.n("x"));
        if (n.has("q")) {
            return x + " IN (" + sql(n.n("q")) + ")";
        }
        if (n.has("field")) {
            return x + " IN " + sql(n.n("field"));
        }
        return x + " IN (" + list(n.l("items")) + ")";
    }

    private String caseSql(Node n) {
        StringBuilder sb = new StringBuilder("CASE");
        if (n.has("op")) {
            sb.append(" ").append(sql(n.n("op")));
        }
        for (Node w : n.l("whens")) {
            sb.append(" WHEN ").append(sql(w.n("c"))).append(" THEN ").append(sql(w.n("r")));
        }
        if (n.has("else")) {
            sb.append(" ELSE ").append(sql(n.n("else")));
        }
        return sb.append(" END").toString();
    }

    private String column(Node c) {
        StringBuilder sb = new StringBuilder();
        for (Node p : c.l("parts")) {
            if (sb.length() > 0) {
                sb.append(".");
            }
            sb.append(sql(p));
        }
        return sb.toString();
    }

    private String index(Node n) {
        Node i = n.n("i");
        String idx = sql(i);
        if (d != Dialect.POSTGRES && i.is("num") && i.s("v").matches("-?\\d+")) {
            idx = String.valueOf(Long.parseLong(i.s("v")) + 1);
        }
        return sql(n.n("x")) + "[" + idx + "]";
    }

    private String concatLiterals(Node n) {
        StringBuilder sb = new StringBuilder();
        for (Node p : n.l("parts")) {
            if (sb.length() > 0) {
                sb.append(" || ");
            }
            sb.append(sql(p));
        }
        return sb.toString();
    }

    private String argWords(Node n) {
        StringBuilder sb = new StringBuilder(sql(n.n("x")));
        for (Object o : (List<?>) n.get("rest")) {
            Lexer.Tok t = (Lexer.Tok) o;
            sb.append(" ").append(t.type == Lexer.T.STRING ? quote(t.text) : t.text);
        }
        return sb.toString();
    }

    private String interval(Node n) {
        Node v = n.n("v");
        if (n.has("unitFunc")) {
            return "INTERVAL " + quote(name(v) + " " + func(n.n("unitFunc")));
        }
        String unit = n.s("unit");
        String value = name(v);
        if (v.is("str")) {
            Matcher m = INTERVAL_STRING.matcher(value);
            if (m.matches()) {
                // INTERVAL '90 days': the unit is in the string
                value = m.group(1);
                unit = m.group(2).toUpperCase(Locale.ROOT);
            }
        }
        if (value.isEmpty()) {
            return "INTERVAL" + (unit != null ? " " + unit : "");
        }
        if (unit == null) {
            return "INTERVAL " + quote(value);
        }
        if (unit.contains(" TO ")) {
            return "INTERVAL " + quote(value) + " " + unit;
        }
        return "INTERVAL " + quote(value + " " + unit);
    }

    /** sqlglot's {@code .name}: a column's or literal's text, empty for anything else. */
    private static String name(Node v) {
        return switch (v.kind) {
            case "num", "str", "nstr" -> v.s("v");
            case "col" -> v.l("parts").get(v.l("parts").size() - 1).s("name");
            case "id" -> v.s("name");
            default -> "";
        };
    }

    private String typedLiteral(Node n) {
        String type = n.s("type");
        String v = n.s("v");
        if (type.equals("TIMESTAMP") && d == Dialect.ORACLE) {
            return "TO_TIMESTAMP(" + quote(v) + ", 'YYYY-MM-DD HH24:MI:SS.US')";
        }
        String[] t = type(type);
        return "CAST(" + quote(v) + " AS " + (t != null ? t[0] : type) + ")";
    }

    private String substring(Node x, Node start, Node len) {
        StringBuilder sb = new StringBuilder("SUBSTRING(").append(sql(x));
        if (start != null) {
            sb.append(" FROM ").append(sql(start));
        }
        if (len != null) {
            sb.append(" FOR ").append(sql(len));
        }
        return sb.append(")").toString();
    }

    private String trim(Node n) {
        StringBuilder sb = new StringBuilder("TRIM(");
        if (n.s("where") != null) {
            sb.append(n.s("where")).append(" ");
        }
        if (n.has("chars")) {
            sb.append(sql(n.n("chars"))).append(" FROM ");
        } else if (n.s("where") != null) {
            sb.append("FROM ");
        }
        return sb.append(sql(n.n("x"))).append(")").toString();
    }

    private String typeSql(Node t) {
        String name = t.s("name");
        List<?> params = (List<?>) t.get("params");
        if (t.s("tz") != null && name.equals("TIMESTAMP")) {
            name = "TIMESTAMPTZ";
        }
        String[] map = type(name);
        if (params == null || params.isEmpty()) {
            if (map != null) {
                if (map[0].startsWith("!")) {
                    throw new Failure(map[0].substring(1));
                }
                return map[0];
            }
            if (d == Dialect.MYSQL) {
                throw new Failure("ParseError");
            }
            return t.has("text") ? t.s("text") : name;
        }
        String base = t.has("text") ? t.s("text") : name;
        if (map == null && d == Dialect.MYSQL) {
            throw new Failure("ParseError");
        }
        if (map != null) {
            if (map[1].startsWith("!")) {
                throw new Failure(map[1].substring(1));
            }
            base = map[1];
        }
        StringBuilder sb = new StringBuilder(base).append("(");
        for (int k = 0; k < params.size(); k++) {
            if (k > 0) {
                sb.append(", ");
            }
            sb.append(params.get(k));
        }
        return sb.append(")").toString();
    }

    // ---- windows -------------------------------------------------------------------------------

    private String windowSpec(Node w) {
        if (w.has("name") && !w.has("partition") && !w.has("order") && !w.has("frame") && !w.has("base")) {
            return ident(w.n("name"));
        }
        List<String> parts = new ArrayList<>();
        if (w.has("base")) {
            parts.add(ident(w.n("base")));
        }
        if (w.has("partition")) {
            parts.add("PARTITION BY " + list(w.l("partition")));
        }
        if (w.has("order")) {
            parts.add("ORDER BY " + list(w.l("order")));
        }
        if (w.has("frame")) {
            Node f = w.n("frame");
            String frame = f.s("kind") + " ";
            frame += f.has("end") ? "BETWEEN " + f.s("start") + " AND " + f.s("end") : f.s("start");
            if (f.s("exclude") != null) {
                frame += " EXCLUDE " + f.s("exclude");
            }
            parts.add(frame);
        }
        return String.join(" ", parts);
    }

    private String over(Node f, String call) {
        StringBuilder sb = new StringBuilder(call);
        if (f.has("keep")) {
            Node k = f.n("keep");
            sb.append(" KEEP (DENSE_RANK ").append(k.s("which")).append(" ORDER BY ").append(list(k.l("order"))).append(")");
        }
        if (f.has("within")) {
            sb.append(" WITHIN GROUP (ORDER BY ").append(list(f.l("within"))).append(")");
        }
        if (f.has("filter")) {
            sb.append(" FILTER(WHERE ").append(sql(f.n("filter"))).append(")");
        }
        if (f.s("nulls") != null) {
            sb.append(" ").append(f.s("nulls"));
        }
        if (f.has("over")) {
            Node w = f.n("over");
            if (w.has("name") && !w.has("partition") && !w.has("order") && !w.has("frame")) {
                sb.append(" OVER ").append(ident(w.n("name")));
            } else {
                sb.append(" OVER (").append(windowSpec(w)).append(")");
            }
        }
        return sb.toString();
    }

    // ---- functions -----------------------------------------------------------------------------

    private String func(Node f) {
        return over(f, call(f));
    }

    private String call(Node f) {
        String name = f.s("name");
        String up = name.toUpperCase(Locale.ROOT);
        List<Node> args = f.l("args");
        if (f.b("keepCase")) {
            return name + "(" + argList(f, args) + ")";
        }
        if (f.b("noparen")) {
            return switch (up) {
                case "SYSDATE" -> "CURRENT_TIMESTAMP";
                case "CURRENT_TIME" -> "CURRENT_TIME()";
                case "SYSTIMESTAMP" -> "SYSTIMESTAMP()";
                default -> up;
            };
        }
        if (f.has("prefix")) {
            StringBuilder sb = new StringBuilder();
            for (Node p : f.l("prefix")) {
                sb.append(ident(p)).append(".");
            }
            return sb + name + "(" + argList(f, args) + ")";
        }
        if (up.equals("LISTAGG") && d == Dialect.ORACLE || up.equals("STRING_AGG") || up.equals("GROUP_CONCAT")) {
            return stringAgg(f, up, args);
        }
        String special = special(f, up, args);
        if (special != null) {
            return special;
        }
        String base = up + "/" + args.size();
        String template = fn(base);
        if (template == null || args.stream().anyMatch(a -> a.is("argwords"))) {
            return up + "(" + argList(f, args) + ")";
        }
        String unit = null;
        String number = null;
        String format = null;
        String variant = null;
        for (int p = 0; p < args.size() && variant == null; p++) {
            Node a = args.get(p);
            if (simpleWord(a)) {
                String u = a.l("parts").get(0).s("name");
                String key = base + "/U" + (p + 1) + ":" + u.toLowerCase(Locale.ROOT);
                if (fn(key) != null) {
                    for (int q = 0; q < args.size(); q++) {
                        String num = numberText(args.get(q));
                        if (q != p && num != null && fn(key + "/N" + (q + 1)) != null) {
                            key = key + "/N" + (q + 1);
                            number = num;
                            break;
                        }
                    }
                    variant = key;
                    unit = u;
                }
            }
        }
        for (int p = 0; p < args.size() && variant == null; p++) {
            if (!simpleWord(args.get(p)) && !args.get(p).is("str") && fn(base + "/U" + (p + 1) + ":*") != null) {
                variant = base + "/U" + (p + 1) + ":*";
            }
        }
        for (int q = 0; q < args.size() && variant == null; q++) {
            String num = numberText(args.get(q));
            if (num != null && fn(base + "/N" + (q + 1)) != null) {
                variant = base + "/N" + (q + 1);
                number = num;
            }
        }
        for (int p = 0; p < args.size() && variant == null; p++) {
            Node a = args.get(p);
            if (a.is("str") && fn(base + "/F" + (p + 1)) != null && !Pattern.compile("X" + (p + 1) + "\\b").matcher(template).find()) {
                variant = base + "/F" + (p + 1);
                format = a.s("v");
            }
        }
        if (variant != null) {
            template = fn(variant);
        }
        if (template.startsWith("!")) {
            throw new Failure(template.substring(1));
        }
        String out = fill(template, f, args);
        if (unit != null) {
            out = out.replace("{u}", unit);
        }
        if (number != null) {
            out = out.replace("{n}", number);
            if (out.contains("{d}")) {
                // SQL Server reads a number as that many days after 1900-01-01
                out = out.replace("{d}", java.time.LocalDate.of(1900, 1, 1).plusDays(Long.parseLong(number)).toString());
            }
        }
        if (format != null) {
            out = out.replace("{fs}", quote(Formats.fromStrftime(format))).replace("{f}", quote(Formats.toPostgres(format, d)));
        }
        return out;
    }

    /** LISTAGG / STRING_AGG / GROUP_CONCAT: STRING_AGG(x, sep ORDER BY ...). */
    private String stringAgg(Node f, String up, List<Node> args) {
        List<Node> order = f.has("within") ? f.l("within") : f.l("innerOrder");
        f.set("within", null);
        StringBuilder sb = new StringBuilder("STRING_AGG(");
        if (f.b("distinct")) {
            sb.append("DISTINCT ");
        }
        Node x = args.isEmpty() ? null : args.get(0);
        if (up.equals("GROUP_CONCAT") && args.size() > 1) {
            x = Node.of("func", "name", "CONCAT", "args", args);
        }
        sb.append(x == null ? "" : sql(x));
        Node sep = f.has("sep") ? f.n("sep") : (up.equals("GROUP_CONCAT") ? Node.of("str", "v", ",") : (args.size() > 1 ? args.get(1) : null));
        if (sep != null) {
            sb.append(", ").append(sql(sep));
        }
        if (!order.isEmpty()) {
            sb.append(" ORDER BY ").append(list(order));
        }
        return sb.append(")").toString();
    }

    private String argList(Node f, List<Node> args) {
        String body = list(args);
        if (f.b("distinct")) {
            body = "DISTINCT " + body;
        }
        if (f.has("innerOrder")) {
            body += " ORDER BY " + list(f.l("innerOrder"));
        }
        return body;
    }

    /** Fill a generated template: {@code x1..x8} are the arguments, {@code X1}/'X1' units. */
    private String fill(String template, Node f, List<Node> args) {
        // DISTINCT / ORDER BY inside the call go with a plain NAME(x1, ...) template
        String plainPrefix = null;
        Matcher plain = Pattern.compile("([A-Z_][A-Z0-9_]*)\\((x1(, x\\d)*)?\\)").matcher(template);
        if (plain.matches() && (f.b("distinct") || f.has("innerOrder"))) {
            plainPrefix = plain.group(1);
            return plainPrefix + "(" + argList(f, args) + ")";
        }
        Matcher m = ARG.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String rep;
            if (m.group(1) != null) {
                rep = quote(unitText(args.get(Integer.parseInt(m.group(1)) - 1)));
            } else if (m.group(2).equals("X")) {
                Node a = args.get(Integer.parseInt(m.group(3)) - 1);
                rep = simpleWord(a) || a.is("str") ? unitText(a) : sql(a);
            } else {
                rep = sql(args.get(Integer.parseInt(m.group(3)) - 1));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean simpleWord(Node a) {
        return a.is("col") && a.l("parts").size() == 1 && !a.l("parts").get(0).b("quoted");
    }

    /** A number literal's text ((7) and -7 too), or null. */
    private static String numberText(Node a) {
        if (a.is("paren")) {
            return numberText(a.n("x"));
        }
        return a.is("num") ? a.s("v") : null;
    }

    private String unitText(Node a) {
        if (a.is("str")) {
            return a.s("v").toUpperCase(Locale.ROOT);
        }
        if (a.is("col") && a.l("parts").size() == 1) {
            return a.l("parts").get(0).s("name").toUpperCase(Locale.ROOT);
        }
        return sql(a);
    }

    /** The keys of a simple JSON path ({@code $.a.b[0]}), or null for anything else. */
    private static List<String> jsonPath(String path) {
        Matcher m = Pattern.compile("\\$((?:\\.[A-Za-z_][A-Za-z0-9_]*|\\[\\d+\\])*)").matcher(path);
        if (!m.matches()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        Matcher p = Pattern.compile("\\.([A-Za-z_][A-Za-z0-9_]*)|\\[(\\d+)\\]").matcher(m.group(1));
        while (p.find()) {
            parts.add(p.group(1) != null ? p.group(1) : p.group(2));
        }
        return parts;
    }

    // ---- simple type inference for the overloaded functions --------------------------------

    private boolean temporal(Node n) {
        if (n == null) {
            return false;
        }
        return switch (n.kind) {
            case "typedlit" -> true;
            case "cast" -> TEMPORAL.contains(n.n("type").s("name"));
            case "func" -> {
                String up = n.s("name").toUpperCase(Locale.ROOT);
                yield Set.of("SYSDATE", "SYSTIMESTAMP", "CURRENT_DATE", "CURRENT_TIMESTAMP", "LOCALTIMESTAMP", "TO_DATE",
                        "TO_TIMESTAMP", "ADD_MONTHS", "LAST_DAY", "NOW", "GETDATE").contains(up) && !n.b("keepCase");
            }
            case "paren" -> temporal(n.n("x"));
            case "bin" -> (n.s("op").equals("+") || n.s("op").equals("-")) && temporal(n.n("l"));
            default -> false;
        };
    }

    private static final Set<String> DOUBLE_FUNCS = Set.of("AVG", "SQRT", "STDDEV", "STDDEV_POP", "STDDEV_SAMP", "VARIANCE",
            "VAR_POP", "VAR_SAMP", "EXP", "LN", "LOG", "LOG10", "POWER", "POW", "CBRT", "CORR", "COVAR_POP", "COVAR_SAMP",
            "PERCENT_RANK", "CUME_DIST", "RAND", "RANDOM", "SIN", "COS", "TAN", "ASIN", "ACOS", "ATAN", "ATAN2", "DEGREES",
            "RADIANS", "PI");

    /** The SQL library's inferred number type: INT, BIGINT, DOUBLE, or null when unknown. */
    private String numType(Node n) {
        switch (n.kind) {
            case "num" -> {
                return n.s("v").matches(".*[.eE].*") ? "DOUBLE" : "INT";
            }
            case "paren" -> {
                return numType(n.n("x"));
            }
            case "cast" -> {
                String t = n.n("type").s("name");
                return Set.of("DOUBLE", "DOUBLE PRECISION", "FLOAT8").contains(t) ? "DOUBLE"
                        : Set.of("INT", "INTEGER", "BIGINT", "SMALLINT").contains(t) ? "INT" : null;
            }
            case "func" -> {
                String up = n.s("name").toUpperCase(Locale.ROOT);
                if (n.b("keepCase")) {
                    return null;
                }
                if (DOUBLE_FUNCS.contains(up)) {
                    return "DOUBLE";
                }
                return up.equals("COUNT") ? "BIGINT" : (up.equals("LENGTH") ? "INT" : null);
            }
            case "bin" -> {
                String op = n.s("op");
                if (!Set.of("+", "-", "*", "/").contains(op)) {
                    return null;
                }
                String l = numType(n.n("l"));
                String r = numType(n.n("r"));
                if (l == null || r == null) {
                    return null;
                }
                if (op.equals("/") && d != Dialect.TSQL && d != Dialect.POSTGRES) {
                    return "DOUBLE";
                }
                return l.equals("DOUBLE") || r.equals("DOUBLE") ? "DOUBLE" : (l.equals("BIGINT") || r.equals("BIGINT") ? "BIGINT" : "INT");
            }
            default -> {
                return null;
            }
        }
    }

    private boolean numeric(Node n) {
        if (n == null) {
            return false;
        }
        return switch (n.kind) {
            case "num" -> true;
            case "neg" -> numeric(n.n("x"));
            case "cast" -> NUMERIC.contains(n.n("type").s("name"));
            default -> false;
        };
    }

    private static boolean text(Node n) {
        return n != null && (n.is("str") || n.is("nstr"));
    }

    /** Functions whose conversion depends on the argument kinds, not only on their number. */
    private String special(Node f, String up, List<Node> args) {
        switch (up) {
            case "DECODE" -> {
                if (args.size() < 3) {
                    return null;
                }
                String x = sql(args.get(0));
                StringBuilder sb = new StringBuilder("CASE");
                int k = 1;
                for (; k + 1 < args.size(); k += 2) {
                    Node search = args.get(k);
                    String cond;
                    if (search.is("null")) {
                        cond = x + " IS NULL";
                    } else if (search.is("num") && !search.s("v").startsWith("-") || search.is("str") || search.is("nstr")) {
                        cond = x + " = " + sql(search);
                    } else {
                        String s = sql(search);
                        cond = x + " = " + s + " OR (" + x + " IS NULL AND " + s + " IS NULL)";
                    }
                    sb.append(" WHEN ").append(cond).append(" THEN ").append(sql(args.get(k + 1)));
                }
                if (k < args.size()) {
                    sb.append(" ELSE ").append(sql(args.get(k)));
                }
                return sb.append(" END").toString();
            }
            case "TRUNC" -> {
                if (d != Dialect.ORACLE || args.isEmpty() || args.size() > 2) {
                    return null;
                }
                Node x = args.get(0);
                Node unit = args.size() > 1 ? args.get(1) : null;
                if ((temporal(x) && true) || text(unit)) {
                    String u = unit == null ? "'DD'" : (text(unit) ? quote(unit.s("v").toUpperCase(Locale.ROOT)) : sql(unit));
                    return "DATE_TRUNC(" + u + ", " + sql(x) + ")";
                }
                if (numeric(x) || numeric(unit)) {
                    return "TRUNC(" + list(args) + ")";
                }
                return "TRUNC(" + list(args) + ")";
            }
            case "REGEXP_LIKE" -> {
                if (args.size() == 3 && args.get(2).is("str") && args.get(2).s("v").equals("i")) {
                    return sql(args.get(0)) + " ~* " + sql(args.get(1));
                }
                return null;
            }
            case "JSON_VALUE", "JSON_QUERY" -> {
                if (args.size() != 2 || !args.get(1).is("str")) {
                    return null;
                }
                List<String> path = jsonPath(args.get(1).s("v"));
                if (path == null) {
                    return null;
                }
                if (d == Dialect.TSQL) {
                    String fn = up.equals("JSON_VALUE") ? "JSON_EXTRACT_PATH_TEXT" : "JSON_EXTRACT_PATH";
                    if (path.isEmpty()) {
                        return fn + "(" + sql(args.get(0)) + ", VARIADIC '{}')";
                    }
                    StringBuilder sb = new StringBuilder(fn).append("(").append(sql(args.get(0)));
                    for (String part : path) {
                        sb.append(", ").append(quote(part));
                    }
                    return sb.append(")").toString();
                }
                if ((d == Dialect.ORACLE || d == Dialect.MYSQL) && up.equals("JSON_VALUE") && !path.isEmpty()) {
                    return "JSON_VALUE(" + sql(args.get(0)) + ", " + quote(String.join("", path)) + ")";
                }
                return null;
            }
            case "STR_TO_DATE" -> {
                if (d != Dialect.MYSQL || args.size() != 2 || !args.get(1).is("str")) {
                    return null;
                }
                String fmt = args.get(1).s("v");
                boolean time = false;
                for (int k = 0; k + 1 < fmt.length(); k++) {
                    if (fmt.charAt(k) == '%') {
                        k++;
                        if ("fHhIiklprSsT".indexOf(fmt.charAt(k)) >= 0) {
                            time = true;
                            break;
                        }
                    }
                }
                return (time ? "TO_TIMESTAMP(" : "TO_DATE(") + sql(args.get(0)) + ", " + quote(Formats.toPostgres(fmt, d)) + ")";
            }
            case "DATE_ADD", "DATE_SUB" -> {
                if (args.size() == 2 && args.get(1).is("interval")) {
                    return sql(args.get(0)) + (up.equals("DATE_ADD") ? " + " : " - ") + sql(args.get(1));
                }
                return null;
            }
            case "ROUND" -> {
                if (args.size() == 2 && "DOUBLE".equals(numType(args.get(0)))) {
                    return "ROUND(CAST(" + sql(args.get(0)) + " AS DECIMAL), " + sql(args.get(1)) + ")";
                }
                return null;
            }
            case "COUNT" -> {
                if (args.size() == 1 && args.get(0).is("star")) {
                    return "COUNT(*)";
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }
}
