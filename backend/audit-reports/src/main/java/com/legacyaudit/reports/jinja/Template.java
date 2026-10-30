package com.legacyaudit.reports.jinja;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One parsed template: statements ({@code if for set macro block extends include import}) and output. */
public final class Template {

    final String name;
    final List<Stmt> body;
    final Environment env;

    /** Render state shared by a template and the ones it extends: the blocks in force. */
    static final class Ctx {
        final Map<String, List<Stmt>> blocks = new HashMap<>();
    }

    interface Stmt {
        void render(Scope s, StringBuilder out, Ctx ctx);
    }

    Template(String name, String source, Environment env) {
        this.name = name;
        this.env = env;
        Parser p = new Parser(Lexer.split(source, name), name);
        this.body = p.until();
        if (!p.done()) {
            throw new TemplateError(name + ": unexpected '{% " + p.peekTag() + " %}'");
        }
    }

    /** Renders with the given variables (on top of the environment's globals). */
    public String render(Map<String, Object> vars) {
        Scope root = new Scope(env.globalScope(), new HashMap<>(vars), env);
        StringBuilder out = new StringBuilder();
        renderTop(root, out, new Ctx());
        return out.toString();
    }

    /** The top level, following {@code extends}: this template's blocks override its parent's. */
    void renderTop(Scope s, StringBuilder out, Ctx ctx) {
        Extends ext = null;
        for (Stmt st : body) {
            if (st instanceof Extends e) {
                ext = e;
            }
        }
        collectBlocks(body, ctx);
        if (ext == null) {
            renderAll(body, s, out, ctx);
            return;
        }
        renderAll(body, s, new StringBuilder(), ctx); // imports, sets and macros only; output goes nowhere
        Template parent = env.get(Values.str(ext.parent.eval(s)));
        parent.renderTop(s, out, ctx);
    }

    private static void collectBlocks(List<Stmt> stmts, Ctx ctx) {
        for (Stmt st : stmts) {
            if (st instanceof Block b) {
                ctx.blocks.putIfAbsent(b.name, b.body); // the most derived template is collected first
                collectBlocks(b.body, ctx);
            }
        }
    }

    static void renderAll(List<Stmt> stmts, Scope s, StringBuilder out, Ctx ctx) {
        for (Stmt st : stmts) {
            st.render(s, out, ctx);
        }
    }

    // ---- statements ----------------------------------------------------------------------------

    record Text(String text) implements Stmt {
        public void render(Scope s, StringBuilder out, Ctx ctx) {
            out.append(text);
        }
    }

    record Print(Expr.Node expr, String where) implements Stmt {
        public void render(Scope s, StringBuilder out, Ctx ctx) {
            Object v;
            try {
                v = expr.eval(s);
            } catch (TemplateError e) {
                throw e.getMessage().startsWith(where) ? e : new TemplateError(where + ": " + e.getMessage());
            }
            if (v != Values.UNDEFINED) {
                out.append(Expr.esc(v));
            }
        }
    }

    record If(List<Expr.Node> conds, List<List<Stmt>> bodies, List<Stmt> otherwise) implements Stmt {
        public void render(Scope s, StringBuilder out, Ctx ctx) {
            for (int i = 0; i < conds.size(); i++) {
                if (Values.truthy(conds.get(i).eval(s))) {
                    renderAll(bodies.get(i), s, out, ctx);
                    return;
                }
            }
            if (otherwise != null) {
                renderAll(otherwise, s, out, ctx);
            }
        }
    }

    record For(List<String> targets, Expr.Node iter, Expr.Node filter, List<Stmt> body, List<Stmt> otherwise) implements Stmt {
        public void render(Scope s, StringBuilder out, Ctx ctx) {
            List<Object> items = Values.iterate(iter.eval(s));
            if (filter != null) {
                List<Object> kept = new ArrayList<>();
                Scope probe = s.child();
                for (Object item : items) {
                    bind(probe, item);
                    if (Values.truthy(filter.eval(probe))) {
                        kept.add(item);
                    }
                }
                items = kept;
            }
            if (items.isEmpty()) {
                if (otherwise != null) {
                    renderAll(otherwise, s, out, ctx);
                }
                return;
            }
            Scope loop = s.child();
            for (int i = 0; i < items.size(); i++) {
                bind(loop, items.get(i));
                loop.set("loop", new Loop(i, items.size()));
                renderAll(body, loop, out, ctx);
            }
        }

