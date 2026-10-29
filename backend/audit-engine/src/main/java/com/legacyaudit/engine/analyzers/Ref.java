package com.legacyaudit.engine.analyzers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A by-name reference the linker resolves against symbols or file paths. */
public final class Ref {

    /** Edge kind: calls, includes, forwards, invokes, uses, ... */
    public final String kind;
    /** Id of the referencing fact, or the file id. */
    public final String source;
    public final String sourceLabel;
    public final String target;
    /** Symbol kinds, or 'path' / 'file'. */
    public final List<String> targetKinds;
    public final String file;
    public final Integer line;
    public boolean reportUnresolved = true;
    public final Map<String, String> data = new LinkedHashMap<>();

    public Ref(String kind, String source, String sourceLabel, String target, List<String> targetKinds, String file, Integer line) {
        this.kind = kind;
        this.source = source;
        this.sourceLabel = sourceLabel;
        this.target = target;
        this.targetKinds = new ArrayList<>(targetKinds);
        this.file = file;
        this.line = line;
    }

    public Ref quiet() {
        reportUnresolved = false;
        return this;
    }

    public Ref reportUnresolved(boolean report) {
        reportUnresolved = report;
        return this;
    }

    public Ref data(String key, String value) {
        data.put(key, value);
        return this;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("source", source);
        m.put("source_label", sourceLabel);
        m.put("target", target);
        m.put("target_kinds", new ArrayList<>(targetKinds));
        m.put("file", file);
        m.put("line", line == null ? null : (Object) line.longValue());
        m.put("report_unresolved", reportUnresolved);
        m.put("data", new LinkedHashMap<>(data));
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Ref fromMap(Map<String, Object> m) {
        Ref r = new Ref((String) m.get("kind"), (String) m.get("source"), (String) m.get("source_label"), (String) m.get("target"),
                (List<String>) m.get("target_kinds"), (String) m.get("file"), m.get("line") instanceof Number n ? n.intValue() : null);
        r.reportUnresolved = !Boolean.FALSE.equals(m.get("report_unresolved"));
        if (m.get("data") instanceof Map<?, ?> d) {
            ((Map<String, Object>) d).forEach((k, v) -> r.data.put(k, String.valueOf(v)));
        }
        return r;
    }
}
