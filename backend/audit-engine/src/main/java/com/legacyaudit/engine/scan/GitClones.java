package com.legacyaudit.engine.scan;

import com.legacyaudit.model.config.AuditConfig;
import com.legacyaudit.model.config.AuditConfig.GitRepo;
import com.legacyaudit.model.config.ConfigException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

/**
 * Git sources: a shallow clone of each repository in a cache outside the project, as the Python
 * tool did ({@code legacy_audit/source/git.py}), with the {@code git} command.
 *
 * <p>The token is read from the environment variable named in the config and handed to git as an
 * HTTP header through {@code GIT_CONFIG_*} environment variables, so it never appears in a command
 * line, a remote URL, {@code .git/config}, a log line or an output file. Each clone sits in
 * {@code <cache>/<host>/<owner>/<repo>@<ref>} and records its last use in
 * {@code .git/legacy-audit-clone.json}, which the clean-up of the hosted site reads.
 */
public final class GitClones {

    private static final Pattern SAFE = Pattern.compile("[^A-Za-z0-9._-]+");
    private static final Pattern SCP = Pattern.compile("^[\\w.-]+@([\\w.-]+):(.+)$");
    private static final Pattern USER_AT = Pattern.compile("^[\\w.-]+@");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Inside {@code .git}, so {@code git clean} keeps it. */
    public static final String USED_FILE = "legacy-audit-clone.json";

    private GitClones() {}

    /** A failed git step, in words that never hold the token. */
    public static final class GitException extends ConfigException {
        public GitException(String message) {
            super(message);
        }
    }

    /** One clone in the cache. {@code key} is {@code host/owner/name@ref}; {@code lastUsed} in epoch seconds. */
    public record Clone(Path path, String key, String url, String ref, String commit, double lastUsed, long size) {}

    /** A fetched repository: its folder, the commit checked out and the address without credentials. */
    public record Fetched(Path path, String commit, String url) {}

    /** {@code LEGACY_AUDIT_CACHE}, else {@code ~/.legacy-audit/cache}. */
    public static Path defaultCacheDir() {
        String env = System.getenv("LEGACY_AUDIT_CACHE");
        return env != null && !env.isEmpty() ? Path.of(env) : Path.of(System.getProperty("user.home"), ".legacy-audit", "cache");
    }

