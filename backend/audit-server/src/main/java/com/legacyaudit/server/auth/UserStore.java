package com.legacyaudit.server.auth;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Users in the app_user table. */
@Repository
public class UserStore {

    private final JdbcClient db;

    public UserStore(JdbcClient db) {
        this.db = db;
    }

    public List<User> all() {
        return db.sql("SELECT name, role, disabled, created_at, last_login FROM app_user ORDER BY LOWER(name)")
                .query((rs, n) -> new User(
                        rs.getString("name"),
                        rs.getString("role"),
                        rs.getBoolean("disabled"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("last_login", OffsetDateTime.class)))
                .list();
    }

    /** Creates or changes a user's role and disabled flag (the admin page). */
    public User save(String name, String role, boolean disabled) {
        int updated = db.sql("UPDATE app_user SET role = ?, disabled = ? WHERE name = ?").params(role, disabled, name).update();
        if (updated == 0) {
            db.sql("INSERT INTO app_user (name, role, disabled, created_at) VALUES (?, ?, ?, ?)")
                    .params(name, role, disabled, OffsetDateTime.now(ZoneOffset.UTC))
                    .update();
        }
        return find(name).orElseThrow();
    }

    public void delete(String name) {
        db.sql("DELETE FROM app_user WHERE name = ?").param(name).update();
    }

    public Optional<User> find(String name) {
        return db.sql("SELECT name, role, disabled, created_at, last_login FROM app_user WHERE name = ?")
                .param(name)
                .query((rs, n) -> new User(
                        rs.getString("name"),
                        rs.getString("role"),
                        rs.getBoolean("disabled"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("last_login", OffsetDateTime.class)))
                .optional();
    }

    /** Creates the user or updates the role, and stamps the login time. */
    public User recordLogin(String name, String role) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int updated = db.sql("UPDATE app_user SET role = ?, last_login = ? WHERE name = ?")
                .params(role, now, name)
                .update();
        if (updated == 0) {
            db.sql("INSERT INTO app_user (name, role, disabled, created_at, last_login) VALUES (?, ?, FALSE, ?, ?)")
                    .params(name, role, now, now)
                    .update();
        }
        return find(name).orElseThrow();
    }
}
