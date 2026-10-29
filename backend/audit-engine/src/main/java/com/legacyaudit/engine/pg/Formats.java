package com.legacyaudit.engine.pg;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Date format strings from a source dialect to PostgreSQL ({@code TO_CHAR} patterns), as the SQL
 * library converted them: the source tokens to strftime ones, then strftime to PostgreSQL, each
 * step taking the longest token that matches ({@code formats.tsv}, taken from that library).
 */
final class Formats {

    private static final Map<String, Map<String, String>> MAPS = new HashMap<>();

    static {
        try (InputStream in = Formats.class.getResourceAsStream("formats.tsv");
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) {
                    String[] c = line.split("\t", -1);
                    MAPS.computeIfAbsent(c[0], k -> new LinkedHashMap<>()).put(c[1], c[2]);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Formats() {}

    static String key(Dialect d) {
        return switch (d) {
            case ORACLE -> "oracle";
            case TSQL -> "tsql";
            case MYSQL -> "mysql";
            case POSTGRES -> "postgres";
            default -> "";
        };
    }

    /** A source-dialect format as a PostgreSQL one. */
    static String toPostgres(String format, Dialect d) {
        return fromStrftime(convert(format, MAPS.getOrDefault(key(d), Map.of())));
    }

    /** A strftime format ({@code %Y-%m-%d}) as a PostgreSQL one. */
    static String fromStrftime(String format) {
        return convert(format, MAPS.get("to-postgres"));
    }

    /** The strftime format of a SQL Server CONVERT style number, or null. */
    static String tsqlStyle(String style) {
        return MAPS.get("tsql-style").get(style);
    }

    /** sqlglot's format_time: split on the longest known tokens, map each. */
    static String convert(String s, Map<String, String> mapping) {
        if (s == null || s.isEmpty() || mapping.isEmpty()) {
            return s;
        }
        int start = 0;
        int end = 1;
        int size = s.length();
        String prefix = "";
        String sym = null;
        StringBuilder out = new StringBuilder();
        java.util.List<String> chunks = new java.util.ArrayList<>();
        while (end <= size) {
            String chars = s.substring(start, end);
            String probe = prefix + chars.charAt(chars.length() - 1);
            boolean exists = mapping.containsKey(probe);
            boolean isPrefix = exists || hasPrefix(mapping, probe);
            if (!isPrefix) {
                if (sym != null) {
                    end -= 1;
                    chars = sym;
                    sym = null;
                } else {
                    chars = chars.substring(0, 1);
                    end = start + 1;
                }
                start += chars.length();
                chunks.add(chars);
                prefix = "";
            } else {
                prefix = probe;
                if (exists) {
                    sym = chars;
                }
            }
            end += 1;
            if (isPrefix && end > size) {
                chunks.add(chars);
            }
        }
        for (String c : chunks) {
            out.append(mapping.getOrDefault(c, c));
        }
        return out.toString();
    }

    private static boolean hasPrefix(Map<String, String> mapping, String p) {
        for (String k : mapping.keySet()) {
            if (k.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
