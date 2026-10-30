package com.legacyaudit.reports.jinja;

import com.legacyaudit.engine.rules.Py;
import com.legacyaudit.model.json.PyJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Python value semantics for template data: str(), truth, ==, ordering and arithmetic. */
public final class Values {

    private Values() {}

    /** Jinja's {@code ChainableUndefined}: a missing name, attribute or key; empty and false. */
    public static final Object UNDEFINED = new Object() {
        @Override
        public String toString() {
            return "";
        }
    };

    public static boolean undefined(Object v) {
        return v == UNDEFINED;
    }

    /** {@code str(value)}. */
    public static String str(Object v) {
        if (v == UNDEFINED) {
            return "";
        }
        if (v instanceof Markup m) {
            return m.html();
        }
        if (v instanceof List<?> l) {
            List<Object> plain = new ArrayList<>();
            l.forEach(x -> plain.add(x instanceof Markup m ? m.html() : x));
            return Py.repr(plain, v instanceof Tuple);
        }
        return Py.str(v);
    }

    public static boolean truthy(Object v) {
        if (v == UNDEFINED) {
            return false;
        }
        if (v instanceof Markup m) {
            return !m.html().isEmpty();
        }
        return Py.truthy(v);
    }

    /** A tuple (from {@code a, b} or {@code dict.items()}): a list that prints with parentheses. */
    public static final class Tuple extends ArrayList<Object> {
        public Tuple(List<?> items) {
            super(items);
        }
    }

    private static boolean isNum(Object v) {
        return v instanceof Number || v instanceof Boolean;
    }

    private static boolean isInt(Object v) {
        return v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte || v instanceof Boolean;
    }

    static long asLong(Object v) {
        return v instanceof Boolean b ? (b ? 1 : 0) : ((Number) v).longValue();
    }

    static double asDouble(Object v) {
        return v instanceof Boolean b ? (b ? 1 : 0) : ((Number) v).doubleValue();
    }

