package com.legacyaudit.engine.pg;

import com.legacyaudit.engine.pg.Lexer.T;
import com.legacyaudit.engine.pg.Lexer.Tok;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A recursive-descent parser for the statements the audit finds (queries, DML, common DDL), in
 * the shape the Python tool's SQL library gave them: what it could not parse is a parse error
 * here too, so the PostgreSQL grade stays the same.
 */
final class Parser {

    static final Set<String> NO_PAREN_FUNCS = Set.of("CURRENT_DATE", "CURRENT_TIMESTAMP", "CURRENT_TIME", "CURRENT_USER",
            "LOCALTIME", "LOCALTIMESTAMP", "CURRENT_SCHEMA", "CURRENT_CATALOG", "SESSION_USER");
    private static final Set<String> SET_OPS = Set.of("UNION", "EXCEPT", "INTERSECT", "MINUS");
    private static final Set<String> JOIN_WORDS = Set.of("JOIN", "LEFT", "RIGHT", "FULL", "INNER", "CROSS", "NATURAL", "OUTER",
            "SEMI", "ANTI", "STRAIGHT_JOIN");
    private static final Set<String> MYSQL_SELECT_MODIFIERS = Set.of("HIGH_PRIORITY", "STRAIGHT_JOIN", "SQL_SMALL_RESULT",
            "SQL_BIG_RESULT", "SQL_BUFFER_RESULT", "SQL_NO_CACHE", "SQL_CALC_FOUND_ROWS");
    private static final Set<String> TYPE_LITERALS = Set.of("DATE", "TIMESTAMP", "TIME", "DATETIME", "TIMESTAMPTZ");

    private final Dialect d;
    private final List<Tok> toks;
    private int i;
    /** Reading the target of INSERT/UPDATE/DELETE/MERGE or DDL: a name, never a table function. */
    private boolean target;

    private Parser(Dialect d, List<Tok> toks) {
        this.d = d;
        this.toks = toks;
    }

    static Node parse(Dialect d, String sql) {
        List<Tok> toks = Lexer.lex(d, sql);
        Parser p = new Parser(d, toks);
        Node stmt = p.statement(sql);
        while (p.cur().is(";")) {
            p.i++;
        }
        if (p.cur().type != T.EOF) {
            throw new Transpiler.Failure("ParseError");
        }
        return stmt == null ? Node.raw("") : stmt;
    }

    // ---- token helpers -----------------------------------------------------------------------

    private Tok cur() {
        return toks.get(i);
    }

    private Tok peek(int k) {
        return toks.get(Math.min(i + k, toks.size() - 1));
    }

    private boolean at(String word) {
        return cur().is(word);
    }

    private boolean atSeq(String... words) {
        for (int k = 0; k < words.length; k++) {
            if (!peek(k).is(words[k])) {
                return false;
            }
        }
        return true;
    }

    private boolean match(String word) {
        if (at(word)) {
            i++;
            return true;
        }
        return false;
    }

    private boolean matchSeq(String... words) {
        if (atSeq(words)) {
            i += words.length;
            return true;
        }
        return false;
    }

    private void expect(String word) {
        if (!match(word)) {
            throw new Transpiler.Failure("ParseError");
        }
    }

    private static ParseFail fail() {
        return new ParseFail();
    }

    /** A parse error (sqlglot's ParseError). */
    static final class ParseFail extends Transpiler.Failure {
        ParseFail() {
            super("ParseError");
        }
    }

    /** A word that can be a name here (not a reserved keyword). */
    private boolean isName(Tok t) {
        if (t.type == T.IDENT) {
            return true;
        }
        return t.type == T.VAR && !d.notId.contains(t.up);
    }

    // ---- statements --------------------------------------------------------------------------

    private Node statement(String sql) {
        Tok t = cur();
        if (t.type == T.EOF) {
            return null;
        }
        if (t.type == T.VAR) {
            if (d == Dialect.TSQL && (t.up.equals("EXEC") || t.up.equals("EXECUTE"))) {
                throw new Transpiler.Failure("UnsupportedError");
            }
            if (d == Dialect.TSQL && t.up.equals("UPDATE") && peek(1).is("STATISTICS")) {
                return command(sql);
            }
            if (Lexer.COMMANDS.contains(t.up) || (d == Dialect.POSTGRES && t.up.equals("EXEC"))) {
                return command(sql);
            }
            switch (t.up) {
                case "SELECT", "WITH" -> {
                    return query(true);
                }
                case "FROM" -> {
                    return query(true);
                }
                case "VALUES" -> {
                    Node v = values();
                    return modifiers(setOps(v));
                }
                case "INSERT" -> {
                    i++;
                    return insert();
                }
                case "REPLACE" -> {
                    if (d == Dialect.MYSQL && peek(1).is("INTO")) {
                        i++;
                        return insert().set("replace", true);
                    }
                }
                case "UPDATE" -> {
                    i++;
                    return update();
                }
                case "DELETE" -> {
                    i++;
                    return delete();
                }
                case "MERGE" -> {
                    i++;
                    return merge();
                }
                case "CREATE", "DROP", "ALTER", "TRUNCATE", "GRANT", "REVOKE", "COMMIT", "ROLLBACK", "COMMENT", "LOCK", "USE",
                        "SET", "BEGIN", "DECLARE", "ANALYZE", "DESCRIBE" -> {
                    return Ddl.statement(this, sql);
                }
                default -> {
                    // falls through to an expression statement
                }
            }
        }
        if (t.is("(")) {
            Node q = query(true);
            return q;
        }
        Node e = expressionWithAlias();
        if (e == null) {
            throw fail();
        }
        return modifiers(setOps(e));
    }

    private Node command(String sql) {
        Tok kw = cur();
        i++;
        int start = cur().start;
        while (cur().type != T.EOF && !cur().is(";")) {
            i++;
        }
        String rest = sql.substring(Math.min(start, sql.length()), cur().type == T.EOF ? sql.length() : cur().start).strip();
        return Node.of("command", "this", kw.up, "rest", rest);
    }

    // ---- queries -----------------------------------------------------------------------------

    /** SELECT / WITH / ( query ) / FROM-first, with set operations and trailing modifiers. */
    Node query(boolean modifiers) {
        Node q = queryTerm();
        q = setOps(q);
        return modifiers ? modifiers(q) : q;
    }

    private Node queryTerm() {
        if (at("WITH")) {
            i++;
            boolean recursive = match("RECURSIVE");
            List<Node> ctes = new ArrayList<>();
            do {
                Node name = identifier();
                if (name == null) {
                    throw fail();
                }
                List<Node> cols = null;
                if (at("(")) {
                    i++;
                    cols = csv(this::identifierOrFail);
                    expect(")");
                }
                expect("AS");
                matchSeq("NOT", "MATERIALIZED");
                match("MATERIALIZED");
                expect("(");
                Node body = statementInParens();
                expect(")");
                ctes.add(Node.of("cte", "name", name, "cols", cols, "q", body));
            } while (match(","));
            Node body = statementAfterWith();
            body.set("with", ctes).set("recursive", recursive);
            return body;
        }
        if (at("(")) {
            int save = i;
            i++;
            if (at("SELECT") || at("WITH") || at("(") || at("FROM") || at("VALUES")) {
                Node inner = query(true);
                if (match(")")) {
                    Node sub = Node.of("sub", "q", inner);
                    return sub;
                }
            }
            i = save;
            throw fail();
        }
        if (at("VALUES")) {
            return values();
        }
        if (at("SELECT")) {
            return select();
        }
        if (at("FROM")) {
            // a query that starts with FROM (HQL, DuckDB): SELECT * FROM ...
            Node s = Node.of("select", "exprs", new ArrayList<>(List.of(Node.of("star"))));
            fromAndRest(s);
            return s;
        }
        throw fail();
    }

    private Node statementAfterWith() {
        if (at("SELECT") || at("(") || at("FROM") || at("VALUES") || at("WITH")) {
            return query(false);
        }
        if (match("INSERT")) {
            return insert();
        }
        if (match("UPDATE")) {
            return update();
        }
        if (match("DELETE")) {
            return delete();
        }
        if (match("MERGE")) {
            return merge();
        }
        throw fail();
    }

    private Node statementInParens() {
        if (at("SELECT") || at("WITH") || at("(") || at("VALUES") || at("FROM")) {
            return query(true);
        }
        return statementAfterWith();
    }

