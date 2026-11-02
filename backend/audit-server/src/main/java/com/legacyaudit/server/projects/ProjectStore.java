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
    private static final String HIDDEN = ", hidden_reports, hidden_sections";

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
        return db.sql("SELECT " + COLUMNS + HIDDEN + " FROM project ORDER BY LOWER(title), id")
                .query((rs, n) -> new Project(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("owner"),
                        rs.getString("source_path"),
                        rs.getBoolean("archived"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        Map.copyOf(members.getOrDefault(rs.getString("id"), Map.of())),
                        split(rs.getString("hidden_reports")),
                        split(rs.getString("hidden_sections"))))
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

    private static List<String> split(String text) {
        return text == null || text.isBlank() ? List.of() : List.of(text.split(","));
    }

    /** Saves what the owner or an admin may change: title, description, owner, archived, hidden reports and sections. */
    public void update(Project p) {
        db.sql("UPDATE project SET title = ?, description = ?, owner = ?, archived = ?, hidden_reports = ?, hidden_sections = ? WHERE id = ?")
                .params(p.title(), p.description(), p.owner(), p.archived(), String.join(",", p.hiddenReports()),
                        String.join(",", p.hiddenSections()), p.id())
                .update();
    }

    /** Removes the project row with its members, scans, inventory and components. */
    public void delete(String id) {
        db.sql("DELETE FROM component WHERE scan_id IN (SELECT id FROM scan WHERE project_id = ?)").param(id).update();
        db.sql("DELETE FROM inventory_file WHERE scan_id IN (SELECT id FROM scan WHERE project_id = ?)").param(id).update();
        db.sql("DELETE FROM scan WHERE project_id = ?").param(id).update();
        db.sql("DELETE FROM project_member WHERE project_id = ?").param(id).update();
        db.sql("DELETE FROM project WHERE id = ?").param(id).update();
    }

    public void setMembers(String id, Map<String, String> members) {
        db.sql("DELETE FROM project_member WHERE project_id = ?").param(id).update();
        members.forEach((user, role) -> db.sql("INSERT INTO project_member (project_id, user_name, role) VALUES (?, ?, ?)")
                .params(id, user, role)
                .update());
    }
}
