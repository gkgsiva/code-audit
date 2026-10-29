package com.legacyaudit.model.facts;

/** Where a file comes from: the scanned code or the supplementary inputs. */
public enum Origin implements Valued {
    SOURCE("source"),
    INPUT("input");

    private final String value;

    Origin(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    /** The constant whose value is {@code value}. */
    public static Origin of(String value) {
        return Valued.of(values(), value, "Origin");
    }
}
