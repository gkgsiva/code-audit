package com.legacyaudit.model.config;

import com.legacyaudit.model.json.PyJson;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes plain data (maps, lists, text, numbers, booleans, null) as YAML the way PyYAML's
 * {@code safe_dump(data, sort_keys=False, allow_unicode=True, width=...)} does: block style,
 * lists not indented under their key, the same quoting choices and the same line wrapping. Used
 * where the Python tool rewrote a YAML file (audit.yaml edits, audit-overrides.yaml), so the
 * files come out the same.
 */
public final class PyYaml {

    private static final Pattern BOOL = Pattern.compile("^(?:yes|Yes|YES|no|No|NO|true|True|TRUE|false|False|FALSE|on|On|ON|off|Off|OFF)$");
    private static final Pattern FLOAT = Pattern.compile("^(?:[-+]?(?:[0-9][0-9_]*)\\.[0-9_]*(?:[eE][-+][0-9]+)?|\\.[0-9_]+(?:[eE][-+][0-9]+)?"
            + "|[-+]?[0-9][0-9_]*(?::[0-5]?[0-9])+\\.[0-9_]*|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))$");
    private static final Pattern INT = Pattern.compile("^(?:[-+]?0b[0-1_]+|[-+]?0[0-7_]+|[-+]?(?:0|[1-9][0-9_]*)|[-+]?0x[0-9a-fA-F_]+"
            + "|[-+]?[1-9][0-9_]*(?::[0-5]?[0-9])+)$");
    private static final Pattern NULL = Pattern.compile("^(?:~|null|Null|NULL|)$");
    private static final Pattern TIMESTAMP = Pattern.compile("^(?:[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]"
            + "|[0-9][0-9][0-9][0-9]-[0-9][0-9]?-[0-9][0-9]?(?:[Tt]|[ \\t]+)[0-9][0-9]?:[0-9][0-9]:[0-9][0-9](?:\\.[0-9]*)?"
            + "(?:[ \\t]*(?:Z|[-+][0-9][0-9]?(?::[0-9][0-9])?))?)$");
    private static final String BREAKS = "\n\u0085  ";
    private static final String SPACE_OR_BREAK = "\0 \t\r\n\u0085  ";

    private final StringBuilder out = new StringBuilder();
    private final int width;
    private int column;
    private int indent;
    private boolean whitespace = true;
    private boolean indention = true;

    private PyYaml(int width) {
        this.width = width;
    }

    /** The YAML text of {@code data}, ending with a line break. */
    public static String dump(Object data, int width) {
        PyYaml y = new PyYaml(width > 1 ? width : 80);
        y.node(data, 0, false, false);
        y.lineBreak();
        return y.out.toString();
    }

    // ---- structure ----------------------------------------------------------------------------

