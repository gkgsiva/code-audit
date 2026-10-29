package com.legacyaudit.model.facts;

/** The main kind of work a component does. */
public enum ComponentType implements Valued {
    WEB("web"),
    FRONTEND("frontend"),
    API("api"),
    DESKTOP("desktop"),
    BATCH("batch"),
    CFT("cft"),
    SCRIPT("script"),
    REPORT("report"),
    LIBRARY("library"),
    DB("db"),
    CONFIG("config"),
    ONLINE_MAINFRAME("online-mainframe"),
    UNKNOWN("unknown");

    private final String value;

    ComponentType(String value) {
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
    public static ComponentType of(String value) {
        return Valued.of(values(), value, "ComponentType");
    }
}
