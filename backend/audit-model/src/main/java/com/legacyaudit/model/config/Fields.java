package com.legacyaudit.model.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads one mapping of a YAML file key by key, with defaults, and collects every problem with
 * its location ({@code inventory.workers: ...}, {@code unknown key 'inventory.foo'}) instead of
 * stopping at the first. Values are coerced as the Python tool's pydantic models did: numbers
 * and {@code yes}/{@code no} given as text are accepted, a number is not a text.
 *
 * <p>What was read, defaults included, is kept in {@link #dump()}, which the config hash is
 * computed from.
 */
public final class Fields {

    private final Map<?, ?> map;
    private final String at;
    private final List<String> errors;
    private final Set<String> known = new LinkedHashSet<>();
    private final Map<String, Object> dump = new LinkedHashMap<>();

    private Fields(Map<?, ?> map, String at, List<String> errors) {
        this.map = map;
        this.at = at;
        this.errors = errors;
    }

    /** The top-level mapping. */
    public static Fields root(Map<?, ?> map, List<String> errors) {
        return new Fields(map, "", errors);
    }

    public Map<String, Object> dump() {
        return dump;
    }

    public List<String> errors() {
        return errors;
    }

    public boolean has(String key) {
        return map.containsKey(key);
    }

    /** The value as written, or null. */
    public Object raw(String key) {
        known.add(key);
        return map.get(key);
    }

    private String loc(String key) {
        return at.isEmpty() ? key : at + "." + key;
    }

    /** Records a problem with {@code key}. */
    public void error(String key, String message) {
        errors.add("  - " + loc(key) + ": " + message);
    }

    public String str(String key, String otherwise) {
        Object v = raw(key);
        String out = otherwise;
        if (v != null) {
            if (v instanceof String s) {
                out = s;
            } else {
                error(key, "Input should be a valid string");
            }
        } else if (map.containsKey(key) && otherwise != null) {
            error(key, "Input should be a valid string");
        }
        dump.put(key, out);
        return out;
    }

    public String required(String key) {
        if (!map.containsKey(key)) {
            known.add(key);
            errors.add("  - missing required key '" + loc(key) + "'");
            return "";
        }
        String out = str(key, "");
        return out == null ? "" : out;
    }

    /** One of {@code choices}. */
    public String choice(String key, String otherwise, List<String> choices) {
        String out = str(key, otherwise);
        if (out != null && !choices.contains(out)) {
            error(key, "Input should be " + String.join(", ", choices.stream().map(c -> "'" + c + "'").toList()));
            out = otherwise;
            dump.put(key, out);
        }
        return out;
    }

    public boolean bool(String key, boolean otherwise) {
        Object v = raw(key);
        boolean out = otherwise;
        if (v instanceof Boolean b) {
            out = b;
        } else if (v instanceof Number n && (n.intValue() == 0 || n.intValue() == 1) && n.doubleValue() == n.intValue()) {
            out = n.intValue() == 1;
        } else if (v instanceof String s) {
            switch (s.strip().toLowerCase(Locale.ROOT)) {
                case "true", "yes", "on", "y", "t", "1" -> out = true;
                case "false", "no", "off", "n", "f", "0" -> out = false;
                default -> error(key, "Input should be a valid boolean");
            }
        } else if (v != null || map.containsKey(key)) {
            error(key, "Input should be a valid boolean");
        }
        dump.put(key, out);
        return out;
    }

    public int integer(String key, int otherwise, int min, int max) {
        Object v = raw(key);
        int out = otherwise;
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            out = n.intValue();
        } else if (v instanceof String s && s.strip().matches("[+-]?\\d+")) {
            out = Integer.parseInt(s.strip());
        } else if (v != null || map.containsKey(key)) {
            error(key, "Input should be a valid integer");
        }
        if (out < min) {
            error(key, "Input should be greater than or equal to " + min);
            out = otherwise;
        } else if (out > max) {
            error(key, "Input should be less than or equal to " + max);
            out = otherwise;
        }
        dump.put(key, out);
        return out;
    }

    public double number(String key, double otherwise) {
        Object v = raw(key);
        double out = otherwise;
        if (v instanceof Number n) {
            out = n.doubleValue();
        } else if (v instanceof String s) {
            try {
                out = Double.parseDouble(s.strip());
            } catch (NumberFormatException e) {
                error(key, "Input should be a valid number");
            }
        } else if (v != null || map.containsKey(key)) {
            error(key, "Input should be a valid number");
        }
        dump.put(key, out);
        return out;
    }

    /** An optional number: null when missing or written as null. */
    public Double optionalNumber(String key, Double otherwise) {
        if (map.containsKey(key) && map.get(key) == null) {
            known.add(key);
            dump.put(key, null);
            return null;
        }
        return number(key, otherwise == null ? 0 : otherwise);
    }

    public List<String> strings(String key, List<String> otherwise) {
        Object v = raw(key);
        List<String> out = new ArrayList<>(otherwise);
        if (v instanceof List<?> items) {
            out.clear();
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i) instanceof String s) {
                    out.add(s);
                } else {
                    error(key + "." + i, "Input should be a valid string");
                }
            }
        } else if (v != null || map.containsKey(key)) {
            error(key, "Input should be a valid list");
        }
        dump.put(key, out);
        return out;
    }

    /** A mapping of text to text; {@code otherwise} holds the defaults a partial mapping keeps. */
    public Map<String, String> stringMap(String key, Map<String, String> otherwise) {
        Object v = raw(key);
        Map<String, String> out = new LinkedHashMap<>(otherwise);
        if (v instanceof Map<?, ?> m) {
            m.forEach((k, val) -> {
                if (val instanceof String s) {
                    out.put(String.valueOf(k), s);
                } else {
                    error(key + "." + k, "Input should be a valid string");
                }
            });
        } else if (v != null || map.containsKey(key)) {
            error(key, "Input should be a valid dictionary");
        }
        dump.put(key, out);
        return out;
    }

    /** A mapping of text to numbers, merged over {@code defaults}. */
    public Map<String, Double> numberMap(String key, Map<String, Double> defaults) {
        Object v = raw(key);
        Map<String, Double> out = new LinkedHashMap<>(defaults);
        if (v instanceof Map<?, ?> m) {
            m.forEach((k, val) -> {
                if (val instanceof Number n) {
                    out.put(String.valueOf(k), n.doubleValue());
                } else {
                    error(key + "." + k, "Input should be a valid number");
                }
            });
        } else if (v != null || map.containsKey(key)) {
            error(key, "Input should be a valid dictionary");
        }
        dump.put(key, out);
        return out;
    }

    /** A nested mapping; missing means all its defaults. */
    public Fields section(String key) {
        Object v = raw(key);
        Map<?, ?> m = Map.of();
        if (v instanceof Map<?, ?> given) {
            m = given;
        } else if (v != null || map.containsKey(key)) {
            error(key, "Input should be a valid dictionary");
        }
        Fields child = new Fields(m, loc(key), errors);
        dump.put(key, child.dump);
        return child;
    }

    /** A list of mappings. */
    public List<Fields> sections(String key) {
        Object v = raw(key);
        List<Fields> out = new ArrayList<>();
        List<Object> dumps = new ArrayList<>();
        if (v instanceof List<?> items) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i) instanceof Map<?, ?> m) {
                    Fields child = new Fields(m, loc(key) + "." + i, errors);
                    out.add(child);
                    dumps.add(child.dump);
                } else {
                    error(key + "." + i, "Input should be a valid dictionary");
                }
            }
        } else if (v != null || map.containsKey(key)) {
            error(key, "Input should be a valid list");
        }
        dump.put(key, dumps);
        return out;
    }

    /** Reports every key that was never asked for. Call once all keys are read. */
    public void finish() {
        for (Object k : map.keySet()) {
            if (!known.contains(String.valueOf(k))) {
                errors.add("  - unknown key '" + loc(String.valueOf(k)) + "'");
            }
        }
    }

    /** Keeps a mapping that is not read in detail yet (a section a later phase uses). */
    public void keep(String key, Object value) {
        known.add(key);
        dump.put(key, value);
    }
}
