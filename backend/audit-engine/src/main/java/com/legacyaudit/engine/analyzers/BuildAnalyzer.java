package com.legacyaudit.engine.analyzers;

import static com.legacyaudit.engine.analyzers.TextUtil.truthy;

import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Schemas;
import com.legacyaudit.model.json.PyJson;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Build files: Maven, Gradle (best effort), Ant/Ivy and Makefiles. Outputs declared libraries
 * and tech items (Java version, build tool, Spring Boot). Maven versions inherited from a parent
 * pom are resolved by the linker from the {@code maven-*} symbols declared here.
 */
public final class BuildAnalyzer implements Analyzer {

    private static final Pattern PROP = Rx.of("\\$\\{([^}]+)\\}");

    @Override
    public String name() {
        return "build";
    }

    @Override
    public String version() {
        return "1";
    }

    public static Doc tech(String name, String version, String category, String file, Integer line) {
        return Doc.of(Schemas.TECH_ITEM,
                "id", Fact.makeId("tech", name, version == null ? "" : version),
                "name", name,
                "version", version,
                "category", category,
                "evidence", new ArrayList<>(List.of(new Evidence(file, line, null))));
    }

    public static Doc library(String group, String artifact, String version, String source, String file, Integer line, boolean declared,
            String scope, String language, String sha1) {
        return Doc.of(Schemas.LIBRARY,
                "id", Fact.makeId("library", language, group == null ? "" : group, artifact, version == null ? "" : version),
                "group", group,
                "artifact", artifact,
                "version", version,
                "source", source,
                "declared", declared,
                "scope", scope,
                "language", language,
                "sha1", sha1,
                "where_used", new ArrayList<>(List.of(file)),
                "evidence", new ArrayList<>(List.of(new Evidence(file, line, null))));
    }

    public static Doc library(String group, String artifact, String version, String source, String file, Integer line, String scope) {
        return library(group, artifact, version, source, file, line, true, scope, "java", null);
    }

    static String resolve(String value, Map<String, String> props) {
        return resolve(value, props, 0);
    }

    private static String resolve(String value, Map<String, String> props, int depth) {
        if (value == null || depth > 5) {
            return value;
        }
        String out = TextUtil.sub(PROP, value.strip(), m -> props.getOrDefault(m.group(1), m.group()));
        return !out.equals(value) && out.contains("${") ? resolve(out, props, depth + 1) : out;
    }

