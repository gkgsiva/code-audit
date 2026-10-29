package com.legacyaudit.engine.scan;

import com.legacyaudit.model.config.AuditConfig.GitSource;
import com.legacyaudit.model.config.AuditConfig.LocalSource;
import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.LoadedConfig;
import com.legacyaudit.model.facts.SourceInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The config's {@code source} section as folders on disk. */
public final class Sources {

    /** One folder tree to scan; {@code subpaths} limits the walk to parts of it. */
    public record Root(String name, Path path, String type, String url, String ref, String commit, List<String> subpaths) {

        public SourceInfo info() {
            return new SourceInfo(name, type, path.toString().replace('\\', '/'), url, ref, commit);
        }
    }

    private Sources() {}

    public static List<Root> resolve(LoadedConfig loaded) {
        if (loaded.config().source() instanceof GitSource) {
            throw new ConfigException("Git sources are not supported yet by this version (they come in a later phase). "
                    + "Clone the repository into a folder and use source: {type: local, paths: [<folder>]}.");
        }
        List<Root> roots = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (String p : ((LocalSource) loaded.config().source()).paths()) {
            Path path = real(loaded.resolve(p));
            if (!Files.isDirectory(path)) {
                throw new ConfigException("Source folder not found: " + path);
            }
            Path fileName = path.getFileName();
            roots.add(new Root(unique(fileName == null ? "" : fileName.toString(), taken), path, "local", null, null, null, List.of()));
        }
        return roots;
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (java.io.IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    static String unique(String name, Set<String> taken) {
        String base = name.replaceAll("[^A-Za-z0-9._-]+", "_");
        base = base.replaceAll("^_+|_+$", "");
        if (base.isEmpty()) {
            base = "source";
        }
        String candidate = base;
        for (int n = 2; taken.contains(candidate); n++) {
            candidate = base + "-" + n;
        }
        taken.add(candidate);
        return candidate;
    }
}
