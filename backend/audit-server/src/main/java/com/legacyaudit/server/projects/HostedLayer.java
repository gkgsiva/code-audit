package com.legacyaudit.server.projects;

import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.server.ServerSettings;
import com.legacyaudit.server.admin.AdminStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The admin's common settings and the site's folder rules, applied whenever a project's
 * {@code audit.yaml} is loaded, as the Python hosted site's {@code AdminLayer} did. Order: the
 * admin {@code defaults} first, the project's own file over them, then every {@code locked} key
 * put back to the admin's value (or removed, so the built-in default applies). Then each project
 * is kept inside its folder: output, inputs and overrides stay in the project, a
 * local source must sit under a sources root or in the project folder, and a Git address must
 * be https://, ssh:// or git@. A config outside the audits root (the command line in the same
 * process, tests) is left alone. Settings edited on the web page cannot reach other folders of
 * the server this way.
 */
@Component
public class HostedLayer {

    static final List<String> GIT_SCHEMES = List.of("https://", "http://", "ssh://", "git@");

    private static final Object MISSING = new Object();

    private final ServerSettings settings;
    private final AdminStore admin;
    private Runnable remove = () -> { };

    public HostedLayer(ServerSettings settings, AdminStore admin) {
        this.settings = settings;
        this.admin = admin;
    }

    @PostConstruct
    void register() {
        remove = ConfigLoader.addLayer(this::apply);
    }

    @PreDestroy
    void unregister() {
        remove.run();
    }

    /** The folders a local source may point into: the sources roots and the admin's extra ones. */
    public List<Path> sourceRoots() {
        List<Path> roots = new ArrayList<>(settings.sourcesRoots());
        for (String extra : admin.get().paths().extraSourceRoots()) {
            roots.add(Path.of(extra).toAbsolutePath().normalize());
        }
        return roots;
    }

    /** Where Git sources are cloned: the admin's {@code paths.git_cache_dir}, else {@code <admin folder>/git}. */
    public Path gitCache() {
        String dir = admin.get().paths().gitCacheDir();
        return (dir != null && !dir.isBlank() ? Path.of(dir) : settings.adminDir().resolve("git")).toAbsolutePath().normalize();
    }

    /** {@code over} wins; mappings merge key by key, anything else (lists too) is replaced. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> deepMerge(Map<String, Object> base, Map<?, ?> over) {
        Map<String, Object> out = copy(base);
        over.forEach((k, v) -> {
            String key = String.valueOf(k);
            if (v instanceof Map<?, ?> vm && out.get(key) instanceof Map<?, ?> om) {
                out.put(key, deepMerge((Map<String, Object>) om, vm));
            } else {
                out.put(key, copyValue(v));
            }
        });
        return out;
    }

    static Object getPath(Map<String, Object> data, String dotted) {
        Object node = data;
        for (String part : dotted.split("\\.")) {
            if (!(node instanceof Map<?, ?> m) || !m.containsKey(part)) {
                return MISSING;
            }
            node = m.get(part);
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    static void setPath(Map<String, Object> data, String dotted, Object value) {
        String[] parts = dotted.split("\\.");
        Map<String, Object> node = data;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!(node.get(parts[i]) instanceof Map<?, ?>)) {
                node.put(parts[i], new LinkedHashMap<>());
            }
            node = (Map<String, Object>) node.get(parts[i]);
        }
        if (value == MISSING) {
            node.remove(parts[parts.length - 1]);
        } else {
            node.put(parts[parts.length - 1], copyValue(value));
        }
    }

    private static Map<String, Object> copy(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), copyValue(v)));
        return out;
    }

    private static Object copyValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            return copy(m);
        }
        if (v instanceof List<?> l) {
            return new ArrayList<>(l.stream().map(HostedLayer::copyValue).toList());
        }
        return v;
    }

    Object apply(Object data, Path configPath) {
        Path root = settings.auditsRoot();
        Path project = configPath.getParent();
        if (project == null || !project.startsWith(root) || project.equals(root)) {
            return data; // not a hosted project
        }
        if (!(data instanceof Map<?, ?> map)) {
            return data; // the config reader reports it
        }
        var s = admin.get();
        Map<String, Object> merged = deepMerge(s.defaults(), map);
        for (String key : s.locked()) {
            setPath(merged, key, getPath(s.defaults(), key));
        }
        confine(merged, project);
        sharedVulnerabilityDatabase(merged);
        return merged;
    }

    /**
     * Dependency-Check reads the site's one NVD copy ({@code VulnDb}) unless the project names its
     * own {@code security.dependency_check.data_dir}.
     */
    @SuppressWarnings("unchecked")
    void sharedVulnerabilityDatabase(Map<String, Object> data) {
        Object dataDir = getPath(data, "security.dependency_check.data_dir");
        if (dataDir == MISSING || dataDir == null || String.valueOf(dataDir).isBlank()) {
            if (data.get("security") instanceof Map<?, ?> sec && !(sec.get("dependency_check") instanceof Map<?, ?>)
                    && sec.containsKey("dependency_check")) {
                return; // not a mapping: the config reader reports it
            }
            setPath(data, "security.dependency_check.data_dir", com.legacyaudit.server.vulndb.VulnDb.dir(settings).toString());
        }
    }

    /** The real path (symbolic links followed) when it exists, else the normalized absolute path. */
    static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (java.io.IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    void confine(Map<String, Object> data, Path project) {
        for (String key : List.of("inputs_dir", "output_dir", "overrides_file")) {
            if (!(data.get(key) instanceof String value)) {
                continue;
            }
            String raw = value.replace('\\', '/');
            boolean drive = raw.length() > 1 && raw.charAt(1) == ':';
            if (raw.startsWith("/") || drive || List.of(raw.split("/")).contains("..")) {
                throw new ConfigException(key + ": '" + value + "' must be a folder inside the project on the hosted site");
            }
        }
        Object source = data.get("source");
        Map<?, ?> src = source instanceof Map<?, ?> m ? m : Map.of("type", "local", "paths", List.of("."));
        Object type = src.get("type");
        if ("git".equals(type == null ? "local" : type)) {
            if (src.get("repos") instanceof List<?> repos) {
                for (Object r : repos) {
                    String url = r instanceof Map<?, ?> rm && rm.get("url") != null ? String.valueOf(rm.get("url")) : "";
                    if (GIT_SCHEMES.stream().noneMatch(url::startsWith)) {
                        throw new ConfigException("source.repos: '" + url + "' must be an https://, ssh:// or git@ address");
                    }
                }
            }
            Map<String, Object> withCache = new java.util.LinkedHashMap<>();
            src.forEach((k, v) -> withCache.put(String.valueOf(k), v));
            withCache.put("cache_dir", gitCache().toString());
            data.put("source", withCache);
            return;
        }
        List<Path> roots = new ArrayList<>(sourceRoots().stream().map(HostedLayer::real).toList());
        Path real = real(project);
        Object paths = src.get("paths");
        List<?> values = paths instanceof List<?> l && !l.isEmpty() ? l : List.of(".");
        for (Object value : values) {
            String raw = String.valueOf(value).replace('\\', '/');
            Path p = Path.of(raw);
            Path resolved = real(p.isAbsolute() ? p : real.resolve(p));
            boolean inside = resolved.startsWith(real) || roots.stream().anyMatch(resolved::startsWith);
            if (!inside) {
                String allowed = roots.isEmpty() ? "(none)" : roots.stream().map(Path::toString).collect(Collectors.joining(", "));
                throw new ConfigException("source.paths: '" + value + "' is outside the folders this site may scan (" + allowed
                        + ", or the project folder)");
            }
        }
    }
}
