package com.legacyaudit.model.facts;

/** Whether a supplementary input was provided. */
public enum CoverageStatus implements Valued {
    PRESENT("present"),
    MISSING("missing");

    private final String value;

    CoverageStatus(String value) {
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
    public static CoverageStatus of(String value) {
        return Valued.of(values(), value, "CoverageStatus");
    }
}
