package com.legacyaudit.server;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings read from application.yml, which maps the LEGACY_AUDIT_* environment variables of the
 * hosted site onto them.
 *
 * @param auth login provider: {@code dummy} (name only) for now; {@code okta} comes later
 * @param admins user names that are always admins
 * @param auditsRoot folder holding one sub-folder per project (audit.yaml, results)
 * @param sourcesRoots folders a new project may scan; the folder picker starts there
 * @param maxScans scans that may run at the same time; more wait in a queue
 */
@ConfigurationProperties("legacy-audit")
public record ServerSettings(String auth, List<String> admins, Path auditsRoot, List<Path> sourcesRoots, Integer maxScans) {

    public ServerSettings {
        auth = auth == null || auth.isBlank() ? "dummy" : auth.strip();
        admins = admins == null ? List.of() : admins.stream().map(String::strip).filter(a -> !a.isEmpty()).toList();
        auditsRoot = (auditsRoot == null ? Path.of("audits") : auditsRoot).toAbsolutePath().normalize();
        sourcesRoots = sourcesRoots == null ? List.of() : sourcesRoots.stream().map(p -> p.toAbsolutePath().normalize()).toList();
        maxScans = maxScans == null || maxScans < 1 ? 2 : maxScans;
    }
}
