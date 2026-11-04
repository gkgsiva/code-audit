package com.legacyaudit.server.vulndb;

import com.legacyaudit.engine.security.Vulns;
import com.legacyaudit.server.ServerSettings;
import com.legacyaudit.server.admin.Activity;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The hosted site's OWASP Dependency-Check database: one NVD copy for every project, kept under
 * {@code <audits>/.legacy-audit/vulndb/dependency-check} (outside the image, so it survives a
 * rebuild). Scans only read it ({@code offline: true}); this class fills and refreshes it with
 * {@code dependency-check --updateonly}: from the Admin page, every night at
 * {@code LEGACY_AUDIT_VULNDB_REFRESH} (default 02:00, {@code off} to stop) and at start when it is
 * still empty and an NVD API key or mirror is set. The key ({@code NVD_API_KEY}) and a proxy
 * password go to the tool in a short-lived properties file, never on its command line, in a
 * setting or in a log. The proxy is the server's own ({@code HTTPS_PROXY}, {@code NO_PROXY});
 * {@code NVD_MIRROR_URL} points at a company copy of the NVD data feed instead of nvd.nist.gov.
 */
@Component
public class VulnDb {

    private static final Logger LOG = LoggerFactory.getLogger(VulnDb.class);
    static final List<String> NAMES = List.of("dependency-check", "dependency-check.sh", "dependency-check.bat");
    static final int TIMEOUT_HOURS = 4;
    static final String DEFAULT_REFRESH = "02:00";

