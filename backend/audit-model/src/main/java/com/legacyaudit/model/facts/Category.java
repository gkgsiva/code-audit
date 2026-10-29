package com.legacyaudit.model.facts;

/** What a file is for, from its language and place. */
public enum Category implements Valued {
    SOURCE("source"),
    CONFIG("config"),
    BUILD("build"),
    SQL("sql"),
    SCREEN("screen"),
    JOB("job"),
    SCRIPT("script"),
    BINARY("binary"),
    TEST("test"),
    GENERATED("generated"),
    DOC("doc"),
    OTHER("other");

    private final String value;

    Category(String value) {
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
    public static Category of(String value) {
        return Valued.of(values(), value, "Category");
    }
}
