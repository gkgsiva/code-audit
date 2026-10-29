package com.legacyaudit.engine.pg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A node of a parsed statement: its kind (select, column, func, ...) and named parts. */
final class Node {

    final String kind;
    private final Map<String, Object> parts = new HashMap<>();

    Node(String kind) {
        this.kind = kind;
    }

    static Node of(String kind, Object... keyValues) {
        Node n = new Node(kind);
        for (int i = 0; i < keyValues.length; i += 2) {
            n.set((String) keyValues[i], keyValues[i + 1]);
        }
        return n;
    }

    Node set(String key, Object value) {
        if (value == null) {
            parts.remove(key);
        } else {
            parts.put(key, value);
        }
        return this;
    }

    boolean is(String k) {
        return kind.equals(k);
    }

    boolean has(String key) {
        return parts.containsKey(key);
    }

    Object get(String key) {
        return parts.get(key);
    }

    Node n(String key) {
        return (Node) parts.get(key);
    }

    @SuppressWarnings("unchecked")
    List<Node> l(String key) {
        Object v = parts.get(key);
        return v == null ? new ArrayList<>() : (List<Node>) v;
    }

    String s(String key) {
        Object v = parts.get(key);
        return v == null ? null : v.toString();
    }

    boolean b(String key) {
        return Boolean.TRUE.equals(parts.get(key));
    }

    static Node id(String name, boolean quoted) {
        return of("id", "name", name, "quoted", quoted);
    }

    static Node raw(String text) {
        return of("raw", "text", text);
    }

    @Override
    public String toString() {
        return kind + parts;
    }
}
