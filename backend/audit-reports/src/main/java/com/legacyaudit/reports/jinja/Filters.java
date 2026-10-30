package com.legacyaudit.reports.jinja;

import com.legacyaudit.engine.rules.Py;
import com.legacyaudit.model.json.PyJson;
import com.legacyaudit.model.json.PyNum;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Jinja's built-in filters and tests, as the report templates use them (autoescape on). */
final class Filters {

    private Filters() {}

    private static Object arg(List<Object> pos, Map<String, Object> kw, int i, String name, Object dflt) {
        if (kw.containsKey(name)) {
            return kw.get(name);
        }
        return i < pos.size() ? pos.get(i) : dflt;
    }

    /** Jinja's attribute getter for filters ({@code attribute="a.b"}): item access per dotted part. */
    static Object attrOf(Object item, Object attribute) {
        if (attribute == null) {
            return item;
        }
        Object v = item;
        for (String part : Values.str(attribute).split("\\.")) {
            v = part.matches("\\d+") ? Runtime.getItem(v, Long.parseLong(part)) : Runtime.getItem(v, part);
        }
        return v;
    }

    private static Object sortKey(Object v, boolean caseSensitive) {
        if (!caseSensitive && (v instanceof String || v instanceof Markup)) {
            return Values.str(v).toLowerCase(Locale.ROOT);
        }
        return v;
    }

