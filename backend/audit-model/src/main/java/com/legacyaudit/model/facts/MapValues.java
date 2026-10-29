package com.legacyaudit.model.facts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reading fact fields back from parsed JSON (numbers come back as Integer, Long or Double). */
final class MapValues {

    private MapValues() {}

    static Integer integer(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    static int integer(Object v, int otherwise) {
        return v instanceof Number n ? n.intValue() : otherwise;
    }

    static long number(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> items) {
            for (Object o : items) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    static Map<String, Integer> counts(Object v) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> m) {
            m.forEach((k, n) -> out.put(String.valueOf(k), integer(n, 0)));
        }
        return out;
    }
}