    private Node values() {
        expect("VALUES");
        List<Node> rows = new ArrayList<>();
        do {
            if (at("(")) {
                i++;
                List<Node> items = csv(this::expressionWithAlias);
                expect(")");
                rows.add(Node.of("tuple", "items", items));
            } else {
                if (d != Dialect.ORACLE && d != Dialect.MYSQL) {
                    throw fail();
                }
                Node e = expression();
                if (e == null) {
                    throw fail();
                }
                rows.add(Node.of("tuple", "items", new ArrayList<>(List.of(e))));
            }
        } while (match(","));
        return Node.of("values", "rows", rows);
    }

    private Node select() {
        expect("SELECT");
        Node s = new Node("select");
        if (at("ALL") && !peek(1).is(".")) {
            i++;
        } else if ((at("DISTINCT") || (d == Dialect.ORACLE && at("UNIQUE")) || (d == Dialect.MYSQL && at("DISTINCTROW")))
                && !peek(1).is(".")) {
            i++;
            s.set("distinct", true);
            if (match("ON")) {
                expect("(");
                s.set("distinctOn", csv(this::expression));
                expect(")");
            }
        }
        if (d == Dialect.MYSQL) {
            List<String> mods = new ArrayList<>();
            while (cur().type == T.VAR && MYSQL_SELECT_MODIFIERS.contains(cur().up)) {
                mods.add(cur().up);
                i++;
            }
            if (!mods.isEmpty()) {
                s.set("modifiers", mods);
            }
        }
        if ((d == Dialect.ORACLE || d == Dialect.TSQL) && at("TOP")) {
            i++;
            Node n;
            if (match("(")) {
                n = expression();
                expect(")");
            } else {
                n = number();
            }
            if (match("PERCENT")) {
                s.set("limitPercent", true);
            }
            matchSeq("WITH", "TIES");
            s.set("limit", n);
            if (!s.b("distinct") && match("DISTINCT")) {
                s.set("distinct", true);
            }
        }
        List<Node> exprs = new ArrayList<>();
        if (!at("FROM")) {
            exprs = csv(this::expressionWithAlias);
        }
        if (exprs.isEmpty() && cur().type != T.EOF) {
            throw fail();
        }
        s.set("exprs", exprs);
        if (at("INTO") || at("BULK COLLECT INTO")) {
            // SELECT ... INTO target: the target is dropped in PostgreSQL output
            i++;
            match("TEMPORARY");
            match("UNLOGGED");
            match("TABLE");
            s.set("into", required(expression()));
        }
        fromAndRest(s);
        return s;
    }

    private void fromAndRest(Node s) {
        if (match("FROM")) {
            Node first = tableWithAlias();
            if (first == null) {
                throw fail();
            }
            s.set("from", first);
            s.set("joins", joins());
        }
        if (match("WHERE")) {
            s.set("where", required(expression()));
        }
        if (d == Dialect.ORACLE || true) {
            hierarchical(s);
        }
        if (match("GROUP BY")) {
            if (match("ALL")) {
                s.set("groupAll", true);
            } else {
                s.set("group", csv(this::groupingElement));
            }
            if (matchSeq("WITH", "ROLLUP")) {
                s.set("withRollup", true);
            }
        }
        hierarchical(s);
        if (match("HAVING")) {
            s.set("having", required(expression()));
        }
        if (match("QUALIFY")) {
            s.set("qualify", required(expression()));
        }
        if (at("WINDOW") && peek(1).type != T.EOF && isName(peek(1)) && peek(2).is("AS")) {
            i++;
            List<Node> windows = new ArrayList<>();
            do {
                Node name = identifierOrFail();
                expect("AS");
                expect("(");
                Node spec = windowSpec();
                expect(")");
                windows.add(Node.of("namedWindow", "name", name, "spec", spec));
            } while (match(","));
            s.set("windows", windows);
        }
    }

    private void hierarchical(Node s) {
        if (atSeq("START", "WITH")) {
            i += 2;
            s.set("startWith", required(expression()));
        }
        if (at("CONNECT BY")) {
            i++;
            boolean nocycle = match("NOCYCLE");
            s.set("connectBy", required(expression())).set("nocycle", nocycle);
            if (atSeq("START", "WITH")) {
                i += 2;
                s.set("startWith", required(expression()));
            }
        }
    }

    private Node groupingElement() {
        for (String kw : List.of("ROLLUP", "CUBE")) {
            if (at(kw) && peek(1).is("(")) {
                i += 2;
                List<Node> items = at(")") ? new ArrayList<>() : csv(this::expression);
                expect(")");
                return Node.of("grouping", "kind", kw, "items", items);
            }
        }
        if (at("GROUPING SETS")) {
            i++;
            expect("(");
            List<Node> items = csv(this::groupingElement);
            expect(")");
            return Node.of("grouping", "kind", "GROUPING SETS", "items", items);
        }
        return required(expression());
    }

    /** Set operations: UNION [ALL|DISTINCT], EXCEPT, INTERSECT, MINUS (Oracle). */
    private Node setOps(Node left) {
        while (true) {
            Tok t = cur();
            if (t.type != T.VAR || !SET_OPS.contains(t.up) || (t.up.equals("MINUS") && d != Dialect.ORACLE)) {
                return left;
            }
            i++;
            String op = t.up.equals("MINUS") ? "EXCEPT" : t.up;
            Boolean distinct = null;
            if (match("ALL")) {
                distinct = false;
            } else if (match("DISTINCT")) {
                distinct = true;
            }
            Node right = queryTerm();
            left = Node.of("union", "op", op, "distinct", distinct, "l", left, "r", right);
        }
    }

    /** ORDER BY, LIMIT, OFFSET, FETCH, FOR UPDATE after a query. */
    Node modifiers(Node q) {
        while (true) {
            if (d == Dialect.ORACLE && atSeq("ORDER", "SIBLINGS", "BY")) {
                i += 3;
                q.set("order", csv(this::ordered)).set("siblings", true);
                continue;
            }
            if (at("ORDER BY")) {
                i++;
                q.set("order", csv(this::ordered));
                continue;
            }
            if (at("LIMIT") && canLimit()) {
                i++;
                Node n = required(expression());
                if (match(",")) {
                    q.set("offset", n);
                    n = required(expression());
                }
                q.set("limit", n);
                if (match("OFFSET")) {
                    q.set("offset", required(expression()));
                }
                continue;
            }
            if (at("OFFSET") && canLimit()) {
                i++;
                q.set("offset", required(expression()));
                if (!match("ROWS")) {
                    match("ROW");
                }
                continue;
            }
            if (at("FETCH")) {
                i++;
                String kind = match("FIRST") ? "FIRST" : (match("NEXT") ? "NEXT" : null);
                if (kind == null) {
                    throw fail();
                }
                Node count = null;
                if (!at("ROW") && !at("ROWS")) {
                    count = required(expression());
                }
                boolean percent = match("PERCENT");
                if (!match("ROWS") && !match("ROW")) {
                    throw fail();
                }
                String only = match("ONLY") ? "ONLY" : (matchSeq("WITH", "TIES") ? "WITH TIES" : null);
                if (only == null) {
                    throw fail();
                }
                q.set("fetch", Node.of("fetch", "kind", kind, "count", count, "percent", percent, "with", only));
                continue;
            }
            if (at("FOR") && (peek(1).is("UPDATE") || peek(1).is("SHARE"))) {
                if (d == Dialect.TSQL) {
                    throw fail();
                }
                i += 2;
                StringBuilder lock = new StringBuilder("FOR ").append(peek(-1).up);
                List<Node> of = null;
                if (match("OF")) {
                    of = csv(this::column);
                }
                String wait = null;
                if (match("NOWAIT")) {
                    wait = "NOWAIT";
                } else if (matchSeq("SKIP", "LOCKED")) {
                    wait = "SKIP LOCKED";
                } else if (match("WAIT")) {
                    wait = "WAIT " + number().s("v");
                }
                q.set("lock", Node.of("lock", "text", lock.toString(), "of", of, "wait", wait));
                continue;
            }
            if (d == Dialect.ORACLE && atSeq("WITH", "READ", "ONLY")) {
                i += 3;
                q.set("readOnly", true);
                continue;
            }
            return q;
        }
    }

    private boolean canLimit() {
        Tok n = peek(1);
        return n.type == T.NUMBER || n.is("?") || n.is("(") || n.is("@") || n.is(":") || (n.type == T.VAR && !d.notId.contains(n.up)
                && !n.up.equals("ROWS") && !peek(2).is("AS"));
    }

    // ---- FROM items and joins ----------------------------------------------------------------

    Node tableWithAlias() {
        Node t = tablePrimary();
        if (t == null) {
            return null;
        }
        return tableSuffixes(t);
    }

