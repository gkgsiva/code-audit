package com.legacyaudit.engine.analyzers;

import java.util.LinkedHashMap;
import java.util.Map;

/** Something a file defines, findable by name: a class, program, copybook, job, view... */
public final class Symbol {

    public final String kind;
    public final String name;
    public final String file;
    public final Integer line;
    /** Id of the fact this symbol stands for. */
    public String owner;
    public final Map<String, String> data = new LinkedHashMap<>();

    public Symbol(String kind, String name, String file, Integer line) {
        this.kind = kind;
        this.name = name;
        this.file = file;
        this.line = line;
    }

    public Symbol owner(String owner) {
        this.owner = owner;
        return this;
    }

    public Symbol data(String key, String value) {
        data.put(key, value);
        return this;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("name", name);
        m.put("file", file);
        m.put("line", line == null ? null : (Object) line.longValue());
        m.put("owner", owner);
        m.put("data", new LinkedHashMap<>(data));
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Symbol fromMap(Map<String, Object> m) {
        Symbol s = new Symbol((String) m.get("kind"), (String) m.get("name"), (String) m.get("file"),
                m.get("line") instanceof Number n ? n.intValue() : null);
        s.owner = (String) m.get("owner");
        if (m.get("data") instanceof Map<?, ?> d) {
            ((Map<String, Object>) d).forEach((k, v) -> s.data.put(k, String.valueOf(v)));
        }
        return s;
    }
}
