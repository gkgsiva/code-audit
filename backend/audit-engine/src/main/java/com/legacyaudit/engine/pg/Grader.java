package com.legacyaudit.engine.pg;

import com.legacyaudit.engine.analyzers.SqlTools;
import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.engine.rules.PyRegex;
import com.legacyaudit.model.facts.Doc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PostgreSQL grades of SQL statements and stored objects (rules in {@code pg-rules.yaml},
 * {@code data.constructs}): <em>auto</em> when the statement converts as it is, <em>review</em>
 * when a known construct needs a look, <em>manual</em> when it must be rewritten by hand. The
 * suggested PostgreSQL text comes from {@link Transpiler}.
 */
public final class Grader {

    public static final String AUTO = "auto";
    public static final String REVIEW = "review";
    public static final String MANUAL = "manual";
    private static final List<String> MANUAL_OBJECTS = List.of("package", "procedure", "function", "trigger");

    private Grader() {}

    /** A vendor construct the rules know: where it is (regex) and what it means for the move. */
    public record Construct(String id, PyRegex pattern, String grade, String reason, boolean validInPg) {}

    /** A statement's grade, the reasons joined with "; " (or null) and the PostgreSQL text (or null). */
    public record Graded(String grade, String reason, String pgText) {}

    record TextGrade(String grade, List<String> reasons, String pgText) {}

    /** The constructs of the pack data; the first entry with an id wins (team entries come first). */
    @SuppressWarnings("unchecked")
    public static List<Construct> constructs(Map<String, Object> data) {
        Map<String, Construct> seen = new LinkedHashMap<>();
        Object list = data.get("constructs");
        if (list instanceof List<?> items) {
            for (Object o : items) {
                Map<String, Object> c = (Map<String, Object>) o;
                String id = String.valueOf(c.get("id"));
                seen.putIfAbsent(id, new Construct(id, PyRegex.compile(String.valueOf(c.get("regex")), Rx.I | Rx.M),
                        c.get("grade") == null ? REVIEW : String.valueOf(c.get("grade")), String.valueOf(c.get("reason")),
                        Boolean.TRUE.equals(c.get("valid_in_pg"))));
            }
        }
        return new ArrayList<>(seen.values());
    }

    private static int order(String grade) {
        return switch (grade) {
            case AUTO -> 0;
            case REVIEW -> 1;
            default -> 2;
        };
    }

    private static String worst(String a, String b) {
        return order(a) >= order(b) ? a : b;
    }

    private static List<Construct> found(String text, List<Construct> rules) {
        List<Construct> out = new ArrayList<>();
        for (Construct c : rules) {
            if (c.pattern().pattern.matcher(text).find()) {
                out.add(c);
            }
        }
        return out;
    }

    static TextGrade gradeText(String text, String dialect, List<Construct> rules) {
        List<Construct> found = found(SqlTools.stripComments(text), rules);
        List<String> reasons = new ArrayList<>();
        found.forEach(c -> reasons.add(c.reason()));
        if (found.stream().anyMatch(c -> c.grade().equals(MANUAL))) {
            return new TextGrade(MANUAL, reasons, null);
        }
        String pgText;
        try {
            pgText = Transpiler.toPostgres(text, dialect);
        } catch (Transpiler.Failure err) {
            if (!found.isEmpty()) { // the flagged constructs explain it: a reviewer converts them by hand
                reasons.add("not converted automatically");
                return new TextGrade(REVIEW, reasons, null);
            }
            return new TextGrade(MANUAL, new ArrayList<>(List.of("does not transpile to PostgreSQL (" + err.kind() + ")")), null);
        }
        if (pgText != null && !pgText.isEmpty()) {
            String converted = pgText;
            if (found.stream().anyMatch(c -> !c.validInPg() && c.pattern().pattern.matcher(converted).find())) {
                pgText = null;
                reasons.add("not converted automatically");
            }
        }
        return new TextGrade(found.isEmpty() ? AUTO : REVIEW, reasons, pgText);
    }