    private Node tableSuffixes(Node t) {
        // Oracle: t PARTITION (p), t SAMPLE (n), t AS OF TIMESTAMP x
        if ((d == Dialect.ORACLE || d == Dialect.MYSQL) && at("PARTITION") && peek(1).is("(")) {
            i += 2;
            List<Node> parts = csv(this::identifierOrFail);
            expect(")");
            t.set("partition", parts);
        }
        if (d == Dialect.ORACLE && (at("SAMPLE") || at("TABLESAMPLE"))) {
            i++;
            match("BLOCK");
            expect("(");
            Node n = required(expression());
            expect(")");
            t.set("sample", n);
        }
        if (d == Dialect.ORACLE && atSeq("AS", "OF") && (peek(2).is("TIMESTAMP") || peek(2).is("SCN"))) {
            String kind = peek(2).up;
            i += 3;
            t.set("asOf", Node.of("asOf", "kind", kind, "x", required(expression())));
        }
        Node alias = tableAlias();
        if (alias != null) {
            t.set("alias", alias);
        }
        if ((at("PIVOT") || at("UNPIVOT")) && peek(1).is("(")) {
            // parsed, but PostgreSQL has no PIVOT: the writer refuses it
            i += 2;
            int depth = 1;
            while (depth > 0 && cur().type != T.EOF) {
                if (at("(")) {
                    depth++;
                } else if (at(")")) {
                    depth--;
                }
                i++;
            }
            t.set("pivot", true);
            Node after = tableAlias();
            if (after != null) {
                t.set("pivotAlias", after);
            }
        }
        // table hints: WITH (NOLOCK) and friends are dropped
        if (at("WITH") && peek(1).is("(")) {
            int save = i;
            i += 2;
            int depth = 1;
            while (depth > 0 && cur().type != T.EOF) {
                if (at("(")) {
                    depth++;
                } else if (at(")")) {
                    depth--;
                }
                i++;
            }
            if (depth != 0) {
                i = save;
            }
        }
        if ((d == Dialect.MYSQL) && (at("USE") || at("FORCE") || at("IGNORE")) && (peek(1).is("INDEX") || peek(1).is("KEY"))) {
            i += 2;
            expect("(");
            csv(this::identifierOrFail);
            expect(")");
        }
        return t;
    }

    /** [AS] alias [(col, ...)] after a table. */
    private Node tableAlias() {
        if (at("LIMIT") || at("OFFSET")) {
            if (canLimit()) {
                return null;
            }
        }
        if (atSeq("START", "WITH")) {
            return null;
        }
        boolean as = match("AS");
        Node name = null;
        Tok t = cur();
        if (t.type == T.IDENT) {
            i++;
            name = Node.id(t.text, true);
        } else if (t.type == T.VAR && (as || (!d.notId.contains(t.up) && !d.notTableAlias.contains(t.up) && !JOIN_WORDS.contains(t.up)
                && !t.up.equals("APPLY") && !t.up.equals("CONNECT BY") && !(t.up.equals("WINDOW") && false)))) {
            if (!as && (t.up.equals("SET") || t.up.equals("USING") || t.up.equals("WHERE") || t.up.equals("ON")
                    || (t.up.equals("OUTER") || t.up.equals("CROSS")) && peek(1).is("APPLY"))) {
                return null;
            }
            if (!as && t.up.equals("FOR") && d != Dialect.DEFAULT) {
                return null;
            }
            if (!as && (t.up.equals("PIVOT") || t.up.equals("UNPIVOT")) && peek(1).is("(")) {
                return null;
            }
            i++;
            name = Node.id(t.text, false);
        } else if (t.type == T.STRING && d == Dialect.MYSQL) {
            i++;
            name = Node.id(t.text, true);
        } else if (t.is("?") && !as) {
            i++;
            name = Node.of("ph");
        } else if (t.is("@") && d != Dialect.ORACLE && peek(1).type == T.VAR && !peek(1).spaceBefore) {
            i += 2;
            name = Node.of("param", "name", peek(-1).text);
        } else if (as) {
            throw fail();
        }
        List<Node> cols = null;
        if (at("(")) {
            int save = i;
            i++;
            List<Node> c = new ArrayList<>();
            boolean ok = true;
            while (true) {
                Tok x = cur();
                if (x.type == T.IDENT || x.type == T.VAR && !d.notId.contains(x.up)) {
                    c.add(Node.id(x.text, x.type == T.IDENT));
                    i++;
                } else if (x.type == T.NUMBER) {
                    c.add(Node.id(x.text, true));
                    i++;
                } else {
                    ok = false;
                    break;
                }
                if (!match(",")) {
                    break;
                }
            }
            if (ok && !c.isEmpty() && match(")")) {
                cols = c;
            } else {
                i = save;
            }
        }
        if (name == null && cols == null) {
            return null;
        }
        return Node.of("talias", "name", name, "cols", cols);
    }

    private Node tablePrimary() {
        Tok t = cur();
        if (t.is("(")) {
            i++;
            if (at("SELECT") || at("WITH") || at("(") && looksLikeQuery() || at("VALUES")) {
                Node q = query(true);
                expect(")");
                return Node.of("tsub", "q", q);
            }
            Node inner = tableWithAlias();
            if (inner == null) {
                throw fail();
            }
            List<Node> js = joins();
            expect(")");
            return Node.of("tparen", "t", inner, "joins", js);
        }
        if (at("LATERAL")) {
            i++;
            Node inner = tablePrimary();
            if (inner == null) {
                throw fail();
            }
            return Node.of("lateral", "t", inner);
        }
        if (t.type == T.VAR && (t.up.equals("TABLE") || t.up.equals("UNNEST")) && peek(1).is("(")) {
            i += 2;
            Node e = required(expression());
            expect(")");
            return Node.of("tfunc", "f", Node.of("func", "name", t.up, "args", new ArrayList<>(List.of(e))));
        }
        if (t.type == T.STRING && d == Dialect.MYSQL) {
            i++;
            List<Node> parts = new ArrayList<>(List.of(Node.id(t.text, true)));
            while (at(".") && (peek(1).type == T.STRING || isName(peek(1)))) {
                i++;
                parts.add(Node.id(cur().text, cur().type != T.VAR));
                i++;
            }
            return Node.of("table", "parts", parts);
        }
        if (t.is("?")) {
            i++;
            return Node.of("table", "parts", new ArrayList<>(List.of(Node.of("ph"))));
        }
        if (d == Dialect.TSQL && t.is("#")) {
            i++;
            match("#");
            Node name = identifier();
            if (name == null) {
                throw fail();
            }
            return Node.of("table", "parts", new ArrayList<>(List.of(name)));
        }
        if (!isName(t) && !(t.type == T.VAR && (t.up.equals("VALUES") ))) {
            return null;
        }
        List<Node> parts = new ArrayList<>();
        parts.add(identifierOrFail());
        while (at(".") ) {
            i++;
            if (at(".")) {
                parts.add(Node.id("", false));
                continue;
            }
            parts.add(identifierOrFail());
        }
        if (!target && (at("(") && !peek(-1).spaceBefore || at("(") && parts.size() == 1 && functionNameOk(parts.get(0)))) {
            // table function: generate_series(1, 10), xmltable(...)
            i++;
            List<Node> args = functionArgs(parts.get(parts.size() - 1).s("name"));
            expect(")");
            String name = parts.get(parts.size() - 1).s("name");
            Node f = Node.of("func", "name", name, "args", args);
            if (parts.size() > 1) {
                f.set("prefix", parts.subList(0, parts.size() - 1));
            }
            return Node.of("tfunc", "f", f);
        }
        if (!target && at("@") && d != Dialect.ORACLE && !cur().spaceBefore && peek(1).type == T.VAR) {
            // name@dblink: read as an alias parameter, like sqlglot did
            i += 2;
            Node tab = Node.of("table", "parts", parts);
            tab.set("alias", Node.of("talias", "name", Node.of("param", "name", peek(-1).text)));
            return tab;
        }
        return Node.of("table", "parts", parts);
    }

    /** A table name that cannot be a table function (the target of DML and DDL). */
    Node tableTarget() {
        target = true;
        try {
            return tablePrimary();
        } finally {
            target = false;
        }
    }

    private boolean functionNameOk(Node id) {
        return !id.b("quoted");
    }

    private boolean looksLikeQuery() {
        int k = i;
        while (toks.get(k).is("(")) {
            k++;
        }
        Tok t = toks.get(k);
        return t.is("SELECT") || t.is("WITH") || t.is("VALUES");
    }