        private void bind(Scope scope, Object item) {
            if (targets.size() == 1) {
                scope.set(targets.get(0), item);
                return;
            }
            List<Object> parts = Values.iterate(item);
            if (parts.size() != targets.size()) {
                throw new TemplateError("cannot unpack " + parts.size() + " values into " + targets.size());
            }
            for (int k = 0; k < targets.size(); k++) {
                scope.set(targets.get(k), parts.get(k));
            }
        }
    }

    record Set(List<String> targets, Expr.Node value) implements Stmt {
        public void render(Scope s, StringBuilder out, Ctx ctx) {
            Object v = value.eval(s);
            if (targets.size() == 1) {
                s.set(targets.get(0), v);
                return;
            }
            List<Object> parts = Values.iterate(v);
            for (int k = 0; k < targets.size(); k++) {
                s.set(targets.get(k), parts.get(k));
            }
        }
    }

    record Macro(String name, List<String> params, List<Expr.Node> defaults, List<Stmt> body) implements Stmt {
        public void render(Scope s, StringBuilder out, Ctx ctx) {
            Scope closure = s;
            s.set(name, (Runtime.Callable) (args, kwargs) -> {
                Scope call = closure.child();
                for (int i = 0; i < params.size(); i++) {
                    String p = params.get(i);
                    Object v;
                    if (i < args.size()) {
                        v = args.get(i);
                    } else if (kwargs.containsKey(p)) {
                        v = kwargs.get(p);
                    } else if (defaults.get(i) != null) {
                        v = defaults.get(i).eval(closure);
                    } else {
                        v = Values.UNDEFINED;
                    }
                    call.set(p, v);
                }
                StringBuilder sb = new StringBuilder();
                renderAll(body, call, sb, new Ctx());
                return new Markup(sb.toString());
            });
        }
    }

    record Block(String name, List<Stmt> body) implements Stmt {
        public void render(Scope s, StringBuilder out, Ctx ctx) {
            renderAll(ctx.blocks.getOrDefault(name, body), s.child(), out, ctx);
        }
    }

    record Extends(Expr.Node parent) implements Stmt {
        public void render(Scope s, StringBuilder out, Ctx ctx) {
            // handled by renderTop
        }
    }

    record Include(Expr.Node template) implements Stmt {
        public void render(Scope s, StringBuilder out, Ctx ctx) {
            Template t = s.env.get(Values.str(template.eval(s)));
            t.renderTop(s.child(), out, new Ctx());
        }
    }

    record Import(Expr.Node template, String alias, boolean withContext) implements Stmt {
        public void render(Scope s, StringBuilder out, Ctx ctx) {
            Template t = s.env.get(Values.str(template.eval(s)));
            Scope module = new Scope(withContext ? s : s.env.globalScope(), new LinkedHashMap<>(), s.env);
            t.renderTop(module, new StringBuilder(), new Ctx());
            s.set(alias, new Runtime.Module(module.vars));
        }
    }

    // ---- parser --------------------------------------------------------------------------------

    private static final class Parser {
        private final List<Lexer.Part> parts;
        private final String name;
        private int i;

        Parser(List<Lexer.Part> parts, String name) {
            this.parts = parts;
            this.name = name;
        }

        boolean done() {
            return i >= parts.size();
        }

        String peekTag() {
            return parts.get(i).text();
        }

        private String keyword(Lexer.Part p) {
            String t = p.text();
            int sp = 0;
            while (sp < t.length() && (Character.isLetterOrDigit(t.charAt(sp)) || t.charAt(sp) == '_')) {
                sp++;
            }
            return t.substring(0, sp);
        }

        /** Statements until one of the end keywords (left unread), or the end. */
        List<Stmt> until(String... ends) {
            List<Stmt> out = new ArrayList<>();
            while (i < parts.size()) {
                Lexer.Part p = parts.get(i);
                String where = name + ":" + p.line();
                switch (p.kind()) {
                    case TEXT -> {
                        out.add(new Text(p.text()));
                        i++;
                    }
                    case VAR -> {
                        out.add(new Print(expr(p.text(), where), where));
                        i++;
                    }
                    default -> {
                        String kw = keyword(p);
                        for (String e : ends) {
                            if (e.equals(kw)) {
                                return out;
                            }
                        }
                        i++;
                        try {
                            out.add(statement(kw, p.text().substring(kw.length()).strip()));
                        } catch (TemplateError e) {
                            throw e.getMessage().startsWith(name) ? e : new TemplateError(where + ": " + e.getMessage());
                        }
                    }
                }
            }
            if (ends.length > 0) {
                throw new TemplateError(name + ": missing {% " + ends[ends.length - 1] + " %}");
            }
            return out;
        }

