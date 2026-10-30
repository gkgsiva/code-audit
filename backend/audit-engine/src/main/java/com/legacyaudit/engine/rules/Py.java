package com.legacyaudit.engine.rules;

import com.legacyaudit.model.json.PyJson;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Python's {@code str()}, {@code repr()}, truth and simple format specs over JSON values. */
public final class Py {

    private Py() {}

    /** Python truth: None, False, 0, "" and empty lists or maps are false. */
    public static boolean truthy(Object v) {
        if (v == null || v instanceof Boolean b && !b) {
            return false;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        if (v instanceof List<?> l) {
            return !l.isEmpty();
        }
        return !(v instanceof Map<?, ?> m) || !m.isEmpty();
    }

    /** {@code str(value)}. */
    public static String str(Object v) {
        return v instanceof String s ? s : repr(v, false);
    }

    /** {@code repr(value)}; a list as a tuple when {@code tuple}. */
    public static String repr(Object v, boolean tuple) {
        if (v == null) {
            return "None";
        }
        if (v instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (v instanceof Double || v instanceof Float) {
            return PyJson.repr(((Number) v).doubleValue());
        }
        if (v instanceof Number n) {
            return Long.toString(n.longValue());
        }
        if (v instanceof String s) {
            return quote(s);
        }
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder(tuple ? "(" : "[");
            for (int i = 0; i < l.size(); i++) {
                sb.append(i > 0 ? ", " : "").append(repr(l.get(i), false));
            }
            if (tuple && l.size() == 1) {
                sb.append(',');
            }
            return sb.append(tuple ? ")" : "]").toString();
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.append(first ? "" : ", ").append(repr(e.getKey(), false)).append(": ").append(repr(e.getValue(), false));
                first = false;
            }
            return sb.append('}').toString();
        }
        return v.toString();
    }

    private static String quote(String s) {
        char q = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder sb = new StringBuilder().append(q);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c == q) {
                        sb.append('\\').append(c);
                    } else if (c < 0x20 || c == 0x7f) {
                        sb.append(String.format(Locale.ROOT, "\\x%02x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append(q).toString();
    }

    /** {@code format(value, spec)} for the specs rule messages use: {@code .Nf}, {@code ,}, {@code d}. */
    public static String format(Object v, String spec) {
        if (v instanceof Number n && !(v instanceof Boolean)) {
            boolean comma = spec.contains(",");
            String s = spec.replace(",", "");
            if (s.matches("\\.\\d+f")) {
                String out = String.format(Locale.ROOT, "%" + (comma ? "," : "") + s, n.doubleValue());
                return out;
            }
            if (s.isEmpty() || s.equals("d")) {
                return comma ? String.format(Locale.ROOT, "%,d", n.longValue()) : str(v);
            }
        }
        return str(v);
    }
}