    List<Node> joins() {
        List<Node> out = new ArrayList<>();
        while (true) {
            if (match(",")) {
                Node t = tableWithAlias();
                if (t == null) {
                    throw fail();
                }
                out.add(Node.of("join", "kind", ",", "t", t));
                continue;
            }
            if ((at("CROSS") || at("OUTER")) && peek(1).is("APPLY")) {
                String kind = cur().up;
                i += 2;
                Node t = tableWithAlias();
                if (t == null) {
                    throw fail();
                }
                out.add(Node.of("join", "kind", kind.equals("CROSS") ? "INNER" : "LEFT", "t", Node.of("lateral", "t", t), "on",
                        Node.of("true")));
                continue;
            }
            int save = i;
            boolean natural = match("NATURAL");
            String side = null;
            if (at("LEFT") || at("RIGHT") || at("FULL")) {
                side = cur().up;
                i++;
            }
            String kind = null;
            if (at("INNER") || at("CROSS") || at("OUTER") || at("SEMI") || at("ANTI")) {
                kind = cur().up;
                i++;
            }
            if (!match("JOIN") && !(d == Dialect.MYSQL && match("STRAIGHT_JOIN"))) {
                i = save;
                return out;
            }
            Node t = tableWithAlias();
            if (t == null) {
                throw fail();
            }
            Node j = Node.of("join", "natural", natural, "side", side, "kind", kind, "t", t);
            if (match("ON")) {
                j.set("on", required(expression()));
            } else if (match("USING")) {
                expect("(");
                j.set("using", csv(this::identifierOrFail));
                expect(")");
            }
            out.add(j);
        }
    }

    // ---- DML ---------------------------------------------------------------------------------

    private Node insert() {
        Node n = new Node("insert");
        if (at("ALL") || at("FIRST")) {
            return multiInsert();
        }
        if (match("OVERWRITE")) {
            n.set("overwrite", true);
        }
        if (at("IGNORE")) {
            if (d != Dialect.MYSQL) {
                throw fail();
            }
            i++;
            n.set("ignore", true);
        }
        if (!match("INTO")) {
            if (d != Dialect.MYSQL && d != Dialect.TSQL) {
                throw fail();
            }
        }
        match("TABLE");
        Node table = tableTarget();
        if (table == null || !table.is("table")) {
            throw fail();
        }
        if (at("@") || cur().type == T.VAR && cur().text.startsWith("@")) {
            throw fail();
        }
        Node alias = insertAlias();
        if (alias != null) {
            table.set("alias", alias);
        }
        n.set("table", table);
        if (at("(") && !looksLikeQueryAfterParen()) {
            i++;
            n.set("cols", csv(this::column));
            expect(")");
        }
        if (d != Dialect.ORACLE && !n.has("cols") && match("SET")) {
            // INSERT INTO t SET a = 1, b = 2: written as a column list and one row of values
            List<Node> sets = csv(this::assignment);
            List<Node> cols = new ArrayList<>();
            List<Node> vals = new ArrayList<>();
            for (Node set : sets) {
                cols.add(set.n("l"));
                vals.add(set.n("r"));
            }
            n.set("cols", cols).set("values", Node.of("values", "rows", new ArrayList<>(List.of(Node.of("tuple", "items", vals)))));
        } else if (at("VALUES")) {
            n.set("values", values());
        } else if (d != Dialect.ORACLE && matchSeq("DEFAULT", "VALUES")) {
            n.set("defaultValues", true);
        } else if (at("SELECT") || at("WITH") || at("(")) {
            n.set("q", query(true));
        } else {
            throw fail();
        }
        if (matchSeq("ON", "DUPLICATE", "KEY", "UPDATE")) {
            n.set("dup", csv(this::assignment));
        } else if (matchSeq("ON", "CONFLICT")) {
            Node c = new Node("conflict");
            if (match("(")) {
                c.set("cols", csv(this::ordered));
                expect(")");
            } else if (matchSeq("ON", "CONSTRAINT")) {
                c.set("constraint", identifierOrFail());
            }
            if (match("WHERE")) {
                c.set("where", required(expression()));
            }
            expect("DO");
            if (match("NOTHING")) {
                c.set("nothing", true);
            } else {
                expect("UPDATE");
                expect("SET");
                c.set("sets", csv(this::assignment));
                if (match("WHERE")) {
                    c.set("updateWhere", required(expression()));
                }
            }
            n.set("conflict", c);
        }
        returning(n);
        return n;
    }

    /** INSERT ALL|FIRST [WHEN c THEN] INTO t [(cols)] [VALUES (...)] ... query. */
    private Node multiInsert() {
        String kind = cur().up;
        i++;
        List<Node> intos = new ArrayList<>();
        while (at("INTO") || at("WHEN") || at("ELSE")) {
            Node into = new Node("into");
            if (match("WHEN")) {
                into.set("when", required(expression()));
                expect("THEN");
            } else if (match("ELSE")) {
                into.set("else", true);
            }
            expect("INTO");
            Node table = tableTarget();
            if (table == null) {
                throw fail();
            }
            Node alias = insertAlias();
            if (alias != null) {
                table.set("alias", alias);
            }
            into.set("table", table);
            if (at("(") && !looksLikeQueryAfterParen()) {
                i++;
                into.set("cols", csv(this::column));
                expect(")");
            }
            if (at("VALUES")) {
                into.set("values", values());
            }
            intos.add(into);
        }
        if (intos.isEmpty()) {
            throw fail();
        }
        return Node.of("multiinsert", "kind", kind, "intos", intos, "q", query(true));
    }

    private Node insertAlias() {
        if (match("AS")) {
            return Node.of("talias", "name", identifierOrFail());
        }
        Tok t = cur();
        if (t.type == T.VAR && !d.notId.contains(t.up) && !t.up.equals("VALUES") && !t.up.equals("SELECT") && !t.up.equals("DEFAULT")
                && !t.up.equals("INTO") && !t.up.equals("WHEN") && !t.up.equals("ON") && !t.up.equals("SET")
                && !t.up.equals("WITH") && !t.up.equals("OVERWRITE") && !t.up.equals("RETURNING")) {
            i++;
            return Node.of("talias", "name", Node.id(t.text, false));
        }
        return null;
    }

    private boolean looksLikeQueryAfterParen() {
        Tok t = peek(1);
        return t.is("SELECT") || t.is("WITH");
    }

    private void returning(Node n) {
        if (match("RETURNING")) {
            n.set("returning", csv(this::expressionWithAlias));
            if (match("INTO")) {
                csv(this::expression);
            }
        }
    }

    private Node update() {
        Node n = new Node("update");
        Node table = tableTarget();
        if (table == null) {
            throw fail();
        }
        Node alias = tableAlias();
        if (alias != null) {
            table.set("alias", alias);
        }
        n.set("table", table);
        List<Node> extra = joins();
        if (!extra.isEmpty()) {
            n.set("joins", extra);
        }
        if (match("SET")) {
            n.set("sets", csv(this::assignment));
        } else if (cur().type != T.EOF) {
            throw fail();
        }
        if (match("FROM")) {
            Node from = tableWithAlias();
            if (from == null) {
                throw fail();
            }
            n.set("from", from).set("fromJoins", joins());
        }
        if (match("WHERE")) {
            n.set("where", required(expression()));
        }
        returning(n);
        if (at("ORDER BY") || at("LIMIT")) {
            modifiers(n);
        }
        return n;
    }

    private Node assignment() {
        Node left;
        if (at("(")) {
            i++;
            left = Node.of("tuple", "items", csv(this::column));
            expect(")");
        } else {
            left = column();
        }
        if (!match("=")) {
            throw fail();
        }
        Node right = required(expression());
        return Node.of("bin", "op", "=", "l", left, "r", right);
    }

    private Node delete() {
        Node n = new Node("delete");
        if (!at("FROM")) {
            // DELETE t1[, t2] FROM t1 JOIN t2 ...: the tables to delete from come first
            int save = i;
            List<Node> targets = new ArrayList<>();
            do {
                Node t = tableTarget();
                if (t == null) {
                    break;
                }
                targets.add(t);
            } while (match(","));
            if (!targets.isEmpty() && at("FROM")) {
                n.set("targets", targets);
            } else {
                i = save;
            }
        }
        boolean from = match("FROM");
        n.set("from", from);
        Node table = n.has("targets") ? tableWithAlias() : tableTarget();
        if (table == null) {
            throw fail();
        }
        if (!n.has("targets")) {
            Node alias = tableAlias();
            if (alias != null) {
                table.set("alias", alias);
            }
        }
        n.set("table", table);
        if (n.has("targets")) {
            n.set("joins", joins());
        }
        if (match("USING")) {
            Node u = tableWithAlias();
            n.set("using", u).set("usingJoins", joins());
        }
        if (match("WHERE")) {
            n.set("where", required(expression()));
        }
        returning(n);
        return n;
    }