    /** Removes any {@code user:password@} part from an http(s) address. */
    public static String stripCredentials(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            int start = url.indexOf("://") + 3;
            int end = url.indexOf('/', start);
            String netloc = end < 0 ? url.substring(start) : url.substring(start, end);
            int at = netloc.lastIndexOf('@');
            if (at >= 0) {
                return url.substring(0, start) + netloc.substring(at + 1) + (end < 0 ? "" : url.substring(end));
            }
        }
        return url;
    }

    /** (host, owner, repo) for the cache folder; local paths use host {@code local}. */
    public static List<String> repoSlug(String url) {
        url = stripCredentials(url);
        String host;
        String path;
        Matcher scp = SCP.matcher(url);
        String scheme = url.contains("://") ? url.substring(0, url.indexOf("://")).toLowerCase(java.util.Locale.ROOT) : "";
        if (scp.matches()) {
            host = scp.group(1);
            path = scp.group(2);
        } else if (List.of("http", "https", "ssh", "git").contains(scheme)) {
            String rest = url.substring(url.indexOf("://") + 3);
            int slash = rest.indexOf('/');
            String netloc = slash < 0 ? rest : rest.substring(0, slash);
            path = slash < 0 ? "" : rest.substring(slash);
            int q = path.indexOf('?');
            path = q < 0 ? path : path.substring(0, q);
            int h = path.indexOf('#');
            path = h < 0 ? path : path.substring(0, h);
            String hn = netloc.substring(netloc.lastIndexOf('@') + 1);
            if (hn.startsWith("[")) {
                hn = hn.substring(1, Math.max(1, hn.indexOf(']')));
            } else if (hn.contains(":")) {
                hn = hn.substring(0, hn.indexOf(':'));
            }
            host = hn.isEmpty() ? "unknown" : hn.toLowerCase(java.util.Locale.ROOT);
        } else {
            host = "local";
            path = url.startsWith("file://") ? url.substring(7) : url;
        }
        List<String> segments = new ArrayList<>();
        for (String s : path.replace('\\', '/').split("/")) {
            if (!s.isEmpty() && !s.endsWith(":")) {
                segments.add(s);
            }
        }
        String repo = segments.isEmpty() ? "repo" : segments.get(segments.size() - 1);
        repo = repo.endsWith(".git") ? repo.substring(0, repo.length() - 4) : repo;
        String owner = segments.size() > 1 ? segments.get(segments.size() - 2) : "_";
        String r = SAFE.matcher(repo).replaceAll("_");
        return List.of(SAFE.matcher(host).replaceAll("_"), SAFE.matcher(owner).replaceAll("_"), r.isEmpty() ? "repo" : r);
    }

    public static Path cloneDir(GitRepo repo, Path cacheDir) {
        List<String> slug = repoSlug(repo.url());
        String ref = repo.ref() != null && !repo.ref().isEmpty() ? SAFE.matcher(repo.ref()).replaceAll("_") : "HEAD";
        return cacheDir.resolve(slug.get(0)).resolve(slug.get(1)).resolve(slug.get(2) + "@" + ref);
    }

    /** A branch, tag or commit name typed by a user; refused when it could be an option or a path trick. */
    public static String checkRef(String ref) {
        String r = ref.strip();
        if (!AuditConfig.GIT_REF.matcher(r).matches() || r.contains("..")) {
            throw new GitException("'" + r + "' is not a branch, tag or commit name");
        }
        return r;
    }

    /** The environment for git: no prompts, and the token as an HTTP header when there is one. */
    static Map<String, String> gitEnv(String token) {
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        env.put("GIT_TERMINAL_PROMPT", "0");
        if (token != null && !token.isEmpty()) {
            String basic = Base64.getEncoder().encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
            env.put("GIT_CONFIG_COUNT", "1");
            env.put("GIT_CONFIG_KEY_0", "http.extraHeader");
            env.put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic);
        }
        return env;
    }

    private static String mask(String text, List<String> secrets) {
        for (String s : secrets) {
            if (s != null && !s.isEmpty()) {
                text = text.replace(s, "****");
            }
        }
        return text;
    }

    private static String run(List<String> args, Path cwd, Map<String, String> env, List<String> secrets) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
        pb.environment().clear();
        pb.environment().putAll(env);
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new GitException("git is not installed or not on PATH");
        }
        try {
            proc.getOutputStream().close();
            var err = java.util.concurrent.CompletableFuture.supplyAsync(() -> read(proc.getErrorStream()));
            String out = read(proc.getInputStream());
            proc.waitFor(1, TimeUnit.HOURS);
            String error = err.join();
            if (proc.exitValue() != 0) {
                String msg = !error.strip().isEmpty() ? error.strip() : out.strip();
                throw new GitException("git " + args.get(0) + " failed: " + mask(msg, secrets));
            }
            return out.strip();
        } catch (InterruptedException e) {
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new GitException("git " + args.get(0) + " was stopped");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(java.io.InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Clones or updates {@code repo} in the cache (one commit deep) and checks out its branch, tag
     * or commit. {@code log} gets the info and warning lines (prefixed {@code WARNING }).
     */
    public static Fetched fetch(GitRepo repo, String cacheDir, Consumer<String> log) {
        String token = repo.tokenEnv() != null ? System.getenv(repo.tokenEnv()) : null;
        if (repo.tokenEnv() != null && (token == null || token.isEmpty())) {
            log.accept("WARNING Environment variable " + repo.tokenEnv() + " is not set; cloning without a token");
            token = null;
        }
        String url = stripCredentials(repo.url());
        if (!url.equals(repo.url())) {
            log.accept("WARNING Credentials in the repository URL were ignored; use token_env instead");
        }
        List<String> secrets = new ArrayList<>();
        if (token != null) {
            secrets.add(token);
        }
        if (!url.equals(repo.url())) {
            secrets.add(repo.url());
        }
        // A local path (bare repository, network share) is passed to git as an absolute path.
        if (!url.contains("://") && !USER_AT.matcher(url).find()) {
            String p = url.startsWith("~") ? System.getProperty("user.home") + url.substring(1) : url;
            url = Path.of(p).toAbsolutePath().normalize().toString();
        }
        Path dest = cloneDir(repo, cacheDir != null ? Path.of(cacheDir) : defaultCacheDir());
        Map<String, String> env = gitEnv(token);
        String ref = repo.ref() != null && !repo.ref().isEmpty() ? repo.ref() : "HEAD";
        try {
            if (!Files.isDirectory(dest.resolve(".git"))) {
                if (Files.exists(dest)) {
                    deleteTree(dest);
                }
                Files.createDirectories(dest);
                run(List.of("init", "-q"), dest, env, secrets);
                run(List.of("remote", "add", "origin", url), dest, env, secrets);
                log.accept("Cloning " + url + " (" + ref + ") into " + dest);
            } else {
                run(List.of("remote", "set-url", "origin", url), dest, env, secrets);
                log.accept("Fetching " + url + " (" + ref + ") in " + dest);
            }
        } catch (IOException e) {
            throw new GitException("Cannot create the clone folder " + dest + ": " + e.getMessage());
        }
        run(List.of("fetch", "-q", "--depth", "1", "origin", ref), dest, env, secrets);
        run(List.of("checkout", "-q", "-f", "FETCH_HEAD"), dest, env, secrets);
        run(List.of("clean", "-q", "-fdx"), dest, env, secrets);
        String commit = run(List.of("rev-parse", "HEAD"), dest, env, secrets);
        markUsed(dest, url, repo.ref(), commit);
        return new Fetched(dest, commit, url);
    }

    static void markUsed(Path dest, String url, String ref, String commit) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", url);
        data.put("ref", ref);
        data.put("commit", commit);
        data.put("last_used", System.currentTimeMillis() / 1000.0);
        try {
            Files.writeString(dest.resolve(".git").resolve(USED_FILE), JSON.writeValueAsString(data));
        } catch (IOException e) {
            // a read-only cache still scans
        }
    }

    private static long size(Path folder) {
        long[] total = {0};
        try (Stream<Path> files = Files.walk(folder)) {
            files.filter(Files::isRegularFile).forEach(p -> {
                try {
                    total[0] += Files.size(p);
                } catch (IOException e) {
                    // gone meanwhile
                }
            });
        } catch (IOException | UncheckedIOException e) {
            // partly read
        }
        return total[0];
    }

    /** Every clone in the cache (folders host/owner/name@ref holding .git), least recently used first. */
    public static List<Clone> list(Path cacheDir, boolean sizes) {
        List<Clone> out = new ArrayList<>();
        if (!Files.isDirectory(cacheDir)) {
            return out;
        }
        for (Path host : children(cacheDir)) {
            for (Path owner : children(host)) {
                for (Path folder : children(owner)) {
                    Path git = folder.resolve(".git");
                    if (!Files.isDirectory(git)) {
                        continue;
                    }
                    Map<?, ?> info = Map.of();
                    try {
                        if (JSON.readValue(Files.readString(git.resolve(USED_FILE)), Object.class) instanceof Map<?, ?> m) {
                            info = m;
                        }
                    } catch (IOException | RuntimeException e) {
                        info = Map.of();
                    }
                    double lastUsed;
                    if (info.get("last_used") instanceof Number n) {
                        lastUsed = n.doubleValue();
                    } else {
                        try {
                            lastUsed = Files.getLastModifiedTime(folder).toMillis() / 1000.0;
                        } catch (IOException e) {
                            lastUsed = 0;
                        }
                    }
                    out.add(new Clone(folder, cacheDir.relativize(folder).toString().replace('\\', '/'), text(info.get("url")),
                            text(info.get("ref")), text(info.get("commit")), lastUsed, sizes ? size(folder) : 0));
                }
            }
        }
        out.sort(Comparator.comparingDouble(Clone::lastUsed));
        return out;
    }

    private static String text(Object o) {
        return o == null || o.toString().isEmpty() ? null : o.toString();
    }

    private static List<Path> children(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Deletes one clone by its key (host/owner/name@ref); refuses anything outside the cache. */
    public static Path remove(Path cacheDir, String key) {
        Path root = cacheDir.toAbsolutePath().normalize();
        Path target = root.resolve(key).normalize();
        if (target.getParent() == null || target.getParent().getParent() == null
                || !root.equals(target.getParent().getParent().getParent()) || !Files.isDirectory(target.resolve(".git"))) {
            throw new GitException("No clone '" + key + "' in the cache");
        }
        deleteTree(target);
        for (Path parent : List.of(target.getParent(), target.getParent().getParent())) {
            try {
                Files.delete(parent); // only when empty
            } catch (IOException e) {
                break;
            }
        }
        return target;
    }

    /** Deletes the whole clone cache. */
    public static Path purge(Path cacheDir) {
        Path target = cacheDir != null ? cacheDir : defaultCacheDir();
        if (Files.exists(target)) {
            deleteTree(target);
        }
        return target;
    }

    /** Deletes a folder tree; git's read-only object files are made writable first (Windows). */
    public static void deleteTree(Path folder) {
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.delete(p);
                } catch (java.nio.file.AccessDeniedException e) {
                    p.toFile().setWritable(true);
                    Files.delete(p);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + folder + ": " + e.getMessage(), e);
        }
    }
}
