package com.legacyaudit.engine.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Code / comment / blank line counts per language comment syntax.
 *
 * <p>A line holding code and a comment counts as code. Blank lines inside block comments count as
 * blank. Comment markers inside string literals are not recognised (a known approximation; it can
 * only move a line from comment to code, never lose it).
 *
 * @param code lines with code
 * @param comment lines with only comments (documentation lines count here)
 * @param blank empty lines
 */
public record Loc(int code, int comment, int blank) {

    public static final Loc NONE = new Loc(0, 0, 0);

    private record Syntax(List<String> line, List<String[]> block) {}

    private static final String[] C_BLOCK = {"/*", "*/"};
    private static final String[] XML_BLOCK = {"<!--", "-->"};
    private static final Syntax C_LIKE = new Syntax(List.of("//"), List.<String[]>of(C_BLOCK));
    private static final Syntax HASH = new Syntax(List.of("#"), List.of());
    private static final Syntax XML = new Syntax(List.of(), List.<String[]>of(XML_BLOCK));
    private static final Syntax NO_SYNTAX = new Syntax(List.of(), List.of());

    private static final Map<String, Syntax> SYNTAX = Map.ofEntries(
            Map.entry("java", C_LIKE),
            Map.entry("c", C_LIKE),
            Map.entry("cpp", C_LIKE),
            Map.entry("proc", C_LIKE),
            Map.entry("javascript", C_LIKE),
            Map.entry("typescript", C_LIKE),
            Map.entry("groovy", C_LIKE),
            Map.entry("css", new Syntax(List.of(), List.<String[]>of(C_BLOCK))),
            Map.entry("sql", new Syntax(List.of("--"), List.<String[]>of(C_BLOCK))),
            Map.entry("plsql", new Syntax(List.of("--"), List.<String[]>of(C_BLOCK))),
            Map.entry("shell", HASH),
            Map.entry("perl", HASH),
            Map.entry("python", HASH),
            Map.entry("make", HASH),
            Map.entry("yaml", HASH),
            Map.entry("crontab", HASH),
            Map.entry("dockerfile", HASH),
            Map.entry("awk", HASH),
            Map.entry("sed", HASH),
            Map.entry("tcl", HASH),
            Map.entry("ruby", HASH),
            Map.entry("powershell", new Syntax(List.of("#"), List.<String[]>of(new String[] {"<#", "#>"}))),
            Map.entry("vbscript", new Syntax(List.of("'"), List.of())),
            Map.entry("sqlloader", new Syntax(List.of("--"), List.of())),
            Map.entry("kotlin", C_LIKE),
            Map.entry("scala", C_LIKE),
            Map.entry("sqlj", C_LIKE),
            Map.entry("csharp", C_LIKE),
            Map.entry("go", C_LIKE),
            Map.entry("php", new Syntax(List.of("//", "#"), List.<String[]>of(C_BLOCK))),
            Map.entry("properties", new Syntax(List.of("#", "!"), List.of())),
            Map.entry("ini", new Syntax(List.of("#", ";"), List.of())),
            Map.entry("jil", new Syntax(List.of("#"), List.<String[]>of(C_BLOCK))),
            Map.entry("xml", XML),
            Map.entry("html", XML),
            Map.entry("msbuild", XML),
            Map.entry("xaml", XML),
            Map.entry("fxml", XML),
            Map.entry("aspx", new Syntax(List.of(), List.of(new String[] {"<%--", "--%>"}, XML_BLOCK))),
            Map.entry("aspnet", new Syntax(List.of(), List.<String[]>of(new String[] {"<%--", "--%>"}))),
            Map.entry("razor", new Syntax(List.of(), List.of(new String[] {"@*", "*@"}, XML_BLOCK))),
            Map.entry("jsp", new Syntax(List.of(), List.of(new String[] {"<%--", "--%>"}, XML_BLOCK))),
            Map.entry("velocity", new Syntax(List.of("##"), List.of(new String[] {"#*", "*#"}, XML_BLOCK))),
            Map.entry("freemarker", new Syntax(List.of(), List.of(new String[] {"<#--", "-->"}, XML_BLOCK))));

    private static final Pattern FREE_FORMAT = Rx.of(">>\\s*SOURCE\\s+FORMAT\\s+(IS\\s+)?FREE", Rx.I);