    private Node merge() {
        expect("INTO");
        Node target = tableTarget();
        if (target == null) {
            throw fail();
        }
        Node alias = tableAlias();
        if (alias != null) {
            target.set("alias", alias);
        }
        expect("USING");
        Node source = tableWithAlias();
        if (source == null) {
            throw fail();
        }
        expect("ON");
        Node on = required(expression());
        List<Node> whens = new ArrayList<>();
        while (match("WHEN")) {
            boolean not = match("NOT");
            expect("MATCHED");
            String by = null;
            if (matchSeq("BY", "SOURCE")) {
                by = "SOURCE";
            } else if (matchSeq("BY", "TARGET")) {
                by = "TARGET";
            }
            Node cond = null;
            if (match("AND")) {
                cond = required(expression());
            }
            expect("THEN");
            Node action;
            if (match("UPDATE")) {
                expect("SET");
                List<Node> sets = csv(this::assignment);
                action = Node.of("mupdate", "sets", sets);
                if (match("WHERE")) {
                    action.set("where", required(expression()));
                }
            } else if (match("INSERT")) {
                List<Node> cols = null;
                if (match("(")) {
                    cols = csv(this::column);
                    expect(")");
                }
                Node vals = null;
                if (at("VALUES")) {
                    vals = values();
                }
                action = Node.of("minsert", "cols", cols, "values", vals);
                if (match("WHERE")) {
                    action.set("where", required(expression()));
                }
            } else if (match("DELETE")) {
                action = Node.of("mdelete");
            } else {
                throw fail();
            }
            whens.add(Node.of("mwhen", "not", not, "by", by, "cond", cond, "action", action));
        }
        if (whens.isEmpty()) {
            throw fail();
        }
        return Node.of("merge", "target", target, "source", source, "on", on, "whens", whens);
    }

    // ---- expressions -------------------------------------------------------------------------

    <X> List<X> csv(java.util.function.Supplier<X> item) {
        List<X> out = new ArrayList<>();
        X first = item.get();
        if (first == null) {
            return out;
        }
        out.add(first);
        while (match(",")) {
            X next = item.get();
            if (next == null) {
                throw fail();
            }
            out.add(next);
        }
        return out;
    }

    static Node required(Node n) {
        if (n == null) {
            throw fail();
        }
        return n;
    }

    Node expressionWithAlias() {
        Node e = expression();
        if (e == null) {
            return null;
        }
        return alias(e);
    }

    private Node alias(Node e) {
        if ((at("LIMIT") || at("OFFSET")) && canLimit()) {
            return e;
        }
        boolean as = match("AS");
        Tok t = cur();
        if (at("(") && as) {
            i++;
            List<Node> names = csv(this::identifierOrFail);
            expect(")");
            return Node.of("aliases", "x", e, "names", names);
        }
        if (t.type == T.IDENT) {
            i++;
            return Node.of("alias", "x", e, "name", Node.id(t.text, true));
        }
        if (t.type == T.VAR && (as || !d.notId.contains(t.up))) {
            if (!as && atSeq("WINDOW") && peek(1).type == T.VAR && peek(2).is("AS")) {
                return e;
            }
            if (!as && (t.up.equals("FOR") || t.up.equals("FETCH")) && false) {
                return e;
            }
            i++;
            return Node.of("alias", "x", e, "name", Node.id(t.text, false));
        }
        if (t.type == T.STRING && (as || d == Dialect.MYSQL || d == Dialect.TSQL)) {
            i++;
            return Node.of("alias", "x", e, "name", Node.id(t.text, true));
        }
        if (as) {
            throw fail();
        }
        return e;
    }

    Node expression() {
        return disjunction();
    }

    private Node disjunction() {
        Node l = conjunction();
        while (l != null && (at("OR") || at("XOR"))) {
            String op = cur().up;
            i++;
            l = Node.of("bin", "op", op, "l", l, "r", required(conjunction()));
        }
        return l;
    }

    private Node conjunction() {
        Node l = equality();
        while (l != null && (at("AND") || (d == Dialect.MYSQL && at("&&")))) {
            i++;
            l = Node.of("bin", "op", "AND", "l", l, "r", required(equality()));
        }
        return l;
    }

    private Node equality() {
        Node l = comparison();
        while (l != null) {
            String op = null;
            if (at("=") || at("==")) {
                op = "=";
            } else if (at("<>") || at("!=")) {
                op = "<>";
            } else if (at("<=>")) {
                op = "<=>";
            }
            if (op == null) {
                return l;
            }
            i++;
            l = Node.of("bin", "op", op, "l", l, "r", required(comparison()));
        }
        return l;
    }

    private Node comparison() {
        Node l = range();
        while (l != null && (at("<") || at(">") || at("<=") || at(">=")
                || d == Dialect.POSTGRES && (at("@>") || at("<@") || at("&&") || at("#>") || at("#>>")))) {
            String op = cur().text;
            i++;
            l = Node.of("bin", "op", op, "l", l, "r", required(range()));
        }
        return l;
    }

    private Node range() {
        Node x = bitwise();
        if (x == null) {
            return null;
        }
        int save = i;
        boolean negate = match("NOT");
        Node r = null;
        if (match("IN")) {
            r = in(x);
        } else if (match("BETWEEN")) {
            Node lo = required(bitwise());
            expect("AND");
            Node hi = required(bitwise());
            r = Node.of("between", "x", x, "lo", lo, "hi", hi);
        } else if (at("LIKE") || at("ILIKE") || at("SIMILAR TO") || at("RLIKE") || at("REGEXP") || at("GLOB")
                || d == Dialect.POSTGRES && (at("~") || at("~*") || at("!~") || at("!~*"))) {
            String op = cur().up;
            i++;
            boolean any = false;
            Node p;
            if ((at("ANY") || at("ALL") || at("SOME")) && peek(1).is("(")) {
                throw fail();
            }
            p = required(bitwise());
            r = Node.of("like", "op", op, "x", x, "p", p);
            if (match("ESCAPE")) {
                r.set("esc", required(primary()));
            }
        } else if (negate) {
            i = save;
            negate = false;
        }
        if (r != null) {
            if (negate && r.is("like")) {
                r.set("negated", true);
                x = r;
            } else {
                x = negate ? Node.of("not", "x", r) : r;
            }
        }
        if (match("IS")) {
            boolean not = match("NOT");
            Node what;
            if (not && d == Dialect.POSTGRES && at("NULL")) {
                i++;
                return Node.of("isnotnull", "x", x);
            }
            if (match("NULL")) {
                what = Node.of("null");
            } else if (match("TRUE")) {
                what = Node.of("true");
            } else if (match("FALSE")) {
                what = Node.of("false");
            } else if (match("UNKNOWN")) {
                what = Node.of("null");
            } else if (matchSeq("DISTINCT", "FROM")) {
                Node other = required(bitwise());
                Node cmp = Node.of(not ? "nullsafeeq" : "nullsafeneq", "l", x, "r", other);
                return cmp;
            } else if (match("NAN")) {
                what = Node.raw("NAN");
            } else {
                throw fail();
            }
            Node is = Node.of("is", "x", x, "r", what);
            x = not ? Node.of("not", "x", is) : is;
        } else if (match("ISNULL")) {
            x = Node.of("is", "x", x, "r", Node.of("null"));
        } else if (match("NOTNULL")) {
            x = Node.of("not", "x", Node.of("is", "x", x, "r", Node.of("null")));
        }
        return x;
    }

    private Node in(Node x) {
        if (at("(")) {
            i++;
            if (at("SELECT") || at("WITH") || at("(") && looksLikeQuery()) {
                Node q = query(true);
                expect(")");
                return Node.of("in", "x", x, "q", q);
            }
            List<Node> items = at(")") ? new ArrayList<>() : csv(this::expressionWithAlias);
            expect(")");
            return Node.of("in", "x", x, "items", items);
        }
        Node e = required(bitwise());
        return Node.of("in", "x", x, "field", e);
    }

    private Node bitwise() {
        Node l = term();
        while (l != null) {
            String op = null;
            if (at("|") || at("&") || at("^") || at("<<") || at(">>")) {
                op = cur().text;
            } else if (at("||")) {
                op = d == Dialect.MYSQL ? "OR" : "||";
            }
            if (op == null) {
                return l;
            }
            i++;
            l = Node.of("bin", "op", op, "l", l, "r", required(term()));
        }
        return l;
    }