    /** One node at {@code indent}; {@code inMapping}: the value of a key (lists are not indented there). */
    private void node(Object value, int at, boolean inMapping, boolean inSequence) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            int inner = inMapping || inSequence ? at + 2 : at;
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                indent = inner;
                if (!(first && inSequence)) {
                    writeIndent();
                }
                first = false;
                scalar(String.valueOf(e.getKey()), true, inner);
                indicator(":", false);
                node(e.getValue(), inner, true, false);
            }
        } else if (value instanceof Collection<?> list && !list.isEmpty()) {
            int inner = inMapping ? at : (inSequence ? at + 2 : at);
            for (Object item : list) {
                indent = inner;
                writeIndent();
                indicator("-", true, true);
                node(item, inner, false, true);
            }
        } else if (value instanceof Map<?, ?>) {
            indicator("{}", true);
        } else if (value instanceof Collection<?>) {
            indicator("[]", true);
        } else {
            indent = at + 2;
            scalarValue(value);
        }
    }

    private void scalarValue(Object value) {
        if (value == null) {
            plain("null");
        } else if (value instanceof Boolean b) {
            plain(b ? "true" : "false");
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            String s;
            if (Double.isNaN(d)) {
                s = ".nan";
            } else if (Double.isInfinite(d)) {
                s = d > 0 ? ".inf" : "-.inf";
            } else {
                s = PyJson.repr(d).toLowerCase(java.util.Locale.ROOT);
                if (!s.contains(".") && s.contains("e")) {
                    s = s.replaceFirst("e", ".0e");
                }
            }
            plain(s);
        } else if (value instanceof Number n) {
            plain(n.toString());
        } else {
            scalar(value.toString(), false, indent);
        }
    }

    /** A text scalar in the style PyYAML picks for it. */
    private void scalar(String text, boolean simpleKey, int at) {
        Analysis a = analyze(text);
        boolean implicitStr = !resolvesToOther(text);
        boolean split = !simpleKey;
        if (implicitStr && !(simpleKey && (a.empty || a.multiline)) && a.allowBlockPlain) {
            plain(text, split);
        } else if (a.allowSingleQuoted && !(simpleKey && a.multiline)) {
            singleQuoted(text, split);
        } else {
            doubleQuoted(text, split);
        }
    }

    private static boolean resolvesToOther(String s) {
        return BOOL.matcher(s).matches() || FLOAT.matcher(s).matches() || INT.matcher(s).matches() || NULL.matcher(s).matches()
                || TIMESTAMP.matcher(s).matches() || s.equals("<<") || s.equals("=");
    }

    // ---- analysis (emitter.analyze_scalar) ----------------------------------------------------

    private record Analysis(boolean empty, boolean multiline, boolean allowBlockPlain, boolean allowSingleQuoted) {}

    private static Analysis analyze(String s) {
        if (s.isEmpty()) {
            return new Analysis(true, false, true, true);
        }
        boolean blockIndicators = false;
        boolean lineBreaks = false;
        boolean special = false;
        boolean leadingSpace = false;
        boolean leadingBreak = false;
        boolean trailingSpace = false;
        boolean trailingBreak = false;
        boolean breakSpace = false;
        boolean spaceBreak = false;
        if (s.startsWith("---") || s.startsWith("...")) {
            blockIndicators = true;
        }
        int[] cps = s.codePoints().toArray();
        int n = cps.length;
        boolean precededByWhitespace = true;
        boolean followedByWhitespace = n == 1 || SPACE_OR_BREAK.indexOf(cps[1]) >= 0;
        boolean previousSpace = false;
        boolean previousBreak = false;
        for (int i = 0; i < n; i++) {
            int ch = cps[i];
            if (i == 0) {
                if ("#,[]{}&*!|>'\"%@`".indexOf(ch) >= 0) {
                    blockIndicators = true;
                }
                if ((ch == '?' || ch == ':') && followedByWhitespace) {
                    blockIndicators = true;
                }
                if (ch == '-' && followedByWhitespace) {
                    blockIndicators = true;
                }
            } else {
                if (ch == ':' && followedByWhitespace) {
                    blockIndicators = true;
                }
                if (ch == '#' && precededByWhitespace) {
                    blockIndicators = true;
                }
            }
            if (BREAKS.indexOf(ch) >= 0) {
                lineBreaks = true;
            }
            if (!(ch == '\n' || (ch >= 0x20 && ch <= 0x7E))) {
                boolean printable = (ch == 0x85 || (ch >= 0xA0 && ch <= 0xD7FF) || (ch >= 0xE000 && ch <= 0xFFFD)
                        || (ch >= 0x10000 && ch < 0x10FFFF)) && ch != 0xFEFF;
                if (!printable) {
                    special = true;
                }
            }
            if (ch == ' ') {
                if (i == 0) {
                    leadingSpace = true;
                }
                if (i == n - 1) {
                    trailingSpace = true;
                }
                if (previousBreak) {
                    breakSpace = true;
                }
                previousSpace = true;
                previousBreak = false;
            } else if (BREAKS.indexOf(ch) >= 0) {
                if (i == 0) {
                    leadingBreak = true;
                }
                if (i == n - 1) {
                    trailingBreak = true;
                }
                if (previousSpace) {
                    spaceBreak = true;
                }
                previousSpace = false;
                previousBreak = true;
            } else {
                previousSpace = false;
                previousBreak = false;
            }
            precededByWhitespace = SPACE_OR_BREAK.indexOf(ch) >= 0;
            followedByWhitespace = i + 2 >= n || SPACE_OR_BREAK.indexOf(cps[i + 2]) >= 0;
        }
        boolean blockPlain = true;
        boolean single = true;
        if (leadingSpace || leadingBreak || trailingSpace || trailingBreak) {
            blockPlain = false;
        }
        if (breakSpace) {
            blockPlain = single = false;
        }
        if (spaceBreak || special) {
            blockPlain = single = false;
        }
        if (lineBreaks || blockIndicators) {
            blockPlain = false;
        }
        return new Analysis(false, lineBreaks, blockPlain, single);
    }

    // ---- writers (emitter.write_*) ------------------------------------------------------------

    private void write(String data) {
        out.append(data);
        column += data.codePointCount(0, data.length());
    }

    private void indicator(String indicator, boolean needWhitespace) {
        indicator(indicator, needWhitespace, false);
    }

    private void indicator(String indicator, boolean needWhitespace, boolean indentionAfter) {
        write(whitespace || !needWhitespace ? indicator : " " + indicator);
        whitespace = false;
        indention = indention && indentionAfter;
    }

    private void writeIndent() {
        if (!indention || column > indent || (column == indent && !whitespace)) {
            lineBreak();
        }
        if (column < indent) {
            whitespace = true;
            out.append(" ".repeat(indent - column));
            column = indent;
        }
    }

    private void lineBreak() {
        out.append('\n');
        whitespace = true;
        indention = true;
        column = 0;
    }

    private void lineBreak(int br) {
        out.appendCodePoint(br);
        whitespace = true;
        indention = true;
        column = 0;
    }

    private void plain(String text) {
        plain(text, true);
    }

    private void plain(String text, boolean split) {
        if (text.isEmpty()) {
            return;
        }
        if (!whitespace) {
            write(" ");
        }
        whitespace = false;
        indention = false;
        int[] cps = text.codePoints().toArray();
        boolean spaces = false;
        boolean breaks = false;
        int start = 0;
        for (int end = 0; end <= cps.length; end++) {
            int ch = end < cps.length ? cps[end] : -1;
            if (spaces) {
                if (ch != ' ') {
                    if (start + 1 == end && column > width && split) {
                        writeIndent();
                        whitespace = false;
                        indention = false;
                    } else {
                        write(new String(cps, start, end - start));
                    }
                    start = end;
                }
            } else if (breaks) {
                if (ch < 0 || BREAKS.indexOf(ch) < 0) {
                    if (cps[start] == '\n') {
                        lineBreak();
                    }
                    for (int k = start; k < end; k++) {
                        if (cps[k] == '\n') {
                            lineBreak();
                        } else {
                            lineBreak(cps[k]);
                        }
                    }
                    writeIndent();
                    whitespace = false;
                    indention = false;
                    start = end;
                }
            } else if (ch < 0 || ch == ' ' || BREAKS.indexOf(ch) >= 0) {
                write(new String(cps, start, end - start));
                start = end;
            }
            if (ch >= 0) {
                spaces = ch == ' ';
                breaks = BREAKS.indexOf(ch) >= 0;
            }
        }
    }

    private void singleQuoted(String text, boolean split) {
        indicator("'", true);
        int[] cps = text.codePoints().toArray();
        boolean spaces = false;
        boolean breaks = false;
        int start = 0;
        for (int end = 0; end <= cps.length; end++) {
            int ch = end < cps.length ? cps[end] : -1;
            if (spaces) {
                if (ch != ' ') {
                    if (start + 1 == end && column > width && split && start != 0 && end != cps.length) {
                        writeIndent();
                    } else {
                        write(new String(cps, start, end - start));
                    }
                    start = end;
                }
            } else if (breaks) {
                if (ch < 0 || BREAKS.indexOf(ch) < 0) {
                    if (cps[start] == '\n') {
                        lineBreak();
                    }
                    for (int k = start; k < end; k++) {
                        if (cps[k] == '\n') {
                            lineBreak();
                        } else {
                            lineBreak(cps[k]);
                        }
                    }
                    writeIndent();
                    start = end;
                }
            } else if (ch < 0 || ch == ' ' || BREAKS.indexOf(ch) >= 0 || ch == '\'') {
                if (start < end) {
                    write(new String(cps, start, end - start));
                    start = end;
                }
            }
            if (ch == '\'') {
                write("''");
                start = end + 1;
            }
            if (ch >= 0) {
                spaces = ch == ' ';
                breaks = BREAKS.indexOf(ch) >= 0;
            }
        }
        indicator("'", false);
    }

    private static String escape(int ch) {
        return switch (ch) {
            case 0 -> "0";
            case 0x07 -> "a";
            case 0x08 -> "b";
            case 0x09 -> "t";
            case 0x0A -> "n";
            case 0x0B -> "v";
            case 0x0C -> "f";
            case 0x0D -> "r";
            case 0x1B -> "e";
            case '"' -> "\"";
            case '\\' -> "\\";
            case 0x85 -> "N";
            case 0xA0 -> "_";
            case 0x2028 -> "L";
            case 0x2029 -> "P";
            default -> null;
        };
    }

    private void doubleQuoted(String text, boolean split) {
        indicator("\"", true);
        int[] cps = text.codePoints().toArray();
        int start = 0;
        for (int end = 0; end <= cps.length; end++) {
            int ch = end < cps.length ? cps[end] : -1;
            if (ch < 0 || "\"\\\u0085  ﻿".indexOf(ch) >= 0
                    || !((ch >= 0x20 && ch <= 0x7E) || (ch >= 0xA0 && ch <= 0xD7FF) || (ch >= 0xE000 && ch <= 0xFFFD))) {
                if (start < end) {
                    write(new String(cps, start, end - start));
                    start = end;
                }
                if (ch >= 0) {
                    String e = escape(ch);
                    String data;
                    if (e != null) {
                        data = "\\" + e;
                    } else if (ch <= 0xFF) {
                        data = String.format("\\x%02X", ch);
                    } else if (ch <= 0xFFFF) {
                        data = String.format("\\u%04X", ch);
                    } else {
                        data = String.format("\\U%08X", ch);
                    }
                    write(data);
                    start = end + 1;
                }
            }
            if (end > 0 && end < cps.length - 1 && (ch == ' ' || start >= end) && column + (end - start) > width && split) {
                String data = new String(cps, start, Math.max(0, end - start)) + "\\";
                if (start < end) {
                    start = end;
                }
                write(data);
                writeIndent();
                whitespace = false;
                indention = false;
                if (cps[start] == ' ') {
                    write("\\");
                }
            }
        }
        indicator("\"", false);
    }
}
