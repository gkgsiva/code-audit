package com.legacyaudit.reports.jinja;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Jinja expressions: parsing and evaluation with Python semantics. */
final class Expr {

    private Expr() {}

    /** An expression node. */
    interface Node {
        Object eval(Scope s);
    }

    record Tok(String kind, String text) {}

    // ---- tokens --------------------------------------------------------------------------------

    static List<Tok> tokens(String src) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(src.charAt(j)) || src.charAt(j) == '_')) {
                    j++;
                }
                out.add(new Tok("name", src.substring(i, j)));
                i = j;
            } else if (Character.isDigit(c)) {
                int j = i;
                boolean dot = false;
                while (j < n && (Character.isDigit(src.charAt(j)) || src.charAt(j) == '_'
                        || (src.charAt(j) == '.' && !dot && j + 1 < n && Character.isDigit(src.charAt(j + 1))))) {
                    dot |= src.charAt(j) == '.';
                    j++;
                }
                if (j < n && (src.charAt(j) == 'e' || src.charAt(j) == 'E')) {
                    dot = true;
                    j++;
                    if (j < n && (src.charAt(j) == '+' || src.charAt(j) == '-')) {
                        j++;
                    }
                    while (j < n && Character.isDigit(src.charAt(j))) {
                        j++;
                    }
                }
                out.add(new Tok(dot ? "float" : "int", src.substring(i, j).replace("_", "")));
                i = j;
            } else if (c == '\'' || c == '"') {
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                while (j < n && src.charAt(j) != c) {
                    char d = src.charAt(j);
                    if (d == '\\' && j + 1 < n) {
                        char e = src.charAt(++j);
                        switch (e) {
                            case 'n' -> sb.append('\n');
                            case 't' -> sb.append('\t');
                            case 'r' -> sb.append('\r');
                            case '\\' -> sb.append('\\');
                            case '\'' -> sb.append('\'');
                            case '"' -> sb.append('"');
                            case 'u' -> {
                                sb.append((char) Integer.parseInt(src.substring(j + 1, j + 5), 16));
                                j += 4;
                            }
                            default -> sb.append('\\').append(e);
                        }
                    } else {
                        sb.append(d);
                    }
                    j++;
                }
                out.add(new Tok("str", sb.toString()));
                i = j + 1;
            } else {
                String two = i + 1 < n ? src.substring(i, i + 2) : "";
                if (List.of("==", "!=", "<=", ">=", "//", "**").contains(two)) {
                    out.add(new Tok("op", two));
                    i += 2;
                } else {
                    out.add(new Tok("op", String.valueOf(c)));
                    i++;
                }
            }
        }
        out.add(new Tok("eof", ""));
        return out;
    }

    // ---- parser --------------------------------------------------------------------------------

    static final class Parser {
        final List<Tok> toks;
        int p;

        Parser(String src) {
            this.toks = tokens(src);
        }

        Tok peek() {
            return toks.get(p);
        }

        Tok peek(int k) {
            return toks.get(Math.min(p + k, toks.size() - 1));
        }

        boolean is(String text) {
            Tok t = peek();
            return (t.kind().equals("op") || t.kind().equals("name")) && t.text().equals(text);
        }

        boolean accept(String text) {
            if (is(text)) {
                p++;
                return true;
            }
            return false;
        }

        void expect(String text) {
            if (!accept(text)) {
                throw new TemplateError("expected '" + text + "', got '" + peek().text() + "'");
            }
        }

        String name() {
            Tok t = peek();
            if (!t.kind().equals("name")) {
                throw new TemplateError("expected a name, got '" + t.text() + "'");
            }
            p++;
            return t.text();
        }

        boolean atEnd() {
            return peek().kind().equals("eof");
        }

        /** A full expression, with a bare tuple ({@code a, b}) when {@code tuples}. */
        Node tuple(boolean tuples) {
            Node first = expr();
            if (!tuples || !is(",")) {
                return first;
            }
            List<Node> items = new ArrayList<>(List.of(first));
            while (accept(",")) {
                if (atEnd() || is(")") || is("%")) {
                    break;
                }
                items.add(expr());
            }
            return s -> new Values.Tuple(items.stream().map(x -> x.eval(s)).toList());
        }

        Node expr() {
            Node body = or();
            if (is("if")) {
                p++;
                Node cond = or();
                Node other = accept("else") ? expr() : null;
                return s -> Values.truthy(cond.eval(s)) ? body.eval(s) : other == null ? Values.UNDEFINED : other.eval(s);
            }
            return body;
        }

        Node or() {
            Node left = and();
            while (accept("or")) {
                Node l = left;
                Node r = and();
                left = s -> {
                    Object v = l.eval(s);
                    return Values.truthy(v) ? v : r.eval(s);
                };
            }
            return left;
        }

        Node and() {
            Node left = not();
            while (accept("and")) {
                Node l = left;
                Node r = not();
                left = s -> {
                    Object v = l.eval(s);
                    return !Values.truthy(v) ? v : r.eval(s);
                };
            }
            return left;
        }

        Node not() {
            if (accept("not")) {
                Node x = not();
                return s -> !Values.truthy(x.eval(s));
            }
            return compare();
        }

        Node compare() {
            Node left = concat();
            while (true) {
                String op;
                if (is("==") || is("!=") || is("<") || is(">") || is("<=") || is(">=") || is("in")) {
                    op = peek().text();
                    p++;
                } else if (is("not") && peek(1).text().equals("in")) {
                    p += 2;
                    op = "not in";
                } else if (is("is")) {
                    p++;
                    boolean neg = accept("not");
                    String test = name();
                    List<Node> args = new ArrayList<>();
                    if (accept("(")) {
                        if (!is(")")) {
                            do {
                                args.add(expr());
                            } while (accept(","));
                        }
                        expect(")");
                    } else if (!atEnd() && startsOperand()) {
                        args.add(concat());
                    }
                    Node l = left;
                    left = s -> {
                        List<Object> a = new ArrayList<>();
                        args.forEach(x -> a.add(x.eval(s)));
                        boolean r = Filters.test(test, l.eval(s), a);
                        return neg != r;
                    };
                    continue;
                } else {
                    return left;
                }
                Node l = left;
                Node r = concat();
                String o = op;
                left = s -> {
                    Object a = l.eval(s);
                    Object b = r.eval(s);
                    return switch (o) {
                        case "==" -> Values.eq(a, b);
                        case "!=" -> !Values.eq(a, b);
                        case "<" -> Values.compare(a, b) < 0;
                        case ">" -> Values.compare(a, b) > 0;
                        case "<=" -> Values.compare(a, b) <= 0;
                        case ">=" -> Values.compare(a, b) >= 0;
                        case "in" -> Values.in(a, b);
                        default -> !Values.in(a, b);
                    };
                };
            }
        }

        private boolean startsOperand() {
            Tok t = peek();
            return t.kind().equals("str") || t.kind().equals("int") || t.kind().equals("float")
                    || t.kind().equals("name") && !List.of("and", "or", "else", "if", "in", "is", "not").contains(t.text());
        }

        Node concat() {
            Node left = add();
            while (accept("~")) {
                Node l = left;
                Node r = add();
                left = s -> {
                    Object a = l.eval(s);
                    Object b = r.eval(s);
                    if (a instanceof Markup || b instanceof Markup) {
                        return new Markup(esc(a) + esc(b));
                    }
                    return Values.str(a) + Values.str(b);
                };
            }
            return left;
        }

        Node add() {
            Node left = mul();
            while (is("+") || is("-")) {
                String op = peek().text();
                p++;
                Node l = left;
                Node r = mul();
                left = s -> Values.arith(op, l.eval(s), r.eval(s));
            }
            return left;
        }

        Node mul() {
            Node left = unary();
            while (is("*") || is("/") || is("//") || is("%")) {
                String op = peek().text();
                p++;
                Node l = left;
                Node r = unary();
                left = s -> Values.arith(op, l.eval(s), r.eval(s));
            }
            return left;
        }

        Node unary() {
            if (accept("-")) {
                Node x = unary();
                return s -> Values.arith("-", 0L, x.eval(s));
            }
            if (accept("+")) {
                return unary();
            }
            return pow();
        }

        Node pow() {
            Node left = filtered();
            if (accept("**")) {
                Node l = left;
                Node r = unary();
                return s -> Values.arith("**", l.eval(s), r.eval(s));
            }
            return left;
        }

        /** A primary with its postfixes, then {@code |filter} chains. */
        Node filtered() {
            Node node = postfix(primary());
            while (accept("|")) {
                String name = name();
                while (accept(".")) {
                    name += "." + name();
                }
                Args args = is("(") ? args() : new Args(List.of(), Map.of());
                Node target = node;
                String f = name;
                node = s -> Filters.apply(f, target.eval(s), args.positional(s), args.keywords(s), s);
            }
            return node;
        }

        record Args(List<Node> pos, Map<String, Node> kw) {
            List<Object> positional(Scope s) {
                List<Object> out = new ArrayList<>();
                pos.forEach(x -> out.add(x.eval(s)));
                return out;
            }

            Map<String, Object> keywords(Scope s) {
                Map<String, Object> out = new LinkedHashMap<>();
                kw.forEach((k, v) -> out.put(k, v.eval(s)));
                return out;
            }
        }

        Args args() {
            expect("(");
            List<Node> pos = new ArrayList<>();
            Map<String, Node> kw = new LinkedHashMap<>();
            while (!is(")")) {
                if (peek().kind().equals("name") && peek(1).text().equals("=") && !peek(1).kind().equals("str")) {
                    String k = name();
                    p++;
                    kw.put(k, expr());
                } else {
                    pos.add(expr());
                }
                if (!accept(",")) {
                    break;
                }
            }
            expect(")");
            return new Args(pos, kw);
        }

        Node postfix(Node node) {
            while (true) {
                if (accept(".")) {
                    Tok t = peek();
                    p++;
                    String attr = t.text();
                    Node target = node;
                    if (t.kind().equals("int")) {
                        long idx = Long.parseLong(attr);
                        node = s -> Runtime.getItem(target.eval(s), idx);
                    } else {
                        node = s -> Runtime.getAttr(target.eval(s), attr);
                    }
                } else if (is("[")) {
                    p++;
                    Node target = node;
                    Node a = null;
                    Node b = null;
                    Node c = null;
                    boolean slice = false;
                    if (!is(":")) {
                        a = expr();
                    }
                    if (accept(":")) {
                        slice = true;
                        if (!is(":") && !is("]")) {
                            b = expr();
                        }
                        if (accept(":") && !is("]")) {
                            c = expr();
                        }
                    }
                    expect("]");
                    if (slice) {
                        Node x = a;
                        Node y = b;
                        Node z = c;
                        node = s -> Runtime.slice(target.eval(s), x == null ? null : x.eval(s), y == null ? null : y.eval(s),
                                z == null ? null : z.eval(s));
                    } else {
                        Node k = a;
                        node = s -> Runtime.getItem(target.eval(s), k.eval(s));
                    }
                } else if (is("(")) {
                    Args args = args();
                    Node target = node;
                    node = s -> Runtime.call(target.eval(s), args.positional(s), args.keywords(s));
                } else {
                    return node;
                }
            }
        }

        Node primary() {
            Tok t = peek();
            p++;
            switch (t.kind()) {
                case "str": {
                    StringBuilder sb = new StringBuilder(t.text());
                    while (peek().kind().equals("str")) { // adjacent literals join
                        sb.append(peek().text());
                        p++;
                    }
                    String v = sb.toString();
                    return s -> v;
                }
                case "int": {
                    Long v = Long.parseLong(t.text());
                    return s -> v;
                }
                case "float": {
                    Double v = Double.parseDouble(t.text());
                    return s -> v;
                }
                case "name": {
                    switch (t.text()) {
                        case "true", "True":
                            return s -> Boolean.TRUE;
                        case "false", "False":
                            return s -> Boolean.FALSE;
                        case "none", "None":
                            return s -> null;
                        default:
                            String name = t.text();
                            return s -> s.lookup(name);
                    }
                }
                default:
                    break;
            }
            switch (t.text()) {
                case "(": {
                    if (accept(")")) {
                        return s -> new Values.Tuple(List.of());
                    }
                    Node first = expr();
                    if (accept(")")) {
                        return first;
                    }
                    List<Node> items = new ArrayList<>(List.of(first));
                    while (accept(",")) {
                        if (is(")")) {
                            break;
                        }
                        items.add(expr());
                    }
                    expect(")");
                    return s -> new Values.Tuple(items.stream().map(x -> x.eval(s)).toList());
                }
                case "[": {
                    List<Node> items = new ArrayList<>();
                    while (!is("]")) {
                        items.add(expr());
                        if (!accept(",")) {
                            break;
                        }
                    }
                    expect("]");
                    return s -> {
                        List<Object> out = new ArrayList<>();
                        items.forEach(x -> out.add(x.eval(s)));
                        return out;
                    };
                }
                case "{": {
                    List<Node[]> pairs = new ArrayList<>();
                    while (!is("}")) {
                        Node k = expr();
                        expect(":");
                        pairs.add(new Node[] {k, expr()});
                        if (!accept(",")) {
                            break;
                        }
                    }
                    expect("}");
                    return s -> {
                        Map<Object, Object> out = new LinkedHashMap<>();
                        pairs.forEach(kv -> out.put(kv[0].eval(s), kv[1].eval(s)));
                        return out;
                    };
                }
                default:
                    throw new TemplateError("unexpected '" + t.text() + "'");
            }
        }
    }

    static String esc(Object v) {
        return v instanceof Markup m ? m.html() : Markup.escape(Values.str(v));
    }
}
