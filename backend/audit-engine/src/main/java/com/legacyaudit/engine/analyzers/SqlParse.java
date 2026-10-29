package com.legacyaudit.engine.analyzers;

import com.legacyaudit.engine.inventory.Rx;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import net.sf.jsqlparser.parser.CCJSqlParser;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.UnsupportedStatement;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.sequence.CreateSequence;
import net.sf.jsqlparser.statement.grant.Grant;
import net.sf.jsqlparser.util.TablesNamesFinder;

/**
 * The tables a SQL statement reads or writes, from JSqlParser: {@code schema.table} in upper
 * case (a catalog or database part is dropped), common table expressions and bind placeholders
 * left out. Null when the statement cannot be parsed. A few rules make the result the one the
 * Python tool's parser (sqlglot) gave: syntax a dialect does not have is not parsed, a bind
 * variable may stand for a table, and Oracle's RETURNING ... INTO and INSERT ALL are read.
 */
final class SqlParse {

    private SqlParse() {}

    private static final String HOLE = "LA_HOLE_";
    private static final Pattern HOLE_TABLE = Rx.of("(\\b(?:FROM|JOIN|INTO|UPDATE|TABLE)\\s+)\\?", Rx.I);
    private static final Pattern HOLE_SUFFIX = Rx.of("(\\b(?:FROM|JOIN|INTO|UPDATE|TABLE)\\s+[\\w.$#]+)\\?", Rx.I);
    private static final Pattern HOLE_QUALIFIER = Rx.of("\\?\\.(?=\\w)");
    private static final Pattern RETURNING = Rx.of("\\bRETURNING\\b[\\s\\S]*$", Rx.I);
    private static final Pattern INSERT_MULTI = Rx.of("^\\s*INSERT\\s+(ALL|FIRST)\\b", Rx.I);
    private static final Pattern MULTI_TABLES = Rx.of("\\b(?:INTO|FROM|JOIN)\\s+([A-Za-z_][\\w$#]*(?:\\.[A-Za-z_][\\w$#]*)?)", Rx.I);
    private static final Pattern MINUS = Rx.of("\\bMINUS\\b", Rx.I);
    private static final Pattern TOP = Rx.of("^\\s*SELECT\\s+(DISTINCT\\s+)?TOP\\s+\\d", Rx.I);
    private static final Pattern AMP_VAR = Rx.of("&\\w");
    private static final Pattern HEX = Rx.of("\\bX'", Rx.I);
    private static final Pattern REPLACE = Rx.of("^\\s*REPLACE\\b", Rx.I);
    private static final Pattern DEFAULT_VALUES = Rx.of("\\bDEFAULT\\s+VALUES\\b", Rx.I);
    private static final Pattern FOR_UPDATE = Rx.of("\\bFOR\\s+UPDATE\\b", Rx.I);
    private static final Pattern HASH_NAME = Rx.of("(^|[\\s,(])#");
    private static final Pattern TILDE = Rx.of("~");

    /** The dialect the Python tool gave its parser: DB2, Sybase and Informix read as generic SQL. */
    static String parserDialect(String dialect) {
        if (dialect == null) {
            return "";
        }
        return switch (dialect) {
            case "db2", "generic", "sybase", "informix" -> "";
            default -> dialect;
        };
    }

    private static final int CACHE_SIZE = 4096;

    /** Recently parsed statements: the same text is often parsed for several facts and files. */
    private static final Map<String, Optional<List<String>>> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Optional<List<String>>> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    /** Tables the statement reads or writes (upper case, sorted), or null when it does not parse. */
    static List<String> tables(String sql, String sqlDialect) {
        String key = sqlDialect + '\0' + sql;
        Optional<List<String>> hit = CACHE.get(key);
        if (hit == null) {
            List<String> found = parseTables(sql, sqlDialect);
            hit = Optional.ofNullable(found == null ? null : List.copyOf(found));
            CACHE.put(key, hit);
        }
        return hit.map(ArrayList::new).orElse(null);
    }

