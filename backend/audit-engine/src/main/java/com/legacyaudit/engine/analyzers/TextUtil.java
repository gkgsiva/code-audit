package com.legacyaudit.engine.analyzers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small text helpers shared by analyzers (the Python tool's textutil). */
public final class TextUtil {

    private TextUtil() {}

    /**
     * Lines as an editor numbers them: split on {@code \n}, {@code \r\n} and {@code \r} only (a
     * last empty piece after a final line break is dropped).
     */
    public static List<String> splitLines(String text) {
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(Arrays.asList(t.split("\n", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        return lines;
    }

    /** Python's {@code str.splitlines()}: every Unicode line boundary, no trailing empty line. */
    public static List<String> pySplitlines(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                out.add(text.substring(start, i));
                i += 2;
                start = i;
                continue;
            }
            if (c == '\n' || c == '\r' || c == '\u000b' || c == '\u000c' || c == '\u001c' || c == '\u001d' || c == '\u001e'
                    || c == '\u0085' || c == '\u2028' || c == '\u2029') {
                out.add(text.substring(start, i));
                start = i + 1;
            }
            i++;
        }
        if (start < text.length()) {
            out.add(text.substring(start));
        }
        return out;
    }

    /** Lines of the text, 1-based: {@code text.count("\n") + 1} as the Python analyzers count. */
    public static int lineCount(String text) {
        return count(text, '\n') + 1;
    }

    public static int count(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** Count of {@code \n} before {@code end}, plus one: the line of offset {@code end}. */
    public static int lineAt(String text, int end) {
        int n = 1;
        for (int i = 0; i < end && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /** Maps character offsets to 1-based line numbers. */
    public static final class LineIndex {
        private final int[] starts;

        public LineIndex(String text) {
            List<Integer> s = new ArrayList<>();
            s.add(0);
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    s.add(i + 1);
                }
            }
            starts = s.stream().mapToInt(Integer::intValue).toArray();
        }

        /** bisect_right(starts, offset). */
        public int line(int offset) {
            int lo = 0;
            int hi = starts.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (offset < starts[mid]) {
                    hi = mid;
                } else {
                    lo = mid + 1;
                }
            }
            return lo;
        }
    }

    private static final Pattern SLASHES = Pattern.compile("[\\\\/]");

    /** Last part of a path with / or \ separators (trailing separators ignored). */
    public static String basename(String path) {
        String p = rstrip(path, "/\\");
        String[] parts = SLASHES.split(p, -1);
        return parts[parts.length - 1];
    }

    public static String stem(String path) {
        String b = basename(path);
        return b.length() > 1 && b.indexOf('.', 1) >= 0 ? b.substring(0, b.lastIndexOf('.')) : b;
    }

    // ---- Python str methods -------------------------------------------------------------------

    public static String strip(String s, String chars) {
        return lstrip(rstrip(s, chars), chars);
    }

    public static String lstrip(String s, String chars) {
        int i = 0;
        while (i < s.length() && chars.indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        return s.substring(i);
    }

    public static String rstrip(String s, String chars) {
        int i = s.length();
        while (i > 0 && chars.indexOf(s.charAt(i - 1)) >= 0) {
            i--;
        }
        return s.substring(0, i);
    }

    /** Python's {@code str.strip()} with no argument: Unicode whitespace. */
    public static String strip(String s) {
        return s.strip();
    }

    /** {@code s.rsplit(sep, 1)[-1]}. */
    public static String afterLast(String s, String sep) {
        int i = s.lastIndexOf(sep);
        return i < 0 ? s : s.substring(i + sep.length());
    }

    /** {@code s.rsplit(sep, 1)[0]}. */
    public static String beforeLast(String s, String sep) {
        int i = s.lastIndexOf(sep);
        return i < 0 ? s : s.substring(0, i);
    }

    /** {@code s.split(sep, 1)[0]}. */
    public static String beforeFirst(String s, String sep) {
        int i = s.indexOf(sep);
        return i < 0 ? s : s.substring(0, i);
    }

    /** {@code s.split(sep, 1)[-1]}. */
    public static String afterFirst(String s, String sep) {
        int i = s.indexOf(sep);
        return i < 0 ? s : s.substring(i + sep.length());
    }

    /** Python's {@code str.split()} with no argument. */
    public static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : s.strip().split("\\s+")) {
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return out;
    }

    /** {@code rx.sub(fn, text)} with a function. */
    public static String sub(Pattern rx, String text, java.util.function.Function<Matcher, String> fn) {
        Matcher m = rx.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start()).append(fn.apply(m));
            last = m.end();
        }
        return sb.append(text.substring(last)).toString();
    }

    /** {@code rx.sub(literal, text)} (no group references in the replacement). */
    public static String sub(Pattern rx, String text, String replacement) {
        return rx.matcher(text).replaceAll(Matcher.quoteReplacement(replacement));
    }

    /** Python's truthiness of an optional string. */
    public static boolean truthy(String s) {
        return s != null && !s.isEmpty();
    }

    /** {@code a or b} for strings. */
    /** Python's {@code str(value)} in an f-string: None prints as "None". */
    public static String py(Object value) {
        return value == null ? "None" : value.toString();
    }

    public static String or(String a, String b) {
        return truthy(a) ? a : b;
    }
}
