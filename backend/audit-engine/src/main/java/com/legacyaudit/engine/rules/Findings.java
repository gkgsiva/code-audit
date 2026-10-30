package com.legacyaudit.engine.rules;

import com.legacyaudit.engine.rules.Packs.Rule;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.Finding;
import com.legacyaudit.model.facts.Severity;
import com.legacyaudit.model.json.PyJson;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Turns rule packs into findings: fact rules over collections, text rules from cached hits.
 *
 * <p>Every run re-evaluates the rules (they are cheap), so switching a rule off or changing its
 * severity in {@code audit.yaml} takes effect without re-analysing files. The same switches apply
 * to findings that analyzers and the linker raise themselves ({@code config-credential},
 * {@code unresolved-reference}, {@code analyzer-error}).
 */
public final class Findings {

    static final int MAX_EVIDENCE = 5;
    private static final Object MISSING = new Object();
    private static final List<String> ORDER = List.of("critical", "high", "medium", "low", "info");

    private Findings() {}

    /** A text-rule hit cached per file: (file, rule id, line, matched text). */
    public record Hit(String file, String ruleId, long line, String text) {}

    @SuppressWarnings("unchecked")
    private static Object get(Map<String, Object> item, String path) {
        Object value = item;
        for (String part : path.split("\\.", -1)) {
            if (value instanceof Map<?, ?> m && m.containsKey(part)) {
                value = ((Map<String, Object>) m).get(part);
            } else {
                return MISSING;
            }
        }
        return value;
    }

    private static Double number(Object v) {
        return v instanceof Number n && !(v instanceof Boolean) ? n.doubleValue() : null;
    }

