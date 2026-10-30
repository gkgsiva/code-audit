package com.legacyaudit.engine.rules;

import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.model.json.PyJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rule packs: YAML files in {@code rules/} (shipped with the tool, inside the jar under
 * {@code legacyaudit/rules/}) plus an optional team folder ({@code rules.packs_dir}) whose rules
 * replace ours by id or add new ones.
 *
 * <p>A rule matches either
 *
 * <ul>
 *   <li>facts: {@code match: {entity: <collection>, where: {<field>: <condition>}}}, evaluated
 *       every run over the linked facts (and the metric collections {@code units},
 *       {@code dead_code}, {@code binaries}); or
 *   <li>text: {@code pattern: {regex: ..., languages: [...]}}, evaluated per file by the
 *       {@code patterns} analyzer and cached with the file (the patterns' hash is part of the
 *       cache key).
 * </ul>
 *
 * <p>Conditions: a plain value means equal; otherwise a mapping of {@code eq}, {@code ne},
 * {@code in}, {@code not_in}, {@code regex}, {@code gt}, {@code gte}, {@code lt}, {@code lte},
 * {@code exists}, {@code empty}, {@code contains}.
 */
public final class Packs {

    public static final Map<String, String> PACK_FILES = Map.of(
            "risk", "risk-rules.yaml",
            "postgresql", "pg-rules.yaml",
            "dependency", "dependency-upgrades.yaml");
    public static final List<String> SEVERITIES = List.of("info", "low", "medium", "high", "critical");
    public static final String RESOURCE_DIR = "legacyaudit/rules/";

    private static final Map<String, Map<String, Object>> CACHE = new ConcurrentHashMap<>();

    private Packs() {}

    /** Which facts a rule matches. */
    public record FactMatch(String entity, Map<String, Object> where) {}

    /** Which text a rule matches, per file. */
    public static final class TextPattern {
        public final String regex;
        public final boolean ignoreCase;
        public final boolean multiline;
        /** Empty: every language. */
        public final List<String> languages;
        /** Regex; matched text to skip. */
        public final String ignore;
        /** Regex; skip files that contain it. */
        public final String unlessFile;
        public final int maxPerFile;
        private PyRegex compiled;
        private PyRegex ignoreRx;
        private PyRegex unlessRx;

        TextPattern(String regex, boolean ignoreCase, boolean multiline, List<String> languages, String ignore, String unlessFile,
                int maxPerFile) {
            this.regex = regex;
            this.ignoreCase = ignoreCase;
            this.multiline = multiline;
            this.languages = languages;
            this.ignore = ignore;
            this.unlessFile = unlessFile;
            this.maxPerFile = maxPerFile;
            compiled = PyRegex.compile(regex, (ignoreCase ? Rx.I : 0) | (multiline ? Rx.M : 0));
            ignoreRx = ignore != null ? PyRegex.compile(ignore) : null;
            unlessRx = unlessFile != null ? PyRegex.compile(unlessFile) : null;
        }

        public PyRegex compiled() {
            return compiled;
        }

        public PyRegex ignoreRegex() {
            return ignoreRx;
        }

        public PyRegex unlessRegex() {
            return unlessRx;
        }

