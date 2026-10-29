package com.legacyaudit.engine.analyzers;

import static com.legacyaudit.engine.analyzers.DynSql.concat;
import static com.legacyaudit.engine.analyzers.DynSql.empty;
import static com.legacyaudit.engine.analyzers.DynSql.hole;
import static com.legacyaudit.engine.analyzers.DynSql.lit;
import static com.legacyaudit.engine.analyzers.ts.TsNode.txt;

import com.legacyaudit.engine.analyzers.DynSql.Built;
import com.legacyaudit.engine.analyzers.ts.TsNode;
import com.legacyaudit.engine.inventory.Rx;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL text that C code builds in buffers, and SQL handed to database client APIs.
 *
 * <p>Buffers are followed in source order through {@code strcpy}/{@code strncpy}/{@code strlcpy}
 * (set), {@code strcat}/{@code strncat}/{@code strlcat} (append), {@code sprintf}/
 * {@code snprintf} (format; writing at {@code buf + strlen(buf)} or formatting {@code "%s..."}
 * from the buffer itself is an append), plain assignment of a {@code char *} and initialisers.
 * Pro*C {@code VARCHAR} host variables ({@code v.arr}) are the variable {@code v}.
 * {@code #define} string constants and file-level strings are fragments. Code inside
 * {@code if}/loops/{@code switch} is conditional, so what it adds is an optional fragment.
 *
 * <p>Pro*C looks a host variable up with {@link #valueAt} for {@code PREPARE ... FROM :var} and
 * {@code EXECUTE IMMEDIATE :var}. ODBC / DB2 CLI, OCI, libpq, MySQL and SQLite calls are SQL
 * call sites on their own ({@link #calls}). One instance per file: not thread-safe.
 */
public final class CSql {

    /** Function, then the index of the SQL text argument and the statement source. */
    record Api(int index, String source) {}

    static final Map<String, Api> SQL_APIS = Map.ofEntries(
            Map.entry("SQLExecDirect", new Api(1, "odbc")),
            Map.entry("SQLExecDirectA", new Api(1, "odbc")),
            Map.entry("SQLExecDirectW", new Api(1, "odbc")),
            Map.entry("SQLPrepare", new Api(1, "odbc")),
            Map.entry("SQLPrepareA", new Api(1, "odbc")),
            Map.entry("SQLPrepareW", new Api(1, "odbc")),
            Map.entry("OCIStmtPrepare", new Api(2, "oci")),
            Map.entry("OCIStmtPrepare2", new Api(3, "oci")),
            Map.entry("PQexec", new Api(1, "libpq")),
            Map.entry("PQexecParams", new Api(1, "libpq")),
            Map.entry("PQprepare", new Api(2, "libpq")),
            Map.entry("mysql_query", new Api(1, "mysql")),
            Map.entry("mysql_real_query", new Api(1, "mysql")),
            Map.entry("sqlite3_exec", new Api(1, "sqlite")),
            Map.entry("sqlite3_prepare", new Api(1, "sqlite")),
            Map.entry("sqlite3_prepare_v2", new Api(1, "sqlite")));
    private static final Set<String> COPY_FUNCS = Set.of("strcpy", "strncpy", "strlcpy", "lstrcpy", "strcpy_s", "strncpy_s", "_mbscpy");
    private static final Set<String> CAT_FUNCS = Set.of("strcat", "strncat", "strlcat", "lstrcat", "strcat_s", "strncat_s", "_mbscat");
    /** Function, then the index of the format argument. */
    private static final Map<String, Integer> FORMAT_FUNCS = Map.of("sprintf", 1, "snprintf", 2, "_snprintf", 2, "sprintf_s", 2, "vsprintf", 1);
    private static final Set<String> COND_NODES = Set.of("if_statement", "while_statement", "for_statement", "do_statement", "switch_statement",
            "case_statement");
    // Possessive loops: the alternatives never overlap, so they match what the Python ones do.
    private static final Pattern DEFINE_STRING = Rx.of("^\\s*((?:\"(?:[^\"\\\\]|\\\\.)*+\"\\s*)++)$");
    private static final Pattern C_STRING = Rx.of("\"((?:[^\"\\\\]|\\\\.)*+)\"");
    private static final Pattern ESCAPE = Rx.of("\\\\(.)");
    private static final Map<String, String> ESCAPES = Map.of("n", "\n", "t", "\t", "r", "\r", "\\", "\\", "\"", "\"", "'", "'", "0", "");

    /** One database API call: line, function, source, the rebuilt text (or null) and the argument as written. */
    public record Call(int line, String fn, String source, Built built, String raw) {}

    private record Target(String name, boolean atEnd) {}

    private final DynSql.Tracker tracker = new DynSql.Tracker();
    private final Map<String, Built> constants = new LinkedHashMap<>();
    public final List<Call> calls = new ArrayList<>();
    private final Set<String> consumed = new HashSet<>();
    private final Set<String> usedConstants = new HashSet<>();

    static String unescape(String raw) {
        return TextUtil.sub(ESCAPE, raw, m -> ESCAPES.getOrDefault(m.group(1), m.group(1)));
    }

    private static int line(TsNode node) {
        return node.line();
    }

    private static String declaredName(TsNode node) {
        while (node != null && !node.type.equals("identifier")) {
            node = node.field("declarator");
        }
        return node != null ? txt(node) : null;
    }

    // ---- lookup -----------------------------------------------------------------------------

    /** What {@code name} ({@code :var} or {@code :var:indicator}) held just before {@code line}. */
    public Built valueAt(String name, int line) {
        String key = TextUtil.strip(TextUtil.beforeFirst(TextUtil.lstrip(name, ":"), ":"));
        consumed.add(key);
        return tracker.at(key, line);
    }

    /**
     * Final values of buffers no SQL call site consumed (call sites behind a wrapper function)
     * and string constants never used as a fragment.
     */
    public List<Built> leftovers() {
        List<Built> values = new ArrayList<>();
        tracker.current().forEach((k, v) -> {
            if (!consumed.contains(k)) {
                values.add(v);
            }
        });
        constants.forEach((k, v) -> {
            if (!usedConstants.contains(k)) {
                values.add(v);
            }
        });
        values.removeIf(v -> TextUtil.strip(v.text).isEmpty());
        return values;
    }

    // ---- walk -------------------------------------------------------------------------------

    private record Item(TsNode node, boolean cond) {}

    public void run(TsNode root) {
        Deque<Item> stack = new ArrayDeque<>();
        stack.push(new Item(root, false));
        while (!stack.isEmpty()) {
            Item item = stack.pop();
            TsNode n = item.node();
            boolean cond = item.cond();
            String t = n.type;
            if (t.equals("preproc_def")) {
                define(n);
                continue;
            }
            if (t.equals("declaration")) {
                declaration(n);
            } else if (t.equals("expression_statement") && statement(n, cond)) {
                continue;
            } else if (t.equals("call_expression")) {
                api(n);
            }
            boolean inner = cond || COND_NODES.contains(t);
            TsNode test = COND_NODES.contains(t) ? n.field("condition") : null;
            for (int i = n.children.size() - 1; i >= 0; i--) {
                TsNode c = n.children.get(i);
                stack.push(new Item(c, c == test ? cond : inner));
            }
        }
    }

    private void define(TsNode n) {
        String name = txt(n.field("name"));
        String value = txt(n.field("value"));
        if (!name.isEmpty() && DEFINE_STRING.matcher(value).lookingAt()) {
            StringBuilder text = new StringBuilder();
            Matcher m = C_STRING.matcher(value);
            while (m.find()) {
                text.append(unescape(m.group(1)));
            }
            constants.put(name, lit(text.toString(), line(n)));
        }
    }

    private void declaration(TsNode n) {
        for (TsNode d : n.namedChildren()) {
            if (!d.type.equals("init_declarator")) {
                continue;
            }
            String name = declaredName(d.field("declarator"));
            TsNode value = d.field("value");
            Built b = value != null ? eval(value) : null;
            if (name != null && !name.isEmpty() && b != null) {
                tracker.assign(name, b, line(d), false, false);
            }
        }
    }

    private boolean statement(TsNode n, boolean cond) {
        List<TsNode> named = n.namedChildren();
        TsNode expr = named.isEmpty() ? null : named.getFirst();
        if (expr == null) {
            return false;
        }
        if (expr.type.equals("assignment_expression")) {
            TsNode left = expr.field("left");
            TsNode right = expr.field("right");
            if (left == null || right == null || !left.type.equals("identifier")) {
                return false;
            }
            if (!txt(expr.field("operator")).equals("=")) {
                return false;
            }
            String name = txt(left);
            Built b = eval(right);
            if (b == null) {
                if (tracker.get(name) == null) {
                    return false;
                }
                b = hole("", line(right));
            }
            tracker.assign(name, b, line(n), cond, false);
            return true;
        }
        if (expr.type.equals("call_expression")) {
            api(expr);
            build(expr, cond);
            return true;
        }
        return false;
    }

    private static List<TsNode> arguments(TsNode call) {
        TsNode args = call.field("arguments");
        return args != null ? args.namedChildren() : List.of();
    }

    private void build(TsNode call, boolean cond) {
        String fn = txt(call.field("function"));
        List<TsNode> argv = arguments(call);
        if (argv.isEmpty() || !(COPY_FUNCS.contains(fn) || CAT_FUNCS.contains(fn) || FORMAT_FUNCS.containsKey(fn))) {
            return;
        }
        Target target = target(argv.getFirst());
        if (target == null) {
            return;
        }
        String name = target.name();
        boolean atEnd = target.atEnd();
        Built prev = tracker.get(name);
        int line = line(call);
        String before = prev != null && (atEnd || CAT_FUNCS.contains(fn)) ? prev.text : "";
        if (FORMAT_FUNCS.containsKey(fn)) {
            int k = FORMAT_FUNCS.get(fn);
            Built fmt = k < argv.size() ? eval(argv.get(k)) : null;
            if (fmt == null) {
                if (prev == null) {
                    return;
                }
                tracker.assign(name, hole(before, line), line, cond, atEnd);
                return;
            }
            List<TsNode> rest = argv.subList(k + 1, argv.size());
            Target first = rest.isEmpty() ? null : target(rest.getFirst());
            if (!atEnd && first != null && first.name().equals(name) && fmt.text.startsWith("%s")) {
                // sprintf(sql, "%s AND x = %d", sql, v): append the rest
                fmt = lit(fmt.text.substring(2), fmt.line);
                rest = rest.subList(1, rest.size());
                atEnd = true;
                before = prev != null ? prev.text : "";
            }
            List<Built> values = new ArrayList<>();
            for (TsNode a : rest) {
                values.add(eval(a));
            }
            tracker.assign(name, DynSql.formatC(fmt, values, line, before), line, cond, atEnd);
            return;
        }
        int k = fn.endsWith("_s") ? 2 : 1; // strcpy_s(dst, size, src)
        Built got = k < argv.size() ? eval(argv.get(k)) : null;
        if (got == null) {
            if (prev == null) {
                return;
            }
            got = hole(before, line);
        }
        Built value = got;
        if (CAT_FUNCS.contains(fn)) {
            value = concat(empty(line), value);
            value.how.add("concatenation");
        }
        tracker.assign(name, value, line, cond, atEnd || CAT_FUNCS.contains(fn));
    }

    /** The buffer a pointer expression writes to, and whether it writes at its end. */
    private Target target(TsNode node) {
        String t = node.type;
        switch (t) {
            case "identifier" -> {
                return new Target(txt(node), false);
            }
            case "parenthesized_expression", "cast_expression" -> {
                TsNode inner = node.field("value");
                if (inner == null) {
                    List<TsNode> named = node.namedChildren();
                    inner = named.isEmpty() ? null : named.getLast();
                }
                return inner != null ? target(inner) : null;
            }
            case "field_expression" -> {
                TsNode arg = node.field("argument");
                if (txt(node.field("field")).equals("arr") && arg != null) {
                    return target(arg); // Pro*C VARCHAR: v.arr is v
                }
                return new Target(txt(node), false);
            }
            case "pointer_expression" -> {
                TsNode arg = node.field("argument");
                return arg != null ? target(arg) : null;
            }
            case "subscript_expression" -> {
                TsNode arg = node.field("argument");
                String index = TextUtil.strip(txt(node.field("index")));
                Target got = arg != null ? target(arg) : null;
                if (got == null) {
                    return null;
                }
                return new Target(got.name(), !index.equals("0") && !index.isEmpty());
            }
            case "binary_expression" -> {
                if (!txt(node.field("operator")).equals("+")) {
                    return null;
                }
                TsNode left = node.field("left");
                Target got = left != null ? target(left) : null;
                return got != null ? new Target(got.name(), true) : null;
            }
            default -> {
                return null;
            }
        }
    }

    // ---- values -----------------------------------------------------------------------------

    Built eval(TsNode node) {
        if (node == null) {
            return null;
        }
        int line = line(node);
        switch (node.type) {
            case "string_literal" -> {
                StringBuilder sb = new StringBuilder();
                for (TsNode c : node.children) {
                    if (c.type.equals("escape_sequence")) {
                        sb.append(unescape(txt(c)));
                    } else if (c.type.equals("string_content")) {
                        sb.append(txt(c));
                    }
                }
                return lit(sb.toString(), line);
            }
            case "concatenated_string" -> {
                List<Built> parts = new ArrayList<>();
                for (TsNode c : node.namedChildren()) {
                    Built b = eval(c);
                    if (b == null) {
                        StringBuilder before = new StringBuilder();
                        for (Built p : parts) {
                            before.append(p.text);
                        }
                        b = hole(before.toString(), line);
                    }
                    parts.add(b);
                }
                Built out = empty(line);
                for (Built p : parts) {
                    out = concat(out, p);
                }
                return out;
            }
            case "identifier" -> {
                String name = txt(node);
                Built got = tracker.get(name);
                if (got == null && constants.containsKey(name)) {
                    usedConstants.add(name);
                    return constants.get(name);
                }
                return got;
            }
            case "cast_expression" -> {
                return eval(node.field("value"));
            }
            case "parenthesized_expression" -> {
                List<TsNode> named = node.namedChildren();
                return named.isEmpty() ? null : eval(named.getFirst());
            }
            case "field_expression" -> {
                Target target = target(node);
                return target != null && !target.atEnd() ? tracker.get(target.name()) : null;
            }
            case "conditional_expression" -> {
                Built a = eval(node.field("consequence"));
                Built b = eval(node.field("alternative"));
                return a != null && b != null ? DynSql.choice(a, b) : null;
            }
            default -> {
                return null;
            }
        }
    }

    private void api(TsNode call) {
        String fn = TextUtil.afterLast(txt(call.field("function")), "->");
        Api api = SQL_APIS.get(fn);
        if (api == null) {
            return;
        }
        List<TsNode> argv = arguments(call);
        if (api.index() >= argv.size()) {
            return;
        }
        TsNode arg = argv.get(api.index());
        Target target = target(arg);
        if (target != null) {
            consumed.add(target.name());
            usedConstants.add(target.name());
        }
        calls.add(new Call(line(call), fn, api.source(), eval(arg), txt(arg)));
    }
}
