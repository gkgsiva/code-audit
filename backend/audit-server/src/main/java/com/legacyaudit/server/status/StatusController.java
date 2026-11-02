package com.legacyaudit.server.status;

import com.legacyaudit.engine.store.Runs;
import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.model.config.LoadedConfig;
import com.legacyaudit.server.ServerSettings;
import com.legacyaudit.server.auth.CurrentUser;
import com.legacyaudit.server.auth.User;
import com.legacyaudit.server.jobs.JobGate;
import com.legacyaudit.server.projects.Project;
import com.legacyaudit.server.projects.ProjectService;
import com.legacyaudit.server.results.RunsController;
import com.legacyaudit.server.web.ApiException;
import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The status page (job slots, running and waiting jobs, the last jobs of the projects the user
 * can see) and the logs page (the server log for admins, each run's scan log for the projects
 * the user can see), as {@code /api/status} and {@code /api/logs} of the Python hosted site.
 */
@RestController
public class StatusController {

    static final int LOG_TAIL = 5000;
    static final Pattern SERVER_LOG = Pattern.compile("^server\\.log(\\.\\d+)?$");
    static final Pattern RUN_LOG = Pattern.compile("^[\\w.-]+\\.log$");

    private final ServerSettings host;
    private final ProjectService projects;
    private final JobGate gate;
    private final CurrentUser current;
    private final String logFile;

    public StatusController(ServerSettings host, ProjectService projects, JobGate gate, CurrentUser current,
            @Value("${logging.file.name:}") String logFile) {
        this.host = host;
        this.projects = projects;
        this.gate = gate;
        this.current = current;
        this.logFile = logFile;
    }

    /** Where the server log is written ({@code LEGACY_AUDIT_LOG_FILE}), else {@code <admin dir>/logs}. */
    Path serverLogDir() {
        return logFile == null || logFile.isBlank() ? host.adminDir().resolve("logs") : Path.of(logFile).toAbsolutePath().getParent();
    }

    @GetMapping("/api/status")
    public Map<String, Object> status(Principal principal) {
        User user = current.of(principal);
        Set<String> visible = projects.visible(user).stream().map(Project::id).collect(Collectors.toSet());
        var snap = gate.snapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slots", gate.slots());
        out.put("running", snap.get("running").size());
        out.put("waiting", snap.get("waiting").size());
        out.put("jobs", gate.recent().stream().filter(j -> visible.contains(j.project)).map(JobGate.Job::info).toList());
        return out;
    }

    @GetMapping("/api/logs")
    public List<Map<String, Object>> logs(Principal principal) {
        User user = current.of(principal);
        List<Map<String, Object>> out = new ArrayList<>();
        if (User.ADMIN.equals(user.role()) && Files.isDirectory(serverLogDir())) {
            try (Stream<Path> files = Files.list(serverLogDir())) {
                for (Path p : files.filter(f -> SERVER_LOG.matcher(f.getFileName().toString()).matches()).sorted().toList()) {
                    out.add(row("server/" + p.getFileName(), "Server log (" + p.getFileName() + ")", null, null, p));
                }
            } catch (IOException e) {
                // no server log listed
            }
        }
        for (Project p : projects.visible(user)) {
            LoadedConfig loaded;
            try {
                loaded = ConfigLoader.load(projects.configPath(p.id()));
            } catch (ConfigException e) {
                continue;
            }
            List<String> ids = new ArrayList<>(Runs.list(loaded.outputDir()));
            Collections.reverse(ids);
            for (String runId : ids) {
                Path folder = Runs.runsDir(loaded.outputDir()).resolve(runId).resolve("logs");
                if (!Files.isDirectory(folder)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(folder)) {
                    for (Path f : files.filter(f -> f.getFileName().toString().endsWith(".log")).sorted().toList()) {
                        out.add(row(p.id() + "/" + runId + "/" + f.getFileName(), p.title() + ": run " + runId + " (" + f.getFileName() + ")",
                                p.id(), runId, f));
                    }
                } catch (IOException e) {
                    // skip this run
                }
            }
        }
        return out;
    }

    private static Map<String, Object> row(String id, String label, String project, String run, Path p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("project", project);
        m.put("run", run);
        long size;
        try {
            size = Files.size(p);
        } catch (IOException e) {
            size = 0;
        }
        m.put("size", size);
        return m;
    }

    /** The file behind a log id; 404 for anything that is not a listed log. */
    Path logPath(User user, String id) {
        String[] parts = id == null ? new String[0] : id.split("/", -1);
        if (parts.length == 2 && parts[0].equals("server")) {
            if (!User.ADMIN.equals(user.role())) {
                throw ApiException.forbidden("Admins only");
            }
            Path p = serverLogDir().resolve(parts[1]);
            if (!SERVER_LOG.matcher(parts[1]).matches() || !Files.isRegularFile(p)) {
                throw ApiException.notFound("No such log");
            }
            return p;
        }
        if (parts.length != 3 || !RUN_LOG.matcher(parts[2]).matches()) {
            throw ApiException.notFound("No such log");
        }
        Project project = projects.get(user, parts[0]);
        if (!RunsController.RUN_ID.matcher(parts[1]).matches() || parts[1].equals("latest") || parts[1].equals("previous")) {
            throw ApiException.notFound("No such log");
        }
        LoadedConfig loaded;
        try {
            loaded = ConfigLoader.load(projects.configPath(project.id()));
        } catch (ConfigException e) {
            throw ApiException.notFound("No such log");
        }
        Path p = Runs.runsDir(loaded.outputDir()).resolve(parts[1]).resolve("logs").resolve(parts[2]);
        if (!Files.isRegularFile(p)) {
            throw ApiException.notFound("No such log");
        }
        return p;
    }

    /** The last lines of a log, optionally only errors or warnings and errors, or lines holding a text. */
    @GetMapping("/api/logs/view")
    public Map<String, Object> view(@RequestParam String id, @RequestParam(defaultValue = "") String level,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "500") int tail, Principal principal) {
        Path p = logPath(current.of(principal), id);
        List<String> lines;
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            lines = new ArrayList<>(List.of(decoder.decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(p))).toString().split("\\R", -1)));
            if (!lines.isEmpty() && lines.getLast().isEmpty()) {
                lines.removeLast();
            }
        } catch (IOException e) {
            throw ApiException.notFound("Cannot read the log: " + e.getMessage());
        }
        Map<String, List<String>> levels = Map.of("error", List.of("ERROR", "CRITICAL"), "warning", List.of("WARNING", "WARN", "ERROR", "CRITICAL"));
        if (levels.containsKey(level)) {
            List<String> wanted = levels.get(level);
            lines = lines.stream().filter(ln -> wanted.stream().anyMatch(lv -> ln.contains(" " + lv + " "))).toList();
        }
        if (!q.isEmpty()) {
            String needle = q.toLowerCase();
            lines = lines.stream().filter(ln -> ln.toLowerCase().contains(needle)).toList();
        }
        int n = Math.max(1, Math.min(tail, LOG_TAIL));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("total", lines.size());
        out.put("lines", lines.subList(Math.max(0, lines.size() - n), lines.size()));
        return out;
    }

    @GetMapping("/api/logs/download")
    public ResponseEntity<Resource> download(@RequestParam String id, Principal principal) {
        Path p = logPath(current.of(principal), id);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(id.replace("/", "_")).build().toString())
                .body(new FileSystemResource(p));
    }
}
