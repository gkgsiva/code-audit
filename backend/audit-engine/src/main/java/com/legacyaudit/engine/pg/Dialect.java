package com.legacyaudit.engine.pg;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The source dialects the Python tool read SQL in (sqlglot's names), with what differs between them. */
enum Dialect {
    DEFAULT, ORACLE, TSQL, MYSQL, POSTGRES;

    private static final List<String> NOT_ID_COMMON = List.of(
            "ALTER", "AND", "AS", "BETWEEN", "CLUSTER BY", "CONNECT BY", "CREATE", "CROSS", "DISTINCT", "DISTRIBUTE BY", "DROP",
            "ELSE", "EXCEPT", "FETCH", "FOR", "FROM", "GLOB", "GRANT", "GROUP BY", "GROUPING SETS", "HAVING", "ILIKE", "IN",
            "INNER", "INSERT", "INTERSECT", "INTO", "JOIN", "LATERAL", "LIKE", "NOT", "NOTNULL", "ON", "OR", "ORDER BY", "OUTER",
            "PARTITION BY", "PARTITIONED BY", "PARTITIONED_BY", "PRIMARY KEY", "QUALIFY", "REGEXP", "REVOKE", "RLIKE",
            "ROLLBACK", "SELECT", "SIMILAR TO", "SORT BY", "SQL SECURITY", "THEN", "UNCACHE", "UNION", "USING", "VALUES",
            "WHEN", "WHERE", "WITH", "XOR");

    /** Words that can never be a name (column, table, alias). */
    final Set<String> notId = new HashSet<>();
    /** Further words that cannot be a table alias. */
    final Set<String> notTableAlias = new HashSet<>();

    static {
        for (Dialect d : values()) {
            d.notId.addAll(NOT_ID_COMMON);
        }
        DEFAULT.notId.addAll(List.of("RETURNING", "STRAIGHT_JOIN", "TABLESAMPLE"));
        DEFAULT.notTableAlias.addAll(List.of("ANTI", "ASOF", "FULL", "LEFT", "LOCK", "NATURAL", "RIGHT", "SEMI", "WINDOW"));
        ORACLE.notId.addAll(List.of("BULK COLLECT INTO", "MINUS", "ORDER SIBLINGS BY", "RETURNING", "SYSTIMESTAMP"));
        ORACLE.notTableAlias.addAll(List.of("ANTI", "ASOF", "FULL", "LEFT", "LOCK", "MATCH_RECOGNIZE", "NATURAL", "RIGHT", "SAMPLE",
                "SEMI", "TABLESAMPLE", "WINDOW"));
        TSQL.notId.addAll(List.of("BEGIN", "OPTION", "TABLESAMPLE"));
        TSQL.notTableAlias.addAll(List.of("ASOF", "FULL", "LEFT", "LOCK", "NATURAL", "OUTPUT", "RETURNING", "RIGHT", "WINDOW"));
        MYSQL.notId.addAll(List.of("CHARSET", "DISTINCTROW", "FORCE", "IGNORE", "KEY", "MEMBER OF", "MOD", "RETURNING", "SEPARATOR",
                "STRAIGHT_JOIN", "TABLESAMPLE"));
        MYSQL.notTableAlias.addAll(List.of("ASOF", "FULL", "LEFT", "LOCK", "NATURAL", "RIGHT", "USE", "WINDOW"));
        POSTGRES.notId.addAll(List.of("ONLY", "RETURNING", "TABLESAMPLE", "VARIADIC"));
        POSTGRES.notTableAlias.addAll(List.of("ANTI", "ASOF", "FULL", "LEFT", "LOCK", "NATURAL", "RIGHT", "SEMI", "WINDOW"));
    }

    /** {@code sqlglot_dialect()} of the Python tool: the audit's dialect name to the parser's. */
    static Dialect of(String dialect) {
        if (dialect == null) {
            return DEFAULT;
        }
        return switch (dialect) {
            case "oracle" -> ORACLE;
            case "sqlserver", "tsql" -> TSQL;
            case "mysql" -> MYSQL;
            case "postgresql", "postgres" -> POSTGRES;
            default -> DEFAULT;
        };
    }

    /** NULLs sort after other values in ascending order (Oracle, PostgreSQL). */
    boolean nullsLarge() {
        return this == ORACLE || this == POSTGRES;
    }
}
