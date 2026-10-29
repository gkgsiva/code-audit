package com.legacyaudit.model.config;

import java.nio.file.Path;

/**
 * A validated config plus where it came from.
 *
 * @param path the config file, absolute
 * @param baseDir its folder; relative paths in the config are resolved against it
 * @param hash 16 hex characters identifying the settings (part of every run id)
 */
public record LoadedConfig(AuditConfig config, Path path, Path baseDir, String hash) {

    public Path resolve(String value) {
        Path p = Path.of(value);
        return p.isAbsolute() ? p : baseDir.resolve(p).toAbsolutePath().normalize();
    }

    public Path outputDir() {
        return resolve(config.outputDir());
    }

    public Path inputsDir() {
        return resolve(config.inputsDir());
    }

    public Path overridesPath() {
        return resolve(config.overridesFile());
    }
}
