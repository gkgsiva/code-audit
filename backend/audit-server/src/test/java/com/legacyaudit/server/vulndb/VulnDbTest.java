package com.legacyaudit.server.vulndb;

import static org.assertj.core.api.Assertions.assertThat;

import com.legacyaudit.server.ServerSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The Dependency-Check database updates: command line, secrets, proxy, nightly time and state. */
class VulnDbTest {

    static VulnDb db(Path audits, Map<String, String> env) {
        return new VulnDb(new ServerSettings(null, null, audits, null, null, null), null, env::get);
    }

    @Test
    void theKeyAndProxyPasswordStayOffTheCommandLine(@TempDir Path audits) {
        Map<String, String> env = new HashMap<>();
        env.put("NVD_API_KEY", "secret-key");
        env.put("HTTPS_PROXY", "http://me:p%40ss@proxy.example.com:3128");
        env.put("NO_PROXY", "localhost, nexus.example.com");
        env.put("NVD_MIRROR_URL", "https://nvd-mirror.example.com/feeds/");
        VulnDb v = db(audits, env);
        VulnDb.Command c = v.command("dependency-check", Path.of("secrets.properties"));
        String line = String.join(" ", c.args());
        assertThat(line).startsWith("dependency-check --updateonly --data " + v.dir())
                .contains("--nvdDatafeed https://nvd-mirror.example.com/feeds/")
                .contains("--proxyserver proxy.example.com --proxyport 3128 --proxyuser me")
                .contains("--nonProxyHosts localhost,nexus.example.com")
                .endsWith("-P secrets.properties")
                .doesNotContain("secret-key").doesNotContain("p@ss");
        assertThat(c.secrets().getProperty("nvd.api.key")).isEqualTo("secret-key");
        assertThat(c.secrets().getProperty("proxy.password")).isEqualTo("p@ss");

        VulnDb plain = db(audits, Map.of());
        assertThat(plain.command("dc", Path.of("s")).args()).isEqualTo(List.of("dc", "--updateonly", "--data", plain.dir().toString()));
        assertThat(plain.dir()).isEqualTo(audits.toAbsolutePath().resolve(".legacy-audit/vulndb/dependency-check"));
    }

    @Test
    void proxyAddresses() {
        assertThat(VulnDb.Proxy.parse("proxy:8080")).isEqualTo(new VulnDb.Proxy("proxy", 8080, null, null));
        assertThat(VulnDb.Proxy.parse("https://proxy.example.com")).isEqualTo(new VulnDb.Proxy("proxy.example.com", 443, null, null));
        assertThat(VulnDb.Proxy.parse("")).isNull();
        assertThat(VulnDb.Proxy.parse("http://")).isNull();
    }

    @Test
    void nightlyRefreshTime(@TempDir Path audits) {
        assertThat(db(audits, Map.of()).refreshAt()).isEqualTo(LocalTime.of(2, 0));
        assertThat(db(audits, Map.of("LEGACY_AUDIT_VULNDB_REFRESH", "3:30")).refreshAt()).isEqualTo(LocalTime.of(3, 30));
        assertThat(db(audits, Map.of("LEGACY_AUDIT_VULNDB_REFRESH", "off")).refreshAt()).isNull();
        assertThat(db(audits, Map.of("LEGACY_AUDIT_VULNDB_REFRESH", "soon")).refreshAt()).isEqualTo(LocalTime.of(2, 0));

        ZonedDateTime morning = ZonedDateTime.of(2026, 11, 4, 2, 5, 0, 0, ZoneOffset.UTC);
        LocalTime two = LocalTime.of(2, 0);
        assertThat(VulnDb.dueAt(morning, two, null)).isTrue();
        assertThat(VulnDb.dueAt(morning, two, Instant.parse("2026-11-03T02:01:00Z"))).isTrue();
        assertThat(VulnDb.dueAt(morning, two, Instant.parse("2026-11-04T02:01:00Z"))).isFalse();
        assertThat(VulnDb.dueAt(morning.withHour(1), two, null)).isFalse();
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void anUpdateIsRunAndRemembered(@TempDir Path audits, @TempDir Path bin) throws Exception {
        Path ok = bin.resolve("dc-ok");
        Files.writeString(ok, "#!/bin/sh\nwhile [ $# -gt 0 ]; do [ \"$1\" = \"--data\" ] && d=\"$2\"; shift; done\n"
                + "mkdir -p \"$d\" && echo db > \"$d/odc.mv.db\"\necho 'INFO done'\n");
        Path bad = bin.resolve("dc-bad");
        Files.writeString(bad, "#!/bin/sh\necho '[ERROR] Error updating the NVD Data; the NVD returned a 403 or 404 error'\nexit 13\n");
        for (Path p : List.of(ok, bad)) {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        VulnDb v = db(audits, Map.of("NVD_API_KEY", "k"));

        VulnDb.Result r = v.runUpdate(bad.toString());
        v.saveState(r);
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("exit code 13").contains("403 or 404");
        assertThat(v.status()).containsEntry("ready", false).containsEntry("last_ok", false).containsEntry("api_key", true);

        r = v.runUpdate(ok.toString());
        v.saveState(r);
        assertThat(r.ok()).isTrue();
        Map<String, Object> s = v.status();
        assertThat(s).containsEntry("ready", true).containsEntry("last_ok", true).containsEntry("output", List.of());
        assertThat(s.get("last_success")).isNotNull();
        assertThat(Files.readString(v.logFile())).doesNotContain("k\n");
        try (var tmp = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            assertThat(tmp.filter(p -> p.getFileName().toString().startsWith("legacy-audit-nvd-"))).isEmpty();
        }
    }
}
