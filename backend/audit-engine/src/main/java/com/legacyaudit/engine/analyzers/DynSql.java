package com.legacyaudit.engine.analyzers;

import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rebuilding SQL text that code assembles at run time (C/Pro*C, COBOL, Java).
 *
 * <p>A {@link Built} value is the text a variable holds at some point: every fragment
 * ({@code text}), only the fragments added on every code path ({@code minimal}), the run-time
 * pieces shown as {@code ?} and classified by {@link SqlTools#holeKind} and whole alternative
 * texts ({@code variants}) from ternaries and branch-only reassignments. {@link Tracker} keeps
 * each variable's history in source order.
 */
public final class DynSql {

    private DynSql() {}

    public static final int MAX_VARIANTS = 5;
    public static final int MAX_EVIDENCE = 10;

    /** printf-style conversions (C sprintf); {@code *} width/precision consume an argument. */
    public static final Pattern C_FORMAT = Rx.of("%([-+ #0]*)(\\*|\\d+)?(?:\\.(\\*|\\d+))?(?:hh|h|ll|l|L|z|j|t|q)?([a-zA-Z%])");

    /** Text a variable holds, and how it was built. */
    public static final class Built {
        public String text;
        public int line;
        public String minimal;
        public int valueHoles;
        public int structuralHoles;
        public int fragments = 1;
        public int optional;
        public Set<String> how = new LinkedHashSet<>();
        public Set<Integer> lines = new LinkedHashSet<>();
        public List<String> variants = new ArrayList<>();

        public Built(String text, int line) {
            this.text = text;
            this.line = line;
        }

        public Built copy() {
            Built b = new Built(text, line);
            b.minimal = minimal;
            b.valueHoles = valueHoles;
            b.structuralHoles = structuralHoles;
            b.fragments = fragments;
            b.optional = optional;
            b.how = new LinkedHashSet<>(how);
            b.lines = new LinkedHashSet<>(lines);
            b.variants = new ArrayList<>(variants);
            return b;
        }

        public String base() {
            return minimal == null ? text : minimal;
        }

        public boolean dynamic() {
            return valueHoles > 0 || structuralHoles > 0 || optional > 0 || !variants.isEmpty() || fragments > 1;
        }
    }

    public static Built lit(String text, int line) {
        Built b = new Built(text, line);
        b.lines.add(line);
        return b;
    }

    public static Built empty(int line) {
        Built b = new Built("", line);
        b.fragments = 0;
        return b;
    }

    public static Built frag(String text, int line) {
        Built b = new Built(text, line);
        b.fragments = 0;
        return b;
    }

    public static Built hole(String before, int line) {
        String kind = SqlTools.holeKind(before);
        Built b = new Built("?", line);
        b.valueHoles = kind.equals("value") ? 1 : 0;
        b.structuralHoles = kind.equals("structural") ? 1 : 0;
        b.fragments = 0;
        b.lines.add(line);
        return b;
    }

    public static Built concat(Built a, Built b) {
        return concat(a, b, false);
    }

    public static Built concat(Built a, Built b, boolean optional) {
        Built out = new Built(a.text + b.text, a.line);
        out.minimal = a.base() + (optional ? "" : b.base());
        out.valueHoles = a.valueHoles + b.valueHoles;
        out.structuralHoles = a.structuralHoles + b.structuralHoles;
        out.fragments = a.fragments + b.fragments;
        out.optional = a.optional + b.optional + (optional ? b.fragments : 0);
        out.how = new LinkedHashSet<>(a.how);
        out.how.addAll(b.how);
        if (optional) {
            out.how.add("conditional");
        }
        out.lines = new LinkedHashSet<>(a.lines);
        out.lines.addAll(b.lines);
        // Variants are whole alternative texts: combine each side's alternatives with the other.
        List<String> v = new ArrayList<>();
        for (String x : a.variants) {
            v.add(x + b.text);
        }
        for (String x : b.variants) {
            v.add(a.text + x);
        }
        out.variants = head(v);
        return out;
    }

    private static List<String> head(List<String> v) {
        return new ArrayList<>(v.subList(0, Math.min(MAX_VARIANTS, v.size())));
    }

    public static Built join(List<Built> parts, int line) {
        Built out = empty(line);
        for (Built p : parts) {
            out = concat(out, p);
        }
        return out;
    }

    /** {@code cond ? a : b}: the text of a, with b as a variant. */
    public static Built choice(Built a, Built b) {
        Built out = concat(empty(a.line), a);
        out.how.add("ternary");
        List<String> v = new ArrayList<>();
        v.add(b.text);
        v.addAll(a.variants);
        v.addAll(b.variants);
        out.variants = head(v);
        out.fragments = a.fragments + b.fragments;
        out.lines.addAll(b.lines);
        return out;
    }

    /**
     * The value of a variable after an assignment ({@code append} = added to the end);
     * {@code cond} when the statement runs on some code paths only.
     */
    public static Built update(Built prev, Built nw, boolean cond, boolean append) {
        if (prev == null) {
            return nw;
        }
        if (append) {
            return concat(prev, nw, cond);
        }
        if (!cond) {
            return nw;
        }
        Built out = concat(prev, empty(prev.line)); // reassigned on some paths only: keep both
        out.how.add("reassignment");
        out.how.addAll(nw.how);
        List<String> v = new ArrayList<>(prev.variants);
        v.add(nw.text);
        out.variants = head(v);
        out.fragments += nw.fragments;
        out.lines.addAll(nw.lines);
        return out;
    }

    /**
     * sprintf text: each conversion takes the next argument (its rebuilt text, or a hole).
     * {@code before} is the text already in the buffer when the result is appended to it.
     */
    public static Built formatC(Built fmt, List<Built> args, int line, String before) {
        Built out = new Built("", fmt.line);
        out.fragments = 0;
        out.how = new LinkedHashSet<>(fmt.how);
        out.lines = new LinkedHashSet<>(fmt.lines);
        int pos = 0;
        int k = 0;
        Matcher m = C_FORMAT.matcher(fmt.text);
        while (m.find()) {
            out = concat(out, frag(fmt.text.substring(pos, m.start()), line));
            pos = m.end();
            String conv = m.group(4);
            if (conv.equals("%")) {
                out = concat(out, frag("%", line));
                continue;
            }
            k += ("*".equals(m.group(2)) ? 1 : 0) + ("*".equals(m.group(3)) ? 1 : 0);
            Built value = k < args.size() ? args.get(k) : null;
            k++;
            if (conv.equals("s") && value != null) {
                out = concat(out, value);
            } else if (conv.equals("s") || conv.equals("c")) {
                out = concat(out, hole(before + out.text, line));
            } else { // a number is always a value, never a name or a clause
                out = concat(out, hole("=", line));
            }
        }
        out = concat(out, frag(fmt.text.substring(pos), line));
        out.fragments++; // the format string itself
        out.how.add("string-format");
        return out;
    }

    /** Values of text variables in source order; {@code at} answers "what did it hold here". */
    public static final class Tracker {
        private final Map<String, Built> current = new HashMap<>();
        private final Map<String, List<Map.Entry<Integer, Built>>> history = new HashMap<>();

        public Built get(String name) {
            return current.get(name);
        }

        public void assign(String name, Built nw, int line, boolean cond, boolean append) {
            Built withLine = nw.copy(); // the statement is evidence too
            withLine.lines.add(line);
            Built value = update(current.get(name), withLine, cond, append);
            current.put(name, value);
            history.computeIfAbsent(name, k -> new ArrayList<>()).add(Map.entry(line, value));
        }

        /**
         * The value just before {@code line}; a variable only built further down the source
         * gives its last value.
         */
        public Built at(String name, int line) {
            List<Map.Entry<Integer, Built>> entries = history.get(name);
            if (entries == null || entries.isEmpty()) {
                return null;
            }
            Built last = null;
            for (var e : entries) {
                if (e.getKey() <= line) {
                    last = e.getValue();
                }
            }
            return last != null ? last : entries.getLast().getValue();
        }
    }

    /** Copies how the text was rebuilt onto the statement, with evidence for each fragment. */
    @SuppressWarnings("unchecked")
    public static Doc fill(Doc fact, Built built) {
        TreeSet<String> how = new TreeSet<>(SqlTools.PyJson_ORDER);
        how.addAll((List<String>) fact.get("built_with"));
        how.addAll(built.how);
        fact.set("built_with", new ArrayList<>(how));
        fact.set("fragments", (long) Math.max(built.fragments, 1));
        fact.set("optional_fragments", (long) built.optional);
        fact.set("value_holes", (long) built.valueHoles);
        fact.set("structural_holes", (long) built.structuralHoles);
        String text = fact.str("text");
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        List<String> all = new ArrayList<>();
        all.add(TextUtil.strip(built.base()));
        for (String v : built.variants) {
            all.add(TextUtil.strip(v));
        }
        for (String v : all) {
            if (!v.isEmpty() && !v.equals(text)) {
                variants.add(v);
            }
        }
        fact.set("variants", head(new ArrayList<>(variants)));
        List<Evidence> evidence = (List<Evidence>) fact.get("evidence");
        Set<Integer> known = new java.util.HashSet<>();
        for (Evidence e : evidence) {
            known.add(e.line());
        }
        TreeSet<Integer> lines = new TreeSet<>(built.lines);
        lines.removeAll(known);
        int n = 0;
        for (int ln : lines) { // where the other fragments are
            if (n++ >= MAX_EVIDENCE) {
                break;
            }
            evidence.add(new Evidence(fact.str("file"), ln, null));
        }
        return fact;
    }
}
