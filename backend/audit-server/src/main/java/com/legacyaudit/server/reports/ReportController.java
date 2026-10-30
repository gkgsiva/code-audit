package com.legacyaudit.server.reports;

import com.legacyaudit.engine.store.Runs;
import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.model.config.LoadedConfig;
import com.legacyaudit.server.auth.CurrentUser;
import com.legacyaudit.server.auth.User;
import com.legacyaudit.server.projects.Project;
import com.legacyaudit.server.projects.ProjectService;
import com.legacyaudit.server.projects.Roles;
import com.legacyaudit.server.scans.Scan;
import com.legacyaudit.server.scans.ScanStore;
import com.legacyaudit.server.web.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports of a project's newest finished scan: which exist, building them in the background,
 * and the report files themselves (the HTML pages open in a new browser tab; their relative
 * links to assets, data and detail pages resolve under the same address). Viewers read;
 * contributors and above build.
 */
@RestController
public class ReportController {

    static final Pattern RUN_ID = Pattern.compile("^\\d{8}-\\d{6}-[0-9a-f]{6}$");
    /** Report link name to its file under {@code reports/}. */
    static final Map<String, String> FILES = new LinkedHashMap<>();

    static {
        FILES.put("tech", "tech/index.html");
        FILES.put("functional", "functional/index.html");
        FILES.put("mgmt", "mgmt/index.html");
        FILES.put("summary", "summary/index.html");
        FILES.put("pdf_summary", "pdf/summary.pdf");
        FILES.put("pdf_mgmt", "pdf/management.pdf");
        FILES.put("pdf_functional", "pdf/functional.pdf");
        FILES.put("pdf_tech", "pdf/technical.pdf");
    }

    private final ProjectService projects;
    private final ScanStore scans;
    private final ReportJobs jobs;
    private final CurrentUser current;

    public ReportController(ProjectService projects, ScanStore scans, ReportJobs jobs, CurrentUser current) {
        this.projects = projects;
        this.scans = scans;
        this.jobs = jobs;
        this.current = current;
    }

    public record BuildBody(String type) {}