        private Expr.Node expr(String src, String where) {
            try {
                Expr.Parser ep = new Expr.Parser(src);
                Expr.Node n = ep.tuple(true);
                if (!ep.atEnd()) {
                    throw new TemplateError("unexpected '" + ep.peek().text() + "'");
                }
                return n;
            } catch (TemplateError e) {
                throw new TemplateError(where + ": " + e.getMessage());
            }
        }

        private String end() {
            String kw = keyword(parts.get(i));
            i++;
            return kw;
        }

        private Stmt statement(String kw, String rest) {
            switch (kw) {
                case "if": {
                    List<Expr.Node> conds = new ArrayList<>();
                    List<List<Stmt>> bodies = new ArrayList<>();
                    List<Stmt> otherwise = null;
                    conds.add(whole(rest));
                    bodies.add(until("elif", "else", "endif"));
                    while (true) {
                        Lexer.Part p = parts.get(i);
                        String k = end();
                        if (k.equals("elif")) {
                            conds.add(whole(p.text().substring(4).strip()));
                            bodies.add(until("elif", "else", "endif"));
                        } else if (k.equals("else")) {
                            otherwise = until("endif");
                        } else {
                            break;
                        }
                    }
                    return new If(conds, bodies, otherwise);
                }
                case "for": {
                    Expr.Parser ep = new Expr.Parser(rest);
                    List<String> targets = new ArrayList<>();
                    boolean paren = ep.accept("(");
                    do {
                        targets.add(ep.name());
                    } while (ep.accept(","));
                    if (paren) {
                        ep.expect(")");
                    }
                    ep.expect("in");
                    Expr.Node iter = ep.or(); // "if" here filters the loop, it is not a conditional expression
                    Expr.Node filter = ep.accept("if") ? ep.expr() : null;
                    ep.accept("recursive");
                    List<Stmt> body = until("else", "endfor");
                    List<Stmt> otherwise = null;
                    if (end().equals("else")) {
                        otherwise = until("endfor");
                        end();
                    }
                    return new For(targets, iter, filter, body, otherwise);
                }
                case "set": {
                    Expr.Parser ep = new Expr.Parser(rest);
                    List<String> targets = new ArrayList<>();
                    do {
                        targets.add(ep.name());
                    } while (ep.accept(","));
                    ep.expect("=");
                    Expr.Node v = ep.tuple(true);
                    return new Set(targets, v);
                }
                case "macro": {
                    Expr.Parser ep = new Expr.Parser(rest);
                    String mname = ep.name();
                    ep.expect("(");
                    List<String> params = new ArrayList<>();
                    List<Expr.Node> defaults = new ArrayList<>();
                    while (!ep.is(")")) {
                        params.add(ep.name());
                        defaults.add(ep.accept("=") ? ep.expr() : null);
                        if (!ep.accept(",")) {
                            break;
                        }
                    }
                    ep.expect(")");
                    List<Stmt> body = until("endmacro");
                    end();
                    return new Macro(mname, params, defaults, body);
                }
                case "block": {
                    String bname = rest.strip();
                    List<Stmt> body = until("endblock");
                    end();
                    return new Block(bname, body);
                }
                case "extends":
                    return new Extends(whole(rest));
                case "include":
                    return new Include(whole(rest.replaceAll("\\s+(with|without)\\s+context$", "")));
                case "import": {
                    Expr.Parser ep = new Expr.Parser(rest);
                    Expr.Node t = ep.expr();
                    ep.expect("as");
                    String alias = ep.name();
                    boolean with = false;
                    if (ep.accept("with")) {
                        ep.expect("context");
                        with = true;
                    } else if (ep.accept("without")) {
                        ep.expect("context");
                    }
                    return new Import(t, alias, with);
                }
                default:
                    throw new TemplateError("unknown tag '" + kw + "'");
            }
        }

        private Expr.Node whole(String src) {
            Expr.Parser ep = new Expr.Parser(src);
            Expr.Node n = ep.tuple(true);
            if (!ep.atEnd()) {
                throw new TemplateError("unexpected '" + ep.peek().text() + "'");
            }
            return n;
        }
    }
}
