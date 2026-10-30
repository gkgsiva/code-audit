package com.legacyaudit.server.workspace;

import com.legacyaudit.ai.doctor.Doctor;
import com.legacyaudit.engine.scan.Prescan;
import com.legacyaudit.engine.store.Runs;
import com.legacyaudit.model.config.AuditConfig;
import com.legacyaudit.model.config.ConfigEdits;
import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.model.config.LoadedConfig;
import com.legacyaudit.server.auth.CurrentUser;
import com.legacyaudit.server.projects.Project;
import com.legacyaudit.server.projects.ProjectService;
import com.legacyaudit.server.projects.Roles;
import com.legacyaudit.server.results.RunsController;
import com.legacyaudit.server.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * A project's settings, as the Python site's project pages had them: {@code audit.yaml} as a
 * form and as text, the extra input files ({@code audit-inputs/}), the component picker
 * ({@code audit-overrides.yaml}) and the tool checks. Changing settings or components needs
 * the owner; adding input files a contributor.
 */
@RestController
public class WorkspaceController {

    /** The form's top-level keys of audit.yaml. */
    static final List<String> FIELDS = List.of("application", "source", "target", "include", "exclude");
    /** Input file names (as the Python GUI's SAFE_NAME). */
    static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._ -]{0,120}$");
    static final long MAX_UPLOAD = 200L * 1024 * 1024;
    static final Map<String, String> UPLOAD_KINDS;

    static {
        Map<String, String> kinds = new LinkedHashMap<>(AuditConfig.INPUT_KINDS);
        kinds.put("security", "Vulnerability reports (JSON)");
        UPLOAD_KINDS = kinds;
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ProjectService projects;
    private final CurrentUser current;

    public WorkspaceController(ProjectService projects, CurrentUser current) {
        this.projects = projects;
        this.current = current;
    }

    public record TextBody(String text) {}

    public record ComponentsBody(Map<String, Boolean> included, Map<String, String> types) {}

    // ---- audit.yaml ---------------------------------------------------------------------------

    @GetMapping("/api/projects/{id}/config")
    public Map<String, Object> config(@PathVariable String id, Principal principal) {
        Project project = projects.get(current.of(principal), id);
        return configView(project);
    }

    private Map<String, Object> configView(Project project) {
        LoadedConfig loaded = RunsController.loaded(projects, project);
        Path path = projects.configPath(project.id());
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String k : FIELDS) {
            fields.put(k, loaded.config().dump().get(k));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", read(path));
        out.put("fields", fields);
        out.put("path", path.toString());
        return out;
    }

    /** Set the form's sections; only what differs from the current settings is written. */
    @PutMapping("/api/projects/{id}/config/fields")
    public Map<String, Object> putFields(@PathVariable String id, @RequestBody Map<String, Object> body, Principal principal) {
        Project project = projects.need(current.of(principal), id, Roles.OWNER, "Only the owner or an admin can change the settings");
        Map<String, Object> now = RunsController.loaded(projects, project).config().dump();
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String k : FIELDS) {
            Object v = body.get(k);
            if (v != null && !Objects.equals(JSON.valueToTree(v), JSON.valueToTree(now.get(k)))) {
                changes.put(k, v);
            }
        }
        Path path = projects.configPath(project.id());
        String before = read(path);
        try {
            ConfigEdits.updateConfig(path, changes);
            ConfigLoader.load(path); // the site's rules (source folders)
        } catch (ConfigException e) {
            write(path, before);
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        }
        return configView(project);
    }

    @PutMapping("/api/projects/{id}/config/text")
    public Map<String, Object> putText(@PathVariable String id, @RequestBody TextBody body, Principal principal) {
        Project project = projects.need(current.of(principal), id, Roles.OWNER, "Only the owner or an admin can change the settings");
        Path path = projects.configPath(project.id());
        String before = read(path);
        try {
            ConfigEdits.writeConfigText(path, body == null || body.text() == null ? "" : body.text());
            ConfigLoader.load(path);
        } catch (ConfigException e) {
            write(path, before);
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        }
        return configView(project);
    }

    // ---- input files --------------------------------------------------------------------------

    @GetMapping("/api/projects/{id}/inputs")
    public Map<String, Object> inputs(@PathVariable String id, Principal principal) {
        Project project = projects.get(current.of(principal), id);
        Path base = RunsController.loaded(projects, project).inputsDir();
        List<Map<String, Object>> kinds = new ArrayList<>();
        for (Map.Entry<String, String> k : UPLOAD_KINDS.entrySet()) {
            Path folder = base.resolve(k.getKey());
            List<Map<String, Object>> files = new ArrayList<>();
            if (Files.isDirectory(folder)) {
                try (Stream<Path> list = Files.list(folder)) {
                    for (Path p : list.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().startsWith("."))
                            .sorted().toList()) {
                        Map<String, Object> f = new LinkedHashMap<>();
                        f.put("name", p.getFileName().toString());
                        f.put("size", Files.size(p));
                        files.add(f);
                    }
                } catch (IOException e) {
                    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot read " + folder + ": " + e.getMessage());
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", k.getKey());
            row.put("label", k.getValue());
            row.put("folder", relative(project, folder));
            row.put("files", files);
            kinds.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inputs_dir", relative(project, base));
        out.put("kinds", kinds);
        return out;
    }

    private Path inputPath(Project project, String kind, String name) {
        if (!UPLOAD_KINDS.containsKey(kind)) {
            throw ApiException.badRequest("Unknown input kind '" + kind + "'");
        }
        if (!SAFE_NAME.matcher(name).matches() || !name.strip().equals(name) || name.contains("..")) {
            throw ApiException.badRequest("'" + name + "' is not an allowed file name");
        }
        return RunsController.loaded(projects, project).inputsDir().resolve(kind).resolve(name);
    }

    /** The request body is the file itself. */
    @PutMapping("/api/projects/{id}/inputs/{kind}/{name}")
    public Map<String, Object> upload(@PathVariable String id, @PathVariable String kind, @PathVariable String name,
            HttpServletRequest request, Principal principal) throws IOException {
        Project project = projects.need(current.of(principal), id, Roles.CONTRIBUTOR, "Viewers cannot add input files; ask the project owner");
        Path target = inputPath(project, kind, name);
        if (request.getContentLengthLong() > MAX_UPLOAD) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "File too large");
        }
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling("." + name + ".upload");
        long size = 0;
        try (InputStream in = request.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                size += n;
                if (size > MAX_UPLOAD) {
                    throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "File too large");
                }
                out.write(buf, 0, n);
            }
        } catch (IOException | ApiException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saved", relative(project, target));
        out.put("size", size);
        return out;
    }

    @DeleteMapping("/api/projects/{id}/inputs/{kind}/{name}")
    public Map<String, Object> deleteInput(@PathVariable String id, @PathVariable String kind, @PathVariable String name,
            Principal principal) throws IOException {
        Project project = projects.need(current.of(principal), id, Roles.CONTRIBUTOR, "Viewers cannot remove input files; ask the project owner");
        Path target = inputPath(project, kind, name);
        if (!Files.isRegularFile(target)) {
            throw ApiException.notFound("No such input file");
        }
        Files.delete(target);
        return Map.of("deleted", relative(project, target));
    }

    // ---- components ---------------------------------------------------------------------------

    @GetMapping("/api/projects/{id}/component-choices")
    @SuppressWarnings("unchecked")
    public Map<String, Object> components(@PathVariable String id, Principal principal) {
        Project project = projects.get(current.of(principal), id);
        LoadedConfig loaded = RunsController.loaded(projects, project);
        Map<String, Object> out;
        try {
            out = Prescan.run(loaded);
        } catch (ConfigException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "Cannot read the source folders: " + e.getMessage());
        }
        // the pre-scan reads no file, so its types are a first guess; the last scan knows better
        Map<String, String> scanned = new LinkedHashMap<>();
        try {
            Path f = Runs.resolve(loaded.outputDir(), "latest").resolve("data").resolve("components.json");
            if (Files.isRegularFile(f)) {
                for (Object c : JSON.readValue(f.toFile(), List.class)) {
                    Map<String, Object> m = (Map<String, Object>) c;
                    scanned.put(String.valueOf(m.get("name")), String.valueOf(m.get("type")));
                }
            }
        } catch (Runs.RunNotFound e) {
            // no scan yet
        }
        for (Map<String, Object> c : (List<Map<String, Object>>) out.get("components")) {
            c.put("scan_type", scanned.get((String) c.get("name")));
        }
        ((List<Map<String, Object>>) out.get("roots")).forEach(r -> r.put("path", relativeSource((String) r.get("path"))));
        return out;
    }

    @PutMapping("/api/projects/{id}/component-choices")
    public Map<String, Object> putComponents(@PathVariable String id, @RequestBody ComponentsBody body, Principal principal) {
        Project project = projects.need(current.of(principal), id, Roles.OWNER, "Only the owner or an admin can change the components");
        LoadedConfig loaded = RunsController.loaded(projects, project);
        try {
            ConfigEdits.setComponentChoices(loaded.overridesPath(), body.included() == null ? Map.of() : body.included(),
                    body.types() == null ? Map.of() : body.types());
        } catch (ConfigException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        }
        return components(id, principal);
    }

    // ---- tool checks --------------------------------------------------------------------------

    @GetMapping("/api/projects/{id}/doctor")
    public List<Map<String, Object>> doctor(@PathVariable String id, Principal principal) {
        Project project = projects.get(current.of(principal), id);
        AuditConfig cfg = null;
        Path out = null;
        try {
            LoadedConfig loaded = ConfigLoader.load(projects.configPath(project.id()));
            cfg = loaded.config();
            out = loaded.outputDir();
        } catch (ConfigException e) {
            // the checks run with the defaults
        }
        return Doctor.run(cfg, out).stream().map(Doctor.Check::toMap).toList();
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** A path inside the project folder, relative to it (the site never shows its own folders). */
    private String relative(Project project, Path p) {
        Path folder = projects.folder(project.id());
        Path abs = p.toAbsolutePath().normalize();
        return abs.startsWith(folder) ? folder.relativize(abs).toString().replace('\\', '/') : abs.toString();
    }

    private String relativeSource(String path) {
        return path == null ? null : path.replace('\\', '/');
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "Cannot read " + path.getFileName() + ": " + e.getMessage());
        }
    }

    private static void write(Path path, String text) {
        try {
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot write " + path.getFileName() + ": " + e.getMessage());
        }
    }
}
