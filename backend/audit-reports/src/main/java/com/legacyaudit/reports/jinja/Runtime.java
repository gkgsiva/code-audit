package com.legacyaudit.reports.jinja;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Attribute and item access, slicing and calls, as Jinja's environment does them. */
public final class Runtime {

    private Runtime() {}

    /** Something a template can call: a macro, a global function or a bound method. */
    @FunctionalInterface
    public interface Callable {
        Object call(List<Object> args, Map<String, Object> kwargs);
    }

    /** An imported template ({@code import "x" as m}): its macros and top-level names. */
    record Module(Map<String, Object> names) {}

    private static final List<String> DICT_METHODS = List.of("items", "keys", "values", "get");

    /** {@code obj.attr}: an attribute (dict methods first, as Python's getattr), then the item. */
    static Object getAttr(Object obj, String attr) {
        if (obj == Values.UNDEFINED || obj == null) {
            return Values.UNDEFINED;
        }
        if (obj instanceof Module m) {
            return m.names().getOrDefault(attr, Values.UNDEFINED);
        }
        if (obj instanceof Map<?, ?> map) {
            if (DICT_METHODS.contains(attr)) {
                return dictMethod(map, attr);
            }
            return map.containsKey(attr) ? map.get(attr) : Values.UNDEFINED;
        }
        if (obj instanceof Loop loop) {
            return loop.attr(attr);
        }
        if (obj instanceof String || obj instanceof Markup) {
            Callable m = stringMethod(Values.str(obj), attr);
            return m == null ? Values.UNDEFINED : m;
        }
        if (obj instanceof Record) {
            return recordAttr(obj, attr);
        }
        return Values.UNDEFINED;
    }

