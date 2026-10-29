package com.legacyaudit.server.projects;

import com.legacyaudit.model.config.AuditConfig;
import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.server.ServerSettings;
import com.legacyaudit.server.auth.User;
import com.legacyaudit.server.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Creates project folders and answers who may do what on a project. */
@Service
public class ProjectService {

    /** Same rule as the Python hosted site (registry.PROJECT_ID). */
    public static final Pattern PROJECT_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,80}$");

    static final String CONFIG_FILE = "audit.yaml";
    static final String OVERRIDES_FILE = "audit-overrides.yaml";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ServerSettings settings;
    private final ProjectStore store;

    public ProjectService(ServerSettings settings, ProjectStore store) {
        this.settings = settings;
        this.store = store;
    }

    /** As Python's slugify: runs of other characters become "-", trimmed, lower case, at most 60. */
    public static String slugify(String name) {
        String s = name.strip().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[-._]+|[-._]+$", "").toLowerCase(Locale.ROOT);
        s = s.length() > 60 ? s.substring(0, 60) : s;
        return s.isEmpty() ? "project" : s;
    }

    public List<Project> visible(User user) {
        return store.all().stream().filter(p -> Roles.on(user, p) != null).toList();
    }

    /** The project and the user's role on it; 404 when the user may not see it. */
    public Project get(User user, String id) {
        Project project = store.find(id).orElse(null);
        if (project == null || Roles.on(user, project) == null) {
            throw ApiException.notFound("No project '" + id + "', or you have no access to it");
        }
        return project;
    }

    public Project need(User user, String id, String role, String refusal) {
        Project project = get(user, id);
        if (!Roles.atLeast(Roles.on(user, project), role)) {
            throw ApiException.forbidden(refusal);
        }
        return project;
    }

    public Path folder(String id) {
        if (!PROJECT_ID.matcher(id).matches()) {
            throw ApiException.badRequest("'" + id + "' is not an allowed folder name");
        }
        return settings.auditsRoot().resolve(id);
    }

    public Path configPath(String id) {
        return folder(id).resolve(CONFIG_FILE);
    }

    /** Creates the folder (audit.yaml, audit-overrides.yaml, audit-inputs/) and the project row. */
    public Project create(User user, String title, String id, String description, String sourcePath) {
        if (!Roles.canCreate(user)) {
            throw ApiException.forbidden("Your role cannot create projects; ask an admin");
        }
        title = title == null ? "" : title.strip();
        if (title.isEmpty()) {
            throw ApiException.badRequest("Give the project a name");
        }
        String pid = id == null || id.isBlank() ? slugify(title) : id.strip();
        if (!PROJECT_ID.matcher(pid).matches()) {
            throw ApiException.badRequest("'" + pid + "' is not an allowed folder name");
        }
        Path folder = folder(pid);
        if (Files.exists(folder) || store.exists(pid)) {
            throw new ApiException(HttpStatus.CONFLICT, "A project folder '" + pid + "' already exists; open it instead");
        }
        Path source = checkSource(sourcePath);
        description = description == null || description.isBlank() ? null : description.strip();
        try {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(CONFIG_FILE), configText(title, user.name(), description, source), StandardCharsets.UTF_8);
            Files.writeString(folder.resolve(OVERRIDES_FILE), template(OVERRIDES_FILE), StandardCharsets.UTF_8);
            for (String kind : AuditConfig.INPUT_KINDS.keySet()) {
                Path d = folder.resolve("audit-inputs").resolve(kind);
                Files.createDirectories(d);
                Files.writeString(d.resolve(".gitkeep"), "");
            }
            ConfigLoader.load(folder.resolve(CONFIG_FILE));
        } catch (IOException | ConfigException e) {
            deleteTree(folder);
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, e instanceof IOException
                    ? "Cannot write the project folder " + folder + ": " + e.getMessage()
                    : e.getMessage());
        }
        return store.create(pid, title, description, user.name(), source.toString());
    }

    /** Sets the members (contributor or viewer); only the owner or an admin may. */
    public Project setMembers(User user, String id, Map<String, String> members) {
        Project project = need(user, id, Roles.OWNER, "Only the owner or an admin can change who has access");
        Map<String, String> clean = new LinkedHashMap<>();
        (members == null ? Map.<String, String>of() : members).forEach((name, role) -> {
            String n = name == null ? "" : name.strip();
            if (n.isEmpty() || n.equals(project.owner())) {
                return;
            }
            if (!Roles.MEMBER_ROLES.contains(role)) {
                throw ApiException.badRequest(n + ": a member is a contributor or a viewer");
            }
            clean.put(n, role);
        });
        store.setMembers(id, clean);
        return store.find(id).orElseThrow();
    }

    /** The folder to scan must be one of the sources roots or below one. */
    Path checkSource(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            throw ApiException.badRequest("Pick the folder holding the code");
        }
        Path target = Path.of(sourcePath.strip()).toAbsolutePath().normalize();
        if (!underSourcesRoot(target) || !Files.isDirectory(target)) {
            throw ApiException.badRequest("Pick a folder from the list; " + sourcePath + " is not one this site may scan");
        }
        return target;
    }

    public List<Path> sourcesRoots() {
        return settings.sourcesRoots();
    }

    public boolean underSourcesRoot(Path target) {
        return settings.sourcesRoots().stream().anyMatch(target::startsWith);
    }

    static String configText(String title, String owner, String description, Path source) {
        StringBuilder sb = new StringBuilder();
        sb.append("# legacy-audit project created on the web app. Relative paths are resolved from this file's\n");
        sb.append("# folder. Add a section to change a default for this project.\n\n");
        sb.append("application:\n");
        sb.append("  name: ").append(quote(title)).append('\n');
        sb.append("  owner: ").append(quote(owner)).append('\n');
        if (description != null) {
            sb.append("  description: ").append(quote(description)).append('\n');
        }
        sb.append("\nsource:\n  type: local\n  paths:\n    - ").append(quote(source.toString().replace('\\', '/'))).append('\n');
        sb.append("\n# Files to scan, in .gitignore syntax.\ninclude:\n  - \"**\"\nexclude: []\n");
        sb.append("\ninputs_dir: audit-inputs\noutput_dir: audit-output\noverrides_file: audit-overrides.yaml\n");
        return sb.toString();
    }

    private static String quote(String s) {
        return JSON.writeValueAsString(s);
    }

    private static String template(String name) throws IOException {
        try (InputStream in = ProjectService.class.getResourceAsStream("/templates/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void deleteTree(Path folder) {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            // leave what could not be removed; creating the same project again reports it
        }
    }
}