    private Node term() {
        Node l = factor();
        while (l != null && (at("+") || at("-") || at("COLLATE"))) {
            if (at("COLLATE")) {
                i++;
                Node coll = required(primaryOrName());
                l = Node.of("collate", "x", l, "c", coll);
                continue;
            }
            String op = cur().text;
            i++;
            l = Node.of("bin", "op", op, "l", l, "r", required(factor()));
        }
        return l;
    }

    private Node factor() {
        Node l = unary();
        while (l != null && (at("*") || at("/") || at("%") || at("DIV") && d == Dialect.MYSQL || at("MOD") && d == Dialect.MYSQL)) {
            String op = cur().up.equals("MOD") ? "%" : cur().up;
            i++;
            Node r = required(unary());
            l = op.equals("DIV") ? Node.of("func", "name", "DIV", "args", new ArrayList<>(List.of(l, r))) : Node.of("bin", "op", op, "l", l, "r", r);
        }
        return l;
    }

    private Node unary() {
        if (match("NOT")) {
            return Node.of("not", "x", required(equality()));
        }
        if (at("-")) {
            i++;
            Node x = required(unary());
            if (x.is("num") && !x.s("v").startsWith("-")) {
                return Node.of("num", "v", "-" + x.s("v"));
            }
            return Node.of("neg", "x", x);
        }
        if (at("+")) {
            i++;
            return required(unary());
        }
        if (at("~")) {
            i++;
            return Node.of("bitnot", "x", required(unary()));
        }
        return type();
    }

    private Node type() {
        Tok t = cur();
        if (t.type == T.VAR && t.up.equals("INTERVAL")) {
            Node iv = interval();
            if (iv != null) {
                return iv;
            }
        }
        if (t.type == T.VAR && TYPE_LITERALS.contains(t.up) && peek(1).type == T.STRING) {
            i += 2;
            return Node.of("typedlit", "type", t.up, "v", peek(-1).text);
        }
        return column();
    }

    private Node interval() {
        int save = i;
        i++;
        Node value;
        if (cur().type == T.STRING) {
            value = Node.of("str", "v", cur().text);
            i++;
        } else if (cur().type == T.NUMBER) {
            value = number();
        } else if (at("-") && peek(1).type == T.NUMBER) {
            i++;
            value = Node.of("num", "v", "-" + number().s("v"));
        } else {
            Node e = primary();
            if (e == null) {
                i = save;
                return null;
            }
            value = e;
        }
        String unit = null;
        Tok u = cur();
        if (u.type == T.VAR && Units.isUnit(u.up)) {
            i++;
            unit = u.up;
            if (at("(")) {
                // INTERVAL '5' DAY(3): sqlglot read DAY(3) as a function
                i++;
                Node arg = required(expression());
                expect(")");
                return Node.of("interval", "v", value, "unitFunc", Node.of("func", "name", unit, "args", new ArrayList<>(List.of(arg))));
            }
            if (match("TO")) {
                Tok u2 = cur();
                if (u2.type != T.VAR || !Units.isUnit(u2.up)) {
                    throw fail();
                }
                i++;
                unit = unit + " TO " + u2.up;
                if (at("(")) {
                    i++;
                    number();
                    expect(")");
                }
            }
        }
        return Node.of("interval", "v", value, "unit", unit);
    }

    /** A column (or any primary) followed by dots, casts, join markers. */
    Node column() {
        Node x = field();
        if (x == null) {
            return null;
        }
        while (true) {
            if (at(".") ) {
                i++;
                Tok t = cur();
                if (t.is("*")) {
                    i++;
                    x = dot(x, Node.of("star"));
                    continue;
                }
                if (t.type == T.VAR || t.type == T.IDENT) {
                    i++;
                    Node part = Node.id(t.text, t.type == T.IDENT);
                    if (at("(") && t.type == T.VAR) {
                        i++;
                        List<Node> args = functionArgs(t.up);
                        expect(")");
                        Node f = Node.of("func", "name", t.text, "args", args, "keepCase", true);
                        x = Node.of("dotfunc", "x", x, "f", windowAfter(f));
                        continue;
                    }
                    x = dot(x, part);
                    continue;
                }
                throw fail();
            }
            if (at("::")) {
                i++;
                Node ty = dataType();
                x = Node.of("cast", "x", x, "type", ty);
                continue;
            }
            if (at("(+)")) {
                i++;
                x = Node.of("joinmark", "x", x);
                continue;
            }
            if (at("->") || at("->>")) {
                String op = cur().text;
                i++;
                Node path = required(primary());
                x = Node.of("json", "op", op, "x", x, "path", path);
                continue;
            }
            if (at("[") && d != Dialect.TSQL) {
                i++;
                Node idx = required(expression());
                expect("]");
                x = Node.of("index", "x", x, "i", idx);
                continue;
            }
            return x;
        }
    }

    private static Node dot(Node x, Node part) {
        if (x.is("col")) {
            List<Node> parts = new ArrayList<>(x.l("parts"));
            parts.add(part);
            return Node.of("col", "parts", parts);
        }
        if (x.is("ph") || x.is("param")) {
            return Node.of("dot", "x", x, "part", part);
        }
        return Node.of("dot", "x", x, "part", part);
    }

    private Node field() {
        Node p = primary();
        if (p != null) {
            return p;
        }
        return nameOrFunction();
    }

    private Node primaryOrName() {
        Node p = primary();
        return p != null ? p : nameOrFunction();
    }

    private Node number() {
        Tok t = cur();
        if (t.type != T.NUMBER) {
            throw fail();
        }
        i++;
        return Node.of("num", "v", t.text);
    }

    private Node primary() {
        Tok t = cur();
        switch (t.type) {
            case NUMBER -> {
                i++;
                return Node.of("num", "v", t.text);
            }
            case STRING -> {
                i++;
                Node s = Node.of("str", "v", t.text);
                if (cur().type == T.STRING) {
                    List<Node> parts = new ArrayList<>(List.of(s));
                    while (cur().type == T.STRING) {
                        parts.add(Node.of("str", "v", cur().text));
                        i++;
                    }
                    return Node.of("concatlit", "parts", parts);
                }
                return s;
            }
            case NATIONAL -> {
                i++;
                return Node.of("nstr", "v", t.text);
            }
            case HEX -> {
                i++;
                return Node.of("hex", "v", t.text);
            }
            default -> {
                // keywords and symbols below
            }
        }
        if (t.is(".") && peek(1).type == T.NUMBER) {
            i += 2;
            return Node.of("num", "v", "0." + peek(-1).text);
        }
        if (t.is("?")) {
            i++;
            return Node.of("ph");
        }
        if (t.is("*")) {
            i++;
            return Node.of("star");
        }
        if (t.is("@")) {
            if (peek(1).is("@")) {
                i += 2;
                Tok v = cur();
                i++;
                return Node.of("sessionvar", "name", v.text);
            }
            i++;
            Tok v = cur();
            if (v.type == T.VAR || v.type == T.NUMBER || v.type == T.IDENT) {
                i++;
                return Node.of("param", "name", v.text);
            }
            throw fail();
        }
        if (d != Dialect.TSQL && t.type == T.VAR && t.up.equals("ARRAY") && peek(1).is("[")) {
            i += 2;
            List<Node> items = at("]") ? new ArrayList<>() : csv(this::expression);
            expect("]");
            return Node.of("array", "items", items);
        }
        if (t.is("$") && d == Dialect.POSTGRES && (peek(1).type == T.NUMBER || peek(1).type == T.VAR) && !peek(1).spaceBefore) {
            i += 2;
            return Node.of("param", "name", peek(-1).text);
        }
        if (t.is(":") && (peek(1).type == T.VAR || peek(1).type == T.NUMBER)) {
            i += 2;
            return Node.of("colonp", "name", peek(-1).text);
        }
        if (t.type == T.VAR) {
            switch (t.up) {
                case "NULL" -> {
                    i++;
                    return Node.of("null");
                }
                case "TRUE" -> {
                    i++;
                    return Node.of("true");
                }
                case "FALSE" -> {
                    i++;
                    return Node.of("false");
                }
                case "CASE" -> {
                    return caseExpr();
                }
                case "EXISTS" -> {
                    if (peek(1).is("(")) {
                        i += 2;
                        Node q = query(true);
                        expect(")");
                        return Node.of("exists", "q", q);
                    }
                }
                case "ANY", "ALL", "SOME" -> {
                    if (peek(1).is("(")) {
                        i++;
                        return Node.of("any", "kind", t.up, "x", required(bitwise()));
                    }
                }
                case "PRIOR" -> {
                    if (true) {
                        i++;
                        return Node.of("prior", "x", required(bitwise()));
                    }
                }
                default -> {
                    // not a literal keyword
                }
            }
        }
        if (t.is("(")) {
            i++;
            if (at("SELECT") || at("WITH") || at("(") && looksLikeQuery() || at("VALUES") && d != Dialect.TSQL) {
                Node q = query(true);
                expect(")");
                return Node.of("sub", "q", q);
            }
            if (at(")")) {
                i++;
                return Node.of("tuple", "items", new ArrayList<>());
            }
            List<Node> items = csv(this::expressionWithAlias);
            if (items.isEmpty()) {
                throw fail();
            }
            Node first = items.get(0);
            if (items.size() == 1 && first.is("sub")) {
                Node q = modifiers(setOps(first));
                expect(")");
                return Node.of("sub", "q", q, "wrapped", true);
            }
            expect(")");
            if (items.size() > 1 || peek(-2).is(",")) {
                return Node.of("tuple", "items", items);
            }
            return Node.of("paren", "x", first);
        }
        return null;
    }

