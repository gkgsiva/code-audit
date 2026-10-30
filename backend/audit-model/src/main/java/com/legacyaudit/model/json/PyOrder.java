package com.legacyaudit.model.json;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Python's ordering of sort keys, for {@code sorted(items, key=lambda x: (a, b, c))}: a key is a
 * list compared item by item; strings compare by code point, numbers by value, {@code false}
 * before {@code true}, lists item by item (a shorter prefix first).
 */
public final class PyOrder {

    private PyOrder() {}

    @SuppressWarnings("unchecked")
    public static int compare(Object a, Object b) {
        if (a instanceof String x && b instanceof String y) {
            return PyJson.compareCodePoints(x, y);
        }
        if (a instanceof Boolean x && b instanceof Boolean y) {
            return Boolean.compare(x, y);
        }
        if (a instanceof Number x && b instanceof Number y) {
            if (x instanceof Double || x instanceof Float || y instanceof Double || y instanceof Float) {
                return Double.compare(x.doubleValue(), y.doubleValue());
            }
            return Long.compare(x.longValue(), y.longValue());
        }
        if (a instanceof Boolean x && b instanceof Number y) {
            return Long.compare(x ? 1 : 0, y.longValue());
        }
        if (a instanceof Number x && b instanceof Boolean y) {
            return Long.compare(x.longValue(), y ? 1 : 0);
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            for (int i = 0; i < Math.min(x.size(), y.size()); i++) {
                int c = compare(x.get(i), y.get(i));
                if (c != 0) {
                    return c;
                }
            }
            return Integer.compare(x.size(), y.size());
        }
        throw new IllegalArgumentException("cannot order " + a + " and " + b);
    }

    /** A comparator over the key {@code key} gives for each item (usually a list). */
    public static <T> Comparator<T> by(Function<T, Object> key) {
        return (p, q) -> compare(key.apply(p), key.apply(q));
    }
}
