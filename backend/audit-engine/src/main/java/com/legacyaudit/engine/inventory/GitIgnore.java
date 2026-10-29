package com.legacyaudit.engine.inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Patterns in {@code .gitignore} syntax, matched as Git does (a port of pathspec's
 * {@code GitIgnoreSpec}, which the Python tool used, so include and exclude lists select exactly
 * the same files): the last pattern that matches decides, {@code !} re-includes, a trailing
 * {@code /} matches folders, {@code **} any number of folders.
 */
public final class GitIgnore {

    private record Rule(Pattern regex, boolean include) {}

    private final List<Rule> rules; // last pattern first

    private GitIgnore(List<Rule> rules) {
        this.rules = rules;
    }

    /** Compiles the patterns; backslashes are taken as folder separators. */
    public static GitIgnore of(List<String> patterns) {
        List<Rule> rules = new ArrayList<>();
        for (String p : patterns) {
            Rule r = compile(p.replace('\\', '/'));
            if (r != null) {
                rules.add(r);
            }
        }
        Collections.reverse(rules);
        return new GitIgnore(rules);
    }

    /** Whether {@code path} (relative, POSIX; a folder ends with {@code /}) is selected. */
    public boolean matches(String path) {
        String file = path.startsWith("/") ? path.substring(1) : path.startsWith("./") ? path.substring(2) : path;
        Boolean include = null;
        int priority = 0;
        for (Rule r : rules) {
            Matcher m = r.regex.matcher(file);
            if (!m.find()) {
                continue;
            }
            int p = m.group("psd") != null ? 1 : 2;
            if (p > priority) {
                include = r.include;
                priority = p;
            }
            if (p == 2) {
                break;
            }
        }
        return Boolean.TRUE.equals(include);
    }

    private static final String DIR_MARK = "(?<psd>/)";
    private static final String DIR_MARK_OPT = "(?:" + DIR_MARK + "|$)";

    /** A pattern as a regular expression, or null for a comment, a blank line or an invalid range. */
    static Rule compile(String pattern) {
        String p = pattern.endsWith("\\ ") ? pattern : pattern.stripTrailing();
        if (p.isEmpty() || p.startsWith("#") || p.equals("/")) {
            return null;
        }
        boolean include = true;
        if (p.startsWith("!")) {
            include = false;
            p = p.substring(1);
        }
        List<String> segs = new ArrayList<>(List.of(p.split("/", -1)));
        boolean dirPattern = segs.getLast().isEmpty();
        String regex;
        // normalize the segments as pathspec does
        if (segs.getFirst().isEmpty()) {
            segs.removeFirst();
        } else if (segs.size() == 1 || (segs.size() == 2 && segs.get(1).isEmpty())) {
            if (!segs.getFirst().equals("**")) {
                segs.addFirst("**");
            }
        }
        if (segs.isEmpty()) {
            throw new IllegalArgumentException("Invalid git pattern: '" + pattern + "'");
        }
        if (segs.getLast().isEmpty()) {
            segs.set(segs.size() - 1, "**");
        }
        for (int i = segs.size() - 1; i > 0; i--) {
            if (segs.get(i - 1).equals("**") && segs.get(i).equals("**")) {
                segs.remove(i);
            }
        }
        int n = segs.size();
        if (n == 1 && segs.get(0).equals("**")) {
            regex = dirPattern ? DIR_MARK : ".";
        } else if (n == 2 && segs.get(0).equals("**") && segs.get(1).equals("*")) {
            regex = ".";
        } else if (n == 3 && segs.get(0).equals("**") && segs.get(1).equals("*") && segs.get(2).equals("**")) {
            regex = dirPattern ? DIR_MARK : "/";
        } else {
            StringBuilder out = new StringBuilder();
            boolean needSlash = false;
            int end = n - 1;
            for (int i = 0; i < n; i++) {
                String seg = segs.get(i);
                if (seg.equals("**")) {
                    if (i == 0) {
                        out.append("^(?:.+/)?");
                    } else if (i < end) {
                        out.append("(?:/.+)?");
                        needSlash = true;
                    } else {
                        out.append(dirPattern ? DIR_MARK : "/");
                    }
                } else {
                    if (i == 0) {
                        out.append('^');
                    }
                    if (needSlash) {
                        out.append('/');
                    }
                    if (seg.equals("*")) {
                        out.append("[^/]+");
                    } else {
                        String glob = glob(seg);
                        if (glob == null) {
                            return null; // invalid range: Git ignores the pattern
                        }
                        out.append(glob);
                    }
                    if (i == end) {
                        out.append(DIR_MARK_OPT);
                    }
                    needSlash = true;
                }
            }
            regex = out.toString();
        }
        if (!regex.contains("(?<psd>")) {
            regex = regex + "(?<psd>(?!))?"; // the group exists but never matches
        }
        return new Rule(Pattern.compile(regex, Pattern.UNIX_LINES), include);
    }

    /** One path segment's glob as a regular expression; null for an unclosed {@code [}. */
    private static String glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        boolean escape = false;
        int i = 0;
        int end = pattern.length();
        while (i < end) {
            char c = pattern.charAt(i++);
            if (escape) {
                escape = false;
                regex.append(quote(c));
            } else if (c == '\\') {
                escape = true;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else if (c == '[') {
                int j = i;
                if (j < end && (pattern.charAt(j) == '!' || pattern.charAt(j) == '^')) {
                    j++;
                }
                if (j < end && pattern.charAt(j) == ']') {
                    j++;
                }
                while (j < end && pattern.charAt(j) != ']') {
                    j++;
                }
                if (j < end) {
                    j++;
                    StringBuilder expr = new StringBuilder("[");
                    if (pattern.charAt(i) == '!' || pattern.charAt(i) == '^') {
                        expr.append('^');
                        i++;
                    }
                    String body = pattern.substring(i, j).replace("\\", "\\\\");
                    // Java reads [ and && inside a class as nesting and intersection; Python does not
                    expr.append(body.substring(0, body.length() - 1).replace("[", "\\[").replace("&&", "&\\&"))
                            .append(']');
                    regex.append(expr);
                    i = j;
                } else {
                    return null;
                }
            } else {
                regex.append(quote(c));
            }
        }
        if (escape) {
            throw new IllegalArgumentException("Escape character found with no next character to escape: '" + pattern + "'");
        }
        return regex.toString();
    }

    private static String quote(char c) {
        // an ASCII punctuation mark is escaped; anything else has no special meaning in a regex
        return c < 128 && !Character.isLetterOrDigit(c) && c != '_' ? "\\" + c : String.valueOf(c);
    }
}
