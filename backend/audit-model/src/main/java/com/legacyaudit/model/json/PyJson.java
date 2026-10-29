package com.legacyaudit.model.json;

import com.legacyaudit.model.facts.Valued;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * JSON text exactly as the Python tool writes it ({@code json.dumps(indent=2, sort_keys=True,
 * ensure_ascii=False)}): two-space indents, {@code ": "} and {@code ","} separators, keys sorted,
 * non-ASCII characters kept, floats as Python prints them. Run files written by both tools can
 * then be compared byte for byte.
 */
public final class PyJson {

    private PyJson() {}

    /** Pretty JSON followed by a newline, as in the run files. */
    public static String pretty(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0, true);
        return out.append('\n').toString();
    }

    /** One-line JSON with sorted keys ({@code json.dumps(value, sort_keys=True)}), as in CSV cells. */
    public static String compact(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0, false);
        return out.toString();
    }

    /**
     * Python's default {@code json.dumps(value)} ({@code sortKeys} for {@code sort_keys=True}):
     * {@code ", "} and {@code ": "} separators, non-ASCII characters escaped as {@code \\uXXXX}.
     */
    public static String dumps(Object value, boolean sortKeys) {
        StringBuilder out = new StringBuilder();
        boolean saved = SORT.get();
        SORT.set(sortKeys);
        try {
            write(out, value, 0, false);
        } finally {
            SORT.set(saved);
        }
        StringBuilder ascii = new StringBuilder(out.length());
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (c >= 0x80) {
                ascii.append(String.format("\\u%04x", (int) c));
            } else {
                ascii.append(c);
            }
        }
        return ascii.toString();
    }

    private static final ThreadLocal<Boolean> SORT = ThreadLocal.withInitial(() -> true);

    private static void write(StringBuilder out, Object value, int depth, boolean pretty) {
        switch (value) {
            case null -> out.append("null");
            case String s -> string(out, s);
            case Boolean b -> out.append(b ? "true" : "false");
            case Double d -> out.append(number(d));
            case Float f -> out.append(number(f.doubleValue()));
            case Number n -> out.append(n.longValue());
            case Valued v -> string(out, v.value());
            case Map<?, ?> map -> object(out, map, depth, pretty);
            case Collection<?> items -> array(out, items, depth, pretty);
            default -> string(out, value.toString());
        }
    }

    private static void object(StringBuilder out, Map<?, ?> map, int depth, boolean pretty) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        List<String> keys = new ArrayList<>();
        for (Object k : map.keySet()) {
            keys.add(String.valueOf(k));
        }
        if (SORT.get()) {
            keys.sort(PyJson::compareCodePoints);
        }
        out.append('{');
        boolean first = true;
        for (String k : keys) {
            if (!first) {
                out.append(',');
                if (!pretty) {
                    out.append(' ');
                }
            }
            first = false;
            newline(out, depth + 1, pretty);
            string(out, k);
            out.append(": ");
            write(out, map.get(k), depth + 1, pretty);
        }
        newline(out, depth, pretty);
        out.append('}');
    }

    private static void array(StringBuilder out, Collection<?> items, int depth, boolean pretty) {
        if (items.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append('[');
        boolean first = true;
        for (Object item : items) {
            if (!first) {
                out.append(',');
                if (!pretty) {
                    out.append(' ');
                }
            }
            first = false;
            newline(out, depth + 1, pretty);
            write(out, item, depth + 1, pretty);
        }
        newline(out, depth, pretty);
        out.append(']');
    }

    private static void newline(StringBuilder out, int depth, boolean pretty) {
        if (pretty) {
            out.append('\n').append("  ".repeat(depth));
        }
    }

    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** Python's {@code repr(float)}. */
    public static String repr(double d) {
        return number(d);
    }

    /** Python's {@code repr(float)}: shortest digits, exponent below 1e-4 and from 1e16. */
    static String number(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        if (d == 0) {
            return (1 / d < 0) ? "-0.0" : "0.0";
        }
        BigDecimal bd = new BigDecimal(Double.toString(d)).stripTrailingZeros();
        String digits = bd.unscaledValue().abs().toString();
        int exp = digits.length() - 1 - bd.scale(); // decimal exponent of the first digit
        String sign = d < 0 ? "-" : "";
        if (exp < -4 || exp >= 16) {
            String mant = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            return sign + mant + "e" + (exp < 0 ? "-" : "+") + String.format("%02d", Math.abs(exp));
        }
        if (exp < 0) {
            return sign + "0." + "0".repeat(-exp - 1) + digits;
        }
        if (digits.length() <= exp + 1) {
            return sign + digits + "0".repeat(exp + 1 - digits.length()) + ".0";
        }
        return sign + digits.substring(0, exp + 1) + "." + digits.substring(exp + 1);
    }

    /** Python sorts strings by code point; Java's compareTo by UTF-16 unit. */
    public static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