    private Node caseExpr() {
        expect("CASE");
        Node operand = null;
        if (!at("WHEN")) {
            operand = required(expression());
        }
        List<Node> whens = new ArrayList<>();
        while (match("WHEN")) {
            Node c = required(expression());
            expect("THEN");
            Node r = required(expression());
            whens.add(Node.of("when", "c", c, "r", r));
        }
        Node els = null;
        if (match("ELSE")) {
            els = required(expression());
        }
        expect("END");
        if (whens.isEmpty()) {
            throw fail();
        }
        return Node.of("case", "op", operand, "whens", whens, "else", els);
    }

    /** A name: a column reference, a function call or a no-parenthesis function. */
    private Node nameOrFunction() {
        Tok t = cur();
        if (t.type == T.IDENT) {
            i++;
            if (at("(") && false) {
                return null;
            }
            return Node.of("col", "parts", new ArrayList<>(List.of(Node.id(t.text, true))));
        }
        if (t.type != T.VAR) {
            return null;
        }
        if (peek(1).is("(") && functionToken(t) && !(d == Dialect.ORACLE && (t.up.equals("SYSDATE") || t.up.equals("SYSTIMESTAMP")))) {
            i += 2;
            Node f = function(t);
            return windowAfter(f);
        }
        if ((d == Dialect.ORACLE || d == Dialect.TSQL) && t.up.equals("NEXT") && peek(1).is("VALUE") && peek(2).is("FOR")) {
            i += 3;
            return Node.of("nextval", "seq", required(column()));
        }
        if (NO_PAREN_FUNCS.contains(t.up)) {
            i++;
            return Node.of("func", "name", t.up, "args", new ArrayList<>(), "noparen", true);
        }
        if (d == Dialect.ORACLE && (t.up.equals("SYSDATE") || t.up.equals("SYSTIMESTAMP"))) {
            i++;
            return Node.of("func", "name", t.up, "args", new ArrayList<>(), "noparen", true);
        }
        if (d.notId.contains(t.up)) {
            return null;
        }
        i++;
        return Node.of("col", "parts", new ArrayList<>(List.of(Node.id(t.text, false))));
    }

    private boolean functionToken(Tok t) {
        if (!d.notId.contains(t.up)) {
            return true;
        }
        return Set.of("LEFT", "RIGHT", "INSERT", "REPLACE", "FIRST", "MOD").contains(t.up) || d == Dialect.MYSQL && t.up.equals("VALUES");
    }

    private Node function(Tok name) {
        String up = name.up;
        Node f;
        switch (up) {
            case "CAST", "TRY_CAST", "SAFE_CAST" -> {
                Node x = required(expression());
                if (!match("AS")) {
                    throw fail();
                }
                Node ty = dataType();
                if (match("FORMAT")) {
                    required(expression());
                }
                expect(")");
                return Node.of("cast", "x", x, "type", ty, "try", !up.equals("CAST"));
            }
            case "TRY_CONVERT" -> {
                if (d != Dialect.TSQL) {
                    break;
                }
                Node ty = dataType();
                expect(",");
                Node x = required(expression());
                Node style = null;
                if (match(",")) {
                    style = required(expression());
                }
                expect(")");
                return Node.of("convert", "x", x, "type", ty, "style", style, "strict", false);
            }
            case "CONVERT" -> {
                if (d == Dialect.TSQL) {
                    Node ty = dataType();
                    expect(",");
                    Node x = required(expression());
                    Node style = null;
                    if (match(",")) {
                        style = required(expression());
                    }
                    expect(")");
                    return Node.of("convert", "x", x, "type", ty, "style", style, "strict", true);
                }
                if (d == Dialect.ORACLE) {
                    List<Node> args = csv(this::expression);
                    expect(")");
                    return Node.of("func", "name", "CONVERT", "args", args);
                }
                if (d == Dialect.MYSQL) {
                    Node x = required(expression());
                    if (match(",")) {
                        Node ty = dataType();
                        expect(")");
                        return Node.of("cast", "x", x, "type", ty);
                    }
                    throw fail();
                }
                Node a = required(expression());
                expect(",");
                Node b = required(expression());
                expect(")");
                return Node.of("castswap", "a", a, "b", b);
            }
            case "EXTRACT" -> {
                Tok part = cur();
                if (part.type != T.VAR && part.type != T.STRING) {
                    throw fail();
                }
                i++;
                expect("FROM");
                Node x = required(expression());
                expect(")");
                return Node.of("extract", "part", part.type == T.STRING ? part.text : part.up, "x", x);
            }
            case "SUBSTRING" -> {
                Node x = required(expression());
                Node start = null;
                Node len = null;
                if (match("FROM")) {
                    start = required(expression());
                    if (match("FOR")) {
                        len = required(expression());
                    }
                } else if (match(",")) {
                    start = required(expression());
                    if (match(",")) {
                        len = required(expression());
                    }
                } else if (match("FOR")) {
                    len = required(expression());
                }
                expect(")");
                return Node.of("substring", "x", x, "start", start, "len", len);
            }
            case "TRIM" -> {
                String where = null;
                if (at("LEADING") || at("TRAILING") || at("BOTH")) {
                    where = cur().up;
                    i++;
                }
                Node first = null;
                if (!at("FROM")) {
                    first = required(expression());
                }
                Node x;
                Node chars = null;
                if (match("FROM")) {
                    chars = first;
                    x = required(expression());
                } else if (match(",")) {
                    x = first;
                    chars = required(expression());
                } else {
                    x = first;
                }
                expect(")");
                return Node.of("trim", "x", x, "chars", chars, "where", where);
            }
            case "POSITION" -> {
                Node sub = required(bitwise());
                if (match("IN")) {
                    Node x = required(expression());
                    expect(")");
                    return Node.of("position", "sub", sub, "x", x);
                }
                List<Node> args = new ArrayList<>(List.of(sub));
                while (match(",")) {
                    args.add(required(expression()));
                }
                expect(")");
                return Node.of("func", "name", name.text, "args", args);
            }
            case "LISTAGG", "STRING_AGG", "GROUP_CONCAT" -> {
                boolean distinct = match("DISTINCT");
                List<Node> args = new ArrayList<>();
                if (!at(")")) {
                    args.add(required(expression()));
                    if (up.equals("GROUP_CONCAT")) {
                        while (match(",")) {
                            args.add(required(expression()));
                        }
                    } else if (match(",")) {
                        args.add(required(expression()));
                    }
                }
                List<Node> inner = null;
                if (match("ORDER BY")) {
                    inner = csv(this::ordered);
                }
                Node sep = null;
                if (d == Dialect.MYSQL && match("SEPARATOR")) {
                    sep = required(primary());
                }
                if (matchSeq("ON", "OVERFLOW")) {
                    while (!at(")") && cur().type != T.EOF) {
                        i++;
                    }
                }
                expect(")");
                f = Node.of("func", "name", name.text, "args", args, "distinct", distinct, "innerOrder", inner, "sep", sep);
                return f;
            }
            default -> {
                // generic function
            }
        }
        boolean distinct = false;
        if (match("DISTINCT")) {
            distinct = true;
        } else {
            match("ALL");
        }
        List<Node> args = functionArgs(up);
        List<Node> inner = null;
        if (match("ORDER BY")) {
            inner = csv(this::ordered);
        }
        if (matchSeq("IGNORE", "NULLS") || matchSeq("RESPECT", "NULLS")) {
            // kept by the generic writer as part of the text
        }
        expect(")");
        return Node.of("func", "name", name.text, "args", args, "distinct", distinct, "innerOrder", inner);
    }

