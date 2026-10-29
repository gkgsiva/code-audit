package com.legacyaudit.server.web;

import com.legacyaudit.model.AppInfo;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/health}: used by Docker and by the login page footer. */
@RestController
public class HealthController {

    private final JdbcClient db;

    public HealthController(JdbcClient db) {
        this.db = db;
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of(
                "status", "ok",
                "name", AppInfo.NAME,
                "version", AppInfo.version(),
                "database", databaseUp() ? "up" : "down");
    }

    private boolean databaseUp() {
        try {
            return db.sql("SELECT 1").query(Integer.class).single() == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
