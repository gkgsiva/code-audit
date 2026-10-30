package com.legacyaudit.reports;

import com.legacyaudit.engine.rules.Py;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Python dict and list idioms over JSON data ({@code Map<String, Object>} and {@code List<Object>}
 * as Jackson reads them), so the report code reads like the Python it reproduces.
 */
public final class D {

    private D() {}

    /** {@code d.get(key)}; null when {@code d} is not a map. */
    public static Object get(Object d, String key) {
        return d instanceof Map<?, ?> m ? m.get(key) : null;
    }

    /** {@code d.get(key, default)}: the default only when the key is absent. */
    public static Object get(Object d, String key, Object dflt) {
        return d instanceof Map<?, ?> m && m.containsKey(key) ? m.get(key) : dflt;
    }

    /** {@code a or b}. */
    public static Object or(Object a, Object b) {
        return Py.truthy(a) ? a : b;
    }

    /** {@code d.get(key) or ""} as a string ({@code str()} of a non-string value). */
    public static String s(Object d, String key) {
        Object v = get(d, key);
        return Py.truthy(v) ? Py.str(v) : "";
    }

    /** {@code str(d.get(key))}; "None" when absent, as Python prints it. */
    public static String str(Object d, String key) {
        return Py.str(get(d, key));
    }

    /** {@code d.get(key) or None} as a string, or null. */
    public static String sOrNull(Object d, String key) {
        Object v = get(d, key);
        return Py.truthy(v) ? Py.str(v) : null;
    }

    public static boolean truthy(Object v) {
        return Py.truthy(v);
    }

    public static boolean t(Object d, String key) {
        return Py.truthy(get(d, key));
    }

    /** {@code d.get(key) or []}. */
    @SuppressWarnings("unchecked")
    public static List<Object> list(Object d, String key) {
        Object v = get(d, key);
        return v instanceof List<?> l ? (List<Object>) l : new ArrayList<>();
    }

    /** {@code d.get(key) or []} as strings. */
    public static List<String> strings(Object d, String key) {
        List<String> out = new ArrayList<>();
        for (Object o : list(d, key)) {
            out.add(Py.str(o));
        }
        return out;
    }

    /** {@code d.get(key) or {}}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object d, String key) {
        Object v = get(d, key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> maps(Object d, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list(d, key)) {
            out.add(o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> maps(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                out.add(o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>());
            }
        }
        return out;
    }

    /** {@code int(d.get(key) or 0)}. */
    public static long i(Object d, String key) {
        Object v = get(d, key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof String s && !s.isEmpty()) {
            return Long.parseLong(s.strip());
        }
        return 0;
    }

    /** {@code d.get(key, 0)} as a number for sums and comparisons. */
    public static double num(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return 0;
    }

    /** A dict literal: {@code of("a", 1, "b", 2)}, keys in order. */
    public static Map<String, Object> of(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int k = 0; k < kv.length; k += 2) {
            out.put((String) kv[k], kv[k + 1]);
        }
        return out;
    }

    /** {@code {**base, k: v, ...}}. */
    public static Map<String, Object> with(Map<String, Object> base, Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (int k = 0; k < kv.length; k += 2) {
            out.put((String) kv[k], kv[k + 1]);
        }
        return out;
    }

    /** {@code list(dict.fromkeys(items))}. */
    public static <T> List<T> unique(Collection<T> items) {
        return new ArrayList<>(new LinkedHashSet<>(items));
    }

    /** {@code text.rsplit(sep, 1)[-1]}. */
    public static String last(String text, String sep) {
        int i = text.lastIndexOf(sep);
        return i < 0 ? text : text.substring(i + sep.length());
    }

    /** {@code (a, b)} as a list that prints and compares like a tuple. */
    public static List<Object> pair(Object a, Object b) {
        List<Object> out = new ArrayList<>(2);
        out.add(a);
        out.add(b);
        return out;
    }
}