    /** Python's {@code ==} over JSON values: 1 == 1.0 and True == 1. */
    static boolean pyEquals(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        Object na = a instanceof Boolean x ? (Object) (x ? 1L : 0L) : a;
        Object nb = b instanceof Boolean x ? (Object) (x ? 1L : 0L) : b;
        if (na instanceof Number x && nb instanceof Number y) {
            return x.doubleValue() == y.doubleValue();
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!pyEquals(la.get(i), lb.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (!ma.keySet().equals(mb.keySet())) {
                return false;
            }
            for (Object k : ma.keySet()) {
                if (!pyEquals(ma.get(k), mb.get(k))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    private static boolean pyIn(Object value, Object container) {
        if (container instanceof List<?> l) {
            return l.stream().anyMatch(x -> pyEquals(value, x));
        }
        if (container instanceof Map<?, ?> m) {
            return m.keySet().stream().anyMatch(x -> pyEquals(value, x));
        }
        if (container instanceof String s) {
            if (!(value instanceof String v)) {
                throw new IllegalArgumentException("'in <string>' requires string as left operand");
            }
            return s.contains(v);
        }
        throw new IllegalArgumentException("argument is not a container");
    }

    private static boolean isEmpty(Object v) {
        return "".equals(v) || v instanceof List<?> l && l.isEmpty() || v instanceof Map<?, ?> m && m.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static boolean check(Object value, Object cond) {
        if (!(cond instanceof Map<?, ?> conds)) {
            return value != MISSING && pyEquals(value, cond);
        }
        boolean present = value != MISSING && value != null;
        for (Map.Entry<String, Object> e : ((Map<String, Object>) conds).entrySet()) {
            Object arg = e.getValue();
            boolean ok = switch (e.getKey()) {
                case "exists" -> present == Py.truthy(arg);
                case "empty" -> (!present || isEmpty(value)) == Py.truthy(arg);
                case "eq" -> present && pyEquals(value, arg);
                case "ne" -> !present || !pyEquals(value, arg);
                case "in" -> present && pyIn(value, arg);
                case "not_in" -> !present || !pyIn(value, arg);
                case "contains" -> present && (value instanceof List<?> || value instanceof String) && pyIn(arg, value);
                case "regex" -> present && PyRegex.compile(Py.str(arg)).pattern.matcher(Py.str(value)).find();
                case "gt", "gte", "lt", "lte" -> {
                    Double n = number(value);
                    Double a = number(arg);
                    yield n != null && a != null && switch (e.getKey()) {
                        case "gt" -> n > a;
                        case "gte" -> n >= a;
                        case "lt" -> n < a;
                        default -> n <= a;
                    };
                }
                default -> throw new IllegalArgumentException("unknown condition '" + e.getKey() + "'");
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Whether every {@code field: condition} of {@code where} holds for the item. */
    public static boolean matches(Map<String, Object> item, Map<String, Object> where) {
        for (Map.Entry<String, Object> e : where.entrySet()) {
            if (!check(get(item, e.getKey()), e.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Python {@code str.format} over the item: {@code {field}} and {@code {a.b}}, unknown or
     * null fields as {@code ?}, top-level lists joined with ", ", {@code {{ }}} as braces.
     */
    public static String render(String template, Map<String, Object> item) {
        Map<String, Object> values = new LinkedHashMap<>();
        item.forEach((k, v) -> values.put(k, v instanceof List<?> l ? String.join(", ", l.stream().map(Py::str).toList()) : v));
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = template.length();
        while (i < n) {
            char c = template.charAt(i);
            if (c == '{' && i + 1 < n && template.charAt(i + 1) == '{') {
                out.append('{');
                i += 2;
            } else if (c == '}' && i + 1 < n && template.charAt(i + 1) == '}') {
                out.append('}');
                i += 2;
            } else if (c == '{') {
                int end = template.indexOf('}', i);
                if (end < 0) {
                    throw new IllegalArgumentException("Single '{' encountered in format string");
                }
                String field = template.substring(i + 1, end);
                String spec = "";
                int colon = field.indexOf(':');
                if (colon >= 0) {
                    spec = field.substring(colon + 1);
                    field = field.substring(0, colon);
                }
                int bang = field.indexOf('!');
                if (bang >= 0) {
                    field = field.substring(0, bang);
                }
                Object v = get(values, field.replace("[", ".").replace("]", ""));
                Object shown = v == MISSING || v == null ? "?" : v;
                out.append(spec.isEmpty() ? Py.str(shown) : Py.format(shown, spec));
                i = end + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private record Location(String file, Integer line, List<Evidence> evidence) {}

    private static Location location(Map<String, Object> item) {
        List<Evidence> ev = new ArrayList<>(Evidence.fromMaps(item.get("evidence")));
        if (ev.size() > MAX_EVIDENCE) {
            ev = new ArrayList<>(ev.subList(0, MAX_EVIDENCE));
        }
        String file = item.get("file") instanceof String s ? s : null;
        Integer line = item.get("line") instanceof Number num && !(item.get("line") instanceof Double) ? num.intValue() : null;
        if (file == null && !ev.isEmpty()) {
            file = ev.get(0).file();
            line = ev.get(0).line();
        }
        if (ev.isEmpty() && file != null) {
            ev = List.of(new Evidence(file, line, null));
        }
        return new Location(file, line, ev);
    }

    static Finding finding(Rule rule, Map<String, Object> item, String key) {
        Location loc = location(item);
        String severity = rule.severity();
        if (rule.severityField() != null && !rule.severityField().isEmpty()) {
            Object raw = item.get(rule.severityField());
            String value = Py.truthy(raw) ? Py.str(raw).toLowerCase(java.util.Locale.ROOT) : "";
            severity = Packs.SEVERITIES.contains(value) ? value : severity;
        }
        return new Finding(Fact.makeId("finding", rule.id(), key), loc.evidence(), rule.id(), Severity.of(severity), rule.category(),
                loc.file(), loc.line(), render(rule.message(), item), rule.remediation());
    }

    /**
     * Findings for fact rules ({@code collection.apply(name)} gives the items) and for text hits.
     */
    public static List<Finding> evaluate(List<Rule> rules, Function<String, List<Map<String, Object>>> collection, List<Hit> hits) {
        List<Finding> out = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule.match() == null) {
                continue;
            }
            for (Map<String, Object> item : collection.apply(rule.match().entity())) {
                if (matches(item, rule.match().where())) {
                    Object id = item.get("id");
                    String key = Py.truthy(id) ? Py.str(id)
                            : Py.repr(java.util.Arrays.asList(item.get("file"), item.get("line"), item.get("name")), true);
                    out.add(finding(rule, item, key));
                }
            }
        }
        Map<String, Rule> byId = new HashMap<>();
        for (Rule r : rules) {
            if (r.pattern() != null) {
                byId.put(r.id(), r);
            }
        }
        for (Hit h : hits) {
            Rule rule = byId.get(h.ruleId());
            if (rule != null) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("file", h.file());
                item.put("line", h.line());
                item.put("match", h.text());
                out.add(finding(rule, item, h.file() + ":" + h.line() + ":" + h.text()));
            }
        }
        return out;
    }

    /** Drop disabled rules, apply severity changes, de-duplicate by id, sort. */
    public static List<Finding> applyConfig(List<Finding> findings, Set<String> disabled, Map<String, String> severity) {
        Map<String, Finding> byId = new LinkedHashMap<>();
        for (Finding f : findings) {
            if (disabled.contains(f.ruleId())) {
                continue;
            }
            String sev = severity.get(f.ruleId());
            if (sev != null && !sev.isEmpty()) {
                f = new Finding(f.id(), f.evidence(), f.ruleId(), Severity.of(sev), f.category(), f.file(), f.line(), f.message(),
                        f.remediation());
            }
            byId.putIfAbsent(f.id(), f);
        }
        List<Finding> out = new ArrayList<>(byId.values());
        out.sort(Comparator.<Finding>comparingInt(f -> {
            int k = ORDER.indexOf(f.severity().value());
            return k < 0 ? 9 : k;
        }).thenComparing(Finding::ruleId, PyJson::compareCodePoints)
                .thenComparing(f -> f.file() == null ? "" : f.file(), PyJson::compareCodePoints)
                .thenComparingInt(f -> f.line() == null ? 0 : f.line()));
        return out;
    }

    /** A finding stored as a document (linker, analyzers) as a {@link Finding}. */
    public static Finding of(Fact f) {
        if (f instanceof Finding finding) {
            return finding;
        }
        Map<String, Object> m = f.toMap();
        Object line = m.get("line");
        return new Finding((String) m.get("id"), Evidence.fromMaps(m.get("evidence")), (String) m.get("rule_id"),
                Severity.of((String) m.get("severity")), (String) m.get("category"), (String) m.get("file"),
                line instanceof Number num ? num.intValue() : null, (String) m.get("message"), (String) m.get("remediation"));
    }
}
