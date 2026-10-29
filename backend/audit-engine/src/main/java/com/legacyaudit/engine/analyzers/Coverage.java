package com.legacyaudit.engine.analyzers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** How much of a file an analyzer could parse: its lines, and the regions it could not. */
public final class Coverage {

    /** A region the analyzer could not parse. */
    public record Unparsed(int start, int end, String reason) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("start", (long) start);
            m.put("end", (long) end);
            m.put("reason", reason);
            return m;
        }
    }

    public int lines;
    public final List<Unparsed> unparsed = new ArrayList<>();

    public void gap(int start, int end, String reason) {
        unparsed.add(new Unparsed(start, end, reason));
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lines", (long) lines);
        m.put("unparsed", unparsed.stream().map(Unparsed::toMap).toList());
        return m;
    }

    @SuppressWarnings("unchecked")
    static Coverage fromMap(Map<String, Object> m) {
        Coverage c = new Coverage();
        if (m == null) {
            return c;
        }
        c.lines = m.get("lines") instanceof Number n ? n.intValue() : 0;
        if (m.get("unparsed") instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> u = (Map<String, Object>) o;
                c.gap(((Number) u.get("start")).intValue(), ((Number) u.get("end")).intValue(), (String) u.get("reason"));
            }
        }
        return c;
    }
}
