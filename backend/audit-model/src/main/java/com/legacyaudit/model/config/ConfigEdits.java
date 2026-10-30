package com.legacyaudit.model.config;

import com.legacyaudit.model.facts.ComponentType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Changes to a project's {@code audit.yaml} and {@code audit-overrides.yaml} made from the web
 * app, as the Python tool's {@code workspace.py} made them: one top-level block is replaced and
 * every other line and comment is kept; the result is checked before it is written.
 */
public final class ConfigEdits {

    static final String OVERRIDES_HEADER = """
            # Human corrections, re-applied after analysis on every run and recorded in run.json.
            # Overrides that match nothing are reported as warnings. Edited by legacy-audit serve too.
            """;

    private ConfigEdits() {}

    /** Line range [start, end) of a top-level {@code key:} block, or null. */
    static int[] block(List<String> lines, String key) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(key + ":")) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        int end = start + 1;
        while (end < lines.size() && (lines.get(end).startsWith(" ") || lines.get(end).startsWith("\t") || lines.get(end).strip().isEmpty())) {
            end++;
        }
        while (end > start + 1 && lines.get(end - 1).strip().isEmpty()) { // keep blank lines outside
            end--;
        }
        return new int[] {start, end};
    }

    /** Replace one top-level block, keeping every other line (and comment) as it was. */
    public static String setTopLevel(String text, String key, Object value) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put(key, value);
        String dumped = PyYaml.dump(one, 100);
        List<String> fresh = new ArrayList<>(Arrays.asList(stripTrailingNewlines(dumped).split("\n", -1)));
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        int[] span = block(lines, key);
        if (span == null) {
            String body = stripTrailingNewlines(text);
            return (body.isEmpty() ? "" : body + "\n\n") + String.join("\n", fresh) + "\n";
        }
        String rest = lines.get(span[0]).substring(key.length() + 1);
        int hash = rest.indexOf('#');
        if (fresh.size() == 1 && hash >= 0) { // keep an inline comment on a one-line value
            fresh.set(0, fresh.get(0) + "  #" + rest.substring(hash + 1));
        }
        List<String> outLines = new ArrayList<>(lines.subList(0, span[0]));
        outLines.addAll(fresh);
        outLines.addAll(lines.subList(span[1], lines.size()));
        return String.join("\n", outLines);
    }

    private static String stripTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '\n') {
            end--;
        }
        return s.substring(0, end);
    }

    /** Set top-level keys of audit.yaml; the result is checked before it is written. */
    public static void updateConfig(Path path, Map<String, Object> changes) {
        String text = read(path);
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            text = setTopLevel(text, e.getKey(), e.getValue());
        }
        ConfigReader.parse(ConfigLoader.parseYaml(text, path.toString()), path.toString()); // ConfigException: file untouched
        write(path, text);
    }

    /** Replace audit.yaml with {@code text} after checking it (the raw editor). */
    public static void writeConfigText(Path path, String text) {
        Object data;
        try {
            data = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions())).load(text);
        } catch (org.yaml.snakeyaml.error.YAMLException e) {
            throw new ConfigException("Not valid YAML: " + e.getMessage());
        }
        ConfigReader.parse(data, path.toString());
        write(path, text);
    }

    /** The {@code files.exclude} pattern that leaves a component folder out. */
    public static String componentExclude(String path) {
        String p = strip(path, '/');
        return p.isEmpty() ? "**" : p + "/**";
    }

    private static String strip(String s, char c) {
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

    /** Write the overrides file as the Python tool does (every section, a fixed header). */
    public static void saveOverrides(Path path, Overrides ov) {
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("include", ov.filesInclude());
        files.put("exclude", ov.filesExclude());
        Map<String, Object> types = new LinkedHashMap<>();
        ov.type().forEach((k, v) -> types.put(k, v.value()));
        Map<String, Object> comps = new LinkedHashMap<>();
        comps.put("assign", ov.assign());
        comps.put("rename", ov.rename());
        comps.put("type", types);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("files", files);
        data.put("components", comps);
        write(path, OVERRIDES_HEADER + "\n" + PyYaml.dump(data, 100));
    }

    /**
     * Tick (keep) or untick (exclude) component folders and set or clear type overrides.
     * {@code included} maps a component folder to ticked or not (a grouped component's folders
     * joined with {@code |}); {@code types} maps a component name to a type, or null to drop the
     * override. Other overrides stay as they are.
     */
    public static Overrides setComponentChoices(Path overridesPath, Map<String, Boolean> included, Map<String, String> types) {
        Overrides ov = Overrides.load(overridesPath);
        List<String> excludes = new ArrayList<>(ov.filesExclude());
        included.forEach((key, keep) -> {
            for (String folder : key.split("\\|", -1)) {
                String pattern = componentExclude(folder);
                if (Boolean.TRUE.equals(keep) && excludes.contains(pattern)) {
                    excludes.remove(pattern);
                } else if (!Boolean.TRUE.equals(keep) && !excludes.contains(pattern)) {
                    excludes.add(pattern);
                }
            }
        });
        Map<String, ComponentType> typeMap = new LinkedHashMap<>(ov.type());
        List<String> valid = Arrays.stream(ComponentType.values()).map(ComponentType::value).toList();
        types.forEach((name, t) -> {
            if (t != null && !t.isEmpty()) {
                if (!valid.contains(t)) {
                    throw new ConfigException("components.type." + name + ": Input should be "
                            + String.join(", ", valid.stream().map(v -> "'" + v + "'").toList()));
                }
                typeMap.put(name, ComponentType.of(t));
            } else {
                typeMap.remove(name);
            }
        });
        Overrides out = new Overrides(ov.filesInclude(), excludes, ov.assign(), ov.rename(), typeMap);
        saveOverrides(overridesPath, out);
        return out;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException("Cannot read " + path + ": " + e.getMessage());
        }
    }

    private static void write(Path path, String text) {
        try {
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException("Cannot write " + path + ": " + e.getMessage());
        }
    }
}
