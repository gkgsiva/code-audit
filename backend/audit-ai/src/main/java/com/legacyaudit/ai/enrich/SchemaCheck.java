package com.legacyaudit.ai.enrich;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks a stage file against its JSON Schema the way the Python tool's pydantic models did, with
 * the same messages and locations ({@code risks.0.severity: Input should be 'critical', 'high',
 * 'medium' or 'low'}).
 *
 * <p>The schemas are the ones pydantic generated ({@code legacyaudit/ai/prompt-schemas/}, in field
 * order). Values are accepted as pydantic's lax mode accepts them: {@code "3"} and {@code 3.0} for
 * an integer, {@code "yes"} or {@code 1} for a boolean. Every object whose schema has both
 * {@code evidence} and {@code assumption} is a claim: once its fields are valid it must cite
 * evidence or say {@code assumption: true}.
 */
public final class SchemaCheck {

    /** One schema error: dotted location ({@code risks.0.severity}) and pydantic's message. */
    public record Error(String loc, String msg) {}

    static final String CLAIM_ERROR = "needs evidence [{file, line}] or assumption: true";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, Map<String, Object>> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> TRUE_WORDS = Set.of("1", "on", "t", "true", "y", "yes");
    private static final Set<String> FALSE_WORDS = Set.of("0", "off", "f", "false", "n", "no");
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private final Map<String, Object> root;
    private final Map<String, Object> defs;
    private final List<Error> errors = new ArrayList<>();

    private SchemaCheck(Map<String, Object> root) {
        this.root = root;
        this.defs = root.get("$defs") instanceof Map<?, ?> d ? cast(d) : Map.of();
    }

