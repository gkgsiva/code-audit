package com.legacyaudit.server.projects;

import com.legacyaudit.server.auth.CurrentUser;
import com.legacyaudit.server.admin.Activity;
import com.legacyaudit.server.admin.AdminSettings;
import com.legacyaudit.server.auth.User;
import com.legacyaudit.server.auth.UserStore;
import com.legacyaudit.server.jobs.JobGate;
import com.legacyaudit.server.scans.Scan;
import com.legacyaudit.server.scans.ScanStore;
import com.legacyaudit.server.web.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Projects the user can see, creating, changing and deleting one, who has access, and the source
 * folder picker.
 */
@RestController
public class ProjectController {

    private final ProjectService projects;
    private final ScanStore scans;
    private final CurrentUser current;
    private final UserStore users;
    private final Activity activity;
    private final JobGate gate;
    private final com.legacyaudit.server.git.Clones clones;

    public ProjectController(ProjectService projects, ScanStore scans, CurrentUser current, UserStore users, Activity activity,
            JobGate gate, com.legacyaudit.server.git.Clones clones) {
        this.projects = projects;
        this.scans = scans;
        this.current = current;
        this.users = users;
        this.activity = activity;
        this.gate = gate;
        this.clones = clones;
    }

    /** What the owner or an admin may change; a field left out (null) stays as it is. */
    public record UpdateBody(String title, String description, String owner, Boolean archived, List<String> hiddenReports,
            List<String> hiddenSections) {}

    public record DeleteBody(String confirm) {}

    /** {@code git}: a Git source instead of {@code sourcePath}. */
    public record CreateBody(String title, String id, String description, String sourcePath, ProjectService.GitSpec git) {}

    public record MembersBody(Map<String, String> members) {}

    @GetMapping("/api/projects")
    public List<Map<String, Object>> list(Principal principal) {
        User user = current.of(principal);
        return projects.visible(user).stream().map(p -> summary(user, p)).toList();
    }

    @PostMapping("/api/projects")
    public Map<String, Object> create(@RequestBody CreateBody body, Principal principal) {
        User user = current.of(principal);
        if (body == null) {
            throw ApiException.badRequest("Give the project a name");
        }
        Project p = projects.create(user, body.title(), body.id(), body.description(), body.sourcePath(), body.git());
        activity.log(user.name(), "create project", p.id(), Map.of("source", body.git() != null ? "git" : "local"));
        return summary(user, p);
    }

    @GetMapping("/api/projects/{id}")
    public Map<String, Object> get(@PathVariable String id, Principal principal) {
        User user = current.of(principal);
        Project p = projects.get(user, id);
        Map<String, Object> out = summary(user, p);
        out.put("git", gitRepos(p));
        return out;
    }

    @PutMapping("/api/projects/{id}/members")
    public Map<String, Object> members(@PathVariable String id, @RequestBody MembersBody body, Principal principal) {
        User user = current.of(principal);
        Project p = projects.setMembers(user, id, body == null ? null : body.members());
        activity.log(user.name(), "change project", id, Map.of("fields", List.of("members")));
        return summary(user, p);
    }

    @PutMapping("/api/projects/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody UpdateBody body, Principal principal) {
        User user = current.of(principal);
        Project p = projects.need(user, id, Roles.OWNER, "Only the owner or an admin can change this project");
        if (body == null) {
            return summary(user, p);
        }
        List<String> fields = new ArrayList<>();
        String title = p.title();
        if (body.title() != null) {
            title = body.title().strip();
            if (title.isEmpty()) {
                throw ApiException.badRequest("Give the project a name");
            }
            fields.add("title");
        }
        String description = p.description();
        if (body.description() != null) {
            description = body.description().isBlank() ? null : body.description().strip();
            fields.add("description");
        }
        String owner = p.owner();
        if (body.owner() != null && !body.owner().equals(p.owner())) {
            if (users.find(body.owner()).isEmpty()) {
                throw ApiException.badRequest("No user '" + body.owner() + "'");
            }
            owner = body.owner();
            fields.add("owner");
        }
        boolean archived = body.archived() != null ? body.archived() : p.archived();
        if (body.archived() != null) {
            fields.add("archived");
        }
        List<String> hiddenReports = p.hiddenReports();
        if (body.hiddenReports() != null) {
            unknown(body.hiddenReports(), AdminSettings.REPORTS.keySet(), "report");
            hiddenReports = List.copyOf(new java.util.LinkedHashSet<>(body.hiddenReports()));
            fields.add("hidden_reports");
        }
        List<String> hiddenSections = p.hiddenSections();
        if (body.hiddenSections() != null) {
            unknown(body.hiddenSections(), AdminSettings.SUMMARY_SECTIONS.keySet(), "section");
            hiddenSections = List.copyOf(new java.util.LinkedHashSet<>(body.hiddenSections()));
            fields.add("hidden_sections");
        }
        Project changed = new Project(p.id(), title, description, owner, p.sourcePath(), archived, p.createdAt(), p.members(),
                hiddenReports, hiddenSections);
        projects.store().update(changed);
        activity.log(user.name(), "change project", id, Map.of("fields", fields.stream().sorted().toList()));
        return summary(user, projects.store().find(id).orElseThrow());
    }