    private final ServerSettings settings;
    private final Activity activity;
    private final Function<String, String> env;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("vulndb").daemon().factory());
    private volatile Instant startedAt;
    private volatile String version;

    @org.springframework.beans.factory.annotation.Autowired
    public VulnDb(ServerSettings settings, Activity activity) {
        this(settings, activity, System::getenv);
    }

    VulnDb(ServerSettings settings, Activity activity, Function<String, String> env) {
        this.settings = settings;
        this.activity = activity;
        this.env = env;
    }

    /** The database folder every hosted project's {@code security.dependency_check.data_dir} points to. */
    public static Path dir(ServerSettings settings) {
        return settings.adminDir().resolve("vulndb").resolve("dependency-check");
    }

    public Path dir() {
        return dir(settings);
    }

    Path stateFile() {
        return dir().getParent().resolve("last-update.properties");
    }

    Path logFile() {
        return dir().getParent().resolve("update.log");
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        try {
            Files.createDirectories(dir());
        } catch (IOException e) {
            LOG.warn("Cannot create the vulnerability database folder {}: {}", dir(), e.getMessage());
        }
        if (exe() != null && !Vulns.dependencyCheckDatabaseReady(dir()) && (hasKey() || mirror() != null)) {
            LOG.info("The Dependency-Check database is empty: downloading it now");
            update("server start");
        }
        timer.scheduleWithFixedDelay(this::nightly, 5, 5, TimeUnit.MINUTES);
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }

    /** {@code HH:mm} of the nightly refresh, or null when it is off. */
    LocalTime refreshAt() {
        String v = env.apply("LEGACY_AUDIT_VULNDB_REFRESH");
        v = v == null || v.isBlank() ? DEFAULT_REFRESH : v.strip();
        if (v.equalsIgnoreCase("off") || v.equalsIgnoreCase("false") || v.equalsIgnoreCase("none")) {
            return null;
        }
        try {
            return LocalTime.parse(v.length() == 4 ? "0" + v : v);
        } catch (java.time.format.DateTimeParseException e) {
            LOG.warn("LEGACY_AUDIT_VULNDB_REFRESH={} is not a time like 02:00; using {}", v, DEFAULT_REFRESH);
            return LocalTime.parse(DEFAULT_REFRESH);
        }
    }

    void nightly() {
        try {
            LocalTime at = refreshAt();
            if (at == null || exe() == null) {
                return;
            }
            ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
            Instant last = lastAttempt();
            if (dueAt(now, at, last)) {
                update("nightly refresh");
            }
        } catch (RuntimeException e) {
            LOG.error("Nightly vulnerability database refresh failed", e);
        }
    }

    /** True when today's refresh time has passed and nothing was tried since then. */
    static boolean dueAt(ZonedDateTime now, LocalTime at, Instant lastAttempt) {
        ZonedDateTime today = now.toLocalDate().atTime(at).atZone(now.getZone());
        return !now.isBefore(today) && (lastAttempt == null || lastAttempt.isBefore(today.toInstant()));
    }

    public String exe() {
        return Vulns.findTool(null, NAMES);
    }

    boolean hasKey() {
        String k = env.apply("NVD_API_KEY");
        return k != null && !k.isBlank();
    }

    String mirror() {
        String m = env.apply("NVD_MIRROR_URL");
        return m == null || m.isBlank() ? null : m.strip();
    }

    public boolean running() {
        return running.get();
    }

    /** Starts an update in the background; false when one is already running or the tool is missing. */
    public boolean update(String by) {
        String exe = exe();
        if (exe == null || !running.compareAndSet(false, true)) {
            return false;
        }
        startedAt = Instant.now();
        Thread.ofPlatform().name("vulndb-update").daemon().start(() -> {
            try {
                Result r = runUpdate(exe);
                saveState(r);
                LOG.info("Dependency-Check database update {}: {}", r.ok() ? "done" : "failed", r.message());
                activity.log(by, "update vulnerability database", null, Map.of("ok", r.ok(), "message", r.message()));
            } catch (RuntimeException e) {
                LOG.error("Dependency-Check database update failed", e);
                saveState(new Result(false, String.valueOf(e.getMessage())));
            } finally {
                running.set(false);
                startedAt = null;
            }
        });
        return true;
    }

    record Result(boolean ok, String message) {}

    /** Proxy host, port, user and password from a URL such as http://user:pass@proxy:8080. */
    record Proxy(String host, int port, String user, String password) {

        static Proxy parse(String url) {
            if (url == null || url.isBlank()) {
                return null;
            }
            String u = url.strip();
            if (!u.contains("://")) {
                u = "http://" + u;
            }
            URI uri;
            try {
                uri = URI.create(u);
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (uri.getHost() == null) {
                return null;
            }
            String user = null;
            String pass = null;
            if (uri.getRawUserInfo() != null) {
                String[] parts = uri.getRawUserInfo().split(":", 2);
                user = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                pass = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : null;
            }
            int port = uri.getPort() > 0 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
            return new Proxy(uri.getHost(), port, user, pass);
        }
    }

    String envAny(String... names) {
        for (String n : names) {
            String v = env.apply(n);
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }

    /**
     * The command line and the secret settings (written to a properties file passed with -P).
     * Nothing secret is on the command line, so it can be logged as it is.
     */
    record Command(List<String> args, Properties secrets) {}

    Command command(String exe, Path secretsFile) {
        List<String> cmd = new ArrayList<>(List.of(exe, "--updateonly", "--data", dir().toString()));
        Properties secrets = new Properties();
        if (hasKey()) {
            secrets.setProperty("nvd.api.key", env.apply("NVD_API_KEY").strip());
        }
        if (mirror() != null) {
            cmd.addAll(List.of("--nvdDatafeed", mirror()));
        }
        Proxy proxy = Proxy.parse(envAny("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy"));
        if (proxy != null) {
            cmd.addAll(List.of("--proxyserver", proxy.host(), "--proxyport", String.valueOf(proxy.port())));
            if (proxy.user() != null && !proxy.user().isEmpty()) {
                cmd.addAll(List.of("--proxyuser", proxy.user()));
            }
            if (proxy.password() != null && !proxy.password().isEmpty()) {
                secrets.setProperty("proxy.password", proxy.password());
            }
            String noProxy = envAny("NO_PROXY", "no_proxy");
            if (noProxy != null) {
                cmd.addAll(List.of("--nonProxyHosts", noProxy.replace(" ", "")));
            }
        }
        if (!secrets.isEmpty()) {
            cmd.addAll(List.of("-P", secretsFile.toString()));
        }
        return new Command(cmd, secrets);
    }

    Result runUpdate(String exe) {
        Path secretsFile = null;
        ReentrantLock lock = Vulns.databaseLock(dir().toString());
        lock.lock();
        try {
            Files.createDirectories(dir());
            secretsFile = Files.createTempFile("legacy-audit-nvd-", ".properties");
            try {
                Files.setPosixFilePermissions(secretsFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException e) {
                // Windows: the temporary folder is the user's own
            }
            Command c = command(exe, secretsFile);
            if (!c.secrets().isEmpty()) {
                try (OutputStream out = Files.newOutputStream(secretsFile)) {
                    c.secrets().store(out, null);
                }
            }
            LOG.info("Running {}", String.join(" ", c.args()));
            if (!hasKey() && mirror() == null) {
                LOG.warn("No NVD_API_KEY: the NVD download is slow (it can take hours); get a free key at "
                        + "https://nvd.nist.gov/developers/request-an-api-key");
            }
            long before = databaseModified();
            ProcessBuilder pb = new ProcessBuilder(c.args()).redirectErrorStream(true).redirectOutput(logFile().toFile());
            Process p = pb.start();
            if (!p.waitFor(TIMEOUT_HOURS, TimeUnit.HOURS)) {
                p.destroyForcibly();
                return new Result(false, "stopped after " + TIMEOUT_HOURS + " hours");
            }
            int code = p.exitValue();
            boolean ready = Vulns.dependencyCheckDatabaseReady(dir());
            if (code == 0 && ready) {
                return new Result(true, "database up to date");
            }
            if (ready && databaseModified() > before) {
                // The NVD part went in; an extra feed (CISA known exploited list, RetireJS, hosted
                // suppressions) could not be read. Scans work, with less detail.
                return new Result(true, "database updated, with a warning (exit code " + code + "): " + lastError());
            }
            return new Result(false, "exit code " + code + ": " + lastError());
        } catch (IOException e) {
            return new Result(false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "interrupted");
        } finally {
            lock.unlock();
            if (secretsFile != null) {
                try {
                    Files.deleteIfExists(secretsFile);
                } catch (IOException e) {
                    LOG.warn("Could not delete {}", secretsFile);
                }
            }
        }
    }

    long databaseModified() {
        try {
            Path db = dir().resolve("odc.mv.db");
            return Files.isRegularFile(db) ? Files.getLastModifiedTime(db).toMillis() : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /** The last ERROR (or last) line of the tool's output, cut to 300 characters. */
    String lastError() {
        List<String> lines = tail(200);
        String pick = "";
        for (String l : lines) {
            if (!l.isBlank()) {
                pick = l;
            }
        }
        for (String l : lines) {
            if (l.contains("ERROR")) {
                pick = l;
            }
        }
        pick = pick.strip();
        return pick.length() > 300 ? pick.substring(0, 300) + "..." : pick;
    }

    List<String> tail(int n) {
        try {
            if (!Files.isRegularFile(logFile())) {
                return List.of();
            }
            List<String> all = Files.readAllLines(logFile(), StandardCharsets.UTF_8);
            return all.subList(Math.max(0, all.size() - n), all.size());
        } catch (IOException | java.io.UncheckedIOException e) {
            return List.of();
        }
    }

    void saveState(Result r) {
        Properties p = new Properties();
        p.setProperty("finished", Instant.now().toString());
        p.setProperty("ok", String.valueOf(r.ok()));
        p.setProperty("message", r.message() == null ? "" : r.message());
        if (r.ok()) {
            p.setProperty("last_success", p.getProperty("finished"));
        } else {
            Properties old = loadState();
            if (old.getProperty("last_success") != null) {
                p.setProperty("last_success", old.getProperty("last_success"));
            }
        }
        try {
            Files.createDirectories(stateFile().getParent());
            try (OutputStream out = Files.newOutputStream(stateFile())) {
                p.store(out, null);
            }
        } catch (IOException e) {
            LOG.warn("Could not save {}: {}", stateFile(), e.getMessage());
        }
    }

    Properties loadState() {
        Properties p = new Properties();
        if (Files.isRegularFile(stateFile())) {
            try (var in = Files.newInputStream(stateFile())) {
                p.load(in);
            } catch (IOException e) {
                // as if never updated
            }
        }
        return p;
    }

    Instant lastAttempt() {
        String f = loadState().getProperty("finished");
        try {
            return f == null ? null : Instant.parse(f);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    String toolVersion(String exe) {
        if (version == null && exe != null) {
            try {
                Process p = new ProcessBuilder(exe, "--version").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                p.waitFor(60, TimeUnit.SECONDS);
                for (String line : out.split("\\R")) {
                    if (line.contains("version")) {
                        version = line.replaceFirst(".*version\\s*", "").strip();
                    }
                }
            } catch (IOException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return version;
    }

    /** What the Admin > Health page shows. */
    public Map<String, Object> status() {
        String exe = exe();
        Properties state = loadState();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool", exe);
        out.put("version", toolVersion(exe));
        out.put("folder", dir().toString());
        boolean ready = Vulns.dependencyCheckDatabaseReady(dir());
        out.put("ready", ready);
        Path db = dir().resolve("odc.mv.db");
        long bytes = 0;
        try {
            bytes = ready ? Files.size(db) : 0;
        } catch (IOException e) {
            // unknown size
        }
        out.put("bytes", bytes);
        out.put("last_success", state.getProperty("last_success"));
        out.put("last_attempt", state.getProperty("finished"));
        out.put("last_ok", state.getProperty("ok") == null ? null : Boolean.valueOf(state.getProperty("ok")));
        out.put("last_message", state.getProperty("message"));
        out.put("running", running.get());
        out.put("started_at", startedAt == null ? null : startedAt.toString());
        out.put("api_key", hasKey());
        out.put("mirror", mirror());
        LocalTime at = refreshAt();
        out.put("refresh_at", at == null ? null : at.toString());
        out.put("output", running.get() || Boolean.FALSE.toString().equals(state.getProperty("ok")) ? tail(15) : List.of());
        return out;
    }
}
