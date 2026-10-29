package com.legacyaudit.model.config;

import com.legacyaudit.model.facts.ComponentType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code audit-overrides.yaml}: human corrections re-applied on every run.
 *
 * <pre>
 * files:
 *   exclude: ["legacy/old-copy/**"]    # dropped from all facts
 *   include: ["build/generated-sql/"]  # re-included despite default excludes
 * components:
 *   assign: {"src/reports/**": reports}  # move files to a (possibly new) component
 *   rename: {web-old: customer-portal}
 *   type: {customer-portal: web}
 * </pre>
 */
public record Overrides(
        List<String> filesInclude,
        List<String> filesExclude,
        Map<String, String> assign,
        Map<String, String> rename,
        Map<String, ComponentType> type) {

    public static final Overrides NONE = new Overrides(List.of(), List.of(), Map.of(), Map.of(), Map.of());

    /** The overrides file, or {@link #NONE} when there is none. */
    public static Overrides load(Path path) {
        if (!Files.isRegularFile(path)) {
            return NONE;
        }
        Object data = ConfigLoader.readYaml(path);
        if (data == null) {
            return NONE;
        }
        if (!(data instanceof Map<?, ?> map)) {
            throw new ConfigException(path + " is invalid:\n  - (top level): Input should be a valid dictionary");
        }
        List<String> errors = new ArrayList<>();
        Fields top = Fields.root(map, errors);
        Fields files = top.section("files");
        List<String> include = files.strings("include", List.of());
        List<String> exclude = files.strings("exclude", List.of());
        files.finish();
        Fields comps = top.section("components");
        Map<String, String> assign = comps.stringMap("assign", Map.of());
        Map<String, String> rename = comps.stringMap("rename", Map.of());
        Map<String, ComponentType> type = new LinkedHashMap<>();
        List<String> types = Arrays.stream(ComponentType.values()).map(ComponentType::value).toList();
        comps.stringMap("type", Map.of()).forEach((name, t) -> {
            if (types.contains(t)) {
                type.put(name, ComponentType.of(t));
            } else {
                comps.error("type." + name, "Input should be " + String.join(", ", types.stream().map(v -> "'" + v + "'").toList()));
            }
        });
        comps.finish();
        top.finish();
        if (!errors.isEmpty()) {
            throw new ConfigException(path + " is invalid:\n" + String.join("\n", errors));
        }
        return new Overrides(include, exclude, assign, rename, type);
    }
}
