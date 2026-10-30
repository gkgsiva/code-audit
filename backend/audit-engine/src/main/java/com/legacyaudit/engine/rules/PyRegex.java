package com.legacyaudit.engine.rules;

import com.legacyaudit.engine.inventory.Rx;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A regular expression written for Python's {@code re} (rule packs are shared with the Python
 * tool), compiled for Java: {@code (?P<name>...)} becomes a Java named group (Java group names
 * allow letters and digits only, so other characters are dropped and a number added when two
 * names collide), {@code (?P=name)} becomes {@code \k<name>} and {@code \Z} becomes {@code \z}.
 * {@link #pyName} gives back the Python name of a Java group.
 */
public final class PyRegex {

    private static final Pattern NAMED = Pattern.compile("\\(\\?P<([^>]+)>|\\(\\?P=([^)]+)\\)|\\\\Z|\\\\.");

    public final Pattern pattern;
    /** Java group name to the Python name, in pattern order. */
    public final Map<String, String> groups;

    private PyRegex(Pattern pattern, Map<String, String> groups) {
        this.pattern = pattern;
        this.groups = groups;
    }

    public static PyRegex compile(String regex, int flags) {
        Map<String, String> javaNames = new LinkedHashMap<>(); // python -> java
        StringBuilder out = new StringBuilder();
        Matcher m = NAMED.matcher(regex);
        int pos = 0;
        while (m.find()) {
            out.append(regex, pos, m.start());
            pos = m.end();
            if (m.group(1) != null) {
                String py = m.group(1);
                String base = py.replaceAll("[^A-Za-z0-9]", "");
                if (base.isEmpty() || !Character.isLetter(base.charAt(0))) {
                    base = "g" + base;
                }
                String name = base;
                for (int k = 2; javaNames.containsValue(name); k++) {
                    name = base + k;
                }
                javaNames.put(py, name);
                out.append("(?<").append(name).append('>');
            } else if (m.group(2) != null) {
                String name = javaNames.get(m.group(2));
                if (name == null) {
                    throw new IllegalArgumentException("unknown group name '" + m.group(2) + "'");
                }
                out.append("\\k<").append(name).append('>');
            } else if (m.group().equals("\\Z")) {
                out.append("\\z");
            } else {
                out.append(m.group()); // any other escape stays as it is
            }
        }
        out.append(regex.substring(pos));
        Map<String, String> groups = new LinkedHashMap<>();
        javaNames.forEach((py, java) -> groups.put(java, py));
        return new PyRegex(Rx.of(out.toString(), flags), groups);
    }

    public static PyRegex compile(String regex) {
        return compile(regex, 0);
    }

    public String pyName(String javaName) {
        return groups.getOrDefault(javaName, javaName);
    }
}