    private static List<String> parseTables(String sql, String sqlDialect) {
        String dialect = parserDialect(sqlDialect);
        String code = SqlTools.STRING_LIT.matcher(sql).replaceAll("''");
        if (!dialectHas(code, dialect)) {
            return null;
        }
        if ((dialect.isEmpty() || dialect.equals("postgresql")) && code.indexOf('@') >= 0 && Rx.of("^\\s*INSERT\\b", Rx.I).matcher(code).lookingAt()) {
            return null;
        }
        if (INSERT_MULTI.matcher(sql).lookingAt()) {
            TreeSet<String> out = new TreeSet<>();
            var m = MULTI_TABLES.matcher(code);
            while (m.find()) {
                out.add(m.group(1).toUpperCase(Locale.ROOT));
            }
            out.removeAll(SqlTools.NOT_TABLES);
            return new ArrayList<>(out);
        }
        String s = HOLE_TABLE.matcher(sql).replaceAll("$1" + HOLE);
        s = HOLE_SUFFIX.matcher(s).replaceAll("$1 " + HOLE);
        s = HOLE_QUALIFIER.matcher(s).replaceAll(HOLE + ".");
        s = RETURNING.matcher(s).replaceFirst("");
        // Simple parsing first: much faster, and complex parsing only for what it cannot read.
        Statement st = parse(s, dialect, false);
        if (st == null) {
            st = parse(s, dialect, true);
        }
        if (st == null || st instanceof UnsupportedStatement) {
            return null;
        }
        Set<String> names;
        Finder finder = new Finder(dialect.isEmpty() || dialect.equals("postgresql"));
        try {
            names = switch (st) {
                case CreateIndex ci -> Set.of(finder.name(ci.getTable()));
                case CreateSequence cs -> Set.of(cs.getSequence().getName());
                case Grant g -> g.getObjectName() == null ? Set.of() : Set.of(g.getObjectName());
                default -> finder.getTables(st);
            };
        } catch (RuntimeException e) {
            return null;
        }
        TreeSet<String> out = new TreeSet<>();
        for (String n : names) {
            if (n == null || n.isEmpty() || n.contains("?") || n.contains(HOLE)) {
                continue;
            }
            if ("sqlserver".equals(dialect)) {
                n = TextUtil.lstrip(n, "#");
            }
            out.add(n.toUpperCase(Locale.ROOT));
        }
        out.removeAll(SqlTools.NOT_TABLES);
        return new ArrayList<>(out);
    }

    private static Statement parse(String sql, String dialect, boolean complex) {
        try {
            return CCJSqlParserUtil.newParser(sql)
                    .withSquareBracketQuotation("sqlserver".equals(dialect))
                    .withAllowComplexParsing(complex)
                    .Statement();
        } catch (Exception | Error e) {
            return null;
        }
    }

    /** False for syntax the dialect does not have (the Python tool's parser rejected it). */
    private static boolean dialectHas(String code, String dialect) {
        String d = dialect;
        if (code.indexOf('`') >= 0 && !d.equals("mysql")) {
            return false;
        }
        if (code.contains("::") && !d.equals("postgresql")) {
            return false;
        }
        if (HASH_NAME.matcher(code).find() && !d.equals("sqlserver")) {
            return false;
        }
        if (code.contains(":?") || AMP_VAR.matcher(code).find() || REPLACE.matcher(code).lookingAt()) {
            return false;
        }
        if ((HEX.matcher(code).find() && !d.equals("postgresql") && !d.equals("mysql"))
                || (DEFAULT_VALUES.matcher(code).find() && !d.isEmpty() && !d.equals("sqlserver"))) {
            return false;
        }
        if (FOR_UPDATE.matcher(code).find() && d.equals("sqlserver")) {
            return false;
        }
        if (TILDE.matcher(code).find() && !d.equals("postgresql")) {
            return false;
        }
        if (MINUS.matcher(code).find() && !d.equals("oracle")) {
            return false;
        }
        if (TOP.matcher(code).find() && !d.equals("sqlserver") && !d.equals("oracle")) {
            return false;
        }
        return true;
    }

    private static final class Finder extends TablesNamesFinder<Void> {
        private final boolean dropLink;

        Finder(boolean dropLink) {
            this.dropLink = dropLink;
        }

        String name(Table table) {
            return extractTableName(table);
        }

        @Override
        protected String extractTableName(Table table) {
            String name = table.getUnquotedName();
            if (name == null || name.isEmpty()) {
                return "";
            }
            String schema = table.getUnquotedSchemaName();
            String full = schema != null && !schema.isEmpty() ? schema + "." + name : name;
            String fq = table.getFullyQualifiedName();
            int at = fq.lastIndexOf('@');
            return at >= 0 && !dropLink ? full + fq.substring(at) : full;
        }
    }
}