    @Override
    public boolean supports(FileRecord file) {
        String name = TextUtil.afterLast(file.path, "/").toLowerCase();
        return name.equals("pom.xml") || name.equals("build.xml") || name.equals("ivy.xml")
                || ((file.language.equals("make") || file.language.equals("groovy")) && file.category.value().equals("build"))
                || name.endsWith(".gradle.kts");
    }

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        Facts facts = new Facts();
        String text = ctx.textOrEmpty();
        facts.coverage.lines = TextUtil.lineCount(text);
        String name = TextUtil.afterLast(file.path, "/").toLowerCase();
        try {
            if (name.equals("pom.xml")) {
                maven(file.path, Xml.parse(text), facts);
            } else if (name.equals("build.xml")) {
                ant(file.path, Xml.parse(text), facts);
            } else if (name.equals("ivy.xml")) {
                ivy(file.path, Xml.parse(text), facts);
            } else if (file.language.equals("make")) {
                make(file.path, text, facts);
            } else {
                gradle(file.path, text, facts);
            }
        } catch (Xml.ParseError err) {
            facts.coverage.gap(1, facts.coverage.lines, "XML parse error: " + err.getMessage());
        }
        return facts;
    }

    // ---- Maven --------------------------------------------------------------------------------

    private void maven(String path, Xml.El root, Facts facts) {
        Xml.El parent = root.find("parent");
        String pGroup = parent != null ? parent.childText("groupId") : null;
        String pArtifact = parent != null ? parent.childText("artifactId") : null;
        String pVersion = parent != null ? parent.childText("version") : null;
        String group = TextUtil.or(root.childText("groupId"), pGroup);
        String artifact = TextUtil.or(root.childText("artifactId"), "");
        String version = TextUtil.or(root.childText("version"), pVersion);
        Map<String, String> props = new LinkedHashMap<>();
        Xml.El propsEl = root.find("properties");
        if (propsEl != null) {
            for (Xml.El p : propsEl) {
                props.put(p.tag, p.textOrEmpty().strip());
            }
        }
        props.put("project.groupId", group == null ? "" : group);
        props.put("project.artifactId", artifact);
        props.put("project.version", version == null ? "" : version);
        props.put("pom.version", version == null ? "" : version);
        props.put("project.parent.version", pVersion == null ? "" : pVersion);
        String coord = TextUtil.py(group) + ":" + artifact;
        facts.symbol("maven-pom", coord, path, root.line)
                .data("parent", truthy(pArtifact) ? TextUtil.py(pGroup) + ":" + pArtifact : "")
                .data("version", version == null ? "" : version)
                .data("properties", PyJson.dumps(props, true));
        facts.add("tech_stack", tech("Maven", null, "build", path, root.line));

        Map<String, String> managed = new HashMap<>();
        Xml.El dm = root.find("dependencyManagement/dependencies");
        if (dm != null) {
            for (Xml.El d : dm.findall("dependency")) {
                String g = d.childText("groupId");
                String a = d.childText("artifactId");
                String v = resolve(d.childText("version"), props);
                if (truthy(g) && truthy(a) && truthy(v)) {
                    managed.put(resolve(g, props) + ":" + a, v);
                    facts.symbol("maven-managed", coord + "|" + resolve(g, props) + ":" + a, path, d.line).data("version", v);
                }
                if ("import".equals(d.childText("scope"))) {
                    facts.add("libraries", library(resolve(g, props), a == null ? "" : a, v, "maven", path, d.line, "import"));
                }
            }
        }

        Xml.El deps = root.find("dependencies");
        for (Xml.El d : deps != null ? deps.findall("dependency") : List.<Xml.El>of()) {
            String g = resolve(d.childText("groupId"), props);
            String a = TextUtil.or(d.childText("artifactId"), "");
            String v = resolve(d.childText("version"), props);
            if (v == null) {
                v = managed.get(TextUtil.py(g) + ":" + a);
            }
            Doc lib = library(g, a, v, "maven", path, d.line, TextUtil.or(d.childText("scope"), "compile"));
            if (v == null || v.contains("${")) {
                // Resolved later from the parent pom chain.
                facts.symbol("maven-unresolved", lib.id(), path, d.line)
                        .data("pom", coord)
                        .data("key", TextUtil.py(g) + ":" + a)
                        .data("raw", v == null ? "" : v);
            }
            facts.add("libraries", lib);
        }

        // Java version and Spring Boot.
        String java = null;
        for (String key : List.of("maven.compiler.release", "maven.compiler.source", "java.version")) {
            if (truthy(props.get(key))) {
                java = resolve(props.get(key), props);
                break;
            }
        }
        for (Xml.El plugin : root.iter("plugin")) {
            if ("maven-compiler-plugin".equals(plugin.childText("artifactId"))) {
                Xml.El conf = plugin.find("configuration");
                if (conf != null) {
                    String r = resolve(TextUtil.or(conf.childText("release"), conf.childText("source")), props);
                    java = TextUtil.or(r, java);
                }
            }
        }
        if (truthy(java) && !java.contains("${")) {
            facts.add("tech_stack", tech("Java", java, "language", path, root.line));
        }
        if ("spring-boot-starter-parent".equals(pArtifact)) {
            facts.add("tech_stack", tech("Spring Boot", pVersion, "framework", path, parent != null ? parent.line : null));
        }
    }

    // ---- Ant / Ivy ----------------------------------------------------------------------------

    private void ant(String path, Xml.El root, Facts facts) {
        if (!root.tag.equals("project")) {
            return;
        }
        facts.add("tech_stack", tech("Ant", null, "build", path, root.line));
        Map<String, String> props = new HashMap<>();
        for (Xml.El el : root.iter("property")) {
            Map<String, String> a = el.attrs();
            if (a.containsKey("name") && a.containsKey("value")) {
                props.put(a.get("name"), a.get("value"));
            }
        }
        for (Xml.El javac : root.iter("javac")) {
            Map<String, String> a = javac.attrs();
            String v = resolve(TextUtil.or(TextUtil.or(a.get("source"), a.get("target")), a.get("release")), props);
            if (truthy(v) && !v.contains("${")) {
                facts.add("tech_stack", tech("Java", v, "language", path, javac.line));
                break;
            }
        }
    }

    private void ivy(String path, Xml.El root, Facts facts) {
        for (Xml.El d : root.iter("dependency")) {
            Map<String, String> a = d.attrs();
            if (truthy(a.get("name"))) {
                facts.add("libraries", library(a.get("org"), a.get("name"), a.get("rev"), "ivy", path, d.line, a.get("conf")));
            }
        }
    }

    // ---- Gradle -------------------------------------------------------------------------------

    private static final Pattern GRADLE_DEP = Rx.of(
            "^\\s*(implementation|api|compile|compileOnly|runtime|runtimeOnly|testImplementation|"
                    + "testCompile|providedCompile|providedRuntime)\\s*\\(?\\s*['\"]([^:'\"]+):([^:'\"]+):([^'\"@]+)['\"]",
            Rx.M);
    private static final Pattern GRADLE_MAP = Rx.of(
            "^\\s*(\\w+)\\s*\\(?\\s*group\\s*:\\s*['\"]([^'\"]+)['\"]\\s*,\\s*name\\s*:\\s*['\"]([^'\"]+)['\"]"
                    + "\\s*,\\s*version\\s*:\\s*['\"]([^'\"]+)['\"]",
            Rx.M);
    private static final Pattern GRADLE_JAVA = Rx.of("sourceCompatibility\\s*=\\s*['\"]?(?:JavaVersion\\.VERSION_)?([\\d._]+)");

    private void gradle(String path, String text, Facts facts) {
        facts.add("tech_stack", tech("Gradle", null, "build", path, 1));
        for (Pattern rx : List.of(GRADLE_DEP, GRADLE_MAP)) {
            Matcher m = rx.matcher(text);
            while (m.find()) {
                int line = TextUtil.lineAt(text, m.start());
                facts.add("libraries", library(m.group(2), m.group(3), m.group(4), "gradle", path, line, m.group(1)));
            }
        }
        Matcher jm = GRADLE_JAVA.matcher(text);
        if (jm.find()) {
            facts.add("tech_stack", tech("Java", jm.group(1).replace('_', '.'), "language", path, TextUtil.lineAt(text, jm.start())));
        }
    }

    // ---- Make ---------------------------------------------------------------------------------

    private static final Pattern TARGET = Rx.of("^([A-Za-z0-9_.$(){}/-]+)\\s*:(?!=)\\s*(.*)$");
    private static final Pattern OUT = Rx.of("-o\\s+(\\$\\(\\w+\\)|[\\w./-]+)");
    private static final Pattern PROC = Rx.of("(\\$\\(PROC\\)|proc)\\s");
    private static final Pattern VAR = Rx.of("^\\s*(\\w+)\\s*[:+?]?=\\s*(.*)$");

    private void make(String path, String text, Facts facts) {
        facts.add("tech_stack", tech("Make", null, "build", path, 1));
        Map<String, String> variables = new HashMap<>();
        int no = 0;
        for (String line : TextUtil.splitLines(text)) {
            no++;
            if (line.startsWith("\t") || line.stripLeading().startsWith("#")) {
                String cmd = line.strip();
                if (PROC.matcher(cmd).lookingAt()) {
                    facts.add("tech_stack", tech("Oracle Pro*C", null, "build", path, no));
                }
                Matcher m = OUT.matcher(cmd);
                while (m.find()) {
                    String out = m.group(1);
                    if (out.startsWith("$(")) {
                        out = variables.getOrDefault(out.substring(2, out.length() - 1), "");
                    }
                    if (!out.isEmpty() && !out.endsWith(".o") && !out.contains("$")) {
                        facts.symbol("c-executable", TextUtil.afterLast(out, "/"), path, no);
                    }
                }
                continue;
            }
            Matcher var = VAR.matcher(line);
            if (var.lookingAt()) {
                variables.put(var.group(1), var.group(2).strip());
                if (var.group(1).equals("CC") || var.group(1).equals("CXX")) {
                    facts.add("tech_stack", tech("C compiler (" + TextUtil.words(var.group(2)).getFirst() + ")", null, "build", path, no));
                }
                continue;
            }
            Matcher tm = TARGET.matcher(line);
            if (tm.lookingAt()) {
                String target = tm.group(1);
                if (target.startsWith(".") || List.of("all", "clean", "install", "test").contains(target)) {
                    continue;
                }
                if (target.contains("$(")) {
                    target = variables.getOrDefault(TextUtil.strip(target, "$()"), "");
                }
                if (!target.isEmpty() && !target.endsWith(".o") && !target.endsWith(".c") && !target.endsWith(".h") && !target.contains("%")) {
                    facts.symbol("c-executable", TextUtil.afterLast(target, "/"), path, no);
                }
            }
        }
    }
}
