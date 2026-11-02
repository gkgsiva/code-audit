package com.legacyaudit.server.admin;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * The activity log in the {@code activity} table (the Python hosted site kept
 * {@code activity.jsonl}): every entry has a time, a user, an action, a project and details.
 * Entries read back as flat maps with the details beside the four main fields, as before.
 */
@Repository
public class Activity {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    private final JdbcClient db;

    public Activity(JdbcClient db) {
        this.db = db;
    }

    /** Adds an entry; {@code detail} entries with a null value are kept (as JSON null). */
    public void log(String user, String action, String project, Map<String, ?> detail) {
        String json = detail == null || detail.isEmpty() ? null : JSON.writeValueAsString(detail);
        db.sql("INSERT INTO activity (at, user_name, action, project_id, detail) VALUES (?, ?, ?, ?, ?)")
                .params(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS), user, action, project, json)
                .update();
    }

    public void log(String user, String action, String project) {
        log(user, action, project, null);
    }

    /** The newest entries first, at most {@code limit}, of one project when given. */
    public List<Map<String, Object>> recent(int limit, String project) {
        String where = project == null || project.isEmpty() ? "" : " WHERE project_id = :p";
        var q = db.sql("SELECT at, user_name, action, project_id, detail FROM activity" + where + " ORDER BY id DESC LIMIT :n")
                .param("n", limit);
        if (!where.isEmpty()) {
            q = q.param("p", project);
        }
        return q.query((rs, n) -> row(rs.getObject(1, OffsetDateTime.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5))).list();
    }

    /** Entries at or after {@code start}, newest first. */
    public List<Map<String, Object>> since(OffsetDateTime start) {
        return db.sql("SELECT at, user_name, action, project_id, detail FROM activity WHERE at >= ? ORDER BY id DESC")
                .param(start)
                .query((rs, n) -> row(rs.getObject(1, OffsetDateTime.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5))).list();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(OffsetDateTime at, String user, String action, String project, String detail) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("time", at.withOffsetSameInstant(ZoneOffset.UTC).format(TIME));
        e.put("user", user);
        e.put("action", action);
        e.put("project", project);
        if (detail != null && !detail.isEmpty()) {
            Object d = JSON.readValue(detail, Object.class);
            if (d instanceof Map<?, ?> m) {
                ((Map<String, Object>) m).forEach(e::putIfAbsent);
            }
        }
        return e;
    }
}