    /** Lines as an editor numbers them: split on {@code \n}, {@code \r\n} and {@code \r} only. */
    public static List<String> splitLines(String text) {
        String t = Encodings.newlines(text);
        List<String> lines = new ArrayList<>(List.of(t.split("\n", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        return lines;
    }

    public static Loc count(String text, String language) {
        if (Languages.noLoc(language)) {
            return NONE; // data files and backup copies are listed, not measured
        }
        List<String> lines = splitLines(text);
        return switch (language) {
            case "cobol", "procobol" -> cobol(lines);
            case "jcl" -> byLine(lines, raw -> starts(raw, "//*"));
            case "batch" -> byLine(lines, Loc::batch);
            case "mqsc", "bms" -> byLine(lines, raw -> starts(raw, "*"));
            case "vb6", "vbnet" -> byLine(lines, Loc::basic);
            // Documentation: every non-blank line is prose, counted as comment.
            case "markdown", "text" -> byLine(lines, raw -> strip(raw).isEmpty() ? "blank" : "comment");
            default -> generic(lines, SYNTAX.getOrDefault(language, NO_SYNTAX));
        };
    }

    private static Loc generic(List<String> lines, Syntax syn) {
        int code = 0;
        int comments = 0;
        int blank = 0;
        String inBlock = null;
        for (String raw : lines) {
            String s = strip(raw);
            if (s.isEmpty()) {
                blank++;
                continue;
            }
            boolean isCode = false;
            boolean comment = false;
            int i = 0;
            int n = s.length();
            while (i < n) {
                if (inBlock != null) {
                    comment = true;
                    int end = s.indexOf(inBlock, i);
                    if (end < 0) {
                        break;
                    }
                    i = end + inBlock.length();
                    inBlock = null;
                    continue;
                }
                // Nearest comment start from position i; on a tie the longer marker wins, then the
                // marker first in alphabetical order (as Python's min over tuples).
                int best = -1;
                String marker = null;
                String endMark = null;
                for (String prefix : syn.line()) {
                    int j = s.indexOf(prefix, i);
                    if (j >= 0 && better(j, prefix, "", best, marker, endMark)) {
                        best = j;
                        marker = prefix;
                        endMark = "";
                    }
                }
                for (String[] b : syn.block()) {
                    int j = s.indexOf(b[0], i);
                    if (j >= 0 && better(j, b[0], b[1], best, marker, endMark)) {
                        best = j;
                        marker = b[0];
                        endMark = b[1];
                    }
                }
                if (marker == null) {
                    isCode = isCode || !strip(s.substring(i)).isEmpty();
                    break;
                }
                if (!strip(s.substring(i, best)).isEmpty()) {
                    isCode = true;
                }
                comment = true;
                if (endMark.isEmpty()) {
                    break;
                }
                inBlock = endMark;
                i = best + marker.length();
            }
            if (isCode) {
                code++;
            } else if (comment) {
                comments++;
            } else {
                blank++;
            }
        }
        return new Loc(code, comments, blank);
    }

    /** Python compared (position, -length, marker, end) tuples and took the smallest. */
    private static boolean better(int j, String marker, String end, int best, String bestMarker, String bestEnd) {
        if (bestMarker == null) {
            return true;
        }
        if (j != best) {
            return j < best;
        }
        if (marker.length() != bestMarker.length()) {
            return marker.length() > bestMarker.length();
        }
        int c = marker.compareTo(bestMarker);
        return c != 0 ? c < 0 : end.compareTo(bestEnd) < 0;
    }

    private static Loc byLine(List<String> lines, Function<String, String> kindOf) {
        int code = 0;
        int comment = 0;
        int blank = 0;
        for (String raw : lines) {
            switch (kindOf.apply(raw)) {
                case "code" -> code++;
                case "comment" -> comment++;
                default -> blank++;
            }
        }
        return new Loc(code, comment, blank);
    }

    private static String starts(String raw, String commentMark) {
        String s = strip(raw);
        if (s.isEmpty()) {
            return "blank";
        }
        return s.startsWith(commentMark) ? "comment" : "code";
    }

    private static Loc cobol(List<String> lines) {
        boolean free = false;
        for (String line : lines.subList(0, Math.min(20, lines.size()))) {
            free |= FREE_FORMAT.matcher(line).find();
        }
        final boolean freeFormat = free;
        return byLine(lines, raw -> {
            String line = rstripNewlines(raw);
            if (!freeFormat) {
                int[] cps = line.codePoints().toArray();
                if (cps.length > 6 && (cps[6] == '*' || cps[6] == '/')) {
                    return "comment";
                }
                line = new String(cps, Math.min(7, cps.length), Math.max(0, Math.min(72, cps.length) - Math.min(7, cps.length)));
            }
            String s = strip(line);
            if (s.isEmpty()) {
                return "blank";
            }
            return s.startsWith("*>") ? "comment" : "code";
        });
    }

    private static String batch(String raw) {
        String s = strip(raw);
        if (s.isEmpty()) {
            return "blank";
        }
        String low = s.toLowerCase(java.util.Locale.ROOT);
        if (low.startsWith("rem ") || low.startsWith("@rem ") || low.startsWith("::") || low.equals("rem") || low.equals("@rem")) {
            return "comment";
        }
        return "code";
    }

    /**
     * Visual Basic 6 and VB.NET: a line starting with {@code '} or {@code REM} is a comment (a
     * {@code '} after code leaves the line code).
     */
    private static String basic(String raw) {
        String s = strip(raw);
        if (s.isEmpty()) {
            return "blank";
        }
        String low = s.toLowerCase(java.util.Locale.ROOT);
        return s.startsWith("'") || low.equals("rem") || low.startsWith("rem ") ? "comment" : "code";
    }

    private static String rstripNewlines(String s) {
        int b = s.length();
        while (b > 0 && (s.charAt(b - 1) == '\r' || s.charAt(b - 1) == '\n')) {
            b--;
        }
        return s.substring(0, b);
    }

    /** Python's {@code str.strip()}: removes Unicode whitespace, including the separators 0x1c-0x1f and 0x85. */
    public static String strip(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && isSpace(s.charAt(a))) {
            a++;
        }
        while (b > a && isSpace(s.charAt(b - 1))) {
            b--;
        }
        return s.substring(a, b);
    }

    static boolean isSpace(char c) {
        return (c >= 0x09 && c <= 0x0D) || (c >= 0x1C && c <= 0x20) || c == 0x85 || c == 0xA0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000;
    }
}