    static Object apply(String name, Object value, List<Object> pos, Map<String, Object> kw, Scope scope) {
        switch (name) {
            case "join": {
                String sep = Values.str(arg(pos, kw, 0, "d", ""));
                Object attribute = arg(pos, kw, 1, "attribute", null);
                List<Object> items = new ArrayList<>();
                for (Object x : Values.iterate(value)) {
                    items.add(attrOf(x, attribute));
                }
                boolean markup = items.stream().anyMatch(x -> x instanceof Markup) || arg(pos, kw, 0, "d", "") instanceof Markup;
                List<String> parts = new ArrayList<>();
                for (Object x : items) {
                    parts.add(markup ? Expr.esc(x) : Values.str(x));
                }
                return markup ? new Markup(String.join(markup ? Expr.esc(arg(pos, kw, 0, "d", "")) : sep, parts)) : String.join(sep, parts);
            }
            case "length", "count":
                return Values.len(value);
            case "list":
                return Values.iterate(value);
            case "round": {
                long precision = Values.asLong(arg(pos, kw, 0, "precision", 0L));
                String method = Values.str(arg(pos, kw, 1, "method", "common"));
                if (!(value instanceof Number) && !(value instanceof Boolean)) {
                    throw new TemplateError("round of " + Values.typeName(value));
                }
                double d = Values.asDouble(value);
                if (method.equals("ceil") || method.equals("floor")) {
                    double f = Math.pow(10, precision);
                    return (method.equals("ceil") ? Math.ceil(d * f) : Math.floor(d * f)) / f;
                }
                if (value instanceof Long || value instanceof Integer) {
                    return value;
                }
                return PyNum.round(d, (int) precision);
            }
            case "replace": {
                Object old = pos.get(0);
                Object rep = pos.get(1);
                if (value instanceof Markup m) {
                    return new Markup(m.html().replace(Expr.esc(old), Expr.esc(rep)));
                }
                if (old instanceof Markup || rep instanceof Markup) {
                    return new Markup(Markup.escape(Values.str(value)).replace(Expr.esc(old), Expr.esc(rep)));
                }
                return Values.str(value).replace(Values.str(old), Values.str(rep));
            }
            case "int": {
                Object dflt = arg(pos, kw, 0, "default", 0L);
                if (value instanceof Boolean b) {
                    return b ? 1L : 0L;
                }
                if (value instanceof Long || value instanceof Integer) {
                    return Values.asLong(value);
                }
                if (value instanceof Number n) {
                    return (long) n.doubleValue();
                }
                try {
                    return Long.parseLong(Values.str(value).strip());
                } catch (NumberFormatException e) {
                    try {
                        return (long) Double.parseDouble(Values.str(value).strip());
                    } catch (NumberFormatException e2) {
                        return dflt;
                    }
                }
            }
            case "float": {
                if (value instanceof Number || value instanceof Boolean) {
                    return Values.asDouble(value);
                }
                try {
                    return Double.parseDouble(Values.str(value).strip());
                } catch (NumberFormatException e) {
                    return arg(pos, kw, 0, "default", 0.0);
                }
            }
            case "format":
                return Printf.format(Values.str(value), pos);
            case "tojson": {
                String json = PyJson.dumps(plain(value), true).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
                        .replace("'", "\\u0027");
                return new Markup(json);
            }
            case "sort": {
                boolean reverse = Values.truthy(arg(pos, kw, 0, "reverse", false));
                boolean cs = Values.truthy(arg(pos, kw, 1, "case_sensitive", false));
                Object attribute = arg(pos, kw, 2, "attribute", null);
                List<Object> items = Values.iterate(value);
                // Python's sorted(reverse=True) keeps equal items in their order: sort descending, stably
                items.sort((a, b) -> {
                    int c = Values.compare(sortKey(attrOf(a, attribute), cs), sortKey(attrOf(b, attribute), cs));
                    return reverse ? -c : c;
                });
                return items;
            }
            case "selectattr", "rejectattr": {
                boolean select = name.equals("selectattr");
                Object attribute = pos.get(0);
                List<Object> out = new ArrayList<>();
                for (Object x : Values.iterate(value)) {
                    Object v = attrOf(x, attribute);
                    boolean ok = pos.size() < 2 ? Values.truthy(v) : test(Values.str(pos.get(1)), v, pos.subList(2, pos.size()));
                    if (ok == select) {
                        out.add(x);
                    }
                }
                return out;
            }
            case "select", "reject": {
                boolean select = name.equals("select");
                List<Object> out = new ArrayList<>();
                for (Object x : Values.iterate(value)) {
                    boolean ok = pos.isEmpty() ? Values.truthy(x) : test(Values.str(pos.get(0)), x, pos.subList(1, pos.size()));
                    if (ok == select) {
                        out.add(x);
                    }
                }
                return out;
            }
            case "map": {
                List<Object> out = new ArrayList<>();
                if (kw.containsKey("attribute")) {
                    Object dflt = kw.getOrDefault("default", Values.UNDEFINED);
                    for (Object x : Values.iterate(value)) {
                        Object v = attrOf(x, kw.get("attribute"));
                        out.add(Values.undefined(v) ? dflt : v);
                    }
                } else {
                    String f = Values.str(pos.get(0));
                    List<Object> rest = pos.subList(1, pos.size());
                    for (Object x : Values.iterate(value)) {
                        out.add(apply(f, x, rest, Map.of(), scope));
                    }
                }
                return out;
            }
            case "max", "min": {
                boolean cs = Values.truthy(arg(pos, kw, 0, "case_sensitive", false));
                Object attribute = arg(pos, kw, 1, "attribute", null);
                Object best = Values.UNDEFINED;
                for (Object x : Values.iterate(value)) {
                    if (best == Values.UNDEFINED) {
                        best = x;
                        continue;
                    }
                    int c = Values.compare(sortKey(attrOf(x, attribute), cs), sortKey(attrOf(best, attribute), cs));
                    if (name.equals("max") ? c > 0 : c < 0) {
                        best = x;
                    }
                }
                return best;
            }
            case "sum": {
                Object attribute = arg(pos, kw, 0, "attribute", null);
                Object total = arg(pos, kw, 1, "start", 0L);
                for (Object x : Values.iterate(value)) {
                    total = Values.arith("+", total, attrOf(x, attribute));
                }
                return total;
            }
            case "default", "d": {
                Object dflt = arg(pos, kw, 0, "default_value", "");
                boolean bool = Values.truthy(arg(pos, kw, 1, "boolean", false));
                return Values.undefined(value) || bool && !Values.truthy(value) ? dflt : value;
            }
            case "capitalize":
                return capitalize(Values.str(value));
            case "lower":
                return Values.str(value).toLowerCase(Locale.ROOT);
            case "upper":
                return Values.str(value).toUpperCase(Locale.ROOT);
            case "title":
                return title(Values.str(value));
            case "trim":
                return Values.str(value).strip();
            case "string":
                return value instanceof Markup ? value : Values.str(value);
            case "e", "escape":
                return value instanceof Markup ? value : new Markup(Markup.escape(Values.str(value)));
            case "safe":
                return value instanceof Markup ? value : new Markup(Values.str(value));
            case "first": {
                List<Object> items = Values.iterate(value);
                return items.isEmpty() ? Values.UNDEFINED : items.get(0);
            }
            case "last": {
                List<Object> items = Values.iterate(value);
                return items.isEmpty() ? Values.UNDEFINED : items.get(items.size() - 1);
            }
            case "abs":
                return value instanceof Double d ? (Object) Math.abs(d) : (Object) Math.abs(Values.asLong(value));
            case "unique": {
                List<Object> out = new ArrayList<>();
                for (Object x : Values.iterate(value)) {
                    if (out.stream().noneMatch(y -> Values.eq(sortKey(x, false), sortKey(y, false)))) {
                        out.add(x);
                    }
                }
                return out;
            }
            case "items": {
                if (value == Values.UNDEFINED) {
                    return List.of();
                }
                return Runtime.call(Runtime.getAttr(value, "items"), List.of(), Map.of());
            }
            case "dictsort": {
                List<Object> items = Values.iterate(Runtime.call(Runtime.getAttr(value, "items"), List.of(), Map.of()));
                items.sort((a, b) -> Values.compare(sortKey(((List<?>) a).get(0), false), sortKey(((List<?>) b).get(0), false)));
                return items;
            }
            case "batch": {
                long size = Values.asLong(pos.get(0));
                List<Object> out = new ArrayList<>();
                List<Object> cur = new ArrayList<>();
                for (Object x : Values.iterate(value)) {
                    cur.add(x);
                    if (cur.size() == size) {
                        out.add(cur);
                        cur = new ArrayList<>();
                    }
                }
                if (!cur.isEmpty()) {
                    if (pos.size() > 1) {
                        while (cur.size() < size) {
                            cur.add(pos.get(1));
                        }
                    }
                    out.add(cur);
                }
                return out;
            }
            default: {
                Object f = scope.env.filter(name);
                if (f instanceof Runtime.Callable c) {
                    List<Object> all = new ArrayList<>();
                    all.add(value);
                    all.addAll(pos);
                    return c.call(all, kw);
                }
                throw new TemplateError("no filter named '" + name + "'");
            }
        }
    }