    private LoadedConfig config(Project project) {
        try {
            return ConfigLoader.load(projects.configPath(project.id()));
        } catch (ConfigException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** A run folder of the project, or 404. */
    private Path runDir(Project project, String runId) {
        if (runId == null || !RUN_ID.matcher(runId).matches()) {
            throw ApiException.notFound("No run '" + runId + "'");
        }
        Path dir = Runs.runsDir(config(project).outputDir()).resolve(runId);
        if (!Files.isDirectory(dir)) {
            throw ApiException.notFound("Run " + runId + " was removed");
        }
        return dir;
    }

    @GetMapping("/api/projects/{id}/reports")
    public Map<String, Object> list(@PathVariable String id, Principal principal) {
        Project project = projects.get(current.of(principal), id);
        Scan scan = scans.latestDone(project.id()).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scan", scan);
        Map<String, Object> reports = new LinkedHashMap<>();
        String runId = null;
        if (scan != null && scan.runId() != null) {
            Path dir = Runs.runsDir(config(project).outputDir()).resolve(scan.runId());
            if (Files.isDirectory(dir)) {
                runId = scan.runId();
                for (Map.Entry<String, String> f : FILES.entrySet()) {
                    boolean there = Files.isRegularFile(dir.resolve("reports").resolve(f.getValue()));
                    reports.put(f.getKey(), there ? "/api/projects/" + project.id() + "/runs/" + runId + "/reports/" + f.getValue() : null);
                }
            }
        }
        out.put("run_id", runId);
        out.put("reports", reports);
        out.put("datapack", runId == null ? null : "/api/projects/" + project.id() + "/runs/" + runId + "/datapack.zip");
        out.put("job", jobView(jobs.last(project.id())));
        return out;
    }

    @PostMapping("/api/projects/{id}/reports")
    public Map<String, Object> build(@PathVariable String id, @RequestBody BuildBody body, Principal principal) {
        User user = current.of(principal);
        Project project = projects.need(user, id, Roles.CONTRIBUTOR, "Viewers cannot build reports; ask the project owner");
        String type = body == null || body.type() == null ? "all" : body.type();
        if (!ReportJobs.TYPES.contains(type)) {
            throw ApiException.badRequest("Report type must be one of " + String.join(", ", ReportJobs.TYPES));
        }
        Scan scan = scans.latestDone(project.id()).orElse(null);
        if (scan == null || scan.runId() == null) {
            throw ApiException.badRequest("No finished scan yet: start a scan first");
        }
        Path dir = runDir(project, scan.runId());
        if (jobs.start(project.id(), config(project), dir, type, user.name()) == null) {
            throw new ApiException(HttpStatus.CONFLICT, "Reports are already being built for this project");
        }
        return list(id, principal);
    }

    static Map<String, Object> jobView(ReportJobs.Job job) {
        if (job == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", job.type);
        out.put("run_id", job.runId);
        out.put("user", job.user);
        out.put("state", job.state);
        out.put("stage", job.stage);
        out.put("done", job.done);
        out.put("total", job.total);
        synchronized (job.messages) {
            out.put("messages", new ArrayList<>(job.messages));
        }
        out.put("error", job.error);
        out.put("started_at", job.startedAt.toString());
        out.put("ended_at", job.endedAt == null ? null : job.endedAt.toString());
        return out;
    }

    /** A file of a run's reports (pages, assets, data, PDFs). */
    @GetMapping("/api/projects/{id}/runs/{runId}/reports/{*path}")
    public ResponseEntity<Resource> file(@PathVariable String id, @PathVariable String runId, @PathVariable String path,
            Principal principal) {
        Project project = projects.get(current.of(principal), id);
        Path base = runDir(project, runId).resolve("reports").toAbsolutePath().normalize();
        String rel = path.startsWith("/") ? path.substring(1) : path;
        Path target = base.resolve(rel).normalize();
        if (rel.isEmpty() || !target.startsWith(base) || !Files.isRegularFile(target)) {
            throw ApiException.notFound("No such report file");
        }
        MediaType type = MediaTypeFactory.getMediaType(target.getFileName().toString()).orElse(MediaType.APPLICATION_OCTET_STREAM);
        if (type.getType().equals("text") || type.getSubtype().equals("javascript") || type.getSubtype().equals("json")) {
            type = new MediaType(type, java.nio.charset.StandardCharsets.UTF_8);
        }
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok().contentType(type);
        String name = target.getFileName().toString();
        if (name.endsWith(".csv") || name.endsWith(".json") && !rel.startsWith("tech/") || name.endsWith(".pdf")) {
            ok.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name).build().toString());
        }
        return ok.body(new FileSystemResource(target));
    }

    /** The facts of a run as a ZIP: run.json, data/ and the enrichment files. */
    @GetMapping("/api/projects/{id}/runs/{runId}/datapack.zip")
    public ResponseEntity<byte[]> datapack(@PathVariable String id, @PathVariable String runId, Principal principal) {
        Project project = projects.get(current.of(principal), id);
        Path dir = runDir(project, runId);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buf)) {
            List<Path> files = new ArrayList<>();
            if (Files.isRegularFile(dir.resolve("run.json"))) {
                files.add(dir.resolve("run.json"));
            }
            files.addAll(walk(dir.resolve("data"), ""));
            files.addAll(walk(dir.resolve("enrichment"), ".json"));
            for (Path p : files) {
                zip.putNextEntry(new ZipEntry(runId + "/" + dir.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, zip);
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("legacy-audit-" + runId + "-data.zip").build().toString())
                .body(buf.toByteArray());
    }

    private static List<Path> walk(Path dir, String suffix) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
        }
    }
}
