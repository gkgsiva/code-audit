package com.legacyaudit.engine.analyzers;

import java.util.ArrayList;
import java.util.List;

/**
 * Python's {@code shlex.split(text, comments=False, posix=True)}: words split on blanks, quotes
 * removed, a backslash escapes the next character (inside double quotes only {@code \\} and
 * {@code \"}). An unclosed quote or a trailing backslash raises {@link PyError} ValueError, as
 * Python does.
 */
public final class PyShlex {

    private PyShlex() {}

    private static final String WHITESPACE = " \t\r\n";

    public static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        int[] pos = {0};
        while (true) {
            String token = next(text, pos);
            if (token == null) {
                return out;
            }
            out.add(token);
        }
    }

    /** One token, or null at the end (the posix branch of {@code shlex.read_token}). */
    private static String next(String text, int[] pos) {
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        char state = ' ';
        char escapedState = ' ';
        while (true) {
            boolean eof = pos[0] >= text.length();
            char c = eof ? 0 : text.charAt(pos[0]++);
            if (state == ' ') {
                if (eof) {
                    break;
                } else if (WHITESPACE.indexOf(c) >= 0) {
                    continue;
                } else if (c == '\\') {
                    escapedState = 'a';
                    state = '\\';
                } else if (c == '\'' || c == '"') {
                    state = c;
                } else {
                    token.append(c);
                    state = 'a';
                }
            } else if (state == '\'' || state == '"') {
                quoted = true;
                if (eof) {
                    throw new PyError("ValueError", "No closing quotation");
                }
                if (c == state) {
                    state = 'a';
                } else if (c == '\\' && state == '"') {
                    escapedState = state;
                    state = '\\';
                } else {
                    token.append(c);
                }
            } else if (state == '\\') {
                if (eof) {
                    throw new PyError("ValueError", "No escaped character");
                }
                if ((escapedState == '\'' || escapedState == '"') && c != '\\' && c != escapedState) {
                    token.append('\\');
                }
                token.append(c);
                state = escapedState;
            } else { // 'a': inside a word
                if (eof) {
                    break;
                } else if (WHITESPACE.indexOf(c) >= 0) {
                    break;
                } else if (c == '\'' || c == '"') {
                    state = c;
                } else if (c == '\\') {
                    escapedState = 'a';
                    state = '\\';
                } else {
                    token.append(c);
                }
            }
        }
        if (!quoted && token.isEmpty()) {
            return null;
        }
        return token.toString();
    }
}
