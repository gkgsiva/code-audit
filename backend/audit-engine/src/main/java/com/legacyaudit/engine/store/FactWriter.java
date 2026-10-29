package com.legacyaudit.engine.store;

import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.json.PyJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Writes facts as JSON (the source of truth), one CSV per entity and one SQLite database. */
public final class FactWriter {

    /** Facts by entity (file stem in the data folder), in export order, with their CSV columns. */
    public record Entity(String name, List<String> columns, List<? extends Fact> items) {}

    private FactWriter() {}

    public static void writeJson(Path path, Object data) throws IOException {
        Files.writeString(path, PyJson.pretty(data), StandardCharsets.UTF_8);
    }

    /**
     * Facts with an id already used by an earlier fact of the same entity are left out, with a
     * warning each, so one wrong id never stops the run from being written (decision 178).
     */
    public static List<Entity> dropDuplicateIds(List<Entity> entities, List<String> warnings) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : entities) {
            Set<String> seen = new HashSet<>();
            List<Fact> kept = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            for (Fact f : e.items()) {
                if (f.id() != null && !seen.add(f.id())) {
                    dropped.add(f.id());
                    continue;
                }
                kept.add(f);
            }
            out.add(new Entity(e.name(), e.columns(), kept));
            if (!dropped.isEmpty()) {
                List<String> first = new ArrayList<>(new TreeSet<>(dropped)).subList(0, Math.min(5, new TreeSet<>(dropped).size()));
                warnings.add(dropped.size() + " " + e.name() + " row(s) had an id already used and were left out ("
                        + String.join(", ", first) + "). Please report this message with the run log.");
            }
        }
        return out;
    }

    public static void write(Path dataDir, List<Entity> entities) throws IOException {
        Files.createDirectories(dataDir);
        Path db = dataDir.resolve("facts.sqlite");
        Files.deleteIfExists(db);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath())) {
            conn.setAutoCommit(false);
            for (Entity e : entities) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Fact f : e.items()) {
                    rows.add(f.toMap());
                }
                writeJson(dataDir.resolve(e.name() + ".json"), rows);
                StringBuilder csv = new StringBuilder();
                csvRow(csv, e.columns());
                for (Map<String, Object> row : rows) {
                    List<String> cells = new ArrayList<>();
                    for (String c : e.columns()) {
                        cells.add(csvCell(row.get(c)));
                    }
                    csvRow(csv, cells);
                }
                Files.writeString(dataDir.resolve(e.name() + ".csv"), csv, StandardCharsets.UTF_8);
                String cols = String.join(", ", e.columns().stream().map(c -> "\"" + c + "\"").toList());
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE \"" + e.name() + "\" (" + cols
                            + (e.columns().contains("id") ? ", PRIMARY KEY (\"id\"))" : ")"));
                }
                String marks = String.join(", ", e.columns().stream().map(c -> "?").toList());
                try (PreparedStatement ins = conn.prepareStatement("INSERT INTO \"" + e.name() + "\" VALUES (" + marks + ")")) {
                    for (Map<String, Object> row : rows) {
                        for (int i = 0; i < e.columns().size(); i++) {
                            ins.setObject(i + 1, sqlValue(row.get(e.columns().get(i))));
                        }
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
            }
            conn.commit();
        } catch (SQLException ex) {
            throw new IOException("Cannot write " + db + ": " + ex.getMessage(), ex);
        }
    }

    private static Object sqlValue(Object v) {
        if (v instanceof Collection<?> || v instanceof Map<?, ?>) {
            return PyJson.compact(v);
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return v;
    }

    /** A CSV cell as the Python tool wrote it: evidence as {@code file:line;...}, lists joined by {@code ;}. */
    @SuppressWarnings("unchecked")
    static String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(v -> v instanceof Map<?, ?>)) {
            if (list.stream().allMatch(v -> ((Map<String, Object>) v).containsKey("file"))) { // evidence
                List<String> parts = new ArrayList<>();
                for (Object o : list) {
                    Map<String, Object> m = (Map<String, Object>) o;
                    Object line = m.get("line");
                    boolean hasLine = line instanceof Number n && n.longValue() != 0;
                    parts.add(hasLine ? m.get("file") + ":" + line : String.valueOf(m.get("file")));
                }
                return String.join(";", parts);
            }
            return PyJson.compact(list);
        }
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object o : list) {
                parts.add(o instanceof Boolean b ? (b ? "True" : "False") : String.valueOf(o));
            }
            return String.join(";", parts);
        }
        if (value instanceof Map<?, ?>) {
            return PyJson.compact(value);
        }
        if (value instanceof Double d) {
            return PyJson.compact(d);
        }
        return value.toString();
    }

    /** One row as Python's csv writer quotes it (minimal quoting, CRLF line ends). */
    private static void csvRow(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            String c = cells.get(i);
            boolean quote = c.indexOf(',') >= 0 || c.indexOf('"') >= 0 || c.indexOf('\r') >= 0 || c.indexOf('\n') >= 0
                    || (cells.size() == 1 && c.isEmpty());
            if (quote) {
                out.append('"').append(c.replace("\"", "\"\"")).append('"');
            } else {
                out.append(c);
            }
        }
        out.append("\r\n");
    }

    /** Columns from the first fact (all facts of an entity share them). */
    public static List<String> columnsOf(Fact sample) {
        return new ArrayList<>(sample.toMap().keySet());
    }

    static Map<String, Object> ordered() {
        return new LinkedHashMap<>();
    }
}
