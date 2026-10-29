package com.legacyaudit.model.facts;

import java.util.LinkedHashMap;
import java.util.Map;

/** One scanned source root, as written into {@code run.json}. */
public record SourceInfo(String name, String type, String path, String url, String ref, String commit) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        m.put("path", path);
        m.put("url", url);
        m.put("ref", ref);
        m.put("commit", commit);
        return m;
    }
}
