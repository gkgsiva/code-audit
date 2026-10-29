package com.legacyaudit.model.config;

import com.legacyaudit.model.config.AuditConfig.Application;
import com.legacyaudit.model.config.AuditConfig.ComponentSpec;
import com.legacyaudit.model.config.AuditConfig.GitRepo;
import com.legacyaudit.model.config.AuditConfig.GitSource;
import com.legacyaudit.model.config.AuditConfig.Inventory;
import com.legacyaudit.model.config.AuditConfig.LocalSource;
import com.legacyaudit.model.config.AuditConfig.Source;
import com.legacyaudit.model.config.AuditConfig.Target;
import com.legacyaudit.model.facts.ComponentType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns the mapping read from {@code audit.yaml} into an {@link AuditConfig}. Every key the Python
 * tool accepted is accepted (and every other one rejected with its location), with the same
 * defaults; sections later phases use are checked and kept in the dump.
 */
public final class ConfigReader {

    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^(?:[A-Za-z]:|[\\\\/]{2}[^\\\\/]+[\\\\/][^\\\\/]+)");

    private static final List<String> COMPONENT_TYPES =
            Arrays.stream(ComponentType.values()).map(ComponentType::value).toList();

    private ConfigReader() {}

    /** {@code source} names the file in error messages. */
    public static AuditConfig parse(Object data, String source) {
        if (!(data instanceof Map<?, ?> map)) {
            throw new ConfigException(source + ": expected a mapping at the top level");
        }
        List<String> errors = new ArrayList<>();
        Fields top = Fields.root(map, errors);

        Fields app = top.section("application");
        if (!map.containsKey("application")) {
            errors.add("  - missing required key 'application'");
        }
        Application application = new Application(app.required("name"), app.str("description", null), app.str("owner", null));
        app.finish();

        Source src = source(top, errors);
        List<String> include = top.strings("include", List.of("**"));
        List<String> exclude = top.strings("exclude", List.of());
        List<ComponentSpec> components = new ArrayList<>();
        for (Fields c : top.sections("components")) {
            String type = c.choice("type", null, COMPONENT_TYPES);
            components.add(new ComponentSpec(
                    c.required("name"), strip(norm(c.required("path")), '/'), type == null ? null : ComponentType.of(type), c.str("root", null)));
            c.finish();
        }
        String inputsDir = norm(top.str("inputs_dir", "audit-inputs"));
        String outputDir = norm(top.str("output_dir", "audit-output"));
        String overridesFile = norm(top.str("overrides_file", "audit-overrides.yaml"));

        Fields t = top.section("target");
        Target target = new Target(
                t.choice("profile", Target.DEFAULT, Target.PROFILES),
                t.str("java", "25"),
                t.str("spring_boot", "4.1"),
                t.str("angular", "21"),
                t.str("wildfly", "41"),
                t.str("jakarta_ee", "11"),
                t.str("database", "postgresql"),
                t.str("cloud", "gcp"));
        t.finish();

        Map<String, String> counting = top.stringMap("counting", AuditConfig.COUNTING_RULES);
        complexity(top.section("complexity"));
        List<String> packs = rules(top.section("rules"));
        branding(top.section("branding"));
        pdf(top.section("pdf"));

        Fields inv = top.section("inventory");
        Inventory inventory = new Inventory(
                inv.bool("include_binaries", false),
                inv.number("max_file_size_mb", 20.0),
                inv.bool("use_default_excludes", true),
                inv.bool("skip_build_output", true),
                inv.choice("component_grouping", "auto", List.of("auto", "module")),
                inv.integer("workers", 0, 0, Integer.MAX_VALUE));
        inv.finish();

        Fields integrations = top.section("integrations");
        integrations.bool("group_by_service", true);
        integrations.finish();
        Fields sql = top.section("sql");
        sql.str("dialect", "oracle");
        sql.finish();
        Fields cobol = top.section("cobol");
        List<String> copybooks = cobol.strings("copybook_paths", List.of()).stream().map(p -> strip(norm(p), '/')).toList();
        cobol.str("sql_dialect", "db2");
        cobol.finish();
        Fields metrics = top.section("metrics");
        int tokens = metrics.integer("duplication_tokens", 50, 10, Integer.MAX_VALUE);
        metrics.finish();
        effort(top.section("effort"));
        security(top.section("security"));
        ai(top.section("ai"));
        top.finish();

        if (!errors.isEmpty()) {
            throw new ConfigException(source + " is invalid:\n" + String.join("\n", errors));
        }
        return new AuditConfig(application, src, include, exclude, components, inputsDir, outputDir, overridesFile,
                target, counting, inventory, copybooks, tokens, packs, top.dump());
    }

    private static Source source(Fields top, List<String> errors) {
        Fields s = top.section("source");
        Object type = s.has("type") ? s.raw("type") : "local";
        if (!"local".equals(type) && !"git".equals(type)) {
            errors.add("  - source.type must be 'local' or 'git', not '" + type + "'");
            type = "local";
        }
        s.keep("type", type);
        Source out;
        if ("git".equals(type)) {
            List<GitRepo> repos = new ArrayList<>();
            for (Fields r : s.sections("repos")) {
                String ref = r.str("ref", null);
                ref = ref == null || ref.isBlank() ? null : ref.strip();
                if (ref != null && (!AuditConfig.GIT_REF.matcher(ref).matches() || ref.contains(".."))) {
                    r.error("ref", "'" + ref + "' is not a branch, tag or commit name");
                }
                repos.add(new GitRepo(r.required("url"), ref, r.strings("paths", List.of()).stream().map(ConfigReader::norm).toList(),
                        r.str("name", null), r.str("token_env", null)));
                r.finish();
            }
            if (repos.isEmpty()) {
                s.error("repos", "List should have at least 1 item after validation");
            }
            out = new GitSource(repos, s.str("cache_dir", null));
        } else {
            List<String> paths = s.strings("paths", List.of(".")).stream().map(ConfigReader::norm).toList();
            if (paths.isEmpty()) {
                s.error("paths", "List should have at least 1 item after validation");
            }
            out = new LocalSource(paths);
        }
        s.finish();
        return out;
    }

