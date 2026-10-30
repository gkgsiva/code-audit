package com.legacyaudit.engine.security;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version comparison close to Maven's ordering, and simple range expressions.
 *
 * <p>{@code 1.2.17 < 1.2.18}, {@code 2.0-beta1 < 2.0-rc1 < 2.0 = 2.0.0 = 2.0.Final < 2.0-sp1 < 2.0.1}.
 * Ranges are comma-separated bounds that must all hold: {@code "<2"}, {@code ">=3.0,<3.5"},
 * {@code "<=5.3.39"}; {@code "*"} matches any version (also an unknown one).
 */
public final class Versions {

    private static final Map<String, Integer> PRE = Map.ofEntries(Map.entry("alpha", -5), Map.entry("a", -5), Map.entry("beta", -4),
            Map.entry("b", -4), Map.entry("milestone", -3), Map.entry("m", -3), Map.entry("ea", -3), Map.entry("rc", -2),
            Map.entry("cr", -2), Map.entry("pre", -2), Map.entry("snapshot", -1));
    private static final Set<String> RELEASE = Set.of("", "ga", "final", "release", "r");
    private static final Pattern PART = Pattern.compile("\\d+|[a-z]+");
    private static final Pattern BOUND = Pattern.compile("(<=|>=|<|>|==|=)?\\s*(.+)", Pattern.DOTALL);

    private Versions() {}

    private static List<Object> parts(String version) {
        List<Object> out = new ArrayList<>();
        Matcher m = PART.matcher(version.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String p = m.group();
            out.add(Character.isDigit(p.charAt(0)) ? new BigInteger(p) : p);
        }
        while (!out.isEmpty()) {
            Object last = out.get(out.size() - 1);
            if (BigInteger.ZERO.equals(last) || last instanceof String s && RELEASE.contains(s)) {
                out.remove(out.size() - 1);
            } else {
                break;
            }
        }
        return out;
    }

    /** (kind, number, text) as a comparable triple. */
    private record Rank(int kind, BigInteger n, String s) implements Comparable<Rank> {
        @Override
        public int compareTo(Rank o) {
            int c = Integer.compare(kind, o.kind);
            if (c == 0) {
                c = n.compareTo(o.n);
            }
            return c != 0 ? c : s.compareTo(o.s);
        }
    }

    private static Rank rank(Object p) {
        if (p instanceof BigInteger n) {
            return new Rank(1, n, "");
        }
        String s = (String) p;
        if (PRE.containsKey(s)) {
            return new Rank(-1, BigInteger.valueOf(PRE.get(s)), "");
        }
        if (RELEASE.contains(s)) {
            return new Rank(0, BigInteger.ZERO, "");
        }
        return new Rank(0, BigInteger.ONE, s); // sp, other qualifiers: just above the release
    }

    public static int compare(String a, String b) {
        List<Object> pa = parts(a);
        List<Object> pb = parts(b);
        for (int i = 0; i < Math.max(pa.size(), pb.size()); i++) {
            // A missing part is 0 next to a number and "release" next to a qualifier.
            Object other = i >= pa.size() ? pb.get(i) : pa.get(i);
            Rank pad = other instanceof BigInteger ? new Rank(1, BigInteger.ZERO, "") : new Rank(0, BigInteger.ZERO, "");
            Rank ra = i < pa.size() ? rank(pa.get(i)) : pad;
            Rank rb = i < pb.size() ? rank(pb.get(i)) : pad;
            int c = ra.compareTo(rb);
            if (c != 0) {
                return c < 0 ? -1 : 1;
            }
        }
        return 0;
    }

    public static List<String> sort(List<String> versions) {
        List<String> out = new ArrayList<>(versions);
        out.sort(Versions::compare);
        return out;
    }

    /** TRUE/FALSE, or null when the version is unknown and the range is not {@code *}. */
    public static Boolean inRange(String version, String spec) {
        if (spec == null || spec.strip().isEmpty() || spec.strip().equals("*") || spec.strip().equals("any")) {
            return true;
        }
        if (version == null || version.isEmpty()) {
            return null;
        }
        for (String raw : spec.split(",", -1)) {
            String bound = raw.strip();
            Matcher m = BOUND.matcher(bound);
            if (!m.lookingAt()) {
                continue;
            }
            String op = m.group(1) == null ? "=" : m.group(1);
            int c = compare(version, m.group(2));
            boolean ok = switch (op) {
                case "<" -> c < 0;
                case "<=" -> c <= 0;
                case ">" -> c > 0;
                case ">=" -> c >= 0;
                default -> c == 0;
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
