package com.legacyaudit.engine.analyzers;

import static com.legacyaudit.engine.analyzers.BuildAnalyzer.library;
import static com.legacyaudit.engine.analyzers.BuildAnalyzer.tech;

import com.legacyaudit.engine.inventory.LanguageTables;
import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.FileRecord;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import tools.jackson.databind.JsonNode;

/**
 * Undeclared dependencies: jars/wars/ears (manifest, pom.properties, file name), npm
 * {@code package.json} (with the Node.js version it asks for), npm lock files (the exact version
 * of every package), Bower {@code bower.json} and bundled JavaScript libraries.
 */
public final class DepsAnalyzer implements Analyzer {

    private static final Pattern JAR_NAME = Rx.of("^(?<a>.+?)[-_](?<v>\\d+(?:[._]\\d+)*(?:[.-][\\w.]+?)?)\\.(jar|war|ear|zip)$", Rx.I);
    private static final Pattern JS_NAME = Rx.of(
            "^(?<a>jquery(?:[.-]ui)?|angular|bootstrap|prototype|scriptaculous|dojo|mootools|"
                    + "underscore|backbone|ext-all|yui|knockout|moment|lodash|react|vue)[.-]v?(?<v>\\d+(?:\\.\\d+)+)",
            Rx.I);
    private static final Pattern JS_HEADER = Rx.of(
            "(?<a>jQuery UI|jQuery|AngularJS|Bootstrap|Prototype JavaScript framework|Dojo|"
                    + "MooTools|Underscore\\.js|Backbone\\.js|Ext JS|Knockout|Moment\\.js|Lodash)"
                    + "[^\\n\\d]{0,20}v?(?<v>\\d+\\.\\d+(?:\\.\\d+)?)");
    private static final List<String> NPM_SCOPES = List.of("dependencies", "devDependencies", "peerDependencies", "optionalDependencies");
    private static final Pattern YARN_HEAD = Rx.of("^\"?((?:@[^@/\\s\"]+/)?[^@\\s\",]+)@");
    private static final Pattern YARN_VERSION = Rx.of("^\\s+version:?\\s+\"?([^\"\\s]+)\"?");
    private static final Pattern PNPM_KEY = Rx.of("^\\s{2}'?/?((?:@[^@/\\s]+/)?[^@/\\s(']+)[@/]([0-9][^\\s:('_]*)");
    private static final Pattern NODE_VERSION = Rx.of("(\\d+(?:\\.\\d+){0,2})");
    private static final Pattern NPM_VERSION = Rx.of("^\\s*(?:npm:[^@]+@)?[\\^~=v>< ]*(\\d+(?:\\.[\\dxX*]+)*(?:-[\\w.]+)?)");
    private static final Pattern WILDCARD_TAIL = Rx.of("\\.[xX*].*$");
    private static final Pattern POM_PROPS = Rx.of("META-INF/maven/[^/]+/[^/]+/pom\\.properties$");

    @Override
    public String name() {
        return "deps";
    }

    @Override
    public String version() {
        return "2";
    }

    /** The version a package.json range starts from ({@code ^17.3.0} -> {@code 17.3.0}). */
    public static String npmVersion(String spec) {
        Matcher m = NPM_VERSION.matcher(spec);
        if (!m.lookingAt()) {
            return null;
        }
        return WILDCARD_TAIL.matcher(m.group(1)).replaceFirst("");
    }

    /** Major.minor of a Node.js engines range or .nvmrc ({@code >=18.17} -> {@code 18.17}). */
    public static String nodeVersion(String spec) {
        Matcher m = NODE_VERSION.matcher(spec == null ? "" : spec);
        return m.find() ? m.group(1) : null;
    }

    private static boolean archive(String p) {
        return p.endsWith(".jar") || p.endsWith(".war") || p.endsWith(".ear");
    }

