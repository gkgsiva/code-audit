package com.legacyaudit.model.facts;

/** An enum written as its lower-case value in the facts ({@code "source"}, {@code "online-mainframe"}). */
public interface Valued {

    String value();

    static <E extends Enum<E> & Valued> E of(E[] values, String value, String what) {
        for (E e : values) {
            if (e.value().equals(value)) {
                return e;
            }
        }
        throw new IllegalArgumentException("'" + value + "' is not a " + what);
    }
}
