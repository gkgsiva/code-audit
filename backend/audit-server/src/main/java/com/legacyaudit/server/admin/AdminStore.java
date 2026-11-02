package com.legacyaudit.server.admin;

import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.model.config.ConfigReader;
import com.legacyaudit.model.config.PyYaml;
import com.legacyaudit.server.ServerSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code admin.yaml} read once and kept in memory; the values the environment fixes
 * ({@code LEGACY_AUDIT_MAX_SCANS}, {@code LEGACY_AUDIT_KEEP_RUNS}) are applied on every read and
 * shown as locked on the admin page ({@code AdminStore} of the Python hosted site).
 */
@Component
public class AdminStore {

    static final String FILE = "admin.yaml";
    static final String HEADER = "# legacy-audit hosting settings, written by the admin page. No secrets here.\n";
    /** audit.yaml keys that belong to each project, never to the defaults. */
    static final List<String> PER_PROJECT = List.of("application", "source", "components", "inputs_dir", "output_dir");

    private final ServerSettings host;
    private final Path path;
    private AdminSettings cache;

    public AdminStore(ServerSettings host) {
        this.host = host;
        this.path = host.adminDir().resolve(FILE);
    }

    public Path path() {
        return path;
    }

    public synchronized AdminSettings get() {
        if (cache == null) {
            cache = read();
        }
        AdminSettings.Limits l = cache.limits();
        return cache.withLimits(new AdminSettings.Limits(
                host.maxScans() != null ? host.maxScans() : l.maxScans(),
                host.keepRuns() != null ? host.keepRuns() : l.keepRuns(),
                l.scanTimeoutMinutes(), l.cloneKeepDays(), l.minFreeGb()));
    }

    /** Settings the environment fixes, by their dotted key. */
    public Map<String, Object> envLocked() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (host.maxScans() != null) {
            out.put("limits.max_scans", host.maxScans());
        }
        if (host.keepRuns() != null) {
            out.put("limits.keep_runs", host.keepRuns());
        }
        return out;
    }

    private AdminSettings read() {
        if (!Files.isRegularFile(path)) {
            return AdminSettings.initial();
        }
        try {
            Object data = ConfigLoader.readYaml(path);
            return AdminSettings.parse(data instanceof Map<?, ?> m ? m : Map.of());
        } catch (ConfigException | AdminSettings.Invalid e) {
            throw new AdminSettings.Invalid(path + " is invalid: " + e.getMessage());
        }
    }

    /** Checks and writes the settings; throws {@link AdminSettings.Invalid} with every problem. */
    public synchronized AdminSettings save(Map<?, ?> data) {
        AdminSettings s = AdminSettings.parse(data);
        validateDefaults(s.defaults());
        String text = HEADER + PyYaml.dump(s.toMap(), 80);
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(FILE + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new AdminSettings.Invalid("Cannot write " + path + ": " + e.getMessage());
        }
        cache = s;
        return get();
    }

    /** The defaults must be valid audit.yaml settings (checked with a placeholder application). */
    static void validateDefaults(Map<String, Object> defaults) {
        for (String key : PER_PROJECT) {
            if (defaults.containsKey(key)) {
                throw new AdminSettings.Invalid("'" + key + "' is set per project, not in the defaults");
            }
        }
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("application", Map.of("name", "check"));
        probe.putAll(defaults);
        try {
            ConfigReader.parse(probe, "defaults");
        } catch (ConfigException e) {
            throw new AdminSettings.Invalid(e.getMessage());
        }
    }
}
