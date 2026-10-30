package com.legacyaudit.server.projects;

import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.server.ServerSettings;
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
 * Keeps every project inside its folder whenever its {@code audit.yaml} is loaded, as the Python
 * hosted site's {@code AdminLayer} did: output, inputs and overrides stay in the project, a
 * local source must sit under a sources root or in the project folder, and a Git address must
 * be https://, ssh:// or git@. A config outside the audits root (the command line in the same
 * process, tests) is left alone. Settings edited on the web page cannot reach other folders of
 * the server this way.
 */
@Component
public class HostedLayer {

    static final List<String> GIT_SCHEMES = List.of("https://", "http://", "ssh://", "git@");

    private final ServerSettings settings;
    private Runnable remove = () -> { };

    public HostedLayer(ServerSettings settings) {
        this.settings = settings;
    }

    @PostConstruct
    void register() {
        remove = ConfigLoader.addLayer(this::apply);
    }

    @PreDestroy
    void unregister() {
        remove.run();
    }

    /** The folders a local source may point into. */
    public List<Path> sourceRoots() {
        return settings.sourcesRoots();
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
        Map<String, Object> merged = new LinkedHashMap<>();
        map.forEach((k, v) -> merged.put(String.valueOf(k), v));
        confine(merged, project);
        return merged;
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