    /** Template values as plain JSON values (Markup as its text). */
    static Object plain(Object v) {
        if (v instanceof Markup m) {
            return m.html();
        }
        if (v == Values.UNDEFINED) {
            return null;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(x -> out.add(plain(x)));
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            m.forEach((k, x) -> out.put(Values.str(k), plain(x)));
            return out;
        }
        return v;
    }

    static boolean test(String name, Object v, List<Object> args) {
        return switch (name) {
            case "defined" -> !Values.undefined(v);
            case "undefined" -> Values.undefined(v);
            case "none" -> v == null;
            case "number" -> v instanceof Number || v instanceof Boolean;
            case "integer" -> v instanceof Long || v instanceof Integer;
            case "float" -> v instanceof Double || v instanceof Float;
            case "boolean" -> v instanceof Boolean;
            case "true" -> Boolean.TRUE.equals(v);
            case "false" -> Boolean.FALSE.equals(v);
            case "string" -> v instanceof String || v instanceof Markup;
            case "mapping" -> v instanceof Map;
            case "sequence", "iterable" -> v instanceof List || v instanceof Map || v instanceof String || v instanceof Markup;
            case "equalto", "eq", "==", "sameas" -> Values.eq(v, args.get(0));
            case "ne", "!=" -> !Values.eq(v, args.get(0));
            case "lt", "lessthan", "<" -> Values.compare(v, args.get(0)) < 0;
            case "le", "<=" -> Values.compare(v, args.get(0)) <= 0;
            case "gt", "greaterthan", ">" -> Values.compare(v, args.get(0)) > 0;
            case "ge", ">=" -> Values.compare(v, args.get(0)) >= 0;
            case "in" -> Values.in(v, args.get(0));
            case "odd" -> Values.asLong(v) % 2 != 0;
            case "even" -> Values.asLong(v) % 2 == 0;
            case "divisibleby" -> Values.asLong(v) % Values.asLong(args.get(0)) == 0;
            case "lower" -> Values.str(v).equals(Values.str(v).toLowerCase(Locale.ROOT));
            case "upper" -> Values.str(v).equals(Values.str(v).toUpperCase(Locale.ROOT));
            default -> throw new TemplateError("no test named '" + name + "'");
        };
    }

    static String capitalize(String s) {
        if (s.isEmpty()) {
            return s;
        }
        int first = s.codePointAt(0);
        int len = Character.charCount(first);
        return new String(Character.toChars(Character.toTitleCase(first))) + s.substring(len).toLowerCase(Locale.ROOT);
    }

    /** Jinja's title: each word's first letter upper, the rest lower. */
    static String title(String s) {
        StringBuilder out = new StringBuilder();
        boolean start = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '\'') {
                out.append(start ? Character.toTitleCase(c) : Character.toLowerCase(c));
                start = false;
            } else {
                out.append(c);
                start = c == ' ' || c == '-' || c == '(' || c == '[' || c == '{' || c == '<' || c == '_' || Character.isWhitespace(c);
            }
        }
        return out.toString();
    }

    /** {@code "...".format(args)} for plain {@code {}}, {@code {0}} and {@code {name}} fields with simple specs. */
    static String strFormat(String fmt, List<Object> args, Map<String, Object> kw) {
        StringBuilder out = new StringBuilder();
        int auto = 0;
        for (int i = 0; i < fmt.length(); i++) {
            char c = fmt.charAt(i);
            if (c == '{' && i + 1 < fmt.length() && fmt.charAt(i + 1) == '{') {
                out.append('{');
                i++;
            } else if (c == '}' && i + 1 < fmt.length() && fmt.charAt(i + 1) == '}') {
                out.append('}');
                i++;
            } else if (c == '{') {
                int end = fmt.indexOf('}', i);
                String field = fmt.substring(i + 1, end);
                String spec = "";
                int colon = field.indexOf(':');
                if (colon >= 0) {
                    spec = field.substring(colon + 1);
                    field = field.substring(0, colon);
                }
                Object v = field.isEmpty() ? args.get(auto++) : field.matches("\\d+") ? args.get(Integer.parseInt(field)) : kw.get(field);
                out.append(spec.isEmpty() ? Values.str(v) : Py.format(v, spec));
                i = end;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
