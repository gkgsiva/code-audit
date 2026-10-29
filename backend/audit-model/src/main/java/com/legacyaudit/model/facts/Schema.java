package com.legacyaudit.model.facts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The fields of one kind of fact, in the order the JSON files and CSV columns list them. */
public final class Schema {

    /** How a field's value is kept and written. */
    public enum Type {
        STR, INT, FLOAT, BOOL, LIST, MAP, EVIDENCE, DOC, DOCS
    }

    /**
     * One field.
     *
     * @param required the Python model had no default
     * @param defaultValue the default for scalars; lists and maps start empty
     * @param nested the schema of a DOC or DOCS field
     */
    public record Field(String name, Type type, boolean required, Object defaultValue, Schema nested) {}

    private final String name;
    private final Map<String, Field> fields = new LinkedHashMap<>();

    public Schema(String name, Field... fields) {
        this.name = name;
        for (Field f : fields) {
            this.fields.put(f.name(), f);
        }
    }

    public static Field field(String name, Type type, boolean required, Object defaultValue, Schema nested) {
        return new Field(name, type, required, defaultValue, nested);
    }

    public String name() {
        return name;
    }

    public Field field(String name) {
        Field f = fields.get(name);
        if (f == null) {
            throw new IllegalArgumentException(this.name + " has no field '" + name + "'");
        }
        return f;
    }

    public boolean has(String name) {
        return fields.containsKey(name);
    }

    public List<Field> fields() {
        return List.copyOf(fields.values());
    }
}
