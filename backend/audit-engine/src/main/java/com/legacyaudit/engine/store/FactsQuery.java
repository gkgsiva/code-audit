package com.legacyaudit.engine.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.sqlite.SQLiteConfig;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only queries over a run's {@code data/facts.sqlite}: rows of one entity with filters,
 * a text search and paging. Shared by the web app's fact browser and, later, the MCP server and
 * the {@code facts} command.
 */
public final class FactsQuery {

    public static final int MAX_LIMIT = 500;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path runDir;

    public FactsQuery(Path runDir) {
        this.runDir = runDir;
    }

    /** A query the caller got wrong (unknown kind or field), or a run without a facts database. */
    public static final class FactsError extends RuntimeException {
        public FactsError(String message) {
            super(message);
        }
    }

    private Connection db() throws SQLException {
        Path path = runDir.resolve("data").resolve("facts.sqlite");
        if (!Files.exists(path)) {
            throw new FactsError("No facts database at " + path + "; run 'legacy-audit scan' first");
        }
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setReadOnly(true);
        return DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath(), cfg.toProperties());
    }

    /** A JSON text cell (lists, objects) as its value. */
    static Object cell(Object value) {
        if (value instanceof String s && !s.isEmpty() && (s.charAt(0) == '[' || s.charAt(0) == '{')) {
            try {
                return JSON.readValue(s, Object.class);
            } catch (RuntimeException e) {
                return value;
            }
        }
        return value;
    }

    private static List<String> columns(Connection conn, String kind) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM pragma_table_info(?)")) {
            ps.setString(1, kind);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    /**
     * Rows of one entity. A filter value matches a column exactly; {@code ~text} matches rows
     * whose column contains the text (for list columns such as {@code tables}). {@code search}
     * keeps rows where any column contains the text; {@code offset} pages through the rest.
     */
    public Map<String, Object> query(String kind, Map<String, String> filters, int limit, int offset, String search) {
        return query(kind, new LinkedHashMap<>(filters), limit, offset, search, null);
    }

    /**
     * {@link #query(String, Map, int, int, String)} with filter values of any JSON type (as the MCP
     * tool gets them) and only the columns in {@code fields} (all when null or empty).
     */
    public Map<String, Object> query(String kind, Map<String, Object> filters, int limit, int offset, String search, List<String> fields) {
        if (!Summary.ENTITIES.contains(kind)) {
            throw new FactsError("Unknown kind '" + kind + "'; one of: " + String.join(", ", Summary.ENTITIES));
        }
        filters = filters == null ? Map.of() : filters;
        try (Connection conn = db()) {
            List<String> columns = columns(conn, kind);
            List<String> named = new ArrayList<>(filters.keySet());
            if (fields != null) {
                named.addAll(fields);
            }
            for (String name : named) {
                if (!columns.contains(name)) {
                    throw new FactsError("'" + name + "' is not a field of " + kind + ": " + String.join(", ", columns));
                }
            }
            List<String> where = new ArrayList<>();
            List<Object> args = new ArrayList<>();
            filters.forEach((col, value) -> {
                if (value instanceof String sv && sv.startsWith("~")) {
                    where.add("\"" + col + "\" LIKE ?");
                    args.add("%" + sv.substring(1) + "%");
                } else {
                    where.add("\"" + col + "\" = ?");
                    args.add(value);
                }
            });
            if (search != null && !search.isEmpty() && !columns.isEmpty()) {
                where.add("(" + String.join(" OR ", columns.stream().map(c -> "\"" + c + "\" LIKE ?").toList()) + ")");
                columns.forEach(c -> args.add("%" + search + "%"));
            }
            String sqlWhere = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
            int lim = Math.max(1, Math.min(limit, MAX_LIMIT));
            int off = Math.max(0, offset);
            long total;
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM \"" + kind + "\"" + sqlWhere)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getLong(1) : 0;
                }
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT " + (fields == null || fields.isEmpty() ? "*"
                    : String.join(", ", fields.stream().map(f -> "\"" + f + "\"").toList())) + " FROM \"" + kind + "\"" + sqlWhere + " LIMIT " + lim + " OFFSET " + off)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            row.put(md.getColumnName(i), cell(rs.getObject(i)));
                        }
                        rows.add(row);
                    }
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", kind);
            out.put("total", total);
            out.put("offset", off);
            out.put("returned", rows.size());
            out.put("rows", rows);
            if (kind.equals("batch_jobs")) {
                out.putAll(unscheduled(conn));
            }
            return out;
        } catch (SQLException e) {
            throw new FactsError("Cannot read the facts database: " + e.getMessage());
        }
    }

    private static void bind(PreparedStatement ps, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            ps.setObject(i + 1, args.get(i));
        }
    }

    /**
     * Batches no scheduler definition starts are batch components, not {@code batch_jobs} rows
     * (decision 116): say so next to the rows, so the count in {@code audit-summary.json}
     * (scheduled + unscheduled) is not read as a contradiction.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> unscheduled(Connection conn) throws SQLException {
        Map<String, Object> summary;
        try {
            summary = JSON.readValue(Files.readString(runDir.resolve("data").resolve("audit-summary.json"), StandardCharsets.UTF_8), Map.class);
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
        List<String> names = (List<String>) summary.getOrDefault("unscheduled_batches", List.of());
        if (names == null || names.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> ids = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM \"components\" WHERE name = ?")) {
            for (String name : names) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    ids.put(name, rs.next() ? rs.getString(1) : null);
                }
            }
        }
        Map<String, List<Map<String, Object>>> entries =
                (Map<String, List<Map<String, Object>>>) summary.getOrDefault("unscheduled_batch_entries", Map.of());
        Map<String, Object> counts = (Map<String, Object>) summary.getOrDefault("counts", Map.of());
        Map<String, Object> bj = counts == null ? Map.of() : (Map<String, Object>) counts.getOrDefault("batch_jobs", Map.of());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : names) {
            List<Map<String, Object>> items = entries == null ? List.of() : entries.getOrDefault(name, List.of());
            if (items.size() >= 2) { // one batch per entry point (decision 168)
                for (Map<String, Object> i : items) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("component", name);
                    r.put("entry_point", i.get("name"));
                    r.put("kind", i.get("kind"));
                    r.put("file", i.get("file"));
                    r.put("job_id", i.get("id"));
                    rows.add(r);
                }
            } else {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("component", name);
                r.put("job_id", ids.get(name));
                rows.add(r);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("note", "These rows are the jobs a scheduler definition starts (Autosys, JCL, cron, "
                + "Windows Task Scheduler, Hangfire...). counts.batch_jobs.total in "
                + "audit-summary.json (" + bj.getOrDefault("total", "?") + ") also counts batch work no "
                + "scheduler definition starts (" + bj.getOrDefault("unscheduled", rows.size()) + ", listed "
                + "below): one batch per entry point of a batch component (a top-level script, a "
                + "program or a Java main no script starts), or the component itself when it has "
                + "one or none. Each is a batch with no schedule found: document it with the "
                + "job_id given; what it runs are its steps.");
        out.put("unscheduled_batches", rows);
        return out;
    }
}
