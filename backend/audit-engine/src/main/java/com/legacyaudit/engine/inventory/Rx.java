package com.legacyaudit.engine.inventory;

import java.util.regex.Pattern;

/**
 * Regular expressions that behave as Python's {@code re} does on text: {@code \w}, {@code \s},
 * {@code \d} and {@code \b} are Unicode-aware, case-insensitive matching is Unicode, and only
 * {@code \n} ends a line for {@code ^}, {@code $} and {@code .}.
 */
public final class Rx {

    public static final int I = Pattern.CASE_INSENSITIVE;
    public static final int M = Pattern.MULTILINE;

    private Rx() {}

    public static Pattern of(String regex) {
        return of(regex, 0);
    }

    public static Pattern of(String regex, int flags) {
        return Pattern.compile(regex, flags | Pattern.UNIX_LINES | Pattern.UNICODE_CHARACTER_CLASS);
    }

    /** The first {@code n} characters (code points, as Python slices). */
    public static String head(String s, int n) {
        if (s.length() <= n) {
            return s;
        }
        int count = s.codePointCount(0, s.length());
        return count <= n ? s : s.substring(0, s.offsetByCodePoints(0, n));
    }
}
