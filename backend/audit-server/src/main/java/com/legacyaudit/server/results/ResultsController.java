package com.legacyaudit.server.results;

import com.legacyaudit.engine.store.Dashboard;
import com.legacyaudit.engine.store.FactsQuery;
import com.legacyaudit.engine.store.Runs;
import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.server.auth.CurrentUser;
import com.legacyaudit.server.projects.Project;
import com.legacyaudit.server.projects.ProjectService;
import com.legacyaudit.server.scans.Scan;
import com.legacyaudit.server.scans.ScanStore;
import com.legacyaudit.server.web.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Results of a project's newest finished scan (or of the run named by {@code ?run=}), read from
 * its run folder: the dashboard figures and every kind of fact (findings, libraries, services...)
 * with filters, search and paging.
 */
@RestController
public class ResultsController {

    private final ProjectService projects;
    private final ScanStore scans;
    private final CurrentUser current;

    public ResultsController(ProjectService projects, ScanStore scans, CurrentUser current) {
        this.projects = projects;
        this.scans = scans;
        this.current = current;
    }

    /** The run folder of the newest finished scan, or null when there is none (or it was removed). */
    private Path runDir(Project project, Scan scan) {
        if (scan == null || scan.runId() == null) {
            return null;
        }
        try {
            Path dir = Runs.runsDir(ConfigLoader.load(projects.configPath(project.id())).outputDir()).resolve(scan.runId());
            return Files.isDirectory(dir) ? dir : null;
        } catch (ConfigException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** {@code run} when given (any run of the project), else the newest finished scan's run. */
    private Path pick(Project project, Scan scan, String run) {
        return run == null || run.isBlank() ? runDir(project, scan) : RunsController.runDir(projects, project, run.strip());
    }

    @GetMapping("/api/projects/{id}/dashboard")
    public Map<String, Object> dashboard(@PathVariable String id, @RequestParam(required = false) String run, Principal principal) {
        Project project = projects.get(current.of(principal), id);
        Scan scan = scans.latestDone(project.id()).orElse(null);
        Path dir = pick(project, scan, run);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scan", scan);
        out.put("run_id", dir == null ? null : dir.getFileName().toString());
        out.put("dashboard", dir == null ? null : Dashboard.of(dir));
        return out;
    }

    @GetMapping("/api/projects/{id}/facts/{kind}")
    public Map<String, Object> facts(@PathVariable String id, @PathVariable String kind,
            @RequestParam Map<String, String> params, Principal principal) {
        Project project = projects.get(current.of(principal), id);
        Scan scan = scans.latestDone(project.id()).orElse(null);
        Map<String, String> filters = new LinkedHashMap<>(params);
        Path dir = pick(project, scan, filters.remove("run"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scan", scan);
        out.put("run_id", dir == null ? null : dir.getFileName().toString());
        if (dir == null) {
            out.put("result", null);
            return out;
        }
        String q = filters.remove("q");
        int limit = parse(filters.remove("limit"), 50);
        int offset = parse(filters.remove("offset"), 0);
        filters.values().removeIf(String::isEmpty);
        try {
            out.put("result", new FactsQuery(dir).query(kind, filters, limit, offset, q));
        } catch (FactsQuery.FactsError e) {
            throw ApiException.badRequest(e.getMessage());
        }
        return out;
    }

    private static int parse(String value, int dflt) {
        try {
            return value == null || value.isBlank() ? dflt : Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("'" + value + "' is not a whole number");
        }
    }
}