    /** The schema of one stage, in pydantic's field order (as the prompts show it). */
    public static Map<String, Object> schema(String stage) {
        return CACHE.computeIfAbsent(stage, s -> {
            String res = "/legacyaudit/ai/prompt-schemas/" + s + ".json";
            try (InputStream in = SchemaCheck.class.getResourceAsStream(res)) {
                if (in == null) {
                    throw new IllegalArgumentException("No schema for stage " + s);
                }
                return cast(JSON.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), Map.class));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** The schema text sent to a model: {@code json.dumps(schema, separators=(",", ":"))}. */
    public static String schemaText(String stage) {
        String res = "/legacyaudit/ai/prompt-schemas/" + stage + ".json";
        try (InputStream in = SchemaCheck.class.getResourceAsStream(res)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Errors of {@code data} against the stage's schema, in pydantic's order (empty when valid). */
    public static List<Error> check(String stage, Object data) {
        SchemaCheck c = new SchemaCheck(schema(stage));
        c.model(c.root, String.valueOf(c.root.get("title")), data, "");
        return c.errors;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static String join(String loc, Object part) {
        return loc.isEmpty() ? String.valueOf(part) : loc + "." + part;
    }

    private void add(String loc, String msg) {
        errors.add(new Error(loc, msg));
    }

    /** One value against one schema node; returns whether it was valid. */
    private boolean value(Map<String, Object> node, Object v, String loc) {
        if (node.get("$ref") instanceof String ref) {
            String name = ref.substring(ref.lastIndexOf('/') + 1);
            return model(cast((Map<?, ?>) defs.get(name)), name, v, loc);
        }
        if (node.get("anyOf") instanceof List<?> options) {
            return anyOf(options, v, loc);
        }
        if (node.get("enum") instanceof List<?> allowed) {
            if (v instanceof String s && allowed.contains(s)) {
                return true;
            }
            add(loc, "Input should be " + literals(allowed));
            return false;
        }
        String type = String.valueOf(node.get("type"));
        return switch (type) {
            case "string" -> {
                if (v instanceof String) {
                    yield true;
                }
                add(loc, "Input should be a valid string");
                yield false;
            }
            case "integer" -> integer(node, v, loc);
            case "number" -> number(v, loc);
            case "boolean" -> bool(v, loc);
            case "array" -> {
                if (!(v instanceof List<?> items)) {
                    add(loc, "Input should be a valid list");
                    yield false;
                }
                Map<String, Object> each = node.get("items") instanceof Map<?, ?> m ? cast(m) : Map.of();
                boolean ok = true;
                for (int i = 0; i < items.size(); i++) {
                    ok &= each.isEmpty() || value(each, items.get(i), join(loc, i));
                }
                yield ok;
            }
            case "object" -> {
                if (!(v instanceof Map<?, ?> map)) {
                    add(loc, "Input should be a valid dictionary");
                    yield false;
                }
                Map<String, Object> each = node.get("additionalProperties") instanceof Map<?, ?> m ? cast(m) : Map.of();
                boolean ok = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    ok &= each.isEmpty() || value(each, e.getValue(), join(loc, e.getKey()));
                }
                yield ok;
            }
            case "null" -> {
                if (v == null) {
                    yield true;
                }
                add(loc, "Input should be None");
                yield false;
            }
            default -> true;
        };
    }

    /**
     * {@code X | None} is one nullable check (errors without a member suffix); other unions try
     * each member and, when none fits, report every member's error ({@code value.float}, ...).
     */
    private boolean anyOf(List<?> options, Object v, String loc) {
        List<Map<String, Object>> members = new ArrayList<>();
        boolean nullable = false;
        for (Object o : options) {
            Map<String, Object> m = cast((Map<?, ?>) o);
            if ("null".equals(m.get("type"))) {
                nullable = true;
            } else {
                members.add(m);
            }
        }
        if (nullable && v == null) {
            return true;
        }
        if (members.size() == 1) {
            return value(members.get(0), v, loc);
        }
        // smart mode: an exact type match wins, then the first member that accepts the value
        for (Map<String, Object> m : members) {
            if (exact(m, v)) {
                return true;
            }
        }
        List<Error> collected = new ArrayList<>();
        for (Map<String, Object> m : members) {
            SchemaCheck sub = new SchemaCheck(root);
            if (sub.value(m, v, join(loc, memberName(m)))) {
                return true;
            }
            collected.addAll(sub.errors);
        }
        errors.addAll(collected);
        return false;
    }

    private static boolean exact(Map<String, Object> m, Object v) {
        return switch (String.valueOf(m.get("type"))) {
            case "string" -> v instanceof String;
            case "integer" -> isInt(v);
            case "number" -> v instanceof Double || v instanceof Float || v instanceof BigDecimal;
            case "boolean" -> v instanceof Boolean;
            default -> false;
        };
    }

    private static String memberName(Map<String, Object> m) {
        return switch (String.valueOf(m.get("type"))) {
            case "string" -> "str";
            case "integer" -> "int";
            case "number" -> "float";
            case "boolean" -> "bool";
            default -> String.valueOf(m.get("type"));
        };
    }

    private static boolean isInt(Object v) {
        return v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof BigInteger;
    }

    private static String literals(List<?> allowed) {
        List<String> quoted = new ArrayList<>();
        for (Object a : allowed) {
            quoted.add("'" + a + "'");
        }
        if (quoted.size() == 1) {
            return quoted.get(0);
        }
        return String.join(", ", quoted.subList(0, quoted.size() - 1)) + " or " + quoted.get(quoted.size() - 1);
    }

    private boolean integer(Map<String, Object> node, Object v, String loc) {
        BigInteger n = null;
        if (v instanceof Boolean b) {
            n = b ? BigInteger.ONE : BigInteger.ZERO;
        } else if (isInt(v)) {
            n = new BigInteger(v.toString());
        } else if (v instanceof Double || v instanceof Float || v instanceof BigDecimal) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                add(loc, "Input should be a finite number");
                return false;
            }
            if (d != Math.rint(d)) {
                add(loc, "Input should be a valid integer, got a number with a fractional part");
                return false;
            }
            n = new BigDecimal(d).toBigInteger();
            if (n.compareTo(LONG_MIN) < 0 || n.compareTo(LONG_MAX) > 0) {
                add(loc, "Unable to parse input string as an integer, exceeded maximum size");
                return false;
            }
        } else if (v instanceof String s) {
            n = parseInt(s.strip());
            if (n == null) {
                add(loc, "Input should be a valid integer, unable to parse string as an integer");
                return false;
            }
        } else {
            add(loc, "Input should be a valid integer");
            return false;
        }
        if (node.get("minimum") instanceof Number min && n.compareTo(BigInteger.valueOf(min.longValue())) < 0) {
            add(loc, "Input should be greater than or equal to " + min);
            return false;
        }
        if (node.get("maximum") instanceof Number max && n.compareTo(BigInteger.valueOf(max.longValue())) > 0) {
            add(loc, "Input should be less than or equal to " + max);
            return false;
        }
        return true;
    }

    /** {@code "3"}, {@code "-3"}, {@code "3.0"}: an integer; anything else is not. */
    private static BigInteger parseInt(String s) {
        if (s.matches("[+-]?\\d+(_\\d+)*")) {
            return new BigInteger(s.replace("_", ""));
        }
        if (s.matches("[+-]?\\d+\\.0*")) {
            return new BigInteger(s.substring(0, s.indexOf('.')));
        }
        return null;
    }

    private boolean number(Object v, String loc) {
        if (v instanceof Number || v instanceof Boolean) {
            return true;
        }
        if (v instanceof String s) {
            String t = s.strip().toLowerCase(java.util.Locale.ROOT);
            if (t.matches("[+-]?(\\d+(_\\d+)*(\\.\\d*)?|\\.\\d+)(e[+-]?\\d+)?|[+-]?(nan|inf|infinity)")) {
                return true;
            }
            add(loc, "Input should be a valid number, unable to parse string as a number");
            return false;
        }
        add(loc, "Input should be a valid number");
        return false;
    }

    private boolean bool(Object v, String loc) {
        if (v instanceof Boolean) {
            return true;
        }
        if (isInt(v)) {
            long n = ((Number) v).longValue();
            if (n == 0 || n == 1) {
                return true;
            }
            add(loc, "Input should be a valid boolean, unable to interpret input");
            return false;
        }
        if (v instanceof Double d) {
            if (d == 0 || d == 1) {
                return true;
            }
            add(loc, "Input should be a valid boolean");
            return false;
        }
        if (v instanceof String s) {
            String t = s.toLowerCase(java.util.Locale.ROOT);
            if (TRUE_WORDS.contains(t) || FALSE_WORDS.contains(t)) {
                return true;
            }
            add(loc, "Input should be a valid boolean, unable to interpret input");
            return false;
        }
        add(loc, "Input should be a valid boolean");
        return false;
    }

    /** An object of a model: fields in order, then extra keys, then the claim rule. */
    private boolean model(Map<String, Object> schema, String name, Object v, String loc) {
        if (!(v instanceof Map<?, ?> map)) {
            add(loc, "Input should be a valid dictionary or instance of " + name);
            return false;
        }
        Map<String, Object> props = schema.get("properties") instanceof Map<?, ?> p ? cast(p) : new LinkedHashMap<>();
        List<?> required = schema.get("required") instanceof List<?> r ? r : List.of();
        boolean ok = true;
        for (Map.Entry<String, Object> e : props.entrySet()) {
            String field = e.getKey();
            if (!map.containsKey(field)) {
                if (required.contains(field)) {
                    add(join(loc, field), "Field required");
                    ok = false;
                }
                continue;
            }
            ok &= value(cast((Map<?, ?>) e.getValue()), map.get(field), join(loc, field));
        }
        if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
            for (Object key : map.keySet()) {
                if (!props.containsKey(String.valueOf(key))) {
                    add(join(loc, key), "Extra inputs are not permitted");
                    ok = false;
                }
            }
        }
        if (ok && props.containsKey("evidence") && props.containsKey("assumption")) {
            Object ev = map.get("evidence");
            boolean cited = ev instanceof List<?> l && !l.isEmpty();
            if (!cited && !truthy(map.get("assumption"))) {
                add(loc, CLAIM_ERROR);
                return false;
            }
        }
        return ok;
    }

    /** A lax boolean's value (the field is already known to be valid). */
    private static boolean truthy(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        return v instanceof String s && TRUE_WORDS.contains(s.toLowerCase(java.util.Locale.ROOT));
    }
}