    /** A record's component or other public no-argument method, as a Python object's attribute. */
    private static Object recordAttr(Object obj, String attr) {
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod(attr);
            if (m.getParameterCount() != 0 || java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                return Values.UNDEFINED;
            }
            return m.invoke(obj);
        } catch (NoSuchMethodException e) {
            return Values.UNDEFINED;
        } catch (ReflectiveOperationException e) {
            throw new TemplateError("cannot read " + attr + ": " + e);
        }
    }

    /** {@code obj[key]}: the item first, then an attribute of that name. */
    static Object getItem(Object obj, Object key) {
        if (obj == Values.UNDEFINED || obj == null) {
            return Values.UNDEFINED;
        }
        if (obj instanceof Map<?, ?> map) {
            Object k = Values.findKey(map, key);
            if (k != Values.UNDEFINED) {
                return map.get(k);
            }
            return key instanceof String s ? getAttr(obj, s) : Values.UNDEFINED;
        }
        if (obj instanceof Markup m) {
            obj = m.html();
        }
        if (obj instanceof List<?> l && (key instanceof Long || key instanceof Integer || key instanceof Boolean)) {
            long i = Values.asLong(key);
            if (i < 0) {
                i += l.size();
            }
            return i >= 0 && i < l.size() ? l.get((int) i) : Values.UNDEFINED;
        }
        if (obj instanceof String s && (key instanceof Long || key instanceof Integer)) {
            int[] cps = s.codePoints().toArray();
            long i = Values.asLong(key);
            if (i < 0) {
                i += cps.length;
            }
            return i >= 0 && i < cps.length ? new String(Character.toChars(cps[(int) i])) : Values.UNDEFINED;
        }
        if (obj instanceof Loop || obj instanceof Module) {
            return key instanceof String s ? getAttr(obj, s) : Values.UNDEFINED;
        }
        return key instanceof String s ? getAttr(obj, s) : Values.UNDEFINED;
    }

    static Object slice(Object obj, Object a, Object b, Object c) {
        if (obj == Values.UNDEFINED) {
            return Values.UNDEFINED;
        }
        boolean isString = obj instanceof String || obj instanceof Markup;
        List<Object> items = Values.iterate(obj);
        int n = items.size();
        long step = c == null ? 1 : Values.asLong(c);
        List<Object> out = new ArrayList<>();
        if (step > 0) {
            long start = a == null ? 0 : clamp(Values.asLong(a), n, 0);
            long stop = b == null ? n : clamp(Values.asLong(b), n, 0);
            for (long i = start; i < stop; i += step) {
                out.add(items.get((int) i));
            }
        } else {
            long start = a == null ? n - 1 : clamp(Values.asLong(a), n, -1);
            long stop = b == null ? -1 : clamp(Values.asLong(b), n, -1);
            for (long i = start; i > stop; i += step) {
                out.add(items.get((int) i));
            }
        }
        if (isString) {
            StringBuilder sb = new StringBuilder();
            out.forEach(x -> sb.append(x));
            return obj instanceof Markup ? new Markup(sb.toString()) : sb.toString();
        }
        return obj instanceof Values.Tuple ? new Values.Tuple(out) : out;
    }

    private static long clamp(long i, int n, int low) {
        if (i < 0) {
            i += n;
        }
        if (i < low) {
            return low;
        }
        return Math.min(i, low < 0 ? n - 1 : n);
    }

    static Object call(Object fn, List<Object> args, Map<String, Object> kwargs) {
        if (fn instanceof Callable c) {
            return c.call(args, kwargs);
        }
        throw new TemplateError("'" + Values.typeName(fn) + "' object is not callable");
    }

    private static Callable dictMethod(Map<?, ?> map, String name) {
        return switch (name) {
            case "items" -> (args, kw) -> {
                List<Object> out = new ArrayList<>();
                map.forEach((k, v) -> out.add(new Values.Tuple(java.util.Arrays.asList(k, v))));
                return out;
            };
            case "keys" -> (args, kw) -> new ArrayList<>(map.keySet());
            case "values" -> (args, kw) -> new ArrayList<>(map.values());
            default -> (args, kw) -> { // get
                Object k = Values.findKey(map, args.get(0));
                return k != Values.UNDEFINED ? map.get(k) : args.size() > 1 ? args.get(1) : null;
            };
        };
    }

    private static Callable stringMethod(String s, String name) {
        return switch (name) {
            case "startswith" -> (a, k) -> s.startsWith(Values.str(a.get(0)));
            case "endswith" -> (a, k) -> s.endsWith(Values.str(a.get(0)));
            case "upper" -> (a, k) -> s.toUpperCase(Locale.ROOT);
            case "lower" -> (a, k) -> s.toLowerCase(Locale.ROOT);
            case "strip" -> (a, k) -> s.strip();
            case "title" -> (a, k) -> Filters.title(s);
            case "capitalize" -> (a, k) -> Filters.capitalize(s);
            case "replace" -> (a, k) -> s.replace(Values.str(a.get(0)), Values.str(a.get(1)));
            case "split" -> (a, k) -> {
                List<Object> out = new ArrayList<>();
                if (a.isEmpty() || a.get(0) == null) {
                    for (String part : s.strip().split("\\s+")) {
                        if (!part.isEmpty()) {
                            out.add(part);
                        }
                    }
                } else {
                    String sep = Values.str(a.get(0));
                    int max = a.size() > 1 ? (int) Values.asLong(a.get(1)) : -1;
                    int from = 0;
                    while (max != 0) {
                        int i = s.indexOf(sep, from);
                        if (i < 0) {
                            break;
                        }
                        out.add(s.substring(from, i));
                        from = i + sep.length();
                        max--;
                    }
                    out.add(s.substring(from));
                }
                return out;
            };
            case "rsplit" -> (a, k) -> {
                String sep = Values.str(a.get(0));
                int max = a.size() > 1 ? (int) Values.asLong(a.get(1)) : -1;
                List<Object> out = new ArrayList<>();
                int to = s.length();
                while (max != 0) {
                    int i = s.lastIndexOf(sep, to - sep.length());
                    if (i < 0 || to - sep.length() < 0) {
                        break;
                    }
                    out.add(0, s.substring(i + sep.length(), to));
                    to = i;
                    max--;
                }
                out.add(0, s.substring(0, to));
                return out;
            };
            case "format" -> (a, k) -> Filters.strFormat(s, a, k);
            case "join" -> (a, k) -> {
                List<String> parts = new ArrayList<>();
                Values.iterate(a.get(0)).forEach(x -> parts.add(Values.str(x)));
                return String.join(s, parts);
            };
            default -> null;
        };
    }
}