    List<Node> functionArgs(String up) {
        if (at(")")) {
            return new ArrayList<>();
        }
        if (at("+") && peek(1).is(")")) {
            i++;
            return new ArrayList<>();
        }
        if (at("*") && peek(1).is(")")) {
            i++;
            return new ArrayList<>(List.of(Node.of("star")));
        }
        List<Node> args = new ArrayList<>();
        do {
            Node a = functionArg();
            if (a == null) {
                throw fail();
            }
            args.add(a);
        } while (match(","));
        return args;
    }

    private Node functionArg() {
        if (at("SELECT") || at("WITH")) {
            return query(true);
        }
        Node e = expression();
        if (e == null) {
            // XMLTABLE('/a' PASSING x COLUMNS ...) and other keyword-shaped arguments: keep the text
            return null;
        }
        if (at("=>") || at(":=")) {
            i++;
            Node v = required(expression());
            return Node.of("kwarg", "k", e, "v", v);
        }
        if (at("AS") && !peek(1).is("(")) {
            return alias(e);
        }
        if (cur().type == T.VAR && !d.notId.contains(cur().up) && !at(")")) {
            // keyword-shaped arguments (XMLTABLE ... PASSING x COLUMNS b INT PATH 'b'): keep the words
            StringBuilder sb = new StringBuilder();
            int depth = 0;
            List<Object> rest = new ArrayList<>();
            while (cur().type != T.EOF && !(depth == 0 && (at(")") || at(",")))) {
                if (at("(")) {
                    depth++;
                } else if (at(")")) {
                    depth--;
                }
                rest.add(cur());
                i++;
            }
            return Node.of("argwords", "x", e, "rest", rest);
        }
        return e;
    }

    /** OVER (...), WITHIN GROUP (...), FILTER (...), KEEP (...) after a function. */
    private Node windowAfter(Node f) {
        if (at("WITHIN") && peek(1).is("GROUP")) {
            i += 2;
            expect("(");
            expect("ORDER BY");
            f.set("within", csv(this::ordered));
            expect(")");
        }
        if (at("FILTER") && peek(1).is("(")) {
            i += 2;
            expect("WHERE");
            f.set("filter", required(expression()));
            expect(")");
        }
        if (d == Dialect.ORACLE && at("KEEP") && peek(1).is("(")) {
            i += 2;
            expect("DENSE_RANK");
            String which = match("FIRST") ? "FIRST" : (match("LAST") ? "LAST" : null);
            if (which == null) {
                throw fail();
            }
            expect("ORDER BY");
            f.set("keep", Node.of("keep", "which", which, "order", csv(this::ordered)));
            expect(")");
        }
        if (matchSeq("IGNORE", "NULLS")) {
            f.set("nulls", "IGNORE NULLS");
        } else if (matchSeq("RESPECT", "NULLS")) {
            f.set("nulls", "RESPECT NULLS");
        }
        if (match("OVER")) {
            if (match("(")) {
                Node spec = windowSpec();
                expect(")");
                f.set("over", spec);
            } else {
                f.set("over", Node.of("window", "name", identifierOrFail()));
            }
        }
        return f;
    }

    private Node windowSpec() {
        Node w = new Node("window");
        if (cur().type == T.VAR && !at("PARTITION BY") && !at("ORDER BY") && !at("ROWS") && !at("RANGE") && !at("GROUPS")
                && isName(cur())) {
            w.set("base", identifierOrFail());
        }
        if (match("PARTITION BY")) {
            w.set("partition", csv(this::expression));
        }
        if (match("ORDER BY")) {
            w.set("order", csv(this::ordered));
        }
        if (at("ROWS") || at("RANGE") || at("GROUPS")) {
            String kind = cur().up;
            i++;
            Node frame = Node.of("frame", "kind", kind);
            if (match("BETWEEN")) {
                frame.set("start", frameBound());
                expect("AND");
                frame.set("end", frameBound());
            } else {
                frame.set("start", frameBound());
            }
            if (match("EXCLUDE")) {
                StringBuilder ex = new StringBuilder();
                while (cur().type == T.VAR) {
                    ex.append(ex.length() > 0 ? " " : "").append(cur().up);
                    i++;
                }
                frame.set("exclude", ex.toString());
            }
            w.set("frame", frame);
        }
        return w;
    }

    private String frameBound() {
        if (matchSeq("UNBOUNDED", "PRECEDING")) {
            return "UNBOUNDED PRECEDING";
        }
        if (matchSeq("UNBOUNDED", "FOLLOWING")) {
            return "UNBOUNDED FOLLOWING";
        }
        if (matchSeq("CURRENT", "ROW")) {
            return "CURRENT ROW";
        }
        Node n = required(bitwise());
        String side = match("PRECEDING") ? "PRECEDING" : (match("FOLLOWING") ? "FOLLOWING" : null);
        if (side == null) {
            throw fail();
        }
        return Writer.sqlOf(Dialect.DEFAULT, n) + " " + side;
    }

    Node ordered() {
        Node x = expression();
        if (x == null) {
            return null;
        }
        Boolean desc = null;
        if (match("ASC")) {
            desc = false;
        } else if (match("DESC")) {
            desc = true;
        }
        boolean nullsFirst = false;
        boolean explicit = false;
        if (matchSeq("NULLS", "FIRST")) {
            nullsFirst = true;
            explicit = true;
        } else if (matchSeq("NULLS", "LAST")) {
            explicit = true;
        }
        boolean isDesc = Boolean.TRUE.equals(desc);
        if (!explicit && ((!isDesc && !d.nullsLarge()) || (isDesc && d.nullsLarge()))) {
            nullsFirst = true;
        }
        return Node.of("ordered", "x", x, "desc", desc, "nullsFirst", nullsFirst);
    }

    Node dataType() {
        Tok t = cur();
        if (t.type != T.VAR && t.type != T.IDENT) {
            throw fail();
        }
        i++;
        String name = t.type == T.IDENT ? t.text : t.up;
        String written = t.text;
        if (at("(")) {
            i++;
            List<String> params = new ArrayList<>();
            do {
                if (at("*")) {
                    i++;
                    params.add("*");
                    continue;
                }
                Tok p = cur();
                if (p.type == T.NUMBER || p.type == T.VAR) {
                    i++;
                    StringBuilder sb = new StringBuilder(p.type == T.NUMBER ? p.text : p.up);
                    if (cur().type == T.VAR && (at("CHAR") || at("BYTE"))) {
                        i++;
                    }
                    params.add(sb.toString());
                } else {
                    throw fail();
                }
            } while (match(","));
            expect(")");
            Node ty = Node.of("type", "name", name, "params", params, "text", written);
            typeSuffix(ty);
            return ty;
        }
        Node ty = Node.of("type", "name", name, "text", written);
        typeSuffix(ty);
        return ty;
    }

    private void typeSuffix(Node ty) {
        if (atSeq("WITH", "TIME", "ZONE")) {
            i += 3;
            ty.set("tz", "WITH TIME ZONE");
        } else if (atSeq("WITH", "LOCAL", "TIME", "ZONE")) {
            i += 4;
            ty.set("tz", "WITH LOCAL TIME ZONE");
        } else if (atSeq("WITHOUT", "TIME", "ZONE")) {
            i += 3;
        }
        if (at("UNSIGNED") || at("SIGNED")) {
            i++;
        }
    }

    Node identifier() {
        Tok t = cur();
        if (t.type == T.IDENT) {
            i++;
            return Node.id(t.text, true);
        }
        if (t.type == T.VAR && !d.notId.contains(t.up)) {
            i++;
            return Node.id(t.text, false);
        }
        return null;
    }

    Node identifierOrFail() {
        Node n = identifier();
        if (n == null) {
            throw fail();
        }
        return n;
    }

    // ---- access for the DDL parser -----------------------------------------------------------

    Dialect dialect() {
        return d;
    }

    Tok tok() {
        return cur();
    }

    Tok tok(int k) {
        return peek(k);
    }

    void advance() {
        i++;
    }

    int pos() {
        return i;
    }

    void seek(int p) {
        i = p;
    }

    boolean accept(String word) {
        return match(word);
    }

    boolean acceptSeq(String... words) {
        return matchSeq(words);
    }

    boolean sees(String word) {
        return at(word);
    }

    void require(String word) {
        expect(word);
    }

    static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }
}
