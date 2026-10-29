package com.legacyaudit.server.scans;

import com.legacyaudit.server.auth.CurrentUser;
import com.legacyaudit.server.auth.User;
import com.legacyaudit.server.projects.Project;
import com.legacyaudit.server.projects.ProjectService;
import com.legacyaudit.server.projects.Roles;
import com.legacyaudit.server.web.ApiException;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Start a scan, follow it (stage, counts, log) and cancel it. */
@RestController
public class ScanController {

    private final ProjectService projects;
    private final ScanStore store;
    private final ScanJobs jobs;
    private final CurrentUser current;

    public ScanController(ProjectService projects, ScanStore store, ScanJobs jobs, CurrentUser current) {
        this.projects = projects;
        this.store = store;
        this.jobs = jobs;
        this.current = current;
    }

    public record StartBody(Boolean full) {}

    @PostMapping("/api/projects/{id}/scans")
    public Map<String, Object> start(@PathVariable String id, @RequestBody(required = false) StartBody body, Principal principal) {
        User user = current.of(principal);
        Project project = projects.need(user, id, Roles.CONTRIBUTOR, "Viewers cannot start a scan; ask the project owner");
        boolean full = body != null && Boolean.TRUE.equals(body.full());
        return status(jobs.start(project.id(), projects.configPath(project.id()), user.name(), full));
    }

    @GetMapping("/api/projects/{id}/scans")
    public List<Scan> list(@PathVariable String id, Principal principal) {
        Project project = projects.get(current.of(principal), id);
        return store.list(project.id(), 50);
    }

    @GetMapping("/api/projects/{id}/scans/{scanId}")
    public Map<String, Object> get(@PathVariable String id, @PathVariable long scanId, Principal principal) {
        return status(scan(current.of(principal), id, scanId, Roles.VIEWER));
    }

    @PostMapping("/api/projects/{id}/scans/{scanId}/cancel")
    public Map<String, Object> cancel(@PathVariable String id, @PathVariable long scanId, Principal principal) {
        Scan scan = scan(current.of(principal), id, scanId, Roles.CONTRIBUTOR);
        if (!jobs.cancel(scan.id())) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "This scan has already ended");
        }
        return status(store.find(scanId).orElseThrow());
    }

    private Scan scan(User user, String id, long scanId, String role) {
        Project project = projects.need(user, id, role, "Viewers cannot change a scan; ask the project owner");
        Scan scan = store.find(scanId).orElse(null);
        if (scan == null || !scan.projectId().equals(project.id())) {
            throw ApiException.notFound("No scan " + scanId + " in this project");
        }
        return scan;
    }

    private Map<String, Object> status(Scan scan) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scan", scan);
        ScanJobs.Live live = jobs.live(scan.id());
        if (live != null) {
            out.put("progress", live.progress());
            out.put("log", live.lines());
        } else {
            String tail = store.logTail(scan.id()).orElse(null);
            out.put("progress", null);
            out.put("log", tail == null || tail.isEmpty() ? List.of() : List.of(tail.split("\n")));
        }
        return out;
    }
}
