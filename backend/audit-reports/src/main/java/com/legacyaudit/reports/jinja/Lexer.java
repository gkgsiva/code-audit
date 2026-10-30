package com.legacyaudit.reports.jinja;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a template into text, {@code {{ }}} and {@code {% %}} parts with Jinja's whitespace
 * rules as the Python reports used them: {@code -} strips whitespace on that side,
 * {@code trim_blocks} drops the newline after a block tag, {@code lstrip_blocks} drops the spaces
 * before a block tag that starts a line. Comments {@code {# #}} are dropped.
 */
final class Lexer {

    enum Kind { TEXT, VAR, BLOCK }

    record Part(Kind kind, String text, int line) {}

    private Lexer() {}

    static List<Part> split(String src, String name) {
        List<Part> out = new ArrayList<>();
        int pos = 0;
        int n = src.length();
        boolean stripNext = false;   // a "-" on the previous tag's end
        boolean trimNewline = false; // trim_blocks after the previous block or comment tag
        while (pos <= n) {
            int open = nextOpen(src, pos);
            String text = open < 0 ? src.substring(pos) : src.substring(pos, open);
            if (stripNext) {
                text = lstripAll(text);
            } else if (trimNewline) {
                if (text.startsWith("\r\n")) {
                    text = text.substring(2);
                } else if (text.startsWith("\n")) {
                    text = text.substring(1);
                }
            }
            stripNext = false;
            trimNewline = false;
            if (open < 0) {
                if (!text.isEmpty()) {
                    out.add(new Part(Kind.TEXT, text, line(src, pos)));
                }
                break;
            }
            char type = src.charAt(open + 1);
            boolean minusLeft = open + 2 < n && src.charAt(open + 2) == '-';
            boolean plusLeft = open + 2 < n && src.charAt(open + 2) == '+';
            if (minusLeft) {
                text = rstripAll(text);
            } else if ((type == '%' || type == '#') && !plusLeft) {
                text = lstripBlock(src, pos, open, text);
            }
            if (!text.isEmpty()) {
                out.add(new Part(Kind.TEXT, text, line(src, pos)));
            }
            String close = type == '{' ? "}}" : type == '%' ? "%}" : "#}";
            int bodyStart = open + 2 + (minusLeft || plusLeft ? 1 : 0);
            int end = type == '#' ? src.indexOf(close, bodyStart) : findClose(src, bodyStart, close);
            if (end < 0) {
                throw new TemplateError(name + ":" + line(src, open) + ": unclosed tag");
            }
            boolean minusRight = end > bodyStart && src.charAt(end - 1) == '-';
            String body = src.substring(bodyStart, minusRight ? end - 1 : end).strip();
            if (type == '{') {
                out.add(new Part(Kind.VAR, body, line(src, open)));
            } else if (type == '%') {
                out.add(new Part(Kind.BLOCK, body, line(src, open)));
            }
            pos = end + 2;
            stripNext = minusRight;
            trimNewline = !minusRight && type != '{';
            if (pos > n) {
                break;
            }
        }
        return out;
    }

    private static int nextOpen(String s, int from) {
        int i = from;
        while (true) {
            int k = s.indexOf('{', i);
            if (k < 0 || k + 1 >= s.length()) {
                return -1;
            }
            char c = s.charAt(k + 1);
            if (c == '{' || c == '%' || c == '#') {
                return k;
            }
            i = k + 1;
        }
    }

    /** The close delimiter outside string literals. */
    private static int findClose(String s, int from, String close) {
        char quote = 0;
        for (int i = from; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == close.charAt(0) && s.charAt(i + 1) == close.charAt(1)) {
                return i;
            } else if (c == '-' && i + 2 < s.length() && s.charAt(i + 1) == close.charAt(0) && s.charAt(i + 2) == close.charAt(1)) {
                return i + 1;
            }
        }
        return -1;
    }

    private static String lstripAll(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    private static String rstripAll(String s) {
        int i = s.length();
        while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) {
            i--;
        }
        return s.substring(0, i);
    }

    /** lstrip_blocks: spaces and tabs between the line start and the tag go. */
    private static String lstripBlock(String src, int pos, int open, String text) {
        int j = open;
        while (j > pos && (src.charAt(j - 1) == ' ' || src.charAt(j - 1) == '\t')) {
            j--;
        }
        boolean lineStart = j == 0 || src.charAt(j - 1) == '\n';
        if (!lineStart) {
            return text;
        }
        int cut = Math.min(open - j, text.length());
        return text.substring(0, text.length() - cut);
    }

    private static int line(String s, int pos) {
        int line = 1;
        for (int i = 0; i < pos && i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