    @Override
    public boolean supports(FileRecord file) {
        String p = file.path.toLowerCase();
        String name = TextUtil.afterLast(p, "/");
        return archive(p) || name.equals("package.json") || name.equals("bower.json") || name.equals(".nvmrc")
                || LanguageTables.NPM_LOCK_FILES.contains(name)
                || (file.language.equals("javascript") && !p.contains("node_modules"));
    }

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) throws IOException {
        Facts facts = new Facts();
        String p = file.path.toLowerCase();
        if (archive(p)) {
            byte[] data = ctx.readBytes();
            String[] id = jarIdentity(TextUtil.basename(file.path), data);
            facts.add("libraries", library(id[0], id[1], id[2], "jar", file.path, null, false, null, "java", sha1(data)));
            if (p.endsWith(".war") || p.endsWith(".ear")) {
                nested(file.path, data, facts);
            }
            return facts;
        }
        String text = ctx.textOrEmpty();
        facts.coverage.lines = TextUtil.lineCount(text);
        String name = TextUtil.afterLast(p, "/");
        if (name.equals(".nvmrc")) {
            String ver = nodeVersion(text.strip());
            if (TextUtil.truthy(ver)) {
                facts.add("tech_stack", tech("Node.js", ver, "runtime", file.path, 1));
            }
            return facts;
        }
        if (LanguageTables.NPM_LOCK_FILES.contains(name)) {
            lock(file.path, name, text, facts);
            return facts;
        }
        if (name.equals("package.json") || name.equals("bower.json")) {
            JsonNode pkg;
            try {
                pkg = PyJsonRead.parse(text);
            } catch (IllegalArgumentException err) {
                facts.coverage.gap(1, facts.coverage.lines, "JSON parse error: " + err.getMessage());
                return facts;
            }
            if (!pkg.isObject()) {
                return facts;
            }
            List<String> lines = TextUtil.splitLines(text);
            String source = name.equals("package.json") ? "npm" : "bower";
            for (String scope : NPM_SCOPES) {
                JsonNode deps = pkg.get(scope);
                if (deps == null || !deps.isObject()) {
                    continue;
                }
                for (var e : deps.properties()) {
                    String dep = e.getKey();
                    Integer line = firstLine(lines, "\"" + dep + "\"");
                    facts.add("libraries", library(null, dep, npmVersion(PyJsonRead.str(e.getValue())), source, file.path, line, true, scope,
                            "javascript", null));
                }
            }
            JsonNode engines = pkg.get("engines");
            if (engines != null && engines.isObject() && engines.get("node") != null && engines.get("node").isString()) {
                String ver = nodeVersion(engines.get("node").asString());
                Integer line = firstLine(lines, "\"node\"");
                facts.add("tech_stack", tech("Node.js", ver, "runtime", file.path, line));
            }
            return facts;
        }
        // A bundled JavaScript library: file name or header comment.
        Matcher m = JS_NAME.matcher(TextUtil.basename(file.path));
        boolean byName = m.lookingAt();
        Matcher h = null;
        String trimmed = text.stripLeading();
        if (trimmed.startsWith("/*") || trimmed.startsWith("//")) {
            Matcher hm = JS_HEADER.matcher(Rx.head(text, 600));
            if (hm.find()) {
                h = hm;
            }
        }
        if (byName || h != null) {
            String artifact = (byName ? m.group("a") : h.group("a")).toLowerCase().replace(" ", "-");
            artifact = TextUtil.strip(artifact.replace("javascript-framework", ""), "-.");
            String version = byName ? m.group("v") : null;
            if (!TextUtil.truthy(version)) {
                version = h != null ? h.group("v") : null;
            }
            facts.add("libraries", library(null, artifact, version, "js-file", file.path, 1, false, null, "javascript", null));
        }
        return facts;
    }

    private static Integer firstLine(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i + 1;
            }
        }
        return null;
    }

    /** Exact versions from an npm, Yarn or pnpm lock file. */
    private void lock(String path, String name, String text, Facts facts) {
        Map<List<String>, Boolean> found = new LinkedHashMap<>(); // (package, version) -> dev only
        if (name.endsWith(".json")) {
            JsonNode data;
            try {
                data = PyJsonRead.parse(text);
            } catch (IllegalArgumentException err) {
                facts.coverage.gap(1, facts.coverage.lines, "JSON parse error: " + err.getMessage());
                return;
            }
            if (!data.isObject()) {
                return;
            }
            JsonNode packages = data.get("packages");
            if (packages != null && packages.isObject()) { // lockfileVersion 2 and 3
                for (var e : packages.properties()) {
                    String key = e.getKey();
                    JsonNode info = e.getValue();
                    if (key.isEmpty() || !key.contains("node_modules/") || !info.isObject()) {
                        continue;
                    }
                    String pkg = TextUtil.afterLast(key, "node_modules/");
                    JsonNode ver = info.get("version");
                    if (ver != null && ver.isString()) {
                        found.putIfAbsent(List.of(pkg, ver.asString()), truthy(info.get("dev")));
                    }
                }
            } else { // lockfileVersion 1: nested "dependencies"
                Deque<JsonNode> stack = new ArrayDeque<>();
                stack.push(data.get("dependencies") == null ? tools.jackson.databind.node.NullNode.getInstance() : data.get("dependencies"));
                while (!stack.isEmpty()) {
                    JsonNode deps = stack.pop();
                    if (!deps.isObject()) {
                        continue;
                    }
                    for (var e : deps.properties()) {
                        JsonNode info = e.getValue();
                        if (!info.isObject()) {
                            continue;
                        }
                        JsonNode ver = info.get("version");
                        if (ver != null && ver.isString()) {
                            found.putIfAbsent(List.of(e.getKey(), ver.asString()), truthy(info.get("dev")));
                        }
                        JsonNode sub = info.get("dependencies");
                        stack.push(sub == null ? tools.jackson.databind.node.NullNode.getInstance() : sub);
                    }
                }
            }
        } else if (name.equals("yarn.lock")) {
            List<String> current = new ArrayList<>();
            for (String line : TextUtil.splitLines(text)) {
                if (!line.isEmpty() && !line.startsWith(" ") && !line.startsWith("#")) {
                    current = new ArrayList<>();
                    for (String part : TextUtil.rstrip(line, ":").split(",", -1)) {
                        Matcher m = YARN_HEAD.matcher(part.strip());
                        if (m.lookingAt()) {
                            current.add(m.group(1));
                        }
                    }
                    continue;
                }
                Matcher m = YARN_VERSION.matcher(line);
                if (m.lookingAt() && !current.isEmpty()) {
                    for (String pkg : current) {
                        found.putIfAbsent(List.of(pkg, m.group(1)), false);
                    }
                    current = new ArrayList<>();
                }
            }
        } else { // pnpm-lock.yaml: "  /name@1.2.3:" or "  name@1.2.3:" under packages
            String section = "";
            for (String line : TextUtil.splitLines(text)) {
                if (!line.isEmpty() && !line.startsWith(" ")) {
                    section = TextUtil.rstrip(line, ":").strip();
                    continue;
                }
                if (!section.equals("packages") && !section.equals("snapshots")) {
                    continue;
                }
                Matcher m = PNPM_KEY.matcher(line);
                if (m.lookingAt() && line.startsWith("  ") && !line.startsWith("   ")) {
                    found.putIfAbsent(List.of(m.group(1), m.group(2)), false);
                }
            }
        }
        TreeMap<List<String>, Boolean> sorted = new TreeMap<>((a, b) -> {
            int c = com.legacyaudit.model.json.PyJson.compareCodePoints(a.get(0), b.get(0));
            return c != 0 ? c : com.legacyaudit.model.json.PyJson.compareCodePoints(a.get(1), b.get(1));
        });
        sorted.putAll(found);
        sorted.forEach((k, dev) -> facts.add("libraries", library(null, k.get(0), k.get(1), "npm-lock", path, null, false,
                dev ? "devDependencies" : "transitive", "javascript", null)));
    }

    private static boolean truthy(JsonNode n) {
        if (n == null || n.isNull()) {
            return false;
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isNumber()) {
            return n.doubleValue() != 0;
        }
        if (n.isString()) {
            return !n.asString().isEmpty();
        }
        return !n.isEmpty();
    }

    private void nested(String path, byte[] data, Facts facts) {
        try {
            for (Map.Entry<String, byte[]> e : entries(data, n -> n.toLowerCase().endsWith(".jar") && n.toLowerCase().contains("lib/")).entrySet()) {
                String n = e.getKey();
                byte[] inner = e.getValue();
                String[] id = jarIdentity(TextUtil.basename(n), inner);
                facts.add("libraries", library(id[0], id[1], id[2], "jar", path + "!/" + n, null, false, null, "java", sha1(inner)));
            }
        } catch (IOException e) {
            facts.coverage.gap(0, 0, "archive could not be opened");
        }
    }

    /** The entries of a zip whose names pass the test, in archive order, with their bytes. */
    static Map<String, byte[]> entries(byte[] zip, java.util.function.Predicate<String> wanted) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        boolean any = false;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry ze;
            while ((ze = zin.getNextEntry()) != null) {
                any = true;
                if (wanted.test(ze.getName())) {
                    out.put(ze.getName(), zin.readAllBytes());
                }
            }
        } catch (IllegalArgumentException e) {
            throw new IOException(e);
        }
        if (!any) {
            throw new IOException("File is not a zip file");
        }
        return out;
    }

    /** (group, artifact, version) from pom.properties, the manifest, then the file name. */
    static String[] jarIdentity(String name, byte[] data) {
        String group = null;
        String version = null;
        String artifact;
        try {
            Map<String, byte[]> all = entries(data, n -> POM_PROPS.matcher(n).lookingAt() || n.equals("META-INF/MANIFEST.MF"));
            List<String> poms = all.keySet().stream().filter(n -> POM_PROPS.matcher(n).lookingAt()).toList();
            if (poms.size() == 1) {
                Map<String, String> props = new LinkedHashMap<>();
                for (String line : TextUtil.pySplitlines(new String(all.get(poms.getFirst()), StandardCharsets.UTF_8))) {
                    if (line.contains("=") && !line.startsWith("#")) {
                        props.put(TextUtil.beforeFirst(line, "="), TextUtil.afterFirst(line, "="));
                    }
                }
                if (TextUtil.truthy(props.get("artifactId"))) {
                    return new String[] {props.get("groupId"), props.get("artifactId"), props.get("version")};
                }
            }
            if (all.containsKey("META-INF/MANIFEST.MF")) {
                Map<String, String> mf = manifest(new String(all.get("META-INF/MANIFEST.MF"), StandardCharsets.UTF_8));
                version = TextUtil.or(mf.get("Implementation-Version"), mf.get("Bundle-Version"));
                group = mf.get("Implementation-Vendor-Id");
                String sym = mf.getOrDefault("Bundle-SymbolicName", "").split(";", -1)[0];
                if (!sym.isEmpty() && sym.contains(".")) {
                    group = TextUtil.or(group, TextUtil.beforeLast(sym, "."));
                }
            }
        } catch (IOException e) {
            // not a zip: name only
        }
        Matcher m = JAR_NAME.matcher(name);
        if (m.lookingAt()) {
            artifact = m.group("a");
            version = TextUtil.or(version, m.group("v"));
        } else {
            artifact = TextUtil.beforeLast(name, ".");
        }
        return new String[] {group, artifact, version};
    }

    private static Map<String, String> manifest(String data) {
        Map<String, String> out = new LinkedHashMap<>();
        String key = null;
        for (String line : TextUtil.pySplitlines(data)) {
            if (line.startsWith(" ") && key != null) {
                out.put(key, out.get(key) + line.substring(1));
                continue;
            }
            if (line.contains(":")) {
                key = TextUtil.beforeFirst(line, ":").strip();
                out.put(key, TextUtil.afterFirst(line, ":").strip());
            }
        }
        return out;
    }

    static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
