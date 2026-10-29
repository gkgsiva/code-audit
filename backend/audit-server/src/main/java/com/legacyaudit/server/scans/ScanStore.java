package com.legacyaudit.server.scans;

import com.legacyaudit.model.facts.Component;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.RunInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Scans and their inventory and components in PostgreSQL. */
@Repository
public class ScanStore {

    private static final String COLUMNS = "id, project_id, status, full_scan, started_by, queued_at, started_at, finished_at, run_id, "
            + "files, analyzed, cached, components, loc_code, warnings, error";

    private final JdbcClient db;
    private final JdbcTemplate jdbc;

    public ScanStore(JdbcClient db, JdbcTemplate jdbc) {
        this.db = db;
        this.jdbc = jdbc;
    }

    public long queue(String projectId, String user, boolean full) {
        var keys = new GeneratedKeyHolder();
        db.sql("INSERT INTO scan (project_id, status, full_scan, started_by, queued_at) VALUES (?, ?, ?, ?, ?)")
                .params(projectId, Scan.QUEUED, full, user, now())
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    public Optional<Scan> find(long id) {
        return db.sql("SELECT " + COLUMNS + " FROM scan WHERE id = ?").param(id).query(ScanStore::row).optional();
    }

    public List<Scan> list(String projectId, int limit) {
        return db.sql("SELECT " + COLUMNS + " FROM scan WHERE project_id = ? ORDER BY id DESC LIMIT " + limit)
                .param(projectId)
                .query(ScanStore::row)
                .list();
    }

    public Optional<Scan> active(String projectId) {
        return db.sql("SELECT " + COLUMNS + " FROM scan WHERE project_id = ? AND status IN ('queued', 'running') ORDER BY id DESC")
                .param(projectId)
                .query(ScanStore::row)
                .list()
                .stream()
                .findFirst();
    }

    /** The newest finished scan, whose inventory the screens show. */
    public Optional<Scan> latestDone(String projectId) {
        return db.sql("SELECT " + COLUMNS + " FROM scan WHERE project_id = ? AND status = 'done' ORDER BY id DESC")
                .param(projectId)
                .query(ScanStore::row)
                .list()
                .stream()
                .findFirst();
    }

    public void running(long id) {
        db.sql("UPDATE scan SET status = ?, started_at = ? WHERE id = ?").params(Scan.RUNNING, now(), id).update();
    }

    public void ended(long id, String status, String error, String logTail) {
        db.sql("UPDATE scan SET status = ?, finished_at = ?, error = ?, log_tail = ? WHERE id = ?")
                .params(status, now(), cut(error, 4000), logTail, id)
                .update();
    }

    /** Stores the run's counts, files and components and marks the scan done. */
    @Transactional
    public void done(long id, RunInfo run, List<FileRecord> files, List<Component> components, String logTail) {
        long loc = files.stream().mapToLong(f -> f.locCode).sum();
        db.sql("UPDATE scan SET status = ?, finished_at = ?, run_id = ?, files = ?, analyzed = ?, cached = ?, components = ?, "
                        + "loc_code = ?, warnings = ?, log_tail = ? WHERE id = ?")
                .params(Scan.DONE, now(), run.runId, run.filesTotal, run.filesAnalyzed, run.filesCached, components.size(), loc,
                        run.warnings.size(), logTail, id)
                .update();
        jdbc.batchUpdate("INSERT INTO inventory_file (scan_id, path, root, origin, component, language, category, size, encoding, "
                        + "loc_code, loc_comment, loc_blank) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                files, 1000, (ps, f) -> {
                    ps.setLong(1, id);
                    ps.setString(2, f.path);
                    ps.setString(3, f.root);
                    ps.setString(4, f.origin == null ? null : f.origin.value());
                    ps.setString(5, f.component);
                    ps.setString(6, f.language);
                    ps.setString(7, f.category == null ? null : f.category.value());
                    ps.setLong(8, f.size);
                    ps.setString(9, f.encoding);
                    ps.setInt(10, f.locCode);
                    ps.setInt(11, f.locComment);
                    ps.setInt(12, f.locBlank);
                });
        jdbc.batchUpdate("INSERT INTO component (scan_id, id, name, type, path, technology, files, loc_code, detected_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                components, 1000, (ps, c) -> {
                    ps.setLong(1, id);
                    ps.setString(2, c.id);
                    ps.setString(3, c.name);
                    ps.setString(4, c.type.value());
                    ps.setString(5, c.path);
                    ps.setString(6, cut(String.join(", ", c.technology), 1000));
                    ps.setInt(7, c.files);
                    ps.setInt(8, c.locCode);
                    ps.setString(9, c.detectedBy);
                });
    }

    public Optional<String> logTail(long id) {
        return db.sql("SELECT log_tail FROM scan WHERE id = ?").param(id).query(String.class).optional();
    }

    /** Scans left queued or running when the server stopped. */
    public int failInterrupted() {
        return db.sql("UPDATE scan SET status = ?, finished_at = ?, error = ? WHERE status IN ('queued', 'running')")
                .params(Scan.FAILED, now(), "The server stopped during this scan; start it again")
                .update();
    }

    private static Scan row(ResultSet rs, int n) throws SQLException {
        return new Scan(
                rs.getLong("id"),
                rs.getString("project_id"),
                rs.getString("status"),
                rs.getBoolean("full_scan"),
                rs.getString("started_by"),
                rs.getObject("queued_at", OffsetDateTime.class),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class),
                rs.getString("run_id"),
                rs.getObject("files", Integer.class),
                rs.getObject("analyzed", Integer.class),
                rs.getObject("cached", Integer.class),
                rs.getObject("components", Integer.class),
                rs.getObject("loc_code", Long.class),
                rs.getObject("warnings", Integer.class),
                rs.getString("error"));
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
