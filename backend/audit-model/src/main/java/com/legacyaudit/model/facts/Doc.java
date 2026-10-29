package com.legacyaudit.model.facts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A fact (or a part of one) kept by field name, checked against its {@link Schema}: an unknown
 * field is an error, as in the Python models. Numbers are kept as {@code Long} or {@code Double}
 * by the field's type, so the JSON is written the same way the Python tool writes it.
 */
public final class Doc implements Fact {

    private final Schema schema;
    private final Map<String, Object> values = new LinkedHashMap<>();

    public Doc(Schema schema) {
        this.schema = schema;
    }

    public static Doc of(Schema schema, Object... keyValues) {
        Doc d = new Doc(schema);
        for (int i = 0; i < keyValues.length; i += 2) {
            d.set((String) keyValues[i], keyValues[i + 1]);
        }
        return d;
    }

    public Schema schema() {
        return schema;
    }

    public Doc set(String field, Object value) {
        values.put(field, coerce(schema.field(field), value));
        return this;
    }

    /** The value, or the field's default (a fresh, kept list or map for collections). */
    public Object get(String field) {
        Schema.Field f = schema.field(field);
        if (values.containsKey(field)) {
            return values.get(field);
        }
        return switch (f.type()) {
            case LIST, EVIDENCE, DOCS -> {
                List<Object> l = new ArrayList<>();
                values.put(field, l);
                yield l;
            }
            case MAP -> {
                Map<String, Object> m = new LinkedHashMap<>();
                values.put(field, m);
                yield m;
            }
            default -> f.defaultValue();
        };
    }

    public String str(String field) {
        Object v = get(field);
        return v == null ? null : v.toString();
    }

    public Integer integer(String field) {
        Object v = get(field);
        return v instanceof Number n ? n.intValue() : null;
    }

    public boolean bool(String field) {
        return Boolean.TRUE.equals(get(field));
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> list(String field) {
        return (List<T>) get(field);
    }

    @SuppressWarnings("unchecked")
    public <V> Map<String, V> map(String field) {
        return (Map<String, V>) get(field);
    }

    @Override
    public String id() {
        return schema.has("id") ? str("id") : null;
    }

    /** A copy with the same values (lists and maps copied one level deep). */
    public Doc copy() {
        Doc d = new Doc(schema);
        values.forEach((k, v) -> d.values.put(k, v instanceof List<?> l ? new ArrayList<>(l)
                : v instanceof Map<?, ?> m ? new LinkedHashMap<>(m) : v));
        return d;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Schema.Field f : schema.fields()) {
            out.put(f.name(), plain(get(f.name())));
        }
        return out;
    }

    /** Reads a parsed JSON object back (unknown keys are an error). */
    @SuppressWarnings("unchecked")
    public static Doc fromMap(Schema schema, Map<String, Object> map) {
        Doc d = new Doc(schema);
        map.forEach((k, v) -> {
            Schema.Field f = schema.field(k);
            Object value = switch (f.type()) {
                case EVIDENCE -> v == null ? null : new ArrayList<>(Evidence.fromMaps(v));
                case DOC -> v == null ? null : fromMap(f.nested(), (Map<String, Object>) v);
                case DOCS -> v == null ? null : new ArrayList<>(((List<Object>) v).stream()
                        .map(o -> (Object) fromMap(f.nested(), (Map<String, Object>) o)).toList());
                case LIST -> v == null ? null : new ArrayList<>((List<Object>) v);
                case MAP -> v == null ? null : new LinkedHashMap<>((Map<String, Object>) v);
                default -> v;
            };
            d.values.put(k, coerce(f, value));
        });
        return d;
    }

    static Object plain(Object v) {
        if (v instanceof Doc d) {
            return d.toMap();
        }
        if (v instanceof Evidence e) {
            return e.toMap();
        }
        if (v instanceof Valued e) {
            return e.value();
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(plain(o));
            }
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), plain(x)));
            return out;
        }
        if (v instanceof Integer i) {
            return i.longValue();
        }
        return v;
    }

    private static Object coerce(Schema.Field f, Object v) {
        if (v == null) {
            return null;
        }
        return switch (f.type()) {
            case INT -> v instanceof Number n ? (Object) n.longValue() : v;
            case FLOAT -> v instanceof Number n ? (Object) n.doubleValue() : v;
            case STR -> v instanceof Valued e ? e.value() : v;
            default -> v;
        };
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Doc d && d.schema == schema && Objects.equals(d.toMap(), toMap());
    }

    @Override
    public int hashCode() {
        return toMap().hashCode();
    }

    @Override
    public String toString() {
        return schema.name() + toMap();
    }
}
