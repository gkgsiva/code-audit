package com.legacyaudit.model.facts;

/** How serious a finding is. */
public enum Severity implements Valued {
    INFO("info"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    CRITICAL("critical");

    private final String value;

    Severity(String value) {
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
    public static Severity of(String value) {
        return Valued.of(values(), value, "Severity");
    }
}