    private static void complexity(Fields c) {
        c.numberMap("weights", doubles("loc", 1.0, "cyclomatic", 2.0, "tables", 1.5, "integrations", 2.0, "vendor_sql", 1.5,
                "fan_out", 1.0, "fields", 0.2));
        c.numberMap("scale", doubles("loc", 100.0, "cyclomatic", 10.0));
        c.numberMap("thresholds", doubles("medium", 10.0, "complex", 25.0, "very_complex", 50.0));
        c.integer("high_unit", 20, Integer.MIN_VALUE, Integer.MAX_VALUE);
        c.finish();
    }

    private static List<String> rules(Fields r) {
        List<String> packs = r.strings("enabled", List.of("risk", "postgresql", "dependency"));
        r.strings("disabled", List.of());
        Map<String, String> severity = r.stringMap("severity", Map.of());
        List<String> allowed = List.of("info", "low", "medium", "high", "critical");
        severity.forEach((rule, sev) -> {
            if (!allowed.contains(sev)) {
                r.error("severity", "rules.severity." + rule + ": '" + sev + "' is not one of " + allowed);
            }
        });
        r.str("packs_dir", null);
        r.finish();
        return packs;
    }

    private static void branding(Fields b) {
        b.str("theme", "neutral");
        b.str("logo", null);
        b.str("primary_color", "#1f4e79");
        b.str("accent_color", "#2e75b6");
        b.str("font", "Segoe UI, Arial, sans-serif");
        b.str("pptx_template", null); // accepted for old files; the PowerPoint report is not carried over
        b.finish();
    }

    private static void pdf(Fields p) {
        p.bool("enabled", true);
        p.str("browser_path", null);
        p.choice("paper", "A4", List.of("A4", "Letter"));
        p.integer("max_table_rows", 500, 10, Integer.MAX_VALUE);
        p.bool("detail_pages", true);
        p.integer("timeout_seconds", 180, 10, Integer.MAX_VALUE);
        p.finish();
    }

    private static void effort(Fields e) {
        e.bool("enabled", true);
        Object weights = e.raw("weights");
        if (weights != null && !(weights instanceof Map<?, ?>)) {
            e.error("weights", "Input should be a valid dictionary");
        } else if (weights instanceof Map<?, ?> items) {
            items.forEach((item, levels) -> {
                if (!(levels instanceof Map<?, ?> lv)) {
                    e.error("weights." + item, "Input should be a valid dictionary");
                    return;
                }
                lv.forEach((level, triple) -> {
                    if (!(triple instanceof List<?> t) || t.size() != 3 || !t.stream().allMatch(Number.class::isInstance)
                            || ((Number) t.get(0)).doubleValue() > ((Number) t.get(1)).doubleValue()
                            || ((Number) t.get(1)).doubleValue() > ((Number) t.get(2)).doubleValue()) {
                        e.error("weights", "effort.weights." + item + "." + level + " must be [low, likely, high], ascending");
                    }
                });
            });
        }
        e.keep("weights", weights == null ? Map.of() : weights);
        e.finish();
    }

    private static void security(Fields s) {
        Fields dc = s.section("dependency_check");
        dc.bool("enabled", true);
        dc.str("path", null);
        dc.str("data_dir", null);
        dc.str("nvd_mirror_url", null);
        dc.bool("offline", true);
        dc.integer("timeout_minutes", 30, Integer.MIN_VALUE, Integer.MAX_VALUE);
        dc.finish();
        Fields osv = s.section("osv_scanner");
        osv.bool("enabled", true);
        osv.str("path", null);
        osv.str("data_dir", null);
        osv.bool("offline", false);
        osv.integer("timeout_minutes", 15, Integer.MIN_VALUE, Integer.MAX_VALUE);
        osv.finish();
        Fields jdk = s.section("jdk_tools");
        jdk.bool("enabled", false);
        jdk.str("java_home", null);
        jdk.finish();
        s.finish();
    }

    private static void ai(Fields a) {
        a.choice("provider", "copilot", List.of("copilot", "openai", "anthropic", "azure-openai", "ollama"));
        a.str("model", null);
        a.str("base_url", null);
        a.str("api_key_env", null);
        a.str("api_version", "2024-10-21");
        a.optionalNumber("temperature", 0.1);
        a.integer("max_output_tokens", 16000, 256, Integer.MAX_VALUE);
        a.bool("json_mode", true);
        a.integer("context_chars", 150_000, 10_000, Integer.MAX_VALUE);
        a.integer("timeout_seconds", 600, 10, Integer.MAX_VALUE);
        a.integer("repair_rounds", 2, 0, 5);
        a.str("ca_bundle", null);
        a.str("copilot_model", null);
        a.finish();
    }

    private static Map<String, Double> doubles(Object... pairs) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], (Double) pairs[i + 1]);
        }
        return out;
    }

    /** Accept Windows separators; keep drive-absolute Windows paths recognisable. */
    public static String norm(String value) {
        if (value == null) {
            return null;
        }
        return WINDOWS_DRIVE.matcher(value).find() ? value : value.replace('\\', '/');
    }

    static String strip(String s, char c) {
        int a = 0;
        int b = s.length();
        while (a < b && s.charAt(a) == c) {
            a++;
        }
        while (b > a && s.charAt(b - 1) == c) {
            b--;
        }
        return s.substring(a, b);
    }
}
