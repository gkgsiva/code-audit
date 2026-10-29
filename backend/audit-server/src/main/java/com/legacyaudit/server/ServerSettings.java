package com.legacyaudit.server;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings read from application.yml, which maps the LEGACY_AUDIT_* environment variables of the
 * hosted site onto them.
 *
 * @param auth login provider: {@code dummy} (name only) for now; {@code okta} comes later
 * @param admins user names that are always admins
 */
@ConfigurationProperties("legacy-audit")
public record ServerSettings(String auth, List<String> admins) {

    public ServerSettings {
        auth = auth == null || auth.isBlank() ? "dummy" : auth.strip();
        admins = admins == null ? List.of() : admins.stream().map(String::strip).filter(a -> !a.isEmpty()).toList();
    }
}