    /** Python {@code ==}. */
    public static boolean eq(Object a, Object b) {
        if (a == UNDEFINED || b == UNDEFINED) {
            return a == b;
        }
        if (a instanceof Markup m) {
            a = m.html();
        }
        if (b instanceof Markup m) {
            b = m.html();
        }
        if (a == null || b == null) {
            return a == b;
        }
        if (isNum(a) && isNum(b)) {
            return isInt(a) && isInt(b) ? asLong(a) == asLong(b) : asDouble(a) == asDouble(b);
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!eq(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (Map.Entry<?, ?> e : x.entrySet()) {
                Object k = findKey(y, e.getKey());
                if (k == UNDEFINED || !eq(e.getValue(), y.get(k))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    /** The key of the map equal to {@code key} in Python terms (1 == 1.0, True == 1), or UNDEFINED. */
    static Object findKey(Map<?, ?> m, Object key) {
        if (key instanceof Markup mk) {
            key = mk.html();
        }
        if (m.containsKey(key)) {
            return key;
        }
        if (isNum(key)) {
            for (Object k : m.keySet()) {
                if (isNum(k) && eq(k, key)) {
                    return k;
                }
            }
        }
        return UNDEFINED;
    }

    /** Python ordering of two values ({@code <}). */
    public static int compare(Object a, Object b) {
        if (a instanceof Markup m) {
            a = m.html();
        }
        if (b instanceof Markup m) {
            b = m.html();
        }
        if (isNum(a) && isNum(b)) {
            return isInt(a) && isInt(b) ? Long.compare(asLong(a), asLong(b)) : Double.compare(asDouble(a), asDouble(b));
        }
        if (a instanceof String x && b instanceof String y) {
            return PyJson.compareCodePoints(x, y);
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
        throw new TemplateError("'<' not supported between " + typeName(a) + " and " + typeName(b));
    }

    static String typeName(Object v) {
        if (v == null) {
            return "NoneType";
        }
        if (v == UNDEFINED) {
            return "Undefined";
        }
        if (v instanceof String || v instanceof Markup) {
            return "str";
        }
        if (v instanceof Boolean) {
            return "bool";
        }
        if (isInt(v)) {
            return "int";
        }
        if (v instanceof Number) {
            return "float";
        }
        if (v instanceof List) {
            return "list";
        }
        if (v instanceof Map) {
            return "dict";
        }
        return v.getClass().getSimpleName();
    }

    /** Python {@code in}. */
    public static boolean in(Object item, Object container) {
        if (container == UNDEFINED || container == null) {
            return false;
        }
        if (container instanceof Markup m) {
            container = m.html();
        }
        if (container instanceof String s) {
            return s.contains(str(item));
        }
        if (container instanceof Map<?, ?> m) {
            return findKey(m, item) != UNDEFINED;
        }
        if (container instanceof List<?> l) {
            for (Object x : l) {
                if (eq(x, item)) {
                    return true;
                }
            }
            return false;
        }
        throw new TemplateError("argument of type '" + typeName(container) + "' is not iterable");
    }

    /** Python {@code len()}. */
    public static long len(Object v) {
        if (v == UNDEFINED) {
            return 0;
        }
        if (v instanceof Markup m) {
            v = m.html();
        }
        if (v instanceof String s) {
            return s.codePointCount(0, s.length());
        }
        if (v instanceof List<?> l) {
            return l.size();
        }
        if (v instanceof Map<?, ?> m) {
            return m.size();
        }
        throw new TemplateError("object of type '" + typeName(v) + "' has no len()");
    }

    /** Iterating a value: a list's items, a map's keys, a string's characters; undefined is empty. */
    public static List<Object> iterate(Object v) {
        if (v == UNDEFINED) {
            return List.of();
        }
        if (v instanceof List<?> l) {
            return new ArrayList<>(l);
        }
        if (v instanceof Map<?, ?> m) {
            return new ArrayList<>(m.keySet());
        }
        if (v instanceof Markup mk) {
            v = mk.html();
        }
        if (v instanceof String s) {
            List<Object> out = new ArrayList<>();
            s.codePoints().forEach(cp -> out.add(new String(Character.toChars(cp))));
            return out;
        }
        throw new TemplateError("'" + typeName(v) + "' object is not iterable");
    }

    /** Python arithmetic. */
    public static Object arith(String op, Object a, Object b) {
        if (a == UNDEFINED || b == UNDEFINED) {
            throw new TemplateError("undefined value in '" + op + "'");
        }
        if (a instanceof Markup m) {
            a = m.html();
        }
        if (b instanceof Markup m) {
            b = m.html();
        }
        if (isNum(a) && isNum(b)) {
            boolean ints = isInt(a) && isInt(b);
            switch (op) {
                case "+":
                    return ints ? (Object) (asLong(a) + asLong(b)) : asDouble(a) + asDouble(b);
                case "-":
                    return ints ? (Object) (asLong(a) - asLong(b)) : asDouble(a) - asDouble(b);
                case "*":
                    return ints ? (Object) (asLong(a) * asLong(b)) : asDouble(a) * asDouble(b);
                case "/":
                    if (asDouble(b) == 0) {
                        throw new TemplateError("division by zero");
                    }
                    return asDouble(a) / asDouble(b);
                case "//":
                    if (ints) {
                        return Math.floorDiv(asLong(a), asLong(b));
                    }
                    return Math.floor(asDouble(a) / asDouble(b));
                case "%":
                    if (ints) {
                        return Math.floorMod(asLong(a), asLong(b));
                    }
                    double x = asDouble(a);
                    double y = asDouble(b);
                    return x - Math.floor(x / y) * y;
                case "**":
                    if (ints && asLong(b) >= 0) {
                        return (long) Math.pow(asLong(a), asLong(b));
                    }
                    return Math.pow(asDouble(a), asDouble(b));
                default:
                    break;
            }
        }
        if (op.equals("+") && a instanceof String x && b instanceof String y) {
            return x + y;
        }
        if (op.equals("+") && a instanceof List<?> x && b instanceof List<?> y) {
            List<Object> out = new ArrayList<>(x);
            out.addAll(y);
            return out;
        }
        if (op.equals("*") && a instanceof String x && isInt(b)) {
            return x.repeat((int) Math.max(0, asLong(b)));
        }
        if (op.equals("%") && a instanceof String x) {
            return Printf.format(x, b instanceof Tuple t ? t : List.of(b));
        }
        throw new TemplateError("unsupported operand types for " + op + ": '" + typeName(a) + "' and '" + typeName(b) + "'");
    }
}
