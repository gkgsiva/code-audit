package com.legacyaudit.server.admin;

import com.legacyaudit.ai.doctor.Doctor;
import com.legacyaudit.model.AppInfo;
import com.legacyaudit.model.config.AuditConfig;
import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.model.config.ConfigReader;
import com.legacyaudit.model.config.PyYaml;
import com.legacyaudit.server.ServerSettings;
import com.legacyaudit.server.auth.CurrentUser;
import com.legacyaudit.server.auth.User;
import com.legacyaudit.server.auth.UserStore;
import com.legacyaudit.server.jobs.JobGate;
import com.legacyaudit.server.projects.HostedLayer;
import com.legacyaudit.server.projects.Project;
import com.legacyaudit.server.projects.ProjectService;
import com.legacyaudit.server.web.ApiException;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.Principal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin pages: site settings and project defaults, users, every project, the activity log
 * (and its CSV), the usage and system dashboards, and the health checks. Admins only, except
 * {@code /api/users} (names for the members picker). Same answers as the Python hosted site's
 * {@code /api/admin...} calls.
 */
@RestController
public class AdminController {

    static final Pattern USER_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._@-]{0,80}$");

    private final ServerSettings host;
    private final AdminStore admin;
    private final UserStore users;
    private final ProjectService projects;
    private final Activity activity;
    private final SystemMetrics metrics;
    private final JobGate gate;
    private final HostedLayer layer;
    private final CurrentUser current;

    public AdminController(ServerSettings host, AdminStore admin, UserStore users, ProjectService projects, Activity activity,
            SystemMetrics metrics, JobGate gate, HostedLayer layer, CurrentUser current,
            com.legacyaudit.server.git.Clones clones) {
        this.clones = clones;
        this.host = host;
        this.admin = admin;
        this.users = users;
        this.projects = projects;
        this.activity = activity;
        this.metrics = metrics;
        this.gate = gate;
        this.layer = layer;
        this.current = current;
    }

    private final com.legacyaudit.server.git.Clones clones;

    public record DefaultsBody(String text) {}

    public record CloneBody(String key) {}

    /** The Git clone cache: every clone with the projects that scan it, space used and free, the clean-up limits. */
    @GetMapping("/api/admin/clones")
    public Map<String, Object> clones(Principal principal) {
        adminOnly(principal);
        return clones.view();
    }

    @PostMapping("/api/admin/clones/delete")
    public Map<String, Object> deleteClone(@RequestBody CloneBody body, Principal principal) {
        User me = adminOnly(principal);
        String key = body == null || body.key() == null ? "" : body.key();
        clones.delete(key, me.name());
        return Map.of("deleted", key);
    }

    @PostMapping("/api/admin/clones/prune")
    public Map<String, Object> pruneClones(Principal principal) {
        User me = adminOnly(principal);
        List<String> removed = clones.prune();
        activity.log(me.name(), "clean up clones", null, Map.of("removed", removed.size()));
        return Map.of("removed", removed);
    }

    public record UserBody(String name, String role, Boolean disabled) {}

    private User adminOnly(Principal principal) {
        User user = current.of(principal);
        if (!User.ADMIN.equals(user.role())) {
            throw ApiException.forbidden("Admins only");
        }
        return user;
    }

    @GetMapping("/api/admin")
    public Map<String, Object> get(Principal principal) {
        adminOnly(principal);
        return view();
    }

    private Map<String, Object> view() {
        Map<String, Object> settings = admin.get().toMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> defaults = (Map<String, Object>) settings.get("defaults");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", settings);
        out.put("defaults_yaml", defaults.isEmpty() ? "" : PyYaml.dump(defaults, 80));
        out.put("env_locked", admin.envLocked());
        out.put("reports", AdminSettings.REPORTS);
        out.put("summary_sections", AdminSettings.SUMMARY_SECTIONS);
        out.put("roles", AdminSettings.ROLES);
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("audits_root", host.auditsRoot().toString());
        h.put("sources_roots", host.sourcesRoots().stream().map(Path::toString).toList());
        h.put("auth", host.auth());
        h.put("admins", host.admins());
        h.put("admin_dir", host.adminDir().toString());
        h.put("admin_file", admin.path().toString());
        h.put("git_cache", layer.gitCache().toString());
        out.put("host", h);
        return out;
    }

    @PutMapping("/api/admin")
    public Map<String, Object> put(@RequestBody Map<String, Object> body, Principal principal) {
        User user = adminOnly(principal);
        save(body == null ? Map.of() : body);
        activity.log(user.name(), "change admin settings", null);
        return view();
    }

    /** Replaces the project defaults with a YAML text (the advanced editor of the admin page). */
    @PutMapping("/api/admin/defaults")
    public Map<String, Object> defaults(@RequestBody DefaultsBody body, Principal principal) {
        User user = adminOnly(principal);
        Object data;
        try {
            data = ConfigLoader.parseYaml(body == null || body.text() == null ? "" : body.text(), "defaults");
        } catch (ConfigException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "Not valid YAML: " + e.getMessage());
        }
        if (data == null) {
            data = Map.of();
        }
        if (!(data instanceof Map<?, ?>)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "The defaults must be a mapping of audit.yaml settings");
        }
        Map<String, Object> settings = admin.get().toMap();
        settings.put("defaults", data);
        save(settings);
        activity.log(user.name(), "change project defaults", null);
        return view();
    }

    private void save(Map<?, ?> data) {
        try {
            admin.save(data);
        } catch (AdminSettings.Invalid e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        }
    }

    // ---- users ----

    /** Every user, with the admins fixed by LEGACY_AUDIT_ADMINS and the number of projects each owns. */
    @GetMapping("/api/admin/users")
    public List<Map<String, Object>> users(Principal principal) {
        adminOnly(principal);
        Map<String, Integer> owned = new LinkedHashMap<>();
        for (Project p : projects.store().all()) {
            if (p.owner() != null) {
                owned.merge(p.owner(), 1, Integer::sum);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (User u : users.all()) {
            seen.add(u.name());
            out.add(userRow(u, owned));
        }
        for (String a : host.admins()) {
            if (!seen.contains(a)) {
                out.add(userRow(new User(a, User.ADMIN, false, null, null), owned));
            }
        }
        out.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
        return out;
    }

    private Map<String, Object> userRow(User u, Map<String, Integer> owned) {
        boolean fixed = host.admins().contains(u.name());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", u.name());
        m.put("role", fixed ? User.ADMIN : u.role());
        m.put("disabled", !fixed && u.disabled());
        m.put("created_at", u.createdAt() == null ? null : u.createdAt().toString());
        m.put("last_login", u.lastLogin() == null ? null : u.lastLogin().toString());
        m.put("fixed", fixed);
        m.put("projects", owned.getOrDefault(u.name(), 0));
        return m;
    }

    /** Names for the members picker (any logged-in user). */
    @GetMapping("/api/users")
    public List<String> names(Principal principal) {
        current.of(principal);
        return users.all().stream().filter(u -> !u.disabled()).map(User::name).toList();
    }

    @PutMapping("/api/admin/users")
    public Map<String, Object> saveUser(@RequestBody UserBody body, Principal principal) {
        User me = adminOnly(principal);
        String name = body == null || body.name() == null ? "" : body.name().strip();
        if (host.admins().contains(name)) {
            throw ApiException.badRequest("This admin is set by LEGACY_AUDIT_ADMINS");
        }
        if (!USER_NAME.matcher(name).matches()) {
            throw ApiException.badRequest("'" + name + "' is not an allowed user name");
        }
        String role = body.role() == null ? "owner" : body.role();
        if (!AdminSettings.ROLES.contains(role)) {
            throw ApiException.badRequest("role must be one of " + String.join(", ", AdminSettings.ROLES));
        }
        boolean disabled = Boolean.TRUE.equals(body.disabled());
        User saved = users.save(name, role, disabled);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("target", name);
        d.put("role", role);
        d.put("disabled", disabled);
        activity.log(me.name(), "change user", null, d);
        return userRow(saved, Map.of());
    }

    @DeleteMapping("/api/admin/users/{name}")
    public Map<String, Object> deleteUser(@PathVariable String name, Principal principal) {
        User me = adminOnly(principal);
        if (host.admins().contains(name)) {
            throw ApiException.badRequest("This admin is set by LEGACY_AUDIT_ADMINS");
        }
        users.delete(name);
        activity.log(me.name(), "delete user", null, Map.of("target", name));
        return Map.of("deleted", name);
    }

    // ---- projects and activity ----

    @GetMapping("/api/admin/projects")
    public List<Map<String, Object>> allProjects(Principal principal) {
        adminOnly(principal);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Project p : projects.store().all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("title", p.title());
            m.put("owner", p.owner());
            m.put("members", p.members());
            m.put("archived", p.archived());
            m.put("created_at", p.createdAt() == null ? null : p.createdAt().toString());
            m.put("hidden_reports", p.hiddenReports());
            m.put("hidden_sections", p.hiddenSections());
            String application = null;
            String problem = null;
            int runs = 0;
            String lastRun = null;
            try {
                var loaded = ConfigLoader.load(projects.configPath(p.id()));
                application = loaded.config().application().name();
                List<String> ids = com.legacyaudit.engine.store.Runs.list(loaded.outputDir());
                runs = ids.size();
                lastRun = ids.isEmpty() ? null : ids.getLast();
            } catch (ConfigException e) {
                problem = e.getMessage().lines().findFirst().orElse(e.getMessage());
            }
            m.put("application", application);
            m.put("runs", runs);
            m.put("last_run", lastRun);
            m.put("problem", problem);
            m.put("bytes", folderSize(projects.folder(p.id())));
            out.add(m);
        }
        return out;
    }

    @GetMapping("/api/admin/activity")
    public List<Map<String, Object>> activity(@RequestParam(defaultValue = "500") int limit,
            @RequestParam(defaultValue = "") String project, Principal principal) {
        adminOnly(principal);
        return activity.recent(Math.max(1, Math.min(limit, 5000)), project);
    }

    @GetMapping("/api/admin/activity.csv")
    public ResponseEntity<String> activityCsv(Principal principal) {
        adminOnly(principal);
        StringBuilder sb = new StringBuilder("time,user,action,project,detail\r\n");
        for (Map<String, Object> e : activity.recent(100_000, "")) {
            List<String> rest = new ArrayList<>();
            e.forEach((k, v) -> {
                if (!List.of("time", "user", "action", "project").contains(k)) {
                    rest.add(k + "=" + py(v));
                }
            });
            sb.append(csv(e.get("time"))).append(',').append(csv(e.get("user"))).append(',').append(csv(e.get("action"))).append(',')
                    .append(csv(e.get("project"))).append(',').append(csv(String.join("; ", rest))).append("\r\n");
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"legacy-audit-activity.csv\"")
                .body(sb.toString());
    }

    /** A value as Python's {@code str()} wrote it in the CSV (None, True, ['a', 'b']). */
    static String py(Object v) {
        if (v == null) {
            return "None";
        }
        if (v instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (v instanceof List<?> l) {
            return "[" + String.join(", ", l.stream().map(x -> x instanceof String s ? "'" + s + "'" : py(x)).toList()) + "]";
        }
        return String.valueOf(v);
    }

    /** One CSV cell as Python's csv module writes it (quoted only when needed). */
    static String csv(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ---- dashboards and health ----

    @GetMapping("/api/admin/dashboard/usage")
    public Map<String, Object> usage(@RequestParam(defaultValue = "30") int days, Principal principal) {
        adminOnly(principal);
        int d = Math.max(1, Math.min(days, 366));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        OffsetDateTime start = today.minusDays(d - 1).atStartOfDay().atOffset(ZoneOffset.UTC);
        List<User> all = users.all();
        List<Project> ps = projects.store().all();
        return Usage.compute(activity.since(start), d, (int) all.stream().filter(u -> !u.disabled()).count(), ps.size(),
                (int) ps.stream().filter(Project::archived).count(), today);
    }

    @GetMapping("/api/admin/dashboard/system")
    public Map<String, Object> system(Principal principal) {
        adminOnly(principal);
        var snap = gate.snapshot();
        Map<String, Integer> jobs = new LinkedHashMap<>();
        jobs.put("running", snap.get("running").size());
        jobs.put("waiting", snap.get("waiting").size());
        Map<String, Object> out = new LinkedHashMap<>(metrics.system(host.auditsRoot(), jobs));
        out.put("version", AppInfo.version());
        out.put("slots", gate.slots());
        List<Map<String, Object>> now = new ArrayList<>();
        snap.get("running").forEach(j -> now.add(j.info()));
        snap.get("waiting").forEach(j -> now.add(j.info()));
        out.put("now", now);
        return out;
    }

    @GetMapping("/api/admin/health")
    public Map<String, Object> health(Principal principal) {
        adminOnly(principal);
        AdminSettings s = admin.get();
        AuditConfig cfg;
        try {
            Map<String, Object> probe = new LinkedHashMap<>();
            probe.put("application", Map.of("name", "health"));
            probe.putAll(s.defaults());
            cfg = ConfigReader.parse(probe, "defaults");
        } catch (ConfigException e) {
            cfg = null;
        }
        List<Map<String, Object>> sizes = new ArrayList<>();
        for (Project p : projects.store().all()) {
            sizes.add(Map.of("project", p.id(), "bytes", folderSize(projects.folder(p.id()))));
        }
        var snap = gate.snapshot();
        Map<String, Object> queue = new LinkedHashMap<>();
        queue.put("slots", gate.slots());
        queue.put("running", snap.get("running").size());
        queue.put("waiting", snap.get("waiting").size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", AppInfo.version());
        out.put("disk", SystemMetrics.disk(host.auditsRoot()));
        out.put("projects", sizes);
        out.put("checks", Doctor.run(cfg, host.auditsRoot()).stream().map(Doctor.Check::toMap).toList());
        out.put("queue", queue);
        out.put("source_roots", layer.sourceRoots().stream().map(Path::toString).toList());
        return out;
    }

    /** Bytes under a folder; stops counting after 200,000 entries on huge trees. */
    static long folderSize(Path folder) {
        long[] total = {0};
        int[] seen = {0};
        if (!Files.isDirectory(folder)) {
            return 0;
        }
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        total[0] += attrs.size();
                    }
                    return ++seen[0] >= 200_000 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // partial count
        }
        return total[0];
    }
}
