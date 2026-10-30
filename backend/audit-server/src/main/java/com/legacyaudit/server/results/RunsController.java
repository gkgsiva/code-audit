package com.legacyaudit.server.results;

import com.legacyaudit.ai.mcp.Facts;
import com.legacyaudit.engine.store.Diff;
import com.legacyaudit.engine.store.FactsQuery;
import com.legacyaudit.engine.store.Runs;
import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.model.config.LoadedConfig;
import com.legacyaudit.server.auth.CurrentUser;
import com.legacyaudit.server.projects.Project;
import com.legacyaudit.server.projects.ProjectService;
import com.legacyaudit.server.web.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every run of a project (not only the newest): the list of runs, comparing two of them, one
 * component with everything that belongs to it, and the source lines behind a piece of
 * evidence. The same answers as the Python site's {@code /api/runs}, {@code /api/compare},
 * {@code /api/runs/{run}/component/{name}} and {@code /api/runs/{run}/evidence}.
 */
@RestController
public class RunsController {

    /** {@code latest}, {@code previous} or a run id (as the Python GUI's RUN_ID). */
    public static final Pattern RUN_ID = Pattern.compile("^(latest|previous|\\d{8}-\\d{6}-[0-9a-f]{6})$");
    static final int DIFF_LIST = 200;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ProjectService projects;
    private final CurrentUser current;

    public RunsController(ProjectService projects, CurrentUser current) {
        this.projects = projects;
        this.current = current;
    }

    /** The project's config with the site's rules applied (422 when it is invalid). */
    public static LoadedConfig loaded(ProjectService projects, Project project) {
        try {
            return ConfigLoader.load(projects.configPath(project.id()));
        } catch (ConfigException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        }
    }

    /** The run folder for {@code ref} (400 for a malformed id, 404 when there is no such run). */
    public static Path runDir(ProjectService projects, Project project, String ref) {
        if (ref == null || !RUN_ID.matcher(ref).matches()) {
            throw ApiException.badRequest("'" + ref + "' is not a run id");
        }
        try {
            return Runs.resolve(loaded(projects, project).outputDir(), ref);
        } catch (Runs.RunNotFound e) {
            throw ApiException.notFound(e.getMessage());
        }
    }

    @GetMapping("/api/projects/{id}/runs")
    public List<Map<String, Object>> runs(@PathVariable String id, Principal principal) {
        Project project = projects.get(current.of(principal), id);
        Path out = loaded(projects, project).outputDir();
        List<String> ids = Runs.list(out);
        String latest = ids.isEmpty() ? null : ids.getLast();
        try {
            latest = Runs.resolve(out, "latest").getFileName().toString();
        } catch (Runs.RunNotFound e) {
            // no LATEST pointer: the newest folder
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = ids.size() - 1; i >= 0; i--) {
            rows.add(runRow(Runs.runsDir(out).resolve(ids.get(i)), latest));
        }
        return rows;
    }

    @GetMapping("/api/projects/{id}/compare")
    @SuppressWarnings("unchecked")
    public Map<String, Object> compare(@PathVariable String id, @RequestParam(defaultValue = "previous") String a,
            @RequestParam(defaultValue = "latest") String b, Principal principal) {
        Project project = projects.get(current.of(principal), id);
        Path da = runDir(projects, project, a);
        Path db = runDir(projects, project, b);
        Map<String, Object> result;
        try {
            result = Diff.runs(da, db);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot read the runs: " + e.getMessage());
        }
        for (Object v : ((Map<String, Object>) result.get("entities")).values()) {
            Map<String, Object> d = (Map<String, Object>) v;
            for (String key : List.of("added", "removed", "changed")) {
                List<Object> list = (List<Object>) d.get(key);
                d.put(key + "_count", list.size());
                d.put(key, new ArrayList<>(list.subList(0, Math.min(DIFF_LIST, list.size()))));
            }
        }
        return result;
    }

    @GetMapping("/api/projects/{id}/runs/{run}/components/{name}")
    public Map<String, Object> component(@PathVariable String id, @PathVariable String run, @PathVariable String name, Principal principal) {
        Project project = projects.get(current.of(principal), id);
        Path dir = runDir(projects, project, run);
        try {
            return new Facts(() -> dir).getComponent(name);
        } catch (FactsQuery.FactsError e) {
            throw ApiException.notFound(e.getMessage());
        }
    }

    @GetMapping("/api/projects/{id}/runs/{run}/evidence")
    public Map<String, Object> evidence(@PathVariable String id, @PathVariable String run, @RequestParam String file,
            @RequestParam(required = false) Integer line, Principal principal) {
        Project project = projects.get(current.of(principal), id);
        Path dir = runDir(projects, project, run);
        Map<String, Object> out;
        try {
            out = new LinkedHashMap<>(new Facts(() -> dir).readEvidence(file, line, 15));
        } catch (FactsQuery.FactsError e) {
            throw ApiException.notFound(e.getMessage());
        }
        out.put("vscode", null); // the files live on the server
        return out;
    }

    /** One line of the runs list ({@code _run_row} of the Python GUI). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> runRow(Path runDir, String latest) {
        Map<String, Object> run = read(runDir.resolve("run.json"));
        Map<String, Object> summary = read(runDir.resolve("data").resolve("audit-summary.json"));
        Map<String, Object> totals = summary.get("totals") instanceof Map<?, ?> t ? (Map<String, Object>) t : Map.of();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run_id", runDir.getFileName().toString());
        row.put("started_at", run.get("started_at"));
        row.put("finished_at", run.get("finished_at"));
        row.put("full_scan", run.get("full_scan"));
        row.put("files", run.get("files_total"));
        row.put("analyzed", run.get("files_analyzed"));
        row.put("cached", run.get("files_cached"));
        row.put("loc_code", totals.get("loc_code"));
        row.put("components", totals.get("components"));
        row.put("findings", totals.get("findings"));
        row.put("warnings", run.get("warnings") instanceof List<?> w ? w.size() : 0);
        row.put("latest", runDir.getFileName().toString().equals(latest));
        double seconds = 0;
        if (run.get("timings") instanceof Map<?, ?> timings) {
            for (Object v : timings.values()) {
                seconds += v instanceof Number n ? n.doubleValue() : 0;
            }
        }
        row.put("seconds", Math.round(seconds * 10) / 10.0);
        List<Map<String, Object>> git = new ArrayList<>();
        if (run.get("sources") instanceof List<?> sources) {
            for (Object s : sources) {
                if (s instanceof Map<?, ?> m && "git".equals(m.get("type"))) {
                    Map<String, Object> g = new LinkedHashMap<>();
                    g.put("name", m.get("name"));
                    g.put("ref", m.get("ref"));
                    String commit = m.get("commit") == null ? "" : String.valueOf(m.get("commit"));
                    g.put("commit", commit.substring(0, Math.min(12, commit.length())));
                    git.add(g);
                }
            }
        }
        row.put("git", git);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path p) {
        if (!Files.isRegularFile(p)) {
            return Map.of();
        }
        try {
            Object v = JSON.readValue(p.toFile(), Object.class);
            return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
