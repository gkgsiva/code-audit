package com.legacyaudit.engine.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule tables for the migration target in {@code audit.yaml > target.profile} (decision 161): each
 * pack's {@code data} may hold a {@code profiles: {<profile>: {key: value}}} block replacing
 * top-level keys, and any entry may hold {@code by_target: {<profile>[,<profile>...]: {field:
 * value} | skip}}. Finding texts carry {@code [[phrase]]} placeholders the target words.
 */
public final class Targets {

    public static final String NONE = "none";
    private static final Pattern PHRASE = Pattern.compile("\\[\\[([a-z0-9-]+)\\]\\]");
    private static final Pattern SPACES = Pattern.compile(" {2,}");
    private static final Object SKIP = new Object();

    private Targets() {}

    private static boolean applies(String key, String profile) {
        for (String k : key.split(",", -1)) {
            if (k.strip().equals(profile)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Object resolveNode(Object node, String profile) {
        if (node instanceof Map<?, ?> m) {
            Map<Object, Object> copy = new LinkedHashMap<>(m);
            Object by = copy.remove("by_target");
            if (by instanceof Map<?, ?> byMap) {
                for (var e : byMap.entrySet()) {
                    if (applies(String.valueOf(e.getKey()), profile)) {
                        if ("skip".equals(e.getValue())) {
                            return SKIP;
                        }
                        if (e.getValue() instanceof Map<?, ?> change) {
                            copy.putAll(change);
                        }
                    }
                }
            }
            Map<Object, Object> out = new LinkedHashMap<>();
            for (var e : copy.entrySet()) {
                Object v = resolveNode(e.getValue(), profile);
                if (v != SKIP) {
                    out.put(e.getKey(), v);
                }
            }
            return out;
        }
        if (node instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object x : l) {
                Object v = resolveNode(x, profile);
                if (v != SKIP) {
                    out.add(v);
                }
            }
            return out;
        }
        return node;
    }

    /** A pack's {@code data} for one target: its {@code profiles} block applied, then {@code by_target}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> resolve(Map<String, Object> data, String profile) {
        Map<String, Object> copy = new LinkedHashMap<>(data);
        Object profiles = copy.remove("profiles");
        if (profiles instanceof Map<?, ?> p) {
            for (var e : p.entrySet()) {
                if (applies(String.valueOf(e.getKey()), profile) && e.getValue() instanceof Map<?, ?> change) {
                    ((Map<Object, Object>) (Map<?, ?>) copy).putAll(change);
                }
            }
        }
        Object out = resolveNode(copy, profile);
        return out instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    /**
     * Replace {@code [[name]]} placeholders (written by analyzers and rules) with the target's
     * wording; an unknown name becomes empty. Doubled spaces left by an empty phrase go.
     */
    public static String fill(String text, Map<String, String> phrases) {
        if (text == null || text.isEmpty() || !text.contains("[[")) {
            return text;
        }
        Matcher m = PHRASE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(phrases.getOrDefault(m.group(1), "")));
        }
        m.appendTail(sb);
        return SPACES.matcher(sb).replaceAll(" ").replace(" .", ".").replace(" ;", ";").strip();
    }
}
