package com.legacyaudit.reports;

import com.legacyaudit.model.json.PyJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** Reading run files into maps and lists, and writing JSON as Python's {@code json.dumps} does. */
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build();

    private Json() {}

    /** {@code json.loads(path.read_text())}, or null when the file is missing. */
    public static Object load(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code json.loads(text)}; throws {@link IllegalArgumentException} on bad JSON. */
    public static Object parse(String text) {
        try {
            return MAPPER.readValue(text, Object.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * {@code json.dumps(value, ensure_ascii=..., sort_keys=..., separators=(item, key))}.
     */
    public static String dumps(Object value, boolean ensureAscii, boolean sortKeys, String itemSep, String keySep) {
        StringBuilder out = new StringBuilder();
        write(out, value, sortKeys, itemSep, keySep);
        if (!ensureAscii) {
            return out.toString();
        }
        StringBuilder ascii = new StringBuilder(out.length());
        for (int k = 0; k < out.length(); k++) {
            char c = out.charAt(k);
            if (c >= 0x80) {
                ascii.append(String.format("\\u%04x", (int) c));
            } else {
                ascii.append(c);
            }
        }
        return ascii.toString();
    }

    /** Pretty JSON as the run files are written ({@code indent=2, sort_keys=True}). */
    public static String pretty(Object value) {
        return PyJson.pretty(value);
    }

    private static void write(StringBuilder out, Object value, boolean sort, String itemSep, String keySep) {
        switch (value) {
            case null -> out.append("null");
            case String s -> string(out, s);
            case Boolean b -> out.append(b ? "true" : "false");
            case Double d -> out.append(number(d));
            case Float f -> out.append(number(f.doubleValue()));
            case Number n -> out.append(n.toString());
            case Map<?, ?> map -> {
                out.append('{');
                List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
                if (sort) {
                    entries.sort((a, b) -> PyJson.compareCodePoints(String.valueOf(a.getKey()), String.valueOf(b.getKey())));
                }
                boolean first = true;
                for (Map.Entry<?, ?> e : entries) {
                    if (!first) {
                        out.append(itemSep);
                    }
                    first = false;
                    string(out, key(e.getKey()));
                    out.append(keySep);
                    write(out, e.getValue(), sort, itemSep, keySep);
                }
                out.append('}');
            }
            case Collection<?> items -> {
                out.append('[');
                boolean first = true;
                for (Object o : items) {
                    if (!first) {
                        out.append(itemSep);
                    }
                    first = false;
                    write(out, o, sort, itemSep, keySep);
                }
                out.append(']');
            }
            case Record r -> string(out, r.toString());
            default -> string(out, value.toString());
        }
    }

    private static String key(Object k) {
        if (k instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (k == null) {
            return "null";
        }
        if (k instanceof Double d) {
            return number(d);
        }
        return k.toString();
    }

    private static String number(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        return PyJson.repr(d);
    }

    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
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
}