    private static void unknown(List<String> given, java.util.Set<String> known, String what) {
        List<String> bad = given.stream().filter(g -> !known.contains(g)).sorted().toList();
        if (!bad.isEmpty()) {
            throw ApiException.badRequest("Unknown " + what + "(s): " + String.join(", ", bad));
        }
    }

    /** Admins only: removes the project folder and its rows; the folder name must be typed to confirm. */
    @PostMapping("/api/projects/{id}/delete")
    public Map<String, Object> delete(@PathVariable String id, @RequestBody(required = false) DeleteBody body, Principal principal) {
        User user = current.of(principal);
        if (!User.ADMIN.equals(user.role())) {
            throw ApiException.forbidden("Admins only");
        }
        Project p = projects.get(user, id);
        if (body == null || !p.id().equals(body.confirm())) {
            throw ApiException.badRequest("Type the folder name '" + p.id() + "' to confirm");
        }
        boolean busy = gate.snapshot().values().stream().flatMap(List::stream).anyMatch(j -> p.id().equals(j.project));
        if (busy || scans.active(p.id()).isPresent()) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "A job is running on this project; cancel it first");
        }
        List<com.legacyaudit.engine.scan.GitClones.Clone> only = clones.clonesOf(p.id());
        projects.delete(p);
        activity.log(user.name(), "delete project", p.id());
        clones.removeFor(only, p.id(), user.name());
        return Map.of("deleted", p.id());
    }

    /** Folders a new project may scan: the sources roots, then their sub-folders. */
    @GetMapping("/api/sources")
    public Map<String, Object> sources(@RequestParam(defaultValue = "") String path, Principal principal) {
        User user = current.of(principal);
        if (!projects.canCreate(user)) {
            throw ApiException.forbidden("Your role cannot create projects; ask an admin");
        }
        List<Path> roots = projects.sourcesRoots();
        Map<String, Object> out = new LinkedHashMap<>();
        if (path.isBlank()) {
            out.put("path", "");
            out.put("parent", null);
            out.put("folders", roots.stream().filter(Files::isDirectory).map(r -> folder(r.toString(), r)).toList());
            return out;
        }
        Path target = Path.of(path).toAbsolutePath().normalize();
        if (!projects.underSourcesRoot(target) || !Files.isDirectory(target)) {
            throw ApiException.notFound("Not a folder this site may scan");
        }
        List<Map<String, String>> folders = new ArrayList<>();
        try (Stream<Path> list = Files.list(target)) {
            list.filter(Files::isDirectory)
                    .filter(d -> !d.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(d -> d.getFileName().toString().toLowerCase()))
                    .limit(500)
                    .forEach(d -> folders.add(folder(d.getFileName().toString(), d)));
        } catch (java.io.IOException e) {
            throw ApiException.forbidden("Cannot read " + target + ": " + e.getMessage());
        }
        out.put("path", target.toString());
        out.put("parent", roots.contains(target) ? null : target.getParent().toString());
        out.put("folders", folders);
        return out;
    }

    private static Map<String, String> folder(String name, Path path) {
        return Map.of("name", name, "path", path.toString());
    }

    /** The Git repositories of the project ({@code url}, {@code ref}), or null for a local source. */
    private List<Map<String, Object>> gitRepos(Project p) {
        try {
            if (com.legacyaudit.model.config.ConfigLoader.load(projects.configPath(p.id())).config().source()
                    instanceof com.legacyaudit.model.config.AuditConfig.GitSource g) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (var r : g.repos()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("url", r.url());
                    m.put("ref", r.ref());
                    out.add(m);
                }
                return out;
            }
        } catch (com.legacyaudit.model.config.ConfigException e) {
            // the settings page shows the problem
        }
        return null;
    }

    private Map<String, Object> summary(User user, Project p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.id());
        out.put("title", p.title());
        out.put("description", p.description());
        out.put("owner", p.owner());
        out.put("members", p.members());
        out.put("role", Roles.on(user, p));
        out.put("sourcePath", p.sourcePath());
        out.put("createdAt", p.createdAt());
        out.put("archived", p.archived());
        out.put("hiddenReports", p.hiddenReports());
        out.put("hiddenSections", p.hiddenSections());
        out.put("lastScan", scans.list(p.id(), 1).stream().findFirst().orElse(null));
        out.put("lastDone", scans.latestDone(p.id()).map(Scan::finishedAt).orElse(null));
        return out;
    }
}
