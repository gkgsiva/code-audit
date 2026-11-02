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
 * @param maxScans jobs that may run at the same time when the environment fixes it, else null
 *     (the admin page decides)
 * @param keepRuns runs kept per project when the environment fixes it, else null
 */
@ConfigurationProperties("legacy-audit")
public record ServerSettings(String auth, List<String> admins, Path auditsRoot, List<Path> sourcesRoots, Integer maxScans,
        Integer keepRuns) {

    public ServerSettings {
        auth = auth == null || auth.isBlank() ? "dummy" : auth.strip();
        admins = admins == null ? List.of() : admins.stream().map(String::strip).filter(a -> !a.isEmpty()).toList();
        auditsRoot = (auditsRoot == null ? Path.of("audits") : auditsRoot).toAbsolutePath().normalize();
        sourcesRoots = sourcesRoots == null ? List.of() : sourcesRoots.stream().map(p -> p.toAbsolutePath().normalize()).toList();
        maxScans = maxScans == null || maxScans < 1 ? null : Math.min(maxScans, 32);
        keepRuns = keepRuns == null || keepRuns < 1 ? null : Math.min(keepRuns, 500);
    }

    /** {@code <audits root>/.legacy-audit}: admin settings, the server log, Git clones. */
    public Path adminDir() {
        return auditsRoot.resolve(".legacy-audit");
    }
}