    /**
     * Grade one statement. Dynamic SQL rebuilt from Java (or MyBatis/iBATIS tags) is graded on
     * its full text and on each variant, and is at least <em>review</em>; it is <em>manual</em>
     * only when part of the statement itself (a column, table or clause) is known only at run time.
     */
    public static Graded grade(Doc st, List<Construct> rules) {
        if ("spring-data".equals(st.str("source"))) {
            return new Graded(AUTO, "derived query; the ORM generates the SQL", null);
        }
        String text = SqlTools.stripComments(st.str("text"));
        List<String> reasons = new ArrayList<>();
        found(text, rules).forEach(c -> reasons.add(c.reason()));
        if ("hql".equals(st.str("dialect"))) {
            return new Graded(reasons.isEmpty() ? AUTO : REVIEW, reasons.isEmpty() ? null : String.join("; ", reasons), null);
        }
        long structural = number(st, "structural_holes");
        if ("DYNAMIC".equals(st.str("type")) || structural > 0) {
            String where = structural > 0 ? " (" + structural + " place(s))" : "";
            List<String> all = new ArrayList<>(List.of("part of the statement is only known at run time" + where));
            all.addAll(reasons);
            return new Graded(MANUAL, String.join("; ", all), null);
        }
        if (!st.bool("parsed")) {
            List<String> all = new ArrayList<>(List.of("statement could not be parsed"));
            all.addAll(reasons);
            return new Graded(MANUAL, String.join("; ", all), null);
        }
        TextGrade g = gradeText(st.str("text"), st.str("dialect"), rules);
        String grade = g.grade();
        List<String> why = new ArrayList<>(g.reasons());
        if (st.bool("dynamic")) {
            List<Object> variants = st.list("variants");
            for (Object variant : variants) {
                TextGrade gv = gradeText(String.valueOf(variant), st.str("dialect"), rules);
                grade = worst(grade, gv.grade());
                why.addAll(gv.reasons());
            }
            grade = worst(grade, REVIEW);
            String parts = number(st, "fragments") + " fragment(s)";
            if (number(st, "optional_fragments") > 0) {
                parts += ", " + number(st, "optional_fragments") + " optional";
            }
            if (number(st, "value_holes") > 0) {
                parts += ", " + number(st, "value_holes") + " concatenated value(s)";
            }
            String head = "dynamic SQL rebuilt from " + parts;
            if (!variants.isEmpty()) {
                head += "; " + (variants.size() + 1) + " variants, check each";
            }
            why.add(0, head);
        }
        String reason = String.join("; ", new LinkedHashSet<>(why));
        return new Graded(grade, reason.isEmpty() ? null : reason, g.pgText());
    }

    private static long number(Doc d, String field) {
        Object v = d.get(field);
        return v instanceof Number n ? n.longValue() : 0;
    }

    private static List<Object> key(Doc st) {
        return java.util.Arrays.asList(st.str("text"), st.str("dialect"), st.str("source"), st.bool("dynamic"), st.bool("parsed"),
                st.str("type"), number(st, "structural_holes"), number(st, "fragments"), number(st, "optional_fragments"),
                number(st, "value_holes"), List.copyOf(st.list("variants")));
    }

    /**
     * Grade in place: {@code pg_grade}, {@code reason} and {@code pg_text} of every statement
     * (identical statements graded once, in parallel), and the stored objects that are code
     * (packages, procedures, functions, triggers) as manual.
     */
    public static void gradeAll(List<Doc> statements, List<Doc> objects, Map<String, Object> data) {
        List<Construct> rules = constructs(data);
        Map<List<Object>, Doc> unique = new LinkedHashMap<>();
        for (Doc st : statements) {
            unique.putIfAbsent(key(st), st);
        }
        Map<List<Object>, Graded> results = new ConcurrentHashMap<>();
        unique.entrySet().parallelStream().forEach(e -> results.put(e.getKey(), grade(e.getValue(), rules)));
        for (Doc st : statements) {
            Graded g = Objects.requireNonNull(results.get(key(st)));
            st.set("pg_grade", g.grade()).set("reason", g.reason()).set("pg_text", g.pgText());
        }
        for (Doc obj : objects) {
            String type = obj.str("object_type");
            if (MANUAL_OBJECTS.contains(type)) {
                obj.set("pg_grade", MANUAL).set("reason", "stored " + type + ": move the logic to Java or rewrite in PL/pgSQL");
            }
        }
    }
}
