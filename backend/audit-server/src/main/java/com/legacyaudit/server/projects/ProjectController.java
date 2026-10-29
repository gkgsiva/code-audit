package com.legacyaudit.server.projects;

import com.legacyaudit.server.auth.CurrentUser;
import com.legacyaudit.server.auth.User;
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

/** Projects the user can see, creating one, who has access, and the source folder picker. */
@RestController
public class ProjectController {

    private final ProjectService projects;
    private final ScanStore scans;
    private final CurrentUser current;

    public ProjectController(ProjectService projects, ScanStore scans, CurrentUser current) {
        this.projects = projects;
        this.scans = scans;
        this.current = current;
    }

    public record CreateBody(String title, String id, String description, String sourcePath) {}

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
        return summary(user, projects.create(user, body.title(), body.id(), body.description(), body.sourcePath()));
    }

    @GetMapping("/api/projects/{id}")
    public Map<String, Object> get(@PathVariable String id, Principal principal) {
        User user = current.of(principal);
        return summary(user, projects.get(user, id));
    }

    @PutMapping("/api/projects/{id}/members")
    public Map<String, Object> members(@PathVariable String id, @RequestBody MembersBody body, Principal principal) {
        User user = current.of(principal);
        return summary(user, projects.setMembers(user, id, body == null ? null : body.members()));
    }

    /** Folders a new project may scan: the sources roots, then their sub-folders. */
    @GetMapping("/api/sources")
    public Map<String, Object> sources(@RequestParam(defaultValue = "") String path, Principal principal) {
        User user = current.of(principal);
        if (!Roles.canCreate(user)) {
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
        out.put("lastScan", scans.list(p.id(), 1).stream().findFirst().orElse(null));
        out.put("lastDone", scans.latestDone(p.id()).map(Scan::finishedAt).orElse(null));
        return out;
    }
}
