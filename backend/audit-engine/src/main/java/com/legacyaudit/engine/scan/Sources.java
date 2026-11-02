package com.legacyaudit.engine.scan;

import com.legacyaudit.model.config.AuditConfig;
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
        return resolve(loaded, line -> {});
    }

    /**
     * The folders to scan; Git repositories are cloned or updated first ({@link GitClones}), and
     * {@code log} gets what git did (warnings start with {@code WARNING }).
     */
    public static List<Root> resolve(LoadedConfig loaded, java.util.function.Consumer<String> log) {
        List<Root> roots = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        if (loaded.config().source() instanceof GitSource g) {
            for (AuditConfig.GitRepo repo : g.repos()) {
                GitClones.Fetched f = GitClones.fetch(repo, g.cacheDir(), log);
                String name = unique(repo.name() != null ? repo.name() : GitClones.repoSlug(repo.url()).get(2), taken);
                roots.add(new Root(name, f.path(), "git", f.url(), repo.ref(), f.commit(), List.copyOf(repo.paths())));
            }
            return roots;
        }
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
