package com.legacyaudit.server.projects;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Projects and their members in the project and project_member tables. */
@Repository
public class ProjectStore {

    private static final String COLUMNS = "id, title, description, owner, source_path, archived, created_at";

    private final JdbcClient db;

    public ProjectStore(JdbcClient db) {
        this.db = db;
    }

    public List<Project> all() {
        Map<String, Map<String, String>> members = new LinkedHashMap<>();
        db.sql("SELECT project_id, user_name, role FROM project_member ORDER BY user_name")
                .query(rs -> {
                    members.computeIfAbsent(rs.getString(1), k -> new LinkedHashMap<>()).put(rs.getString(2), rs.getString(3));
                });
        return db.sql("SELECT " + COLUMNS + " FROM project ORDER BY LOWER(title), id")
                .query((rs, n) -> new Project(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("owner"),
                        rs.getString("source_path"),
                        rs.getBoolean("archived"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        Map.copyOf(members.getOrDefault(rs.getString("id"), Map.of()))))
                .list();
    }

    public Optional<Project> find(String id) {
        return all().stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public boolean exists(String id) {
        return db.sql("SELECT COUNT(*) FROM project WHERE id = ?").param(id).query(Integer.class).single() > 0;
    }

    public Project create(String id, String title, String description, String owner, String sourcePath) {
        db.sql("INSERT INTO project (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, FALSE, ?)")
                .params(id, title, description, owner, sourcePath, OffsetDateTime.now(ZoneOffset.UTC))
                .update();
        return find(id).orElseThrow();
    }

    public void setMembers(String id, Map<String, String> members) {
        db.sql("DELETE FROM project_member WHERE project_id = ?").param(id).update();
        members.forEach((user, role) -> db.sql("INSERT INTO project_member (project_id, user_name, role) VALUES (?, ?, ?)")
                .params(id, user, role)
                .update());
    }
}