        /** {@code model_dump()}: the fields as the Python tool names them. */
        public Map<String, Object> dump() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("regex", regex);
            m.put("ignore_case", ignoreCase);
            m.put("multiline", multiline);
            m.put("languages", languages);
            m.put("ignore", ignore);
            m.put("unless_file", unlessFile);
            m.put("max_per_file", (long) maxPerFile);
            return m;
        }
    }

    /**
     * One rule. {@code message} is a Python format string over the matched item's fields;
     * {@code severityField} names an item field whose value (when a valid severity) replaces
     * {@code severity}.
     */
    public record Rule(String id, String severity, String category, String message, String remediation, String severityField,
            FactMatch match, TextPattern pattern) {}

    /** A pack: its rules and the tables the code uses ({@code data}). */
    public static final class Pack {
        public final String pack;
        public final String description;
        public List<Rule> rules;
        public final Map<String, Object> data;

        Pack(String pack, String description, List<Rule> rules, Map<String, Object> data) {
            this.pack = pack;
            this.description = description;
            this.rules = rules;
            this.data = data;
        }
    }

    private static String fileOf(String name) {
        return PACK_FILES.getOrDefault(name, name + ".yaml");
    }

    private static Map<String, Object> builtin(String file) {
        return CACHE.computeIfAbsent(file, f -> {
            try (InputStream in = Packs.class.getClassLoader().getResourceAsStream(RESOURCE_DIR + f)) {
                if (in == null) {
                    return Map.of();
                }
                return asMap(ConfigLoader.parseYaml(new String(in.readAllBytes(), StandardCharsets.UTF_8), f), f);
            } catch (IOException e) {
                throw new ConfigException("Cannot read rule pack " + f + ": " + e.getMessage());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object data, String source) {
        if (data == null) {
            return Map.of();
        }
        if (!(data instanceof Map<?, ?> m)) {
            throw new ConfigException(source + ": expected a mapping");
        }
        return (Map<String, Object>) m;
    }

    /** The pack {@code name} with the team folder's rules and data merged in (a fresh copy). */
    public static Pack load(String name, Path teamDir) {
        String file = fileOf(name);
        Map<String, Object> base = builtin(file);
        Pack pack = parse(base.isEmpty() ? Map.of("pack", name) : base, file);
        if (teamDir != null) {
            Path extra = teamDir.resolve(file);
            if (Files.isRegularFile(extra)) {
                Map<String, Object> raw;
                try {
                    raw = asMap(ConfigLoader.parseYaml(Files.readString(extra, StandardCharsets.UTF_8), extra.toString()), extra.toString());
                } catch (IOException e) {
                    throw new ConfigException("Cannot read " + extra + ": " + e.getMessage());
                }
                Map<String, Object> withName = new LinkedHashMap<>();
                withName.put("pack", name);
                withName.putAll(raw);
                Pack team = parse(withName, extra.toString());
                Set<String> ids = new LinkedHashSet<>();
                team.rules.forEach(r -> ids.add(r.id()));
                List<Rule> merged = new ArrayList<>();
                for (Rule r : pack.rules) {
                    if (!ids.contains(r.id())) {
                        merged.add(r);
                    }
                }
                merged.addAll(team.rules);
                pack.rules = merged;
                for (var e : team.data.entrySet()) {
                    Object mine = pack.data.get(e.getKey());
                    if (e.getValue() instanceof Map<?, ?> v && mine instanceof Map<?, ?> p) {
                        Map<Object, Object> both = new LinkedHashMap<>(p);
                        both.putAll(v);
                        pack.data.put(e.getKey(), both);
                    } else if (e.getValue() instanceof List<?> v && mine instanceof List<?> p) {
                        List<Object> both = new ArrayList<>(v); // team entries are matched first
                        both.addAll(p);
                        pack.data.put(e.getKey(), both);
                    } else {
                        pack.data.put(e.getKey(), e.getValue());
                    }
                }
            }
        }
        return pack;
    }

    private static final Set<String> PACK_KEYS = Set.of("pack", "description", "rules", "data");
    private static final Set<String> RULE_KEYS = Set.of("id", "severity", "category", "message", "remediation", "severity_field", "match",
            "pattern");
    private static final Set<String> PATTERN_KEYS = Set.of("regex", "ignore_case", "multiline", "languages", "ignore", "unless_file",
            "max_per_file");
    private static final Set<String> MATCH_KEYS = Set.of("entity", "where");

    @SuppressWarnings("unchecked")
    private static Pack parse(Map<String, Object> raw, String source) {
        extra(raw, PACK_KEYS, source);
        Object name = raw.get("pack");
        if (!(name instanceof String)) {
            throw new ConfigException(source + ": 'pack' is required");
        }
        List<Rule> rules = new ArrayList<>();
        Object rs = raw.get("rules");
        if (rs instanceof List<?> list) {
            for (Object o : list) {
                rules.add(rule(asMap(o, source), source));
            }
        } else if (rs != null) {
            throw new ConfigException(source + ": 'rules' must be a list");
        }
        Object data = raw.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> d = data == null ? new LinkedHashMap<>() : (Map<String, Object>) deepCopy(asMap(data, source));
        return new Pack((String) name, (String) raw.get("description"), rules, d);
    }

    /** A copy of YAML data whose maps and lists can be changed without touching the original. */
    public static Object deepCopy(Object node) {
        if (node instanceof Map<?, ?> m) {
            Map<Object, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(k, deepCopy(v)));
            return out;
        }
        if (node instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(v -> out.add(deepCopy(v)));
            return out;
        }
        return node;
    }

    private static void extra(Map<String, Object> raw, Set<String> allowed, String source) {
        for (String k : raw.keySet()) {
            if (!allowed.contains(k)) {
                throw new ConfigException(source + ": unknown field '" + k + "'");
            }
        }
    }

    private static String str(Map<String, Object> m, String key, boolean required, String source) {
        Object v = m.get(key);
        if (v == null) {
            if (required) {
                throw new ConfigException(source + ": rule field '" + key + "' is required");
            }
            return null;
        }
        return String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static Rule rule(Map<String, Object> m, String source) {
        extra(m, RULE_KEYS, source);
        String id = str(m, "id", true, source);
        String severity = str(m, "severity", true, source);
        if (!SEVERITIES.contains(severity)) {
            throw new ConfigException(source + ": rule " + id + ": severity '" + severity + "' is not one of " + SEVERITIES);
        }
        FactMatch match = null;
        if (m.get("match") != null) {
            Map<String, Object> mm = asMap(m.get("match"), source);
            extra(mm, MATCH_KEYS, source);
            Object where = mm.get("where");
            match = new FactMatch(str(mm, "entity", true, source),
                    where == null ? new LinkedHashMap<>() : new LinkedHashMap<>(asMap(where, source)));
        }
        TextPattern pattern = null;
        if (m.get("pattern") != null) {
            Map<String, Object> pm = asMap(m.get("pattern"), source);
            extra(pm, PATTERN_KEYS, source);
            List<String> langs = new ArrayList<>();
            if (pm.get("languages") instanceof List<?> l) {
                l.forEach(x -> langs.add(String.valueOf(x)));
            }
            Object max = pm.get("max_per_file");
            try {
                pattern = new TextPattern(str(pm, "regex", true, source), Boolean.TRUE.equals(pm.get("ignore_case")),
                        Boolean.TRUE.equals(pm.get("multiline")), langs, str(pm, "ignore", false, source),
                        str(pm, "unless_file", false, source), max == null ? 20 : ((Number) max).intValue());
            } catch (RuntimeException e) {
                throw new ConfigException(source + ": rule " + id + ": " + e.getMessage());
            }
        }
        return new Rule(id, severity, str(m, "category", true, source), str(m, "message", true, source),
                str(m, "remediation", false, source), str(m, "severity_field", false, source), match, pattern);
    }

    /**
     * Every text rule of the enabled packs, sorted by id (disabled ones too: switching a rule off
     * is applied when findings are built, so it needs no re-scan).
     */
    public static List<Rule> textRules(List<String> enabled, Path teamDir) {
        List<Rule> out = new ArrayList<>();
        for (String name : enabled) {
            for (Rule r : load(name, teamDir).rules) {
                if (r.pattern() != null) {
                    out.add(r);
                }
            }
        }
        out.sort(Comparator.comparing(Rule::id, PyJson::compareCodePoints));
        return out;
    }

    /** Hash of the text patterns: part of the file-cache key, so changing one re-scans. */
    public static String rulesKey(List<Rule> rules) {
        List<Object> dumps = new ArrayList<>();
        List<Object> ids = new ArrayList<>();
        for (Rule r : rules) {
            if (r.pattern() != null) {
                dumps.add(r.pattern().dump());
            }
            ids.add(r.id());
        }
        String raw = PyJson.dumps(dumps, true) + PyJson.dumps(ids, false);
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
