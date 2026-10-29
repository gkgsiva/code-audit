package com.legacyaudit.server.inventory;

import com.legacyaudit.server.auth.CurrentUser;
import com.legacyaudit.server.projects.Project;
import com.legacyaudit.server.projects.ProjectService;
import com.legacyaudit.server.scans.Scan;
import com.legacyaudit.server.scans.ScanStore;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The files and components of a project's newest finished scan, with filters and totals. */
@RestController
public class InventoryController {

    static final int MAX_PAGE = 500;

    private final ProjectService projects;
    private final ScanStore scans;
    private final CurrentUser current;
    private final JdbcClient db;

    public InventoryController(ProjectService projects, ScanStore scans, CurrentUser current, JdbcClient db) {
        this.projects = projects;
        this.scans = scans;
        this.current = current;
        this.db = db;
    }

    @GetMapping("/api/projects/{id}/inventory")
    public Map<String, Object> inventory(@PathVariable String id,
            @RequestParam(defaultValue = "") String language,
            @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "") String component,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            Principal principal) {
        Project project = projects.get(current.of(principal), id);
        Map<String, Object> out = new LinkedHashMap<>();
        Scan scan = scans.latestDone(project.id()).orElse(null);
        out.put("scan", scan);
        if (scan == null) {
            out.put("total", 0);
            out.put("files", List.of());
            out.put("languages", List.of());
            out.put("categories", List.of());
            return out;
        }
        StringBuilder where = new StringBuilder(" WHERE scan_id = ?");
        List<Object> params = new ArrayList<>(List.of(scan.id()));
        filter(where, params, "language", language);
        filter(where, params, "category", category);
        filter(where, params, "component", component);
        if (!q.isBlank()) {
            where.append(" AND LOWER(path) LIKE ?");
            params.add("%" + q.strip().toLowerCase().replace("%", "").replace("_", "") + "%");
        }
        int pageSize = Math.clamp(size, 1, MAX_PAGE);
        int offset = Math.max(page, 0) * pageSize;
        out.put("total", db.sql("SELECT COUNT(*) FROM inventory_file" + where).params(params).query(Long.class).single());
        out.put("files", db.sql("SELECT path, root, origin, component, language, category, size, encoding, loc_code, loc_comment, "
                        + "loc_blank FROM inventory_file" + where + " ORDER BY path LIMIT " + pageSize + " OFFSET " + offset)
                .params(params)
                .query((rs, n) -> {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("path", rs.getString("path"));
                    f.put("root", rs.getString("root"));
                    f.put("origin", rs.getString("origin"));
                    f.put("component", rs.getString("component"));
                    f.put("language", rs.getString("language"));
                    f.put("category", rs.getString("category"));
                    f.put("size", rs.getLong("size"));
                    f.put("encoding", rs.getString("encoding"));
                    f.put("locCode", rs.getInt("loc_code"));
                    f.put("locComment", rs.getInt("loc_comment"));
                    f.put("locBlank", rs.getInt("loc_blank"));
                    return f;
                })
                .list());
        out.put("languages", totals(scan.id(), "language"));
        out.put("categories", totals(scan.id(), "category"));
        return out;
    }

    @GetMapping("/api/projects/{id}/components")
    public Map<String, Object> components(@PathVariable String id, Principal principal) {
        Project project = projects.get(current.of(principal), id);
        Scan scan = scans.latestDone(project.id()).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scan", scan);
        out.put("components", scan == null ? List.of() : db.sql(
                        "SELECT id, name, type, path, technology, files, loc_code, detected_by FROM component WHERE scan_id = ? ORDER BY name")
                .param(scan.id())
                .query((rs, n) -> {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("id", rs.getString("id"));
                    c.put("name", rs.getString("name"));
                    c.put("type", rs.getString("type"));
                    c.put("path", rs.getString("path"));
                    c.put("technology", rs.getString("technology"));
                    c.put("files", rs.getInt("files"));
                    c.put("locCode", rs.getInt("loc_code"));
                    c.put("detectedBy", rs.getString("detected_by"));
                    return c;
                })
                .list());
        return out;
    }

    private static void filter(StringBuilder where, List<Object> params, String column, String value) {
        if (!value.isBlank()) {
            where.append(" AND ").append(column).append(" = ?");
            params.add(value);
        }
    }

    /** Files and lines per language (or category), largest first. */
    private List<Map<String, Object>> totals(long scanId, String column) {
        return db.sql("SELECT COALESCE(" + column + ", '') AS name, COUNT(*) AS files, SUM(loc_code) AS code, SUM(loc_comment) AS comment, "
                        + "SUM(loc_blank) AS blank FROM inventory_file WHERE scan_id = ? GROUP BY COALESCE(" + column + ", '') "
                        + "ORDER BY SUM(loc_code) DESC, COUNT(*) DESC, COALESCE(" + column + ", '')")
                .param(scanId)
                .query((rs, n) -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("name", rs.getString("name"));
                    t.put("files", rs.getLong("files"));
                    t.put("locCode", rs.getLong("code"));
                    t.put("locComment", rs.getLong("comment"));
                    t.put("locBlank", rs.getLong("blank"));
                    return t;
                })
                .list();
    }
}
