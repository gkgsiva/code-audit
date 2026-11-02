package com.legacyaudit.cli;

import com.legacyaudit.engine.scan.GitClones;
import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.model.config.LoadedConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** {@code legacy-audit purge-cache}: deletes the cloned Git sources and, with a config, its per-file scan cache. */
@Command(name = "purge-cache", description = "Delete cloned Git sources (and, with --config, the per-file scan cache).")
final class PurgeCacheCommand implements Callable<Integer> {

    @Option(names = {"-c", "--config"}, description = "Also clear this config's per-file scan cache.")
    Path config;

    @Option(names = "--cache-dir", description = "Git clone cache folder (default: LEGACY_AUDIT_CACHE or ~/.legacy-audit/cache).")
    Path cacheDir;

    @Override
    public Integer call() {
        Path target = GitClones.purge(cacheDir != null ? cacheDir : GitClones.defaultCacheDir());
        System.out.println("Removed Git clone cache " + target);
        if (config != null) {
            LoadedConfig loaded;
            try {
                loaded = ConfigLoader.load(config);
            } catch (ConfigException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
            Path fileCache = loaded.outputDir().resolve("cache");
            if (Files.exists(fileCache)) {
                GitClones.deleteTree(fileCache);
            }
            System.out.println("Removed file cache " + fileCache);
        }
        return 0;
    }
}
