package com.legacyaudit.engine.security;

import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.engine.rules.PyRegex;
import com.legacyaudit.model.facts.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** End-of-life flags for libraries and tech-stack items (data in {@code rules/eol.yaml}). */
public final class Eol {

    private Eol() {}

    private record Entry(String name, PyRegex pattern, PyRegex group, String versions, String eolDate, String replacement) {}

    @SuppressWarnings("unchecked")
    private static List<Entry> entries(Object items, String key) {
        List<Entry> out = new ArrayList<>();
        if (items instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> e = (Map<String, Object>) o;
                Object name = e.get("name");
                out.add(new Entry(String.valueOf(truthy(name) ? name : e.get(key)), PyRegex.compile(String.valueOf(e.get(key)), Rx.I),
                        truthy(e.get("group")) ? PyRegex.compile(String.valueOf(e.get("group")), Rx.I) : null,
                        e.containsKey("versions") ? String.valueOf(e.get("versions")) : "*",
                        truthy(e.get("eol_date")) ? String.valueOf(e.get("eol_date")) : null,
                        e.get("replacement") == null ? null : String.valueOf(e.get("replacement"))));
            }
        }
        return out;
    }

    static boolean truthy(Object v) {
        return v != null && !(v instanceof String s && s.isEmpty()) && !Boolean.FALSE.equals(v);
    }

    private static boolean full(PyRegex rx, String value) {
        return value != null && rx.pattern.matcher(value).matches();
    }

    private static Entry libraryEntry(Doc lib, List<Entry> entries) {
        for (Entry e : entries) {
            if (!full(e.pattern(), lib.str("artifact"))) {
                continue;
            }
            String group = lib.str("group");
            if (e.group() != null && group != null && !group.isEmpty() && !full(e.group(), group)) {
                continue;
            }
            if (Boolean.TRUE.equals(Versions.inRange(lib.str("version"), e.versions()))) {
                return e;
            }
        }
        return null;
    }

    /**
     * Set {@code eol}, {@code eol_date} and {@code replacement} in place. {@code eol} stays null
     * when the version is unknown and only a version range could decide.
     */
    public static void apply(List<Doc> libraries, List<Doc> tech, Map<String, Object> data) {
        List<Entry> libEntries = entries(data.get("libraries"), "artifact");
        List<Entry> techEntries = entries(data.get("technologies"), "name");
        for (Doc lib : libraries) {
            Entry hit = libraryEntry(lib, libEntries);
            if (hit != null) {
                lib.set("eol", true).set("eol_date", hit.eolDate()).set("replacement", hit.replacement());
            } else if (lib.get("eol") == null && truthy(lib.str("version"))) {
                lib.set("eol", false);
            }
        }
        for (Doc item : tech) {
            boolean decided = false;
            for (Entry e : techEntries) {
                if (!full(e.pattern(), item.str("name"))) {
                    continue;
                }
                Boolean ok = Versions.inRange(item.str("version"), e.versions());
                if (Boolean.TRUE.equals(ok)) {
                    item.set("eol", true).set("eol_date", e.eolDate()).set("replacement", e.replacement());
                    decided = true;
                    break;
                }
                if (Boolean.FALSE.equals(ok)) {
                    decided = true;
                }
            }
            if ((!decided && item.get("eol") == null && truthy(item.str("version"))) || (decided && item.get("eol") == null)) {
                item.set("eol", false);
            }
        }
    }
}
