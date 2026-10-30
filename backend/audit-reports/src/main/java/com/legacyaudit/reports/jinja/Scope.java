package com.legacyaudit.reports.jinja;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Names visible at one point of a template: a chain of frames (loop, macro, template, globals). */
final class Scope {

    final Scope parent;
    final Map<String, Object> vars;
    /** The environment that loads imported and included templates. */
    final Environment env;

    Scope(Scope parent, Map<String, Object> vars, Environment env) {
        this.parent = parent;
        this.vars = vars;
        this.env = env;
    }

    Scope child() {
        return new Scope(this, new HashMap<>(), env);
    }

    Object lookup(String name) {
        for (Scope s = this; s != null; s = s.parent) {
            if (s.vars.containsKey(name)) {
                return s.vars.get(name);
            }
        }
        return Values.UNDEFINED;
    }

    void set(String name, Object value) {
        vars.put(name, value);
    }
}

/** The {@code loop} variable of a for loop. */
final class Loop {
    final int index0;
    final int length;

    Loop(int index0, int length) {
        this.index0 = index0;
        this.length = length;
    }

    Object attr(String name) {
        return switch (name) {
            case "index" -> (long) index0 + 1;
            case "index0" -> (long) index0;
            case "revindex" -> (long) (length - index0);
            case "revindex0" -> (long) (length - index0 - 1);
            case "first" -> index0 == 0;
            case "last" -> index0 == length - 1;
            case "length" -> (long) length;
            case "cycle" -> (Runtime.Callable) (List<Object> args, Map<String, Object> kw) -> args.get(index0 % args.size());
            default -> Values.UNDEFINED;
        };
    }
}
