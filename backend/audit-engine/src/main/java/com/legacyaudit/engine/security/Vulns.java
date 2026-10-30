package com.legacyaudit.engine.security;

import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Schemas;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Known vulnerabilities per library from OWASP Dependency-Check and OSV-Scanner.
 *
 * <p>Reports are imported from {@code audit-inputs/security/*.json} (the kind is detected from
 * the content) or produced by running the tools when they are installed. A tool that is not
 * installed, fails or has no database is a coverage gap (a source with status {@code missing} or
 * {@code failed}), never a crash and never a silent "no vulnerabilities".
 */
public final class Vulns {

    public static final String DC = "dependency-check";
    public static final String OSV = "osv-scanner";
    public static final List<String> SEVERITY_ORDER = List.of("unknown", "low", "medium", "high", "critical");
    private static final Logger LOG = Logger.getLogger("legacy_audit");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Vulns() {}

    /** Vulnerabilities reported for one dependency, with the keys used to match a library. */
    public record Entry(String sha1, List<String> purls, String fileName, List<Doc> vulnerabilities) {}

    /** One report: the tool, its display path, the database date and the entries. */
    public static final class Report {
        public final String tool;
        public final String path;
        public final String databaseDate;
        public final List<Entry> entries = new ArrayList<>();

        Report(String tool, String path, String databaseDate) {
            this.tool = tool;
            this.path = path;
            this.databaseDate = databaseDate;
        }
    }

    /** A tool run result: the report (or null) and the source line for run.json. */
    public record Outcome(Report report, Doc source) {}

    // ---- parsing ---------------------------------------------------------------------------

    static String severityFromScore(Double score) {
        if (score == null) {
            return "unknown";
        }
        if (score >= 9.0) {
            return "critical";
        }
        if (score >= 7.0) {
            return "high";
        }
        if (score >= 4.0) {
            return "medium";
        }
        return score > 0 ? "low" : "unknown";
    }

    static String normSeverity(JsonNode value) {
        String s = text(value) == null ? "" : text(value).strip().toLowerCase(Locale.ROOT);
        if (s.equals("moderate")) {
            return "medium";
        }
        return SEVERITY_ORDER.contains(s) ? s : "unknown";
    }

    private static String text(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        return n.isString() ? n.asString() : n.toString();
    }

    /** Python truthiness of a JSON value. */
    private static boolean truthy(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return false;
        }
        if (n.isString()) {
            return !n.asString().isEmpty();
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isNumber()) {
            return n.asDouble() != 0;
        }
        return !n.isEmpty();
    }

    private static Iterable<JsonNode> items(JsonNode n) {
        return n != null && n.isArray() ? n : List.of();
    }

    private static String head(String s, int n) {
        return s.codePointCount(0, s.length()) <= n ? s : s.substring(0, s.offsetByCodePoints(0, n));
    }

    public static String detectKind(JsonNode data) {
        if (data != null && data.isObject()) {
            if (data.has("dependencies") && (data.has("reportSchema") || data.has("scanInfo"))) {
                return DC;
            }
            if (data.get("results") != null && data.get("results").isArray()) {
                return OSV;
            }
        }
        return null;
    }

    private static Doc vulnerability(String id, List<String> aliases, String severity, Double cvss, Collection<String> fixed, String source,
            String description) {
        return Doc.of(Schemas.VULNERABILITY, "id", id, "aliases", new ArrayList<>(aliases), "severity", severity, "cvss", cvss,
                "fixed_versions", new ArrayList<>(new TreeSet<>(fixed)), "sources", new ArrayList<>(List.of(source)), "description",
                description == null || description.isEmpty() ? null : head(description, 300));
    }

    public static Report parseDependencyCheck(JsonNode data, String path) {
        String max = null;
        JsonNode scanInfo = data.get("scanInfo");
        for (JsonNode s : items(scanInfo != null && truthy(scanInfo) ? scanInfo.get("dataSource") : null)) {
            if (truthy(s.get("timestamp"))) {
                String ts = text(s.get("timestamp"));
                if (max == null || ts.compareTo(max) > 0) {
                    max = ts;
                }
            }
        }
        Report report = new Report(DC, path, max == null ? null : head(max, 10));
        for (JsonNode dep : items(data.get("dependencies"))) {
            List<Doc> vulns = new ArrayList<>();
            for (JsonNode v : items(dep.get("vulnerabilities"))) {
                JsonNode v3 = truthy(v.get("cvssv3")) ? v.get("cvssv3") : null;
                JsonNode v2 = truthy(v.get("cvssv2")) ? v.get("cvssv2") : null;
                JsonNode score = v3 != null && v3.has("baseScore") ? v3.get("baseScore") : (v2 != null ? v2.get("score") : null);
                JsonNode sevNode = truthy(v.get("severity")) ? v.get("severity") : (v3 != null ? v3.get("baseSeverity") : null);
                Double cvss = score == null || score.isNull() ? null : score.asDouble();
                String severity = normSeverity(sevNode);
                if (severity.equals("unknown")) {
                    severity = severityFromScore(cvss);
                }
                List<String> fixed = new ArrayList<>();
                for (JsonNode sw : items(v.get("vulnerableSoftware"))) {
                    JsonNode soft = sw.get("software");
                    if (soft != null && truthy(soft.get("versionEndExcluding"))) {
                        fixed.add(text(soft.get("versionEndExcluding")));
                    }
                }
                vulns.add(vulnerability(text(v.get("name")) == null ? "None" : text(v.get("name")), List.of(), severity, cvss, fixed, DC,
                        text(v.get("description"))));
            }
            if (!vulns.isEmpty()) {
                List<String> purls = new ArrayList<>();
                for (JsonNode p : items(dep.get("packages"))) {
                    if (truthy(p.get("id"))) {
                        purls.add(text(p.get("id")));
                    }
                }
                String sha1 = text(dep.get("sha1"));
                report.entries.add(new Entry(sha1 == null || sha1.isEmpty() ? null : sha1.toLowerCase(Locale.ROOT), purls,
                        text(dep.get("fileName")), vulns));
            }
        }
        return report;
    }

    private static List<String> osvFixed(JsonNode vuln, String name) {
        TreeSet<String> out = new TreeSet<>();
        for (JsonNode aff : items(vuln.get("affected"))) {
            JsonNode pkg = aff.get("package");
            String pkgName = pkg == null ? null : text(pkg.get("name"));
            if (pkgName != null && !pkgName.equals(name)) {
                continue;
            }
            for (JsonNode rng : items(aff.get("ranges"))) {
                for (JsonNode ev : items(rng.get("events"))) {
                    if (truthy(ev.get("fixed"))) {
                        out.add(text(ev.get("fixed")));
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    public static Report parseOsv(JsonNode data, String path) {
        Report report = new Report(OSV, path, null);
        for (JsonNode result : items(data.get("results"))) {
            for (JsonNode pkg : items(result.get("packages"))) {
                JsonNode info = pkg.get("package");
                String name = info == null || !truthy(info.get("name")) ? "" : text(info.get("name"));
                String version = info == null || text(info.get("version")) == null ? "None" : text(info.get("version"));
                String eco = info == null || !truthy(info.get("ecosystem")) ? "" : text(info.get("ecosystem")).toLowerCase(Locale.ROOT);
                Map<String, Double> scores = new HashMap<>();
                for (JsonNode g : items(pkg.get("groups"))) {
                    Double score;
                    try {
                        JsonNode ms = g.get("max_severity");
                        if (ms == null || ms.isNull()) {
                            continue;
                        }
                        score = Double.parseDouble(text(ms).strip());
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    for (JsonNode i : items(g.get("ids"))) {
                        scores.put(text(i), score);
                    }
                }
                List<Doc> vulns = new ArrayList<>();
                for (JsonNode v : items(pkg.get("vulnerabilities"))) {
                    String vid = String.valueOf(text(v.get("id")));
                    Double cvss = scores.get(vid);
                    JsonNode db = v.get("database_specific");
                    String sev = normSeverity(db == null ? null : db.get("severity"));
                    if (sev.equals("unknown")) {
                        sev = severityFromScore(cvss);
                    }
                    List<String> aliases = new ArrayList<>();
                    for (JsonNode a : items(v.get("aliases"))) {
                        aliases.add(text(a));
                    }
                    vulns.add(vulnerability(vid, aliases, sev, cvss, osvFixed(v, name), OSV, text(v.get("summary"))));
                }
                if (vulns.isEmpty()) {
                    continue;
                }
                String purl = null;
                if (eco.equals("maven") && name.contains(":")) {
                    int k = name.indexOf(':');
                    purl = "pkg:maven/" + name.substring(0, k) + "/" + name.substring(k + 1) + "@" + version;
                } else if (eco.equals("npm")) {
                    purl = "pkg:npm/" + name + "@" + version;
                } else if (eco.equals("nuget")) {
                    purl = "pkg:nuget/" + name + "@" + version;
                }
                report.entries.add(new Entry(null, purl != null ? List.of(purl) : List.of(), null, vulns));
            }
        }
        return report;
    }

    public static Report loadReport(Path path, String display) {
        JsonNode data;
        try {
            data = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException err) {
            LOG.warning("Vulnerability report " + display + " could not be read: " + err.getMessage());
            return null;
        }
        String kind = detectKind(data);
        if (DC.equals(kind)) {
            return parseDependencyCheck(data, display);
        }
        if (OSV.equals(kind)) {
            return parseOsv(data, display);
        }
        LOG.warning("Vulnerability report " + display + ": not a Dependency-Check or OSV-Scanner JSON");
        return null;
    }

    // ---- running the tools -----------------------------------------------------------------

    /**
     * {@code shutil.which}: a name with a folder in it is checked as it is, a bare name in each
     * PATH folder; on Windows also with each PATHEXT extension.
     */
    static String which(String name) {
        boolean windows = File.separatorChar == '\\';
        List<String> exts = new ArrayList<>(List.of(""));
        if (windows) {
            String pathext = System.getenv("PATHEXT");
            for (String e : (pathext == null ? ".COM;.EXE;.BAT;.CMD" : pathext).split(";")) {
                if (!e.isEmpty() && !name.toLowerCase(Locale.ROOT).endsWith(e.toLowerCase(Locale.ROOT))) {
                    exts.add(e);
                }
            }
        }
        List<String> dirs = new ArrayList<>();
        if (name.contains("/") || name.contains(File.separator)) {
            dirs.add(null);
        } else {
            String path = System.getenv("PATH");
            if (path != null) {
                for (String dir : path.split(File.pathSeparator)) {
                    if (!dir.isEmpty()) {
                        dirs.add(dir);
                    }
                }
            }
        }
        for (String dir : dirs) {
            for (String ext : exts) {
                try {
                    Path p = dir == null ? Path.of(name + ext) : Path.of(dir, name + ext);
                    if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                        return p.toString();
                    }
                } catch (java.nio.file.InvalidPathException e) {
                    // not a usable path on this system
                }
            }
        }
        return null;
    }

    public static String findTool(String configured, List<String> names) {
        if (configured != null && !configured.isEmpty()) {
            Path p = Path.of(configured);
            return Files.isRegularFile(p) ? p.toString() : which(configured);
        }
        for (String n : names) {
            String found = which(n);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Why a tool was not found: "not installed", or the configured path that does not exist. */
    static String missingDetail(String configured, List<String> names) {
        if (configured == null || configured.isEmpty()) {
            return "not installed";
        }
        String onPath = findTool(null, names);
        return "not found at " + configured + (onPath != null ? "; one is on PATH at " + onPath + ": set path: null or to that file" : "");
    }

    public static Doc source(String name, String status, String detail, String databaseDate, List<String> reports) {
        return Doc.of(Schemas.VULNERABILITY_SOURCE, "name", name, "status", status, "detail", detail, "database_date", databaseDate,
                "reports", new ArrayList<>(reports));
    }

    /** One tool's settings (security.dependency_check / security.osv_scanner). */
    public record ToolConfig(boolean enabled, String path, String dataDir, String nvdMirrorUrl, boolean offline, int timeoutMinutes) {}

    public static Outcome runDependencyCheck(ToolConfig cfg, List<Path> scanDirs, Path outDir, String project) {
        List<String> names = List.of("dependency-check", "dependency-check.sh", "dependency-check.bat");
        String exe = findTool(cfg.path(), names);
        if (exe == null) {
            return new Outcome(null, source(DC, "missing", missingDetail(cfg.path(), names), null, List.of()));
        }
        mkdirs(outDir);
        List<String> cmd = new ArrayList<>(List.of(exe, "--project", project, "--format", "JSON", "--out", outDir.toString()));
        for (Path d : scanDirs) {
            cmd.addAll(List.of("--scan", d.toString()));
        }
        if (cfg.dataDir() != null && !cfg.dataDir().isEmpty()) {
            cmd.addAll(List.of("--data", cfg.dataDir()));
        }
        if (cfg.offline()) {
            cmd.add("--noupdate");
        } else if (cfg.nvdMirrorUrl() != null && !cfg.nvdMirrorUrl().isEmpty()) {
            cmd.addAll(List.of("--nvdDatafeed", cfg.nvdMirrorUrl()));
        }
        return run(cmd, cfg.timeoutMinutes(), DC, outDir.resolve("dependency-check-report.json"), List.of(0), Map.of());
    }

    public static Outcome runOsvScanner(ToolConfig cfg, List<Path> scanDirs, Path outDir) {
        List<String> names = List.of("osv-scanner", "osv-scanner.exe");
        String exe = findTool(cfg.path(), names);
        if (exe == null) {
            return new Outcome(null, source(OSV, "missing", missingDetail(cfg.path(), names), null, List.of()));
        }
        mkdirs(outDir);
        Path out = outDir.resolve("osv-scanner-report.json");
        // OSV-Scanner 2.x: --offline uses the downloaded databases; java/archive adds jar files to
        // the build files it reads by default. Exit code 1 means vulnerabilities were found.
        List<String> cmd = new ArrayList<>(List.of(exe, "--format", "json", "--output", out.toString(), "--recursive"));
        cmd.addAll(List.of("--experimental-plugins", "java/archive"));
        if (cfg.offline()) {
            cmd.add("--offline");
        }
        Map<String, String> env = new LinkedHashMap<>();
        if (cfg.dataDir() != null && !cfg.dataDir().isEmpty()) { // 2.x takes the database folder from the environment only
            env.put("OSV_SCANNER_LOCAL_DB_CACHE_DIRECTORY", cfg.dataDir());
        }
        scanDirs.forEach(d -> cmd.add(d.toString()));
        return run(cmd, cfg.timeoutMinutes(), OSV, out, List.of(0, 1), env);
    }

    private static void mkdirs(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Known scanner failures, in plain words with the fix (searched in the tool's whole output). */
    static final List<String[]> FAILURE_HINTS = List.of(
            new String[] {OSV, "no offline version of the OSV database",
                "offline mode but no OSV database downloaded: set security.osv_scanner.offline: false to ask osv.dev, or download the database"},
            new String[] {OSV, "flag provided but not defined", "OSV-Scanner 1.x is too old: install version 2"},
            new String[] {OSV, "no package sources found",
                "no build files (pom.xml, Gradle, package.json, .csproj/packages.config) or .jar files found in the code folders"},
            new String[] {DC, "No documents exist",
                "its NVD database is not downloaded yet: run dependency-check --updateonly with an NVD API key once, or set "
                        + "security.dependency_check.enabled: false"});

    static String failureHint(String tool, String output) {
        String low = output.toLowerCase(Locale.ROOT);
        for (String[] h : FAILURE_HINTS) {
            if (h[0].equals(tool) && low.contains(h[1].toLowerCase(Locale.ROOT))) {
                return h[2];
            }
        }
        return null;
    }

    private static Outcome run(List<String> cmd, int minutes, String tool, Path out, List<Integer> okCodes, Map<String, String> env) {
        LOG.info("Running " + String.join(" ", cmd));
        int code;
        String stdout;
        String stderr;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().putAll(env);
            Path errFile = Files.createTempFile("legacy-audit-", ".err");
            Path outFile = Files.createTempFile("legacy-audit-", ".out");
            pb.redirectError(errFile.toFile()).redirectOutput(outFile.toFile());
            Process p = pb.start();
            if (!p.waitFor(minutes * 60L, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Outcome(null, source(tool, "failed", "TimeoutExpired", null, List.of()));
            }
            code = p.exitValue();
            stdout = Files.readString(outFile, StandardCharsets.UTF_8);
            stderr = Files.readString(errFile, StandardCharsets.UTF_8);
            Files.deleteIfExists(errFile);
            Files.deleteIfExists(outFile);
        } catch (IOException e) {
            return new Outcome(null, source(tool, "failed", "OSError", null, List.of()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(null, source(tool, "failed", "InterruptedError", null, List.of()));
        }
        if (!okCodes.contains(code) || !Files.isRegularFile(out)) {
            String text = stderr + "\n" + stdout;
            String last = (!stderr.isEmpty() ? stderr : stdout).strip();
            String[] lines = last.split("\\R");
            String tail = lines.length == 0 ? "" : lines[lines.length - 1];
            String detail = ("exit code " + code + ": " + tail).replaceAll("\\s+", " ");
            detail = head(detail, 200);
            String hint = failureHint(tool, text);
            if (hint != null) {
                detail = hint + " (" + detail + ")";
            }
            return new Outcome(null, source(tool, "failed", detail, null, List.of()));
        }
        Report report = loadReport(out, out.getFileName().toString());
        if (report == null) {
            return new Outcome(null, source(tool, "failed", "unreadable report", null, List.of()));
        }
        return new Outcome(report, source(tool, "ran", null, report.databaseDate, List.of(out.getFileName().toString())));
    }

    // ---- matching --------------------------------------------------------------------------

    public static String purl(Doc lib) {
        String version = lib.str("version");
        if (version == null || version.isEmpty()) {
            return null;
        }
        String source = lib.str("source");
        if ("javascript".equals(lib.str("language")) || "npm".equals(source) || "js-file".equals(source)) {
            return "pkg:npm/" + lib.str("artifact") + "@" + version;
        }
        if ("nuget".equals(source)) {
            return "pkg:nuget/" + lib.str("artifact") + "@" + version;
        }
        String group = lib.str("group");
        if (group != null && !group.isEmpty()) {
            return "pkg:maven/" + group + "/" + lib.str("artifact") + "@" + version;
        }
        return null;
    }

    private static String fileName(Doc lib) {
        String v = lib.str("version");
        return v != null && !v.isEmpty() ? lib.str("artifact") + "-" + v + ".jar" : lib.str("artifact") + ".jar";
    }

    private static String purlKey(String p) {
        int q = p.indexOf('?');
        return (q < 0 ? p : p.substring(0, q)).toLowerCase(Locale.ROOT);
    }

    /**
     * Attach vulnerabilities to libraries (by SHA-1, then package URL, then jar file name); merge
     * duplicates by CVE id. Returns how many report entries matched no library.
     */
    public static int applyReports(List<Doc> libraries, List<Report> reports) {
        Map<String, Doc> bySha1 = new HashMap<>();
        Map<String, Doc> byPurl = new HashMap<>();
        Map<String, Doc> byFile = new HashMap<>();
        for (Doc lib : libraries) {
            String sha1 = lib.str("sha1");
            if (sha1 != null && !sha1.isEmpty()) {
                bySha1.put(sha1.toLowerCase(Locale.ROOT), lib);
            }
            String p = purl(lib);
            if (p != null) {
                byPurl.put(purlKey(p), lib);
            }
            byFile.put(fileName(lib).toLowerCase(Locale.ROOT), lib);
        }
        int unmatched = 0;
        for (Report report : reports) {
            for (Entry entry : report.entries) {
                Doc lib = bySha1.get(entry.sha1() == null ? "" : entry.sha1());
                if (lib == null) {
                    for (String p : entry.purls()) {
                        lib = byPurl.get(purlKey(p));
                        if (lib != null) {
                            break;
                        }
                    }
                }
                if (lib == null && entry.fileName() != null && !entry.fileName().isEmpty()) {
                    lib = byFile.get(entry.fileName().toLowerCase(Locale.ROOT));
                }
                if (lib == null) {
                    unmatched++;
                    continue;
                }
                merge(lib, entry.vulnerabilities());
            }
        }
        for (Doc lib : libraries) {
            List<Doc> vulns = lib.list("vulnerabilities");
            if (!vulns.isEmpty()) {
                vulns.sort((a, b) -> {
                    int c = Integer.compare(SEVERITY_ORDER.indexOf(b.str("severity")), SEVERITY_ORDER.indexOf(a.str("severity")));
                    return c != 0 ? c : com.legacyaudit.model.json.PyJson.compareCodePoints(a.str("id"), b.str("id"));
                });
                TreeSet<String> cves = new TreeSet<>(com.legacyaudit.model.json.PyJson::compareCodePoints);
                vulns.forEach(v -> cves.add(v.str("id")));
                lib.<Object>list("cves").forEach(c -> cves.add(String.valueOf(c)));
                lib.set("cves", new ArrayList<>(cves));
                String max = "unknown";
                for (Doc v : vulns) {
                    if (SEVERITY_ORDER.indexOf(v.str("severity")) > SEVERITY_ORDER.indexOf(max)) {
                        max = v.str("severity");
                    }
                }
                lib.set("max_severity", vulns.stream().map(v -> v.str("severity"))
                        .max(java.util.Comparator.comparingInt(SEVERITY_ORDER::indexOf)).orElse(max));
            }
        }
        return unmatched;
    }

    private static TreeSet<String> sortedSet() {
        return new TreeSet<>(com.legacyaudit.model.json.PyJson::compareCodePoints);
    }

    private static List<String> strings(Doc d, String field) {
        List<String> out = new ArrayList<>();
        d.list(field).forEach(o -> out.add(String.valueOf(o)));
        return out;
    }

    private static void merge(Doc lib, List<Doc> vulns) {
        Map<String, Doc> known = new HashMap<>();
        List<Doc> mine = lib.list("vulnerabilities");
        for (Doc v : mine) {
            known.put(v.str("id"), v);
            strings(v, "aliases").forEach(a -> known.put(a, v));
        }
        for (Doc v : vulns) {
            Doc match = known.get(v.str("id"));
            if (match == null) {
                for (String a : strings(v, "aliases")) {
                    match = known.get(a);
                    if (match != null) {
                        break;
                    }
                }
            }
            if (match == null) {
                // Prefer the CVE id as the main id so both tools agree.
                String cve = strings(v, "aliases").stream().filter(a -> a.startsWith("CVE-")).findFirst().orElse(null);
                if (cve != null && !v.str("id").startsWith("CVE-")) {
                    TreeSet<String> aliases = sortedSet();
                    aliases.addAll(strings(v, "aliases"));
                    aliases.add(v.str("id"));
                    aliases.remove(cve);
                    v.set("id", cve).set("aliases", new ArrayList<>(aliases));
                }
                mine.add(v);
                known.put(v.str("id"), v);
                strings(v, "aliases").forEach(a -> known.put(a, v));
                continue;
            }
            TreeSet<String> sources = sortedSet();
            sources.addAll(strings(match, "sources"));
            sources.addAll(strings(v, "sources"));
            match.set("sources", new ArrayList<>(sources));
            TreeSet<String> aliases = sortedSet();
            aliases.addAll(strings(match, "aliases"));
            aliases.addAll(strings(v, "aliases"));
            aliases.add(v.str("id"));
            aliases.remove(match.str("id"));
            match.set("aliases", new ArrayList<>(aliases));
            TreeSet<String> fixed = sortedSet();
            fixed.addAll(strings(match, "fixed_versions"));
            fixed.addAll(strings(v, "fixed_versions"));
            match.set("fixed_versions", new ArrayList<>(fixed));
            if (match.get("cvss") == null) {
                match.set("cvss", v.get("cvss"));
            }
            if (SEVERITY_ORDER.indexOf(v.str("severity")) > SEVERITY_ORDER.indexOf(match.str("severity"))) {
                match.set("severity", v.str("severity"));
            }
            String desc = match.str("description");
            if (desc == null || desc.isEmpty()) {
                match.set("description", v.str("description"));
            }
        }
    }
}
