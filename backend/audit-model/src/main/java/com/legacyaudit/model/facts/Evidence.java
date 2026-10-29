package com.legacyaudit.model.facts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a fact was found. {@code line} is null when the fact is about the whole file.
 *
 * @param file path as reported in the inventory
 * @param line 1-based line, or null
 * @param snippet the text found there, or null
 */
public record Evidence(String file, Integer line, String snippet) {

    public static Evidence of(String file) {
        return new Evidence(file, null, null);
    }

    public static List<Evidence> list(String file) {
        return List.of(of(file));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", file);
        m.put("line", line);
        m.put("snippet", snippet);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static List<Evidence> fromMaps(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .map(o -> (Map<String, Object>) o)
                .map(m -> new Evidence((String) m.get("file"), MapValues.integer(m.get("line")), (String) m.get("snippet")))
                .toList();
    }
}
