package com.legacyaudit.server.enrichment;

import com.legacyaudit.ai.enrich.Validator;
import com.legacyaudit.ai.model.AiClient;
import com.legacyaudit.ai.model.AiError;
import com.legacyaudit.ai.model.AiSettings;
import com.legacyaudit.ai.model.Enricher;
import com.legacyaudit.engine.enrich.Stages;
import com.legacyaudit.engine.enrich.Status;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The AI enrichment of a project's newest finished scan: the state of each stage, validating
 * what Copilot (or a model) wrote, and running the stages with the model set under {@code ai:} in
 * the project's audit.yaml (the API key comes from the server's environment). Viewers read;
 * contributors and above validate and run.
 */
@RestController
public class EnrichmentController {

    private final ProjectService projects;
    private final ScanStore scans;
    private final EnrichJobs jobs;
    private final CurrentUser current;

    public EnrichmentController(ProjectService projects, ScanStore scans, EnrichJobs jobs, CurrentUser current) {
        this.projects = projects;
        this.scans = scans;
        this.jobs = jobs;
        this.current = current;
    }

    /** {@code stale}: only missing and stale stages; {@code stages}: only these. */
    public record RunBody(Boolean stale, List<String> stages) {}

    private LoadedConfig config(Project project) {
        try {
            return ConfigLoader.load(projects.configPath(project.id()));
        } catch (ConfigException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /** The run of the newest finished scan, or null when there is none yet. */
    private Path latestRun(Project project) {
        Scan scan = scans.latestDone(project.id()).orElse(null);
        if (scan == null || scan.runId() == null) {
            return null;
        }
        Path dir = Runs.runsDir(config(project).outputDir()).resolve(scan.runId());
        return Files.isDirectory(dir) ? dir : null;
    }

    private Path needRun(Project project) {
        Path dir = latestRun(project);
        if (dir == null) {
            throw ApiException.badRequest("No finished scan yet: start a scan first");
        }
        return dir;
    }

    /** The Copilot prompt to paste next: stale stages first, then everything, then the first missing. */
    static String nextPrompt(Map<String, Object> status) {
        List<?> stale = (List<?>) status.get("stale");
        List<?> missing = (List<?>) status.get("missing");
        if (!stale.isEmpty()) {
            return "/audit-enrich stale";
        }
        if (missing.size() == ((Map<?, ?>) status.get("stages")).size()) {
            return "/audit-enrich all";
        }
        return missing.isEmpty() ? null : "/audit-enrich " + missing.get(0);
    }

    private Map<String, Object> ai(Project project) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            AiSettings s = AiSettings.of(config(project).config(), null, null, null);
            out.put("provider", s.provider());
            out.put("label", s.label());
            String problem = null;
            if (!s.provider().equals("copilot")) {
                try {
                    new AiClient(s);
                } catch (AiError e) {
                    problem = e.getMessage();
                }
            }
            out.put("problem", problem);
        } catch (AiError e) {
            out.put("provider", "?");
            out.put("label", "?");
            out.put("problem", e.getMessage());
        }
        return out;
    }

    private Map<String, Object> view(Project project, Path runDir) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", runDir == null ? null : runDir.getFileName().toString());
        List<Map<String, Object>> rows = new ArrayList<>();
        String next = null;
        if (runDir != null) {
            Map<String, Object> status = Status.compute(runDir);
            Set<String> invalid = jobs.invalid(runDir.getFileName().toString());
            Map<?, ?> all = (Map<?, ?>) status.get("stages");
            for (Stages.Stage st : Status.activeStages(runDir)) {
                Map<?, ?> e = (Map<?, ?>) all.get(st.name());
                String state = "missing";
                if (Boolean.TRUE.equals(e.get("present"))) {
                    state = invalid.contains(st.name()) ? "invalid" : Boolean.TRUE.equals(e.get("stale")) ? "stale" : "valid";
                    if (state.equals("valid") && !Boolean.TRUE.equals(e.get("validated"))) {
                        state = "not validated";
                    }
                }
                List<String> why = new ArrayList<>();
                if (e.get("changed") instanceof List<?> c) {
                    c.forEach(x -> why.add(String.valueOf(x)));
                }
                if (e.get("stale_upstream") instanceof List<?> u) {
                    u.forEach(x -> why.add("stage:" + x));
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("number", st.number());
                row.put("name", st.name());
                row.put("title", st.title());
                row.put("file", st.file());
                row.put("state", state);
                row.put("why", why);
                rows.add(row);
            }
            next = nextPrompt(status);
        }
        out.put("stages", rows);
        out.put("next_prompt", next);
        out.put("ai", ai(project));
        out.put("ai_allowed", true);
        out.put("job", jobView(jobs.last(project.id())));
        return out;
    }

    @GetMapping("/api/projects/{id}/enrichment")
    public Map<String, Object> status(@PathVariable String id, Principal principal) {
        Project project = projects.get(current.of(principal), id);
        return view(project, latestRun(project));
    }

    @PostMapping("/api/projects/{id}/enrichment/validate")
    public Map<String, Object> validate(@PathVariable String id, Principal principal) {
        Project project = projects.need(current.of(principal), id, Roles.CONTRIBUTOR, "Viewers cannot validate; ask the project owner");
        Path runDir = needRun(project);
        Validator.Report rep = Validator.validate(runDir, null, false);
        Set<String> invalid = new HashSet<>();
        rep.errors().forEach(m -> invalid.add(m.stage()));
        jobs.setInvalid(runDir.getFileName().toString(), invalid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checked", rep.checked);
        out.put("messages", rep.messages.stream().map(Validator.Message::toMap).toList());
        out.putAll(view(project, runDir));
        return out;
    }

    @PostMapping("/api/projects/{id}/enrichment/run")
    public Map<String, Object> run(@PathVariable String id, @RequestBody(required = false) RunBody body, Principal principal) {
        User user = current.of(principal);
        Project project = projects.need(user, id, Roles.CONTRIBUTOR, "Viewers cannot run the AI enrichment; ask the project owner");
        Path runDir = needRun(project);
        boolean stale = body == null || body.stale() == null || body.stale();
        List<String> names = body == null ? null : body.stages();
        List<Stages.Stage> stages;
        AiClient client;
        try {
            client = new AiClient(AiSettings.of(config(project).config(), null, null, null));
            stages = Enricher.pickStages(runDir, names, stale);
        } catch (AiError e) {
            throw ApiException.badRequest(e.getMessage());
        }
        if (stages.isEmpty()) {
            throw ApiException.badRequest("Nothing to do: every stage is present and up to date");
        }
        if (jobs.start(project.id(), runDir, stages, client, user.name()) == null) {
            throw new ApiException(HttpStatus.CONFLICT, "The AI enrichment is already running for this project");
        }
        return view(project, runDir);
    }

    static Map<String, Object> jobView(EnrichJobs.Job job) {
        if (job == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", job.runId);
        out.put("label", job.label);
        out.put("user", job.user);
        out.put("state", job.state);
        out.put("stage", job.stage);
        out.put("done", job.done);
        out.put("total", job.total);
        synchronized (job.messages) {
            out.put("messages", new ArrayList<>(job.messages));
        }
        out.put("valid", job.valid);
        out.put("invalid", job.invalid);
        out.put("error", job.error);
        out.put("started_at", job.startedAt.toString());
        out.put("ended_at", job.endedAt == null ? null : job.endedAt.toString());
        return out;
    }
}
