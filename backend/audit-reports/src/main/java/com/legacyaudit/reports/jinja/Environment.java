package com.legacyaudit.reports.jinja;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A small Jinja2 for the report templates: autoescape, {@code trim_blocks}, {@code lstrip_blocks}
 * and undefined values that print as nothing (Jinja's {@code ChainableUndefined}), as the Python
 * reports configured it. Templates are loaded from a folder on the class path.
 */
public final class Environment {

    private final String folder;
    private final Map<String, Template> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> globals = new HashMap<>();
    private final Map<String, Object> filters = new HashMap<>();

    /** {@code folder}: class-path folder of the templates, such as {@code com/legacyaudit/reports/tech_html}. */
    public Environment(String folder) {
        this.folder = folder.endsWith("/") ? folder : folder + "/";
        global("range", Environment::range);
    }

    /** Jinja's default {@code range(stop)} / {@code range(start, stop[, step])}. */
    private static Object range(List<Object> args) {
        long start = 0;
        long stop;
        long step = 1;
        if (args.size() == 1) {
            stop = Values.asLong(args.get(0));
        } else {
            start = Values.asLong(args.get(0));
            stop = Values.asLong(args.get(1));
            if (args.size() > 2) {
                step = Values.asLong(args.get(2));
            }
        }
        if (step == 0) {
            throw new TemplateError("range() arg 3 must not be zero");
        }
        List<Object> out = new java.util.ArrayList<>();
        for (long i = start; step > 0 ? i < stop : i > stop; i += step) {
            out.add(i);
        }
        return out;
    }

    /** A global function ({@code icon(name)}): positional arguments in, a value out. */
    public Environment global(String name, Function<List<Object>, Object> fn) {
        globals.put(name, (Runtime.Callable) (args, kw) -> fn.apply(args));
        return this;
    }

    /** A global value. */
    public Environment global(String name, Object value) {
        globals.put(name, value);
        return this;
    }

    /** An extra filter: the value first, then the filter's arguments. */
    public Environment filter(String name, Function<List<Object>, Object> fn) {
        filters.put(name, (Runtime.Callable) (args, kw) -> fn.apply(args));
        return this;
    }

    Object filter(String name) {
        return filters.get(name);
    }

    Scope globalScope() {
        return new Scope(null, globals, this);
    }

    public Template get(String name) {
        return cache.computeIfAbsent(name, n -> {
            try (InputStream in = Environment.class.getClassLoader().getResourceAsStream(folder + n)) {
                if (in == null) {
                    throw new TemplateError("template not found: " + folder + n);
                }
                return new Template(n, new String(in.readAllBytes(), StandardCharsets.UTF_8), this);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** A template from text (tests). */
    public Template fromString(String source) {
        return new Template("<string>", source, this);
    }

    public String render(String name, Map<String, Object> vars) {
        return get(name).render(vars);
    }
}
