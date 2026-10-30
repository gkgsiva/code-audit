package com.legacyaudit.reports;

import com.legacyaudit.engine.rules.Py;
import com.legacyaudit.model.config.AuditConfig;
import com.legacyaudit.reports.jinja.Values;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What a report reads: one run folder ({@code run.json}, {@code data/*.json}, {@code logs/}), plus
 * where the scanned files are on disk so evidence can link to them.
 *
 * <p>Reports only display facts: every number comes from these files, nothing is recounted here
 * except grouping rows that are already in the data (e.g. findings per severity for a chart).
 */
public final class RunData {

    public static final List<String> ENTITIES = List.of(
            "inventory", "components", "entry_points", "services", "screens", "sql_statements", "db_objects",
            "integrations", "web_services", "dependencies", "batch_jobs", "libraries", "tech_stack", "findings",
            "metrics", "input_coverage");
    private static final Pattern SLUG = Pattern.compile("[^A-Za-z0-9._-]+");

    public final Path runDir;
    public final Map<String, Object> run;
    public final Map<String, Object> summary;
    public final Map<String, List<Map<String, Object>>> entities = new LinkedHashMap<>();
    /** {@code data/effort.json}, or null. */
    public final Map<String, Object> effort;
    public final List<Map<String, Object>> targetMapping;
    public final boolean hasSbom;
    /** {@code data/target.json} (decision 161). */
    public final Map<String, Object> target;
    /** Inventory root name to its folder. */
    public final Map<String, Path> roots = new LinkedHashMap<>();
    public final Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
    /** File path to its root name. */
    public final Map<String, String> fileRoot = new LinkedHashMap<>();
    /** File path to its component. */
    public final Map<String, String> fileComponent = new LinkedHashMap<>();

    private RunData(Path runDir, Path inputsDir) {
        this.runDir = runDir;
        Path data = runDir.resolve("data");
        run = D.asMap(Json.load(runDir.resolve("run.json")));
        for (String name : ENTITIES) {
            entities.put(name, D.maps(Json.load(data.resolve(name + ".json"))));
        }
        summary = D.asMap(Json.load(data.resolve("audit-summary.json")));
        Object e = Json.load(data.resolve("effort.json"));
        effort = e instanceof Map<?, ?> ? D.asMap(e) : null;
        targetMapping = D.maps(Json.load(data.resolve("target-mapping.json")));
        hasSbom = Files.exists(data.resolve("sbom.cdx.json"));
        target = readTarget(runDir);
        for (Map<String, Object> src : D.maps(run, "sources")) {
            if (D.t(src, "path")) {
                roots.put(D.str(src, "name"), Path.of(D.str(src, "path")));
            }
        }
        if (inputsDir == null && D.t(run, "config_path")) {
            Path parent = Path.of(D.str(run, "config_path")).getParent();
            inputsDir = (parent == null ? Path.of("") : parent).resolve("audit-inputs");
        }
        if (inputsDir != null) {
            Path parent = inputsDir.getParent();
            roots.putIfAbsent("audit-inputs", parent == null ? Path.of("") : parent);
        }
        for (List<Map<String, Object>> items : entities.values()) {
            for (Map<String, Object> item : items) {
                if (item.containsKey("id")) {
                    byId.put(Py.str(item.get("id")), item);
                }
            }
        }
        for (Map<String, Object> item : items("integrations")) {
            for (Object old : D.list(item, "grouped_ids")) { // rows grouped into one (decision 177)
                byId.putIfAbsent(Py.str(old), item);
            }
        }
        for (Map<String, Object> f : items("inventory")) {
            fileRoot.put(D.str(f, "path"), Py.str(D.get(f, "root", "")));
            if (D.t(f, "component")) {
                fileComponent.put(D.str(f, "path"), D.str(f, "component"));
            }
        }
    }

    public static RunData load(Path runDir, Path inputsDir) {
        return new RunData(runDir, inputsDir);
    }

    public static RunData load(Path runDir) {
        return new RunData(runDir, null);
    }

    /** {@code data/target.json} of a run; a run scanned before targets existed had the default. */
    public static Map<String, Object> readTarget(Path runDir) {
        try {
            Object data = Json.load(runDir.resolve("data").resolve("target.json"));
            if (data instanceof Map<?, ?> m
                    && List.of(AuditConfig.Target.DEFAULT, AuditConfig.Target.WILDFLY, AuditConfig.Target.NONE).contains(m.get("profile"))) {
                return D.asMap(m);
            }
        } catch (RuntimeException e) {
            // unreadable: the default target
        }
        return AuditConfig.Target.defaults().info();
    }

    public static String slug(String name) {
        String s = SLUG.matcher(name).replaceAll("-");
        int a = 0;
        int b = s.length();
        while (a < b && s.charAt(a) == '-') {
            a++;
        }
        while (b > a && s.charAt(b - 1) == '-') {
            b--;
        }
        s = s.substring(a, b).toLowerCase(java.util.Locale.ROOT);
        return s.isEmpty() ? "root" : s;
    }

    /** No migration target ({@code target.profile: none}): reports suggest nothing. */
    public boolean auditOnly() {
        return D.t(target, "audit_only");
    }

    public List<Map<String, Object>> items(String name) {
        return entities.getOrDefault(name, List.of());
    }

    public Map<String, Object> item(String id) {
        return id == null ? null : byId.get(id);
    }

    /** Component of a file, service, screen, SQL statement, job... (null if unknown). */
    public String componentOf(String entityId) {
        Map<String, Object> item = byId.get(entityId);
        if (item == null) {
            return null;
        }
        if (D.t(item, "component")) {
            return D.str(item, "component");
        }
        Object path = D.or(D.get(item, "file"), D.get(item, "path"));
        return Py.truthy(path) ? fileComponent.get(Py.str(path)) : null;
    }

    /**
     * Enrichment keyed by integration id, re-keyed to the rows as they are now: a story written for
     * an endpoint later grouped into one row of its service (decision 177) belongs to that row (the
     * row's own story first).
     */
    public Map<String, Map<String, Object>> byRow(Map<String, Map<String, Object>> stories) {
        List<String> keys = new ArrayList<>(stories.keySet());
        keys.sort((a, b) -> Boolean.compare(notOwn(a), notOwn(b)));
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (String key : keys) {
            Map<String, Object> row = byId.get(key);
            out.putIfAbsent(row != null ? D.str(row, "id") : key, stories.get(key));
        }
        return out;
    }

    private boolean notOwn(String k) {
        return !byId.containsKey(k) || !D.str(byId.get(k), "id").equals(k);
    }

    public String label(String entityId) {
        Map<String, Object> item = byId.get(entityId);
        if (item == null) {
            int i = entityId.indexOf(':');
            return i < 0 ? entityId : entityId.substring(i + 1);
        }
        Object name = D.or(D.or(D.or(D.get(item, "name"), D.get(item, "artifact")), D.get(item, "path")), D.get(item, "target"));
        return Py.truthy(name) ? Py.str(name) : entityId;
    }

    /** Where a fact's file is on disk (null when the source is not local). */
    public Path absPath(String file) {
        try {
            String root = fileRoot.get(file);
            if (root != null && !root.isEmpty() && roots.containsKey(root)) {
                return roots.get(root).resolve(file);
            }
            for (Path base : roots.values()) {
                Path p = base.resolve(file);
                if (Files.exists(p)) {
                    return p;
                }
            }
        } catch (InvalidPathException e) {
            return null;
        }
        return null;
    }

    /**
     * {@code {text, href, abs, vscode}} for one evidence location. {@code href} is relative to the
     * report root when the source is on the same drive (else a {@code file://} URI); {@code vscode}
     * opens the file at the line in VS Code.
     */
    public Map<String, Object> evidenceLinks(Path reportRoot, Object file, Object line) {
        if (!Py.truthy(file)) {
            return D.of("text", "", "href", null, "vscode", null);
        }
        String f = Py.str(file);
        String text = Py.truthy(line) ? f + ":" + Py.str(line) : f;
        Path p = absPath(f);
        if (p == null) {
            return D.of("text", text, "href", null, "vscode", null);
        }
        String href;
        boolean absolute;
        try {
            Path from = reportRoot.toAbsolutePath().normalize();
            String rel = from.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
            href = quote(rel.isEmpty() ? "." : rel);
            absolute = false;
        } catch (IllegalArgumentException e) { // another drive on Windows
            href = resolve(p).toUri().toString();
            absolute = true;
        }
        String posix = resolve(p).toString().replace('\\', '/');
        if (!posix.startsWith("/")) {
            posix = "/" + posix; // vscode://file/C:/...
        }
        String vscode = "vscode://file" + quote(posix) + (Py.truthy(line) ? ":" + Py.str(line) : "");
        return D.of("text", text, "href", href, "abs", absolute, "vscode", vscode);
    }

    /** Python's {@code Path.resolve()}: symbolic links followed, also for a path that does not exist. */
    static Path resolve(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        try {
            return abs.toRealPath();
        } catch (IOException e) {
            Path parent = abs.getParent();
            return parent == null || parent.equals(abs) ? abs : resolve(parent).resolve(abs.getFileName());
        }
    }

    /** Python's {@code urllib.parse.quote(text)} (safe: letters, digits, {@code _.-~/}). */
    public static String quote(String text) {
        StringBuilder out = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || "_.-~/".indexOf(c) >= 0) {
                out.append((char) c);
            } else {
                out.append('%').append(String.format("%02X", c));
            }
        }
        return out.toString();
    }

    /** {@code (file, line)} of the first evidence, else the item's own file and line. */
    public Values.Tuple firstEvidence(Map<String, Object> item) {
        List<Map<String, Object>> ev = D.maps(item, "evidence");
        if (!ev.isEmpty()) {
            return tuple(D.get(ev.get(0), "file"), D.get(ev.get(0), "line"));
        }
        return tuple(D.get(item, "file"), D.get(item, "line"));
    }

    public static Values.Tuple tuple(Object... items) {
        return new Values.Tuple(java.util.Arrays.asList(items));
    }
}
