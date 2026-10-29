package com.legacyaudit.engine.analyzers;

import static com.legacyaudit.engine.analyzers.BuildAnalyzer.tech;
import static com.legacyaudit.engine.analyzers.DynSql.concat;
import static com.legacyaudit.engine.analyzers.DynSql.hole;
import static com.legacyaudit.engine.analyzers.DynSql.lit;
import static com.legacyaudit.engine.analyzers.Integrations.integration;
import static com.legacyaudit.engine.analyzers.ts.TsNode.txt;

import com.legacyaudit.engine.analyzers.DynSql.Built;
import com.legacyaudit.engine.analyzers.ts.TsNode;
import com.legacyaudit.engine.analyzers.ts.TsTree;
import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Category;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Schemas;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.treesitter.TSParser;
import org.treesitter.TreeSitterTsx;
import org.treesitter.TreeSitterTypescript;

/**
 * TypeScript and JavaScript (tree-sitter-typescript): Angular, AngularJS and Node.js code. One
 * parse per file finds:
 *
 * <ul>
 *   <li><b>Angular</b> (2+): components ({@code @Component}) with their template and selector,
 *       the router ({@code Routes} arrays, lazy {@code loadComponent}), the bootstrap component,
 *       {@code HttpClient} calls;
 *   <li><b>AngularJS</b> (1.x): modules, controllers, components, {@code $routeProvider}/
 *       {@code $stateProvider} routes and their templates, {@code $http}/{@code $resource} calls;
 *   <li><b>Node.js services</b>: Express/Koa/Fastify/hapi routes and NestJS controllers as
 *       services, the server start-up as an entry point, {@code app.use('/prefix', router)}
 *       mounts (applied by the linker), outgoing calls ({@code fetch}, {@code axios}, ...);
 *   <li><b>database access</b>: SQL in strings and template literals (rebuilt like the Java
 *       analyzer: {@code +}, {@code +=} and {@code ${...}} pieces become holes), TypeORM, Prisma,
 *       Sequelize and Knex calls as ORM statements on their entity, entity-to-table mappings,
 *       database connection settings;
 *   <li><b>scheduled jobs</b>: {@code node-cron}, {@code cron}, {@code node-schedule}, Agenda and
 *       NestJS {@code @Cron}/{@code @Interval}/{@code @Timeout} as batch jobs (scheduler
 *       {@code node-cron});
 *   <li>URLs in strings as integrations (HTTP, SOAP, FTP, database and AMQP connection strings).
 * </ul>
 *
 * <p>Prisma schemas ({@code schema.prisma}) are read here too: their models map entities to
 * tables. {@code .ts}, {@code .mts} and {@code .cts} files use the TypeScript grammar; JavaScript
 * and {@code .tsx} files use the TSX grammar (it accepts JSX).
 *
 * <p>Templates ({@code .html}) are read by the {@code web} analyzer; a component points at its
 * template with a {@code renders} reference and routes point at components with {@code forwards}
 * references, so the linker can tell routed screens from sub-components (decision 130).
 */
public final class TypeScriptAnalyzer implements Analyzer {

    private static final ThreadLocal<TSParser> TS = TsTree.parsers(TreeSitterTypescript::new);
    private static final ThreadLocal<TSParser> TSX = TsTree.parsers(TreeSitterTsx::new);

    static final Set<String> HTTP_VERBS = Set.of("get", "post", "put", "delete", "patch", "head", "options", "all", "del");
    static final Map<String, String> NEST_VERBS = Map.of("Get", "GET", "Post", "POST", "Put", "PUT", "Delete", "DELETE", "Patch", "PATCH",
            "Options", "OPTIONS", "Head", "HEAD", "All", "ANY");
    // Receivers of Express-style route definitions: app.get(...), router.post(...), ...
    private static final Pattern ROUTER_OBJ = Rx.of("^(app|api|server|router|\\w*Router|routes|fastify|instance|r)$", Rx.I);
    private static final Pattern HTTP_CLIENT_OBJ = Rx.of("(^|\\.)(http|httpClient|\\$http|axios|client|api|ky|got|superagent)$", Rx.I);
    private static final Set<String> HTTP_FUNCS = Set.of("fetch", "axios", "got", "request", "needle", "ky");
    private static final Set<String> DB_CALLS = Set.of("query", "execute", "raw", "$queryRaw", "$executeRaw", "$queryRawUnsafe",
            "$executeRawUnsafe", "none", "one", "oneOrNone", "many", "manyOrNone", "any", "result");
    private static final Set<String> DB_TAGS = Set.of("sql", "SQL", "$queryRaw", "$executeRaw", "raw");
    private static final Map<String, String> TYPEORM_OPS = Map.ofEntries(Map.entry("find", "SELECT"), Map.entry("findOne", "SELECT"),
            Map.entry("findBy", "SELECT"), Map.entry("findOneBy", "SELECT"), Map.entry("findAndCount", "SELECT"),
            Map.entry("findOneOrFail", "SELECT"), Map.entry("findByIds", "SELECT"), Map.entry("count", "SELECT"), Map.entry("countBy", "SELECT"),
            Map.entry("exist", "SELECT"), Map.entry("exists", "SELECT"), Map.entry("save", "INSERT"), Map.entry("insert", "INSERT"),
            Map.entry("update", "UPDATE"), Map.entry("upsert", "MERGE"), Map.entry("delete", "DELETE"), Map.entry("remove", "DELETE"),
            Map.entry("softDelete", "UPDATE"), Map.entry("increment", "UPDATE"), Map.entry("decrement", "UPDATE"),
            Map.entry("createQueryBuilder", "SELECT"));
    private static final Map<String, String> PRISMA_OPS = Map.ofEntries(Map.entry("findMany", "SELECT"), Map.entry("findUnique", "SELECT"),
            Map.entry("findFirst", "SELECT"), Map.entry("findUniqueOrThrow", "SELECT"), Map.entry("findFirstOrThrow", "SELECT"),
            Map.entry("count", "SELECT"), Map.entry("aggregate", "SELECT"), Map.entry("groupBy", "SELECT"), Map.entry("create", "INSERT"),
            Map.entry("createMany", "INSERT"), Map.entry("update", "UPDATE"), Map.entry("updateMany", "UPDATE"), Map.entry("upsert", "MERGE"),
            Map.entry("delete", "DELETE"), Map.entry("deleteMany", "DELETE"));
    private static final Map<String, String> SEQUELIZE_OPS = Map.of("findAll", "SELECT", "findByPk", "SELECT", "findAndCountAll", "SELECT",
            "findOrCreate", "MERGE", "bulkCreate", "INSERT", "destroy", "DELETE");
    private static final Map<String, String> SEQUELIZE_WEAK_OPS = Map.of("findOne", "SELECT", "create", "INSERT", "update", "UPDATE",
            "count", "SELECT", "upsert", "MERGE", "max", "SELECT", "min", "SELECT", "sum", "SELECT");
    /** Database type names in code and configuration: product (in this order: the first key of a product is its scheme). */
    static final Map<String, String> DB_TYPES = WebAnalyzer.ordered(
            "postgres", "PostgreSQL",
            "postgresql", "PostgreSQL",
            "pg", "PostgreSQL",
            "mysql", "MySQL",
            "mariadb", "MariaDB",
            "oracle", "Oracle",
            "oracledb", "Oracle",
            "mssql", "SQL Server",
            "sqlserver", "SQL Server",
            "sqlite", "SQLite",
            "better-sqlite3", "SQLite",
            "mongodb", "MongoDB",
            "cockroachdb", "CockroachDB",
            "db2", "DB2");
    private static final Pattern DB_URL = Rx.of("\\b(postgres(?:ql)?|mysql|mariadb|mongodb(?:\\+srv)?|mssql|sqlserver|oracle|redis|rediss|"
            + "amqps?)://[^\\s\"'`<>()\\\\]+", Rx.I);
    private static final Pattern URL = Rx.of("\\b(?:https?|ftps?|sftp|ldaps?|smtp)://[^\\s\"'`<>()\\\\]+", Rx.I);
    private static final Pattern HOST_PATH = Rx.of("^[\\w+]+://([^/?#]+)(/[^?#]*)?");
    /** NestJS CronExpression names in plain cron (minute hour day month weekday). */
    static final Map<String, String> NEST_CRON = Map.ofEntries(
            Map.entry("EVERY_MINUTE", "* * * * *"),
            Map.entry("EVERY_5_MINUTES", "*/5 * * * *"),
            Map.entry("EVERY_10_MINUTES", "*/10 * * * *"),
            Map.entry("EVERY_30_MINUTES", "*/30 * * * *"),
            Map.entry("EVERY_HOUR", "0 * * * *"),
            Map.entry("EVERY_DAY_AT_MIDNIGHT", "0 0 * * *"),
            Map.entry("EVERY_DAY_AT_1AM", "0 1 * * *"),
            Map.entry("EVERY_DAY_AT_2AM", "0 2 * * *"),
            Map.entry("EVERY_DAY_AT_3AM", "0 3 * * *"),
            Map.entry("EVERY_DAY_AT_4AM", "0 4 * * *"),
            Map.entry("EVERY_DAY_AT_5AM", "0 5 * * *"),
            Map.entry("EVERY_DAY_AT_6AM", "0 6 * * *"),
            Map.entry("EVERY_DAY_AT_NOON", "0 12 * * *"),
            Map.entry("EVERY_WEEK", "0 0 * * 0"),
            Map.entry("EVERY_WEEKDAY", "0 0 * * 1-5"),
            Map.entry("EVERY_WEEKEND", "0 0 * * 6,0"),
            Map.entry("EVERY_1ST_DAY_OF_MONTH_AT_MIDNIGHT", "0 0 1 * *"),
            Map.entry("EVERY_YEAR", "0 0 1 1 *"));
    private static final Pattern PRISMA_MODEL = Rx.of("^\\s*model\\s+(\\w+)\\s*\\{(.*?)^\\s*\\}", Rx.M | Pattern.DOTALL);
    private static final Pattern PRISMA_MAP = Rx.of("@@map\\(\\s*\"([^\"]+)\"");
    private static final Pattern PRISMA_PROVIDER = Rx.of("datasource\\s+\\w+\\s*\\{[^}]*provider\\s*=\\s*\"(\\w+)\"", Pattern.DOTALL);
    private static final Pattern QUERY = Rx.of("[?&][\\w.-]+=.*$");
    private static final Pattern ROUTER_VALUE = Rx.of("(^|\\.)Router\\(\\)$|^express\\.Router\\b|^new (Koa)?Router\\b");
    private static final Pattern REPOSITORY = Rx.of("\\bRepository\\s*<\\s*(\\w+)");
    private static final Pattern GET_REPOSITORY = Rx.of("getRepository\\s*\\(\\s*(\\w+)");
    private static final Pattern LOAD_COMPONENT = Rx.of("\\.(\\w+Component)\\b");
    private static final Pattern PRISMA_CLIENT = Rx.of("(^|\\.)(prisma|db|tx)$", Rx.I);
    private static final Pattern CRON = Rx.of("cron", Rx.I);
    private static final Pattern AGENDA = Rx.of("agenda", Rx.I);
    private static final Pattern DOTTED = Rx.of("^[\\w.]+$");
    private static final Pattern EXTENDS_MODEL = Rx.of("extends\\s+Model\\b");
    private static final Pattern TAG = Rx.of("<([a-z][\\w]*-[\\w-]+)");
    private static final Pattern KEBAB = Rx.of("([a-z0-9])([A-Z])");
    private static final Pattern CRON_FIELD = Rx.of("^[\\d*/,?LW#A-Za-z-]+$");
    private static final Pattern MAIN_MODULE = Rx.of("require\\.main\\s*===?\\s*module");
    private static final Set<String> FUNCTIONS = Set.of("function_declaration", "function_expression", "arrow_function", "method_definition",
            "generator_function_declaration");
    private static final Set<String> BRANCHES = Set.of("if_statement", "for_statement", "for_in_statement", "while_statement",
            "do_statement", "switch_case", "catch_clause", "ternary_expression");
    private static final Set<String> STRING_EXPRS = Set.of("string", "template_string", "binary_expression");

    @Override
    public String name() {
        return "typescript";
    }

    @Override
    public String version() {
        return "2"; // 2: web services offered and called
    }

    @Override
    public boolean supports(FileRecord file) {
        if (file.language.equals("prisma")) {
            return true;
        }
        return (file.language.equals("typescript") || file.language.equals("javascript"))
                && file.category != Category.TEST && file.category != Category.GENERATED && file.category != Category.BINARY
                && !file.path.contains("node_modules/") && !file.path.endsWith(".d.ts");
    }

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        String text = ctx.textOrEmpty();
        Facts facts = new Facts();
        facts.coverage.lines = TextUtil.count(text, '\n') + 1;
        if (file.language.equals("prisma")) {
            prisma(file.path, text, facts);
            return facts;
        }
        String ext = TextUtil.afterLast(file.path, ".").toLowerCase(Locale.ROOT);
        TSParser parser = (ext.equals("ts") || ext.equals("mts") || ext.equals("cts") ? TS : TSX).get();
        TsNode root = TsTree.parse(parser, text);
        if (root.hasError) {
            JavaAnalyzer.errors(root, facts, "TypeScript");
        }
        new Visitor(file, ctx, facts, text).run(root);
        return facts;
    }

    private static void prisma(String path, String text, Facts facts) {
        Matcher m = PRISMA_MODEL.matcher(text);
        while (m.find()) {
            Matcher mapped = PRISMA_MAP.matcher(m.group(2));
            int line = TextUtil.count(text.substring(0, m.start()), '\n') + 1;
            facts.symbol("jpa-entity", m.group(1), path, line)
                    .data("table", (mapped.find() ? mapped.group(1) : m.group(1)).toUpperCase(Locale.ROOT))
                    .data("orm", "prisma");
        }
        Matcher p = PRISMA_PROVIDER.matcher(text);
        if (p.find() && DB_TYPES.containsKey(p.group(1).toLowerCase(Locale.ROOT))) {
            int line = TextUtil.count(text.substring(0, p.start()), '\n') + 1;
            facts.add("tech_stack", tech(DB_TYPES.get(p.group(1).toLowerCase(Locale.ROOT)), null, "database", path, line));
        }
    }

    // ---- helpers on the syntax tree -------------------------------------------------------

    /** The value of a plain string literal (or a template literal without {@code ${}}), or null. */
    static String stringValue(TsNode node) {
        if (node == null) {
            return null;
        }
        if (node.type.equals("string")) {
            StringBuilder out = new StringBuilder();
            for (TsNode c : node.namedChildren()) {
                if (c.type.equals("string_fragment") || c.type.equals("escape_sequence")) {
                    out.append(txt(c));
                }
            }
            return out.toString();
        }
        if (node.type.equals("template_string") && node.namedChildren().stream().noneMatch(c -> c.type.equals("template_substitution"))) {
            String t = txt(node);
            int n = t.codePointCount(0, t.length());
            return n <= 2 ? "" : t.substring(t.offsetByCodePoints(0, 1), t.offsetByCodePoints(0, n - 1));
        }
        return null;
    }

    /** Properties of an object literal: key to value node (shorthand {@code { path }} too). */
    static Map<String, TsNode> pairs(TsNode obj) {
        Map<String, TsNode> out = new LinkedHashMap<>();
        if (obj == null) {
            return out;
        }
        for (TsNode c : obj.namedChildren()) {
            if (c.type.equals("pair")) {
                TsNode key = c.field("key");
                TsNode value = c.field("value");
                if (key != null && value != null) {
                    String k = key.type.equals("string") ? stringValue(key) : txt(key);
                    out.put(k == null || k.isEmpty() ? txt(key) : k, value);
                }
            } else if (c.type.equals("shorthand_property_identifier")) {
                out.put(txt(c), c);
            }
        }
        return out;
    }

    /** Receiver text, function name and receiver node of a call: {@code this.http.get} gives ({@code this.http}, {@code get}, node). */
    record Callee(String obj, String name, TsNode objNode) {}

    static Callee callee(TsNode call) {
        TsNode fn = call.field("function");
        if (fn == null) {
            return new Callee("", "", null);
        }
        if (fn.type.equals("member_expression")) {
            TsNode obj = fn.field("object");
            return new Callee(txt(obj), txt(fn.field("property")), obj);
        }
        return new Callee("", txt(fn), null);
    }

    static List<TsNode> args(TsNode call) {
        TsNode args = call.field("arguments");
        if (args == null) {
            return List.of();
        }
        if (args.type.equals("template_string")) { // tagged template: sql`...`
            return List.of(args);
        }
        List<TsNode> out = new ArrayList<>();
        for (TsNode a : args.namedChildren()) {
            if (!a.type.equals("comment")) {
                out.add(a);
            }
        }
        return out;
    }

    private static TsNode prevNamedSibling(TsNode node) {
        TsNode parent = node.parent;
        if (parent == null) {
            return null;
        }
        int i = 0;
        while (i < parent.children.size() && parent.children.get(i) != node) {
            i++;
        }
        for (int j = i - 1; j >= 0; j--) {
            if (parent.children.get(j).named) {
                return parent.children.get(j);
            }
        }
        return null;
    }

    /**
     * Decorators of a class (children, or of the wrapping {@code export}) or of a class member
     * (the decorator siblings just before it).
     */
    static List<TsNode> decorators(TsNode node) {
        List<TsNode> out = new ArrayList<>();
        for (TsNode c : node.children) {
            if (c.type.equals("decorator")) {
                out.add(c);
            }
        }
        TsNode parent = node.parent;
        if ((node.type.equals("class_declaration") || node.type.equals("abstract_class_declaration")) && parent != null) {
            if (parent.type.equals("export_statement")) {
                List<TsNode> outer = new ArrayList<>();
                for (TsNode c : parent.children) {
                    if (c.type.equals("decorator")) {
                        outer.add(c);
                    }
                }
                outer.addAll(out);
                out = outer;
            }
        } else if (parent != null && parent.type.equals("class_body")) {
            TsNode prev = prevNamedSibling(node);
            while (prev != null && prev.type.equals("decorator")) {
                out.addFirst(prev);
                prev = prevNamedSibling(prev);
            }
        }
        return out;
    }

    record Decorator(String name, List<TsNode> args) {}

    /** Name and arguments of {@code @Name(args)} or {@code @Name}. */
    static Decorator decorator(TsNode dec) {
        List<TsNode> named = dec.namedChildren();
        if (named.isEmpty()) {
            return new Decorator("", List.of());
        }
        TsNode expr = named.getFirst();
        if (expr.type.equals("call_expression")) {
            return new Decorator(TextUtil.afterLast(txt(expr.field("function")), "."), args(expr));
        }
        return new Decorator(TextUtil.afterLast(txt(expr), "."), List.of());
    }

    /**
     * The path part of a URL built in code, for matching an API route: holes ({@code ?}) before
     * the first {@code /} are the base URL; {@code http://host/x} gives {@code /x}. Null for
     * absolute URLs to another system (they are integrations) and for text that is not a path.
     */
    public static String urlPath(String text) {
        String t = TextUtil.strip(text);
        if (t.contains("://")) {
            return null;
        }
        if (t.startsWith("?")) {
            t = TextUtil.lstrip(t, "?");
            if (!t.startsWith("/")) {
                t = "/" + t;
            }
        }
        t = TextUtil.beforeFirst(QUERY.matcher(t).replaceAll(""), "#"); // query string
        if (!t.startsWith("/") || t.contains(" ") || t.codePointCount(0, t.length()) < 2) {
            return null;
        }
        return t;
    }

    static List<String> idents(TsNode node) {
        List<String> out = new ArrayList<>();
        if (node == null) {
            return out;
        }
        if (node.type.equals("identifier") || node.type.equals("shorthand_property_identifier_pattern")) {
            out.add(txt(node));
            return out;
        }
        for (TsNode c : node.namedChildren()) {
            if (c.type.equals("import_specifier")) {
                TsNode alias = c.field("alias");
                out.add(txt(alias != null ? alias : c.field("name")));
            } else {
                out.addAll(idents(c));
            }
        }
        return out;
    }

    /** Nodes of the given types under {@code node} (itself included), in source order. */
    static List<TsNode> iter(TsNode node, Set<String> kinds) {
        List<TsNode> out = new ArrayList<>();
        if (node == null) {
            return out;
        }
        List<TsNode> stack = new ArrayList<>();
        stack.add(node);
        while (!stack.isEmpty()) {
            TsNode n = stack.removeLast();
            if (kinds.contains(n.type)) {
                out.add(n);
            }
            for (int i = n.children.size() - 1; i >= 0; i--) {
                stack.add(n.children.get(i));
            }
        }
        return out;
    }

    static TsNode functionParent(TsNode node) {
        for (TsNode p = node.parent; p != null; p = p.parent) {
            if (FUNCTIONS.contains(p.type)) {
                return p;
            }
        }
        return null;
    }

    /** Paths of the routes a child route is nested in ({@code children: [...]}), outermost first. */
    static List<String> parentRoutes(TsNode obj) {
        List<String> out = new ArrayList<>();
        TsNode n = obj.parent;
        while (n != null) {
            if (n.type.equals("pair") && txt(n.field("key")).equals("children")) {
                TsNode parentObj = n.parent;
                if (parentObj != null && parentObj.type.equals("object")) {
                    Map<String, TsNode> p = pairs(parentObj);
                    String path = p.containsKey("path") ? stringValue(p.get("path")) : null;
                    if (path != null && !path.isEmpty()) {
                        out.addFirst(path);
                    }
                    n = parentObj;
                }
            }
            n = n.parent;
        }
        return out;
    }

    /** The first function an inline handler calls ({@code () => runNightly()} gives {@code runNightly}). */
    static String calledIn(TsNode node) {
        if (node == null) {
            return "";
        }
        for (TsNode c : iter(node, Set.of("call_expression"))) {
            String name = txt(c.field("function"));
            if (!name.isEmpty() && DOTTED.matcher(name).find() && !name.startsWith("console.") && !name.startsWith("logger.")) {
                return name.startsWith("this.") ? name.substring(5) : name;
            }
        }
        return "";
    }

    /** A cron expression with 5 fields (a leading seconds field is dropped); null otherwise. */
    public static String cron5(String expr) {
        List<String> parts = TextUtil.words(expr);
        if (parts.size() == 6) {
            parts = parts.subList(1, 6);
        }
        if (parts.size() == 5 && parts.stream().allMatch(p -> CRON_FIELD.matcher(p).find())) {
            return String.join(" ", parts);
        }
        return null;
    }

    /** {@code @Interval(60000)} gives {@code every 60 s}. */
    static String every(String ms) {
        String digits = ms.replace("_", "");
        if (!TextUtil.isDigits(digits)) {
            return ms.isEmpty() ? null : "every " + ms;
        }
        BigInteger n = new BigInteger(digits.chars().map(c -> Character.digit(c, 10)).collect(StringBuilder::new,
                StringBuilder::append, StringBuilder::append).toString());
        BigInteger hour = BigInteger.valueOf(3_600_000);
        BigInteger minute = BigInteger.valueOf(60_000);
        BigInteger second = BigInteger.valueOf(1000);
        if (n.mod(hour).signum() == 0) {
            return "every " + n.divide(hour) + " h";
        }
        if (n.mod(minute).signum() == 0) {
            return "every " + n.divide(minute) + " min";
        }
        return n.compareTo(second) >= 0 ? "every " + n.divide(second) + " s" : "every " + n + " ms";
    }

    private static String or(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }

    /** String values known in a function body: variables and their rebuilt text. */
    private static final class Scope {
        final Map<String, Built> values = new LinkedHashMap<>();
        final Set<String> consumed = new HashSet<>();
    }

    private record Seen(int line, String text) {}

    private static final class Visitor {
        private final String path;
        private final Facts facts;
        private final String text;
        private final String dialect;
        private final Map<String, String> imports = new HashMap<>(); // local name -> module
        private final Set<String> modules = new HashSet<>();
        private final Map<String, Built> consts = new HashMap<>(); // file constants and this.fields
        private final Map<String, String> repos = new HashMap<>(); // repository variable -> entity (TypeORM)
        private final Set<String> routers = new HashSet<>(); // variables holding an Express/Koa router
        private final Set<Integer> done = new HashSet<>(); // start bytes of string expressions already used
        private boolean soap;
        private boolean angularjs;
        private final Set<Seen> sqlSeen = new HashSet<>();
        private final Set<Seen> urlSeen = new HashSet<>();

        Visitor(FileRecord file, AnalyzerContext ctx, Facts facts, String text) {
            this.path = file.path;
            this.facts = facts;
            this.text = text;
            this.dialect = ctx.config().sqlDialect();
        }

        // ---- driving --------------------------------------------------------------------

        void run(TsNode root) {
            imports(root);
            constants(root);
            Scope top = new Scope();
            walk(root, top, false);
            leftovers(top);
            fileLevel();
        }

        private void imports(TsNode root) {
            List<TsNode> stack = new ArrayList<>();
            stack.add(root);
            while (!stack.isEmpty()) {
                TsNode n = stack.removeLast();
                if (n.type.equals("import_statement")) {
                    String src = stringValue(n.field("source"));
                    if (src != null && !src.isEmpty()) {
                        modules.add(src);
                        TsNode clause = null;
                        for (TsNode c : n.namedChildren()) {
                            if (c.type.equals("import_clause")) {
                                clause = c;
                                break;
                            }
                        }
                        for (String ident : idents(clause)) {
                            imports.put(ident, src);
                        }
                    }
                    continue;
                }
                if (n.type.equals("call_expression")) {
                    TsNode fn = n.field("function");
                    if (fn != null && txt(fn).equals("require")) {
                        List<TsNode> args = args(n);
                        String src = args.isEmpty() ? null : stringValue(args.getFirst());
                        if (src != null && !src.isEmpty()) {
                            modules.add(src);
                            TsNode decl = n.parent;
                            if (decl != null && decl.type.equals("variable_declarator")) {
                                for (String ident : idents(decl.field("name"))) {
                                    imports.put(ident, src);
                                }
                            }
                        }
                    }
                }
                stack.addAll(n.children);
            }
            for (String module : new TreeSet<>(modules)) {
                if (module.startsWith(".")) { // the linker resolves it to a file (uses edge)
                    String target = TextUtil.normpath(TextUtil.joinPath(TextUtil.dirname(path), module));
                    facts.ref("uses", "file:" + path, path, target, List.of("module"), path, null).reportUnresolved(false);
                }
            }
            soap = modules.contains("soap") || modules.contains("strong-soap");
            angularjs = text.contains("angular.module(") || modules.contains("angular");
        }

        /**
         * File-level {@code const X = '...'} and class fields {@code baseUrl = '...'} (as
         * {@code this.baseUrl}), and TypeORM repositories ({@code Repository<Order>}).
         */
        private void constants(TsNode root) {
            for (TsNode n : iter(root, Set.of("lexical_declaration", "variable_declaration", "public_field_definition", "required_parameter"))) {
                if (n.type.equals("lexical_declaration") || n.type.equals("variable_declaration")) {
                    if (functionParent(n) != null) {
                        continue;
                    }
                    for (TsNode d : n.namedChildren()) {
                        if (!d.type.equals("variable_declarator")) {
                            continue;
                        }
                        String name = txt(d.field("name"));
                        TsNode value = d.field("value");
                        if (value != null) {
                            Built b = eval(value, null);
                            if (b != null) {
                                consts.put(name, b);
                            }
                        }
                        repoBinding(name, d, value);
                    }
                } else if (n.type.equals("public_field_definition")) {
                    String name = txt(n.field("name"));
                    TsNode value = n.field("value");
                    if (value != null) {
                        Built b = eval(value, null);
                        if (b != null) {
                            consts.put("this." + name, b);
                        }
                    }
                    repoBinding("this." + name, n, value);
                } else { // constructor parameter: private readonly repo: Repository<Order>
                    String name = txt(n.field("pattern"));
                    repoBinding("this." + name, n, null);
                    repoBinding(name, n, null);
                }
            }
        }

        private void repoBinding(String name, TsNode node, TsNode value) {
            Matcher m = REPOSITORY.matcher(txt(node.field("type")));
            if (m.find()) {
                repos.put(name, m.group(1));
                return;
            }
            List<TsNode> decs = decorators(node);
            for (TsNode c : node.children) {
                if (c.type.equals("decorator")) {
                    decs.add(c);
                }
            }
            for (TsNode dec : decs) {
                Decorator d = decorator(dec);
                if (d.name().equals("InjectRepository") && !d.args().isEmpty()) {
                    repos.put(name, txt(d.args().getFirst()));
                    return;
                }
            }
            if (value != null) {
                Matcher g = GET_REPOSITORY.matcher(txt(value));
                if (g.find()) {
                    repos.put(name, g.group(1));
                }
            }
        }

        // ---- string expressions ---------------------------------------------------------

        /**
         * Rebuilds a string expression: literals, template literals, {@code +}, known constants
         * and variables; unknown parts become holes. Null when it is not text.
         */
        private Built eval(TsNode node, Scope scope) {
            String t = node.type;
            int line = node.line();
            switch (t) {
                case "string" -> {
                    String v = stringValue(node);
                    return lit(v == null ? "" : v, line);
                }
                case "template_string" -> {
                    Built out = null;
                    for (TsNode c : node.namedChildren()) {
                        Built piece;
                        if (c.type.equals("string_fragment") || c.type.equals("escape_sequence")) {
                            piece = lit(txt(c), c.line());
                        } else if (c.type.equals("template_substitution")) {
                            List<TsNode> inner = c.namedChildren();
                            Built known = inner.isEmpty() ? null : eval(inner.getFirst(), scope);
                            piece = known != null ? known : hole(out != null ? out.text : "", line);
                        } else {
                            continue;
                        }
                        out = out == null ? piece : concat(out, piece);
                    }
                    return out != null ? out : lit("", line);
                }
                case "parenthesized_expression" -> {
                    List<TsNode> named = node.namedChildren();
                    return named.isEmpty() ? null : eval(named.getFirst(), scope);
                }
                case "binary_expression" -> {
                    if (!txt(node.field("operator")).equals("+")) {
                        return null;
                    }
                    TsNode left = node.field("left");
                    TsNode right = node.field("right");
                    if (left == null || right == null) {
                        return null;
                    }
                    Built a = eval(left, scope);
                    Built b = eval(right, scope);
                    if (a == null && b == null) {
                        return null;
                    }
                    a = a != null ? a : hole("", left.line());
                    b = b != null ? b : hole(a.text, right.line());
                    return concat(a, b);
                }
                case "identifier" -> {
                    String name = txt(node);
                    if (scope != null && scope.values.containsKey(name)) {
                        return scope.values.get(name);
                    }
                    return consts.get(name);
                }
                case "member_expression" -> {
                    return consts.get(txt(node));
                }
                case "as_expression" -> {
                    List<TsNode> named = node.namedChildren();
                    return named.isEmpty() ? null : eval(named.getFirst(), scope);
                }
                default -> {
                    return null;
                }
            }
        }

        private boolean isStringExpr(TsNode node) {
            if (node.type.equals("string") || node.type.equals("template_string")) {
                return true;
            }
            if (node.type.equals("binary_expression") && txt(node.field("operator")).equals("+")) {
                return node.namedChildren().stream().anyMatch(this::isStringExpr);
            }
            return node.type.equals("parenthesized_expression") && node.namedChildren().stream().anyMatch(this::isStringExpr);
        }

        // ---- walking --------------------------------------------------------------------

        /**
         * Visits in source order. Function bodies get their own scope; code under if, loops,
         * switch, catch and {@code ?:} is conditional for SQL text built in pieces.
         */
        private void walk(TsNode node, Scope scope, boolean cond) {
            String t = node.type;
            if (FUNCTIONS.contains(t)) {
                Scope inner = new Scope();
                for (TsNode c : node.children) {
                    walk(c, inner, false);
                }
                leftovers(inner);
                if (t.equals("method_definition")) {
                    memberDecorators(node);
                }
                return;
            }
            switch (t) {
                case "class_declaration", "abstract_class_declaration" -> klass(node);
                case "lexical_declaration", "variable_declaration" -> {
                    declaration(node, scope, cond);
                    return;
                }
                case "expression_statement" -> {
                    if (assignment(node, scope, cond)) {
                        return;
                    }
                }
                case "call_expression" -> call(node, scope);
                case "new_expression" -> newExpression(node, scope);
                case "object" -> object(node);
                case "public_field_definition" -> memberDecorators(node);
                default -> {
                    if (STRING_EXPRS.contains(t) && isStringExpr(node) && !done.contains(node.startByte)) {
                        Built b = eval(node, scope);
                        if (b != null) {
                            mark(node);
                            textFound(b, null, true, false);
                            return;
                        }
                    }
                }
            }
            boolean branch = cond || BRANCHES.contains(t);
            for (TsNode c : node.children) {
                walk(c, scope, branch);
            }
        }

        private void mark(TsNode node) {
            for (TsNode n : iter(node, STRING_EXPRS)) {
                done.add(n.startByte);
            }
        }

        /** {@code const sql = '...'}: the variable's text is tracked; other values are walked. */
        private void declaration(TsNode node, Scope scope, boolean cond) {
            for (TsNode d : node.namedChildren()) {
                if (!d.type.equals("variable_declarator")) {
                    continue;
                }
                TsNode nameNode = d.field("name");
                TsNode value = d.field("value");
                if (value == null) {
                    continue;
                }
                String name = txt(nameNode);
                if (ROUTER_VALUE.matcher(txt(value)).find()) {
                    routers.add(name);
                }
                if (nameNode != null && nameNode.type.equals("identifier") && isStringExpr(value)) {
                    Built b = eval(value, scope);
                    if (b != null) {
                        mark(value);
                        scope.values.put(name, DynSql.update(scope.values.get(name), b, cond, false));
                        continue;
                    }
                }
                walk(value, scope, cond);
            }
        }

        private boolean assignment(TsNode node, Scope scope, boolean cond) {
            List<TsNode> named = node.namedChildren();
            TsNode expr = named.isEmpty() ? null : named.getFirst();
            if (expr == null || !(expr.type.equals("assignment_expression") || expr.type.equals("augmented_assignment_expression"))) {
                return false;
            }
            TsNode left = expr.field("left");
            TsNode right = expr.field("right");
            if (left == null || right == null || !left.type.equals("identifier")) {
                return false;
            }
            String name = txt(left);
            boolean append = expr.type.equals("augmented_assignment_expression") && txt(expr.field("operator")).contains("+=");
            if (!isStringExpr(right) && !(append && scope.values.containsKey(name))) {
                return false;
            }
            Built b = eval(right, scope);
            if (b == null) {
                Built prev = scope.values.get(name);
                b = hole(prev != null ? prev.text : "", right.line());
            }
            mark(right);
            scope.values.put(name, DynSql.update(scope.values.get(name), b, cond, append));
            for (TsNode c : right.children) { // calls inside the right side still count
                walk(c, scope, cond);
            }
            return true;
        }

        /** SQL text held in a variable that no database call used (passed elsewhere). */
        private void leftovers(Scope scope) {
            for (var e : scope.values.entrySet()) {
                if (!scope.consumed.contains(e.getKey())) {
                    textFound(e.getValue(), null, true, false);
                }
            }
        }

        // ---- results --------------------------------------------------------------------

        private void textFound(Built b, String source, boolean strict, boolean hql) {
            String t = TextUtil.strip(b.text);
            if (SqlTools.looksLikeSql(t, strict && source == null)) {
                if (sqlSeen.add(new Seen(b.line, t))) {
                    Doc fact = SqlTools.sqlFact(path, b.line, t, source != null ? source : "ts-string", dialect, b.dynamic(), hql);
                    DynSql.fill(fact, b);
                    facts.add("sql_statements", fact);
                }
            }
            if (t.contains("://")) {
                urls(t, b.line);
            }
        }

        private void urls(String t, int line) {
            if (!urlSeen.add(new Seen(line, t))) {
                return; // the same text seen twice (a call argument walked again)
            }
            Matcher m = DB_URL.matcher(t);
            while (m.find()) {
                String url = Integrations.maskUserinfo(m.group());
                String scheme = m.group(1).toLowerCase(Locale.ROOT);
                Matcher hp = HOST_PATH.matcher(url);
                if (!hp.find()) {
                    continue;
                }
                String host = TextUtil.beforeFirst(hp.group(1), ":");
                if (host.contains("?") || host.isEmpty()) {
                    continue;
                }
                String target = scheme + "://" + hp.group(1) + TextUtil.rstrip(hp.group(2) != null ? hp.group(2) : "", "/");
                String kind = scheme.startsWith("amqp") ? "AMQP" : "DB";
                String product = switch (scheme) {
                    case "mongodb+srv" -> "MongoDB";
                    case "rediss", "redis" -> "Redis";
                    default -> DB_TYPES.getOrDefault(scheme.replace("ql", ""), DB_TYPES.getOrDefault(scheme, ""));
                };
                facts.add("integrations", integration(kind, target, "outbound", path, line, null, scheme, null));
                if (kind.equals("DB") && !product.isEmpty()) {
                    facts.add("tech_stack", tech(product, null, "database", path, line));
                }
            }
            Matcher u = URL.matcher(t);
            while (u.find()) {
                if (TextUtil.beforeFirst(TextUtil.afterFirst(u.group(), "://"), "/").contains("?")) {
                    continue; // the host is built at run time
                }
                String[] c = Integrations.classifyUrl(u.group(), soap);
                if (c != null) {
                    facts.add("integrations", integration(c[0], c[1], "outbound", path, line));
                }
            }
        }

        private void orm(String verb, String entity, String how, int line, String source) {
            String sql = switch (verb) {
                case "SELECT" -> "SELECT /* " + how + " */ FROM " + entity;
                case "INSERT" -> "INSERT /* " + how + " */ INTO " + entity;
                case "UPDATE" -> "UPDATE " + entity + " /* " + how + " */";
                case "DELETE" -> "DELETE /* " + how + " */ FROM " + entity;
                default -> "MERGE /* " + how + " */ INTO " + entity;
            };
            Doc fact = SqlTools.sqlFact(path, line, sql, source, null, false, true);
            fact.set("type", verb);
            fact.set("parsed", false);
            fact.set("reason", how + ": the ORM generates the SQL at run time");
            facts.add("sql_statements", fact);
        }

        private void service(String name, String kind, String handler, String url, int line) {
            String sid = Fact.makeId("service", kind, path, name);
            facts.add("services", Doc.of(Schemas.SERVICE,
                    "id", sid,
                    "name", name,
                    "kind", kind,
                    "file", path,
                    "handler", handler,
                    "url", url,
                    "evidence", new ArrayList<>(List.of(new Evidence(path, line, null)))));
            facts.symbol("service", handler, path, line).owner(sid);
        }

        private void entry(String name, String kind, int line, String detail) {
            facts.add("entry_points", Doc.of(Schemas.ENTRY_POINT,
                    "id", Fact.makeId("entry", path, name, kind),
                    "name", name,
                    "kind", kind,
                    "file", path,
                    "line", line,
                    "evidence", new ArrayList<>(List.of(new Evidence(path, line, detail)))));
        }

        private void job(String name, String schedule, String handler, int line, String how) {
            String jid = Fact.makeId("batch_job", "node-cron", path, line);
            facts.add("batch_jobs", Doc.of(Schemas.BATCH_JOB,
                    "id", jid,
                    "name", name,
                    "scheduler", "node-cron",
                    "description", "Scheduled in the code with " + how,
                    "schedule", schedule,
                    "command", path,
                    "programs", handler.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(handler)),
                    "evidence", new ArrayList<>(List.of(new Evidence(path, line, how)))));
            facts.symbol("cron-job", name, path, line).owner(jid);
        }

        /**
         * A call to an HTTP API: an integration when the URL names another host, a {@code calls}
         * reference to a route of the scanned services when it is a path.
         */
        private void apiCall(Built b, String verb, int line) {
            if (b.text.contains("://")) {
                urls(b.text, line);
                String host = TextUtil.beforeFirst(TextUtil.afterFirst(b.text, "://"), "/");
                if (!host.contains("?")) {
                    String base = WebSvc.splitUrl(b.text)[0];
                    if (base != null && !base.isEmpty()) {
                        boolean angular = modules.stream().anyMatch(m -> m.startsWith("@angular"));
                        String fw = angular ? "Angular HttpClient" : "HTTP client (Node.js)";
                        facts.add("web_services", WebSvc.called("REST", TextUtil.afterFirst(base, "://"), fw, path, line, b.text, null, verb,
                                null, null, null));
                    }
                    return;
                }
            }
            String p = urlPath(b.text);
            if (p == null) {
                return;
            }
            facts.ref("calls", "file:" + path, path, p, List.of("url"), path, line).reportUnresolved(false)
                    .data("method", verb.toUpperCase(Locale.ROOT)).data("match", "api");
        }

        // ---- calls ----------------------------------------------------------------------

        private void call(TsNode n, Scope scope) {
            Callee ce = callee(n);
            String obj = ce.obj();
            String name = ce.name();
            TsNode objNode = ce.objNode();
            List<TsNode> args = args(n);
            int line = n.line();
            TsNode first = args.isEmpty() ? null : args.getFirst();
            Built fb = first != null ? eval(first, scope) : null;
            if (first != null && first.type.equals("identifier") && scope.values.containsKey(txt(first))) {
                scope.consumed.add(txt(first));
            }
            String lname = name.toLowerCase(Locale.ROOT);
            // Express / Koa / Fastify routes
            if (HTTP_VERBS.contains(lname) && first != null && args.size() >= 2 && fb != null
                    && (fb.text.startsWith("/") || fb.text.equals("*") || fb.text.isEmpty())) {
                Built routeOf = null;
                if (objNode != null && objNode.type.equals("call_expression")) {
                    Callee c2 = callee(objNode);
                    List<TsNode> a2 = args(objNode);
                    if (c2.name().equals("route") && !a2.isEmpty()) {
                        routeOf = eval(a2.getFirst(), scope);
                    }
                }
                if (routeOf == null && (ROUTER_OBJ.matcher(obj).find() || routers.contains(obj))) {
                    route(name, fb.text, args, line, obj);
                    mark(first);
                    return;
                }
            }
            if (HTTP_VERBS.contains(lname) && objNode != null && objNode.type.equals("call_expression")) {
                Callee c2 = callee(objNode);
                List<TsNode> a2 = args(objNode);
                Built rb = a2.isEmpty() ? null : eval(a2.getFirst(), scope);
                if (c2.name().equals("route") && rb != null && rb.text.startsWith("/")) {
                    route(name, rb.text, args.isEmpty() ? List.of(objNode) : args, line, c2.obj());
                }
            }
            if (name.equals("route") && first != null && (first.type.equals("object") || first.type.equals("array"))) {
                for (TsNode objLit : first.type.equals("object") ? List.of(first) : first.namedChildren()) {
                    if (!objLit.type.equals("object")) {
                        continue;
                    }
                    Map<String, TsNode> p = pairs(objLit);
                    String url = stringValue(p.get("url") != null ? p.get("url") : p.get("path"));
                    String method = or(stringValue(p.get("method")), "ANY");
                    if (url != null && !url.isEmpty()) {
                        String handler = p.get("handler") != null ? txt(p.get("handler")) : "";
                        routeService(method.toUpperCase(Locale.ROOT), url, handler, line, "");
                    }
                }
            }
            // app.use('/prefix', router): routes of that router get the prefix (in the linker)
            if (name.equals("use") && args.size() >= 2 && fb != null && fb.text.startsWith("/")) {
                for (TsNode a : args.subList(1, args.size())) {
                    String target = txt(a);
                    String module = imports.get(target);
                    if (module != null && module.startsWith(".")) {
                        String resolved = TextUtil.normpath(TextUtil.joinPath(TextUtil.dirname(path), module));
                        facts.symbol("node-route-mount", resolved, path, line).data("prefix", fb.text);
                    } else if (routers.contains(target)) {
                        facts.symbol("node-route-mount", path + "#" + target, path, line).data("prefix", fb.text);
                    }
                }
            }
            if (name.equals("setGlobalPrefix") && fb != null) {
                facts.symbol("nest-global-prefix", TextUtil.strip(fb.text, "/"), path, line);
            }
            // Start-up
            if (name.equals("listen") && (Set.of("app", "server", "httpServer", "fastify").contains(obj) || modules.contains("express")
                    || modules.contains("http"))) {
                entry(path, "node-server", line, obj + ".listen");
                facts.add("tech_stack", tech("Node.js", null, "runtime", path, line));
            }
            if (obj.equals("NestFactory") && (name.equals("create") || name.equals("createApplicationContext"))) {
                entry(path, "node-server", line, "NestFactory." + name);
                facts.add("tech_stack", tech("Node.js", null, "runtime", path, line));
            }
            if ((name.equals("bootstrapApplication") || name.equals("bootstrapModule")) && first != null) {
                entry(txt(first), "angular-app", line, name);
                if (name.equals("bootstrapApplication")) {
                    bootstraps(txt(first), line);
                }
            }
            if (obj.equals("angular") && name.equals("module") && args.size() >= 2 && fb != null) {
                entry(fb.text, "angularjs-app", line, "angular.module");
                facts.add("tech_stack", tech("AngularJS", null, "framework", path, line));
            }
            // AngularJS registrations
            if (angularjs && objNode != null && fb != null && Set.of("controller", "component", "directive", "service", "factory").contains(name)) {
                angularjsRegistration(name, fb.text, args, line);
            }
            if (name.equals("when") && fb != null && args.size() >= 2 && args.get(1).type.equals("object")
                    && (txt(n).contains("$routeProvider") || obj.contains("routeProvider"))) {
                angularjsRoute(fb.text, args.get(1), line, false);
            }
            if (name.equals("state") && !args.isEmpty() && angularjs) {
                if (fb != null && args.size() >= 2 && args.get(1).type.equals("object")) {
                    angularjsRoute(fb.text, args.get(1), line, true);
                } else if (first != null && first.type.equals("object")) {
                    String stateName = stringValue(pairs(first).get("name"));
                    angularjsRoute(stateName != null ? stateName : "", first, line, true);
                }
            }
            // HTTP clients
            if (fb != null && (((HTTP_VERBS.contains(lname) || lname.equals("request") || lname.equals("jsonp"))
                    && HTTP_CLIENT_OBJ.matcher(obj).find()) || (obj.isEmpty() && HTTP_FUNCS.contains(name)) || name.equals("$resource"))) {
                String verb = HTTP_VERBS.contains(lname) ? name : "GET";
                apiCall(fb, verb, line);
                if (first != null) {
                    mark(first);
                }
            } else if (obj.isEmpty() && (name.equals("$http") || name.equals("axios")) && first != null && first.type.equals("object")) {
                Map<String, TsNode> p = pairs(first);
                TsNode u = p.get("url");
                Built ub = u != null ? eval(u, scope) : null;
                if (ub != null) {
                    apiCall(ub, or(stringValue(p.get("method")), "GET"), line);
                }
            }
            if ((name.equals("createClientAsync") || (name.equals("createClient") && soap)) && fb != null && first != null) {
                urls(fb.text, line);
                mark(first);
                String[] bp = WebSvc.splitUrl(fb.text);
                String wsName = or(TextUtil.afterLast(TextUtil.rstrip(bp[1], "/"), "/"), or(bp[0], "SOAP"));
                facts.add("web_services", WebSvc.called("SOAP", wsName, "node-soap", path, line, fb.text, null, null, null, null, null));
            }
            if (name.equals("listen") && obj.equals("soap") && args.size() >= 3) {
                Built pb = eval(args.get(1), scope);
                if (pb != null && pb.text.startsWith("/")) {
                    WebSvc.offeredService(facts, "SOAP " + pb.text, "soap-operation", path, line, path + ":" + line, pb.text, "SOAP", "node-soap",
                            or(TextUtil.strip(pb.text, "/"), pb.text));
                }
            }
            // Database: raw SQL
            if ((DB_CALLS.contains(name) || (obj.isEmpty() && DB_TAGS.contains(name))) && first != null) {
                Built b = fb;
                if (first.type.equals("identifier") && !txt(first).isEmpty() && scope.values.containsKey(txt(first))) {
                    b = scope.values.get(txt(first));
                }
                if (b != null && SqlTools.looksLikeSql(TextUtil.strip(b.text))) {
                    mark(first);
                    textFound(b, "node-db", false, false);
                }
            }
            // ORMs
            ormCall(obj, name, objNode, args, line);
            // Scheduled jobs
            scheduleCall(obj, name, args, fb, line);
        }

        private void route(String verb, String url, List<TsNode> args, int line, String obj) {
            TsNode handlerNode = args.isEmpty() ? null : args.getLast();
            String handler = "";
            if (handlerNode != null && (handlerNode.type.equals("identifier") || handlerNode.type.equals("member_expression"))) {
                handler = txt(handlerNode);
            }
            String mount = routers.contains(obj) ? path + "#" + obj : "";
            routeService(verb.toUpperCase(Locale.ROOT), url, handler, line, mount);
        }

        private void routeService(String verb, String url, String handler, int line, String mount) {
            verb = switch (verb) {
                case "DEL" -> "DELETE";
                case "ALL" -> "ANY";
                default -> verb;
            };
            url = !url.equals("*") && !url.isEmpty() ? "/" + TextUtil.strip(url, "/") : "/*";
            handler = or(handler, path + ":" + line);
            String sidName = verb + " " + url;
            service(sidName, "node-route", handler, url, line);
            if (!mount.isEmpty()) {
                facts.symbol("node-router", mount, path, line).owner(Fact.makeId("service", "node-route", path, sidName));
            }
            if (modules.contains("express")) {
                facts.add("tech_stack", tech("Express", null, "framework", path, line));
            }
        }

        private void newExpression(TsNode n, Scope scope) {
            String ctor = txt(n.field("constructor"));
            List<TsNode> args = args(n);
            int line = n.line();
            if (ctor.equals("CronJob") && !args.isEmpty()) {
                Built b = eval(args.getFirst(), scope);
                String handler;
                if (args.getFirst().type.equals("object")) {
                    Map<String, TsNode> p = pairs(args.getFirst());
                    b = p.containsKey("cronTime") ? eval(p.get("cronTime"), scope) : null;
                    handler = p.get("onTick") != null ? txt(p.get("onTick")) : "";
                } else {
                    handler = args.size() > 1 && args.get(1).type.equals("identifier") ? txt(args.get(1)) : "";
                }
                String sched = b != null ? cron5(b.text) : null;
                job(jobName(handler, line), sched, handler, line, "cron (CronJob)");
            }
            if (Set.of("Pool", "Client", "Sequelize", "DataSource").contains(ctor) && !args.isEmpty()) {
                if (args.getFirst().type.equals("object")) {
                    dbConfig(args.getFirst(), line);
                } else {
                    Built b = eval(args.getFirst(), scope);
                    if (b != null) {
                        urls(b.text, line);
                    }
                }
            }
            if (Set.of("Router", "KoaRouter", "express.Router").contains(ctor)) {
                TsNode parent = n.parent;
                if (parent != null && parent.type.equals("variable_declarator")) {
                    routers.add(txt(parent.field("name")));
                }
            }
        }

        private void object(TsNode obj) {
            Map<String, TsNode> p = pairs(obj);
            // Angular route: { path: 'orders', component: OrderListComponent }
            if (p.containsKey("path") && (p.containsKey("component") || p.containsKey("loadComponent") || p.containsKey("redirectTo"))) {
                String pathText = stringValue(p.get("path"));
                if (pathText == null) {
                    return;
                }
                List<String> parts = new ArrayList<>(parentRoutes(obj));
                parts.add(pathText);
                parts.removeIf(String::isEmpty);
                String full = String.join("/", parts);
                String comp = null;
                if (p.containsKey("component") && p.get("component").type.equals("identifier")) {
                    comp = txt(p.get("component"));
                } else if (p.containsKey("loadComponent")) {
                    Matcher m = LOAD_COMPONENT.matcher(txt(p.get("loadComponent")));
                    while (m.find()) {
                        comp = m.group(1);
                    }
                }
                if (comp != null && !comp.isEmpty()) {
                    facts.ref("forwards", "file:" + path, path, comp, List.of("angular-component"), path, obj.line()).data("route", "/" + full);
                }
                return;
            }
            // Database connection settings: { type: 'postgres', host: ..., database: ... }
            String kind = null;
            for (String k : List.of("type", "dialect", "client")) {
                if (p.containsKey(k)) {
                    kind = k;
                    break;
                }
            }
            if (kind != null) {
                String value = or(stringValue(p.get(kind)), "").toLowerCase(Locale.ROOT);
                if (DB_TYPES.containsKey(value)
                        && List.of("host", "database", "url", "connectString", "connection", "storage").stream().anyMatch(p::containsKey)) {
                    dbConfig(obj, obj.line());
                }
            }
        }

        private void dbConfig(TsNode obj, int line) {
            if (!done.add(obj.startByte)) {
                return;
            }
            Map<String, TsNode> p = pairs(obj);
            String kind = null;
            for (String k : List.of("type", "dialect", "client")) {
                if (p.containsKey(k)) {
                    kind = k;
                    break;
                }
            }
            String product = kind != null ? DB_TYPES.get(or(stringValue(p.get(kind)), "").toLowerCase(Locale.ROOT)) : null;
            if (p.containsKey("connectionString") || p.containsKey("url")) {
                String u = stringValue(p.get("connectionString") != null ? p.get("connectionString") : p.get("url"));
                if (u != null && !u.isEmpty()) {
                    urls(u, line);
                }
            }
            if (p.containsKey("connectString")) { // oracledb: host:port/service
                String cs = stringValue(p.get("connectString"));
                product = product != null ? product : "Oracle";
                if (cs != null && !cs.isEmpty() && !cs.contains("?")) {
                    facts.add("integrations", integration("DB", "oracle://" + TextUtil.lstrip(cs, "/"), "outbound", path, line, null,
                            "oracledb", null));
                }
            }
            String host = p.containsKey("host") ? stringValue(p.get("host")) : null;
            String db = p.containsKey("database") ? stringValue(p.get("database")) : null;
            String port = p.containsKey("port") && p.get("port").type.equals("number") ? txt(p.get("port")) : "";
            if (product == null) {
                String[][] byModule = {{"pg", "PostgreSQL"}, {"mysql", "MySQL"}, {"mysql2", "MySQL"}, {"mssql", "SQL Server"},
                        {"oracledb", "Oracle"}};
                for (String[] mp : byModule) {
                    if (modules.contains(mp[0])) {
                        product = mp[1];
                        break;
                    }
                }
            }
            if (product != null && !product.isEmpty()) {
                facts.add("tech_stack", tech(product, null, "database", path, line));
            }
            if (host != null && !host.isEmpty() && product != null && !product.isEmpty()) {
                String scheme = "db";
                for (var e : DB_TYPES.entrySet()) {
                    if (e.getValue().equals(product)) {
                        scheme = e.getKey();
                        break;
                    }
                }
                String target = scheme + "://" + host.toLowerCase(Locale.ROOT) + (port.isEmpty() ? "" : ":" + port);
                if (db != null && !db.isEmpty()) {
                    target += "/" + db;
                }
                facts.add("integrations", integration("DB", target, "outbound", path, line, null, scheme, null));
            }
        }

        private void ormCall(String obj, String name, TsNode objNode, List<TsNode> args, int line) {
            if (objNode == null) {
                return;
            }
            // TypeORM: this.orderRepo.find(...), dataSource.getRepository(Order).find(...)
            if (TYPEORM_OPS.containsKey(name)) {
                String entity = repos.get(obj);
                if (entity == null && objNode.type.equals("call_expression")) {
                    Callee inner = callee(objNode);
                    List<TsNode> innerArgs = args(objNode);
                    if (inner.name().equals("getRepository") && !innerArgs.isEmpty()) {
                        entity = txt(innerArgs.getFirst());
                    }
                }
                if (entity == null && !Set.of("update", "delete", "count").contains(name)
                        && (obj.endsWith("manager") || obj.endsWith("Manager")) && !args.isEmpty() && args.getFirst().type.equals("identifier")) {
                    entity = txt(args.getFirst());
                }
                if (entity != null && !entity.isEmpty()) {
                    orm(TYPEORM_OPS.get(name), entity, "TypeORM " + name, line, "typeorm");
                    return;
                }
            }
            // Prisma: prisma.order.findMany(...), this.prisma.order.create(...)
            if (PRISMA_OPS.containsKey(name) && objNode.type.equals("member_expression")) {
                String client = txt(objNode.field("object"));
                String model = txt(objNode.field("property"));
                if (PRISMA_CLIENT.matcher(client).find() && !model.isEmpty() && Character.isLetter(model.codePointAt(0))) {
                    int first = model.offsetByCodePoints(0, 1);
                    String entity = model.substring(0, first).toUpperCase(Locale.ROOT) + model.substring(first);
                    orm(PRISMA_OPS.get(name), entity, "Prisma " + name, line, "prisma");
                    return;
                }
            }
            // Sequelize: Order.findAll(...)
            if (objNode.type.equals("identifier") && !obj.isEmpty() && isUpper(obj.substring(0, obj.offsetByCodePoints(0, 1)))) {
                String op = SEQUELIZE_OPS.get(name);
                if (op == null && (modules.contains("sequelize") || modules.contains("sequelize-typescript")
                        || imports.getOrDefault(obj, "").toLowerCase(Locale.ROOT).contains("model"))) {
                    op = SEQUELIZE_WEAK_OPS.get(name);
                }
                if (op != null && !Set.of("Promise", "Object", "Array", "JSON", "Math").contains(obj)) {
                    orm(op, obj, "Sequelize " + name, line, "sequelize");
                    return;
                }
            }
            // Knex: knex('orders').where(...)
            if (modules.contains("knex") && objNode.type.equals("call_expression")) {
                Callee fn = callee(objNode);
                List<TsNode> innerArgs = args(objNode);
                if (fn.obj().isEmpty() && Set.of("knex", "db", "trx").contains(fn.name()) && !innerArgs.isEmpty()) {
                    String table = stringValue(innerArgs.getFirst());
                    String verb = switch (name) {
                        case "insert" -> "INSERT";
                        case "update" -> "UPDATE";
                        case "del", "delete" -> "DELETE";
                        default -> "SELECT";
                    };
                    if (table != null && !table.isEmpty()) {
                        orm(verb, table, "Knex " + name, line, "knex");
                    }
                }
            }
        }

        /** Python's {@code str.isupper()}: has a cased character and no lower-case one. */
        private static boolean isUpper(String s) {
            boolean cased = false;
            for (int i = 0; i < s.length(); ) {
                int c = s.codePointAt(i);
                if (Character.isLowerCase(c)) {
                    return false;
                }
                if (Character.isUpperCase(c) || Character.isTitleCase(c)) {
                    cased = true;
                }
                i += Character.charCount(c);
            }
            return cased;
        }

        private void scheduleCall(String obj, String name, List<TsNode> args, Built fb, int line) {
            List<TsNode> rest = args.size() > 1 ? args.subList(1, args.size()) : List.of();
            TsNode handlerNode = null;
            for (TsNode a : rest) {
                if (Set.of("identifier", "member_expression", "arrow_function", "function_expression").contains(a.type)) {
                    handlerNode = a;
                    break;
                }
            }
            String handler = handlerNode != null && (handlerNode.type.equals("identifier") || handlerNode.type.equals("member_expression"))
                    ? txt(handlerNode)
                    : calledIn(handlerNode);
            TsNode opts = null;
            for (TsNode a : rest) {
                if (a.type.equals("object")) {
                    opts = a;
                    break;
                }
            }
            String given = opts != null ? stringValue(pairs(opts).get("name")) : null;
            if (name.equals("schedule") && CRON.matcher(obj).find() && fb != null) {
                job(or(given, jobName(handler, line)), cron5(fb.text), handler, line, "node-cron");
            } else if (name.equals("scheduleJob") && fb != null) {
                String sched;
                if (args.size() >= 3) { // scheduleJob(name, rule, fn)
                    given = fb.text;
                    Built rb = eval(args.get(1), null);
                    sched = rb != null ? cron5(rb.text) : null;
                } else {
                    sched = cron5(fb.text);
                }
                job(or(given, jobName(handler, line)), sched, handler, line, "node-schedule");
            } else if (name.equals("every") && AGENDA.matcher(obj).find() && fb != null && args.size() > 1) {
                Built jb = eval(args.get(1), null);
                String jname = jb != null ? jb.text : jobName("", line);
                String sched = cron5(fb.text);
                job(jname, sched != null ? sched : "every " + fb.text, jname, line, "Agenda");
            }
        }

        private String jobName(String handler, int line) {
            String stem = TextUtil.beforeLast(TextUtil.afterLast(path, "/"), ".");
            return !handler.isEmpty() && DOTTED.matcher(handler).find() ? stem + "." + handler : stem + ":" + line;
        }

        // ---- classes and decorators -----------------------------------------------------

        private void klass(TsNode node) {
            String name = txt(node.field("name"));
            int line = node.line();
            for (TsNode dec : decorators(node)) {
                Decorator d = decorator(dec);
                List<TsNode> dargs = d.args();
                Map<String, TsNode> opts = !dargs.isEmpty() && dargs.getFirst().type.equals("object") ? pairs(dargs.getFirst()) : Map.of();
                switch (d.name()) {
                    case "Component" -> {
                        if (!opts.isEmpty()) {
                            angularComponent(name, opts, line);
                        }
                    }
                    case "NgModule" -> {
                        if (opts.get("bootstrap") != null) {
                            for (TsNode c : opts.get("bootstrap").namedChildren()) {
                                bootstraps(txt(c), line);
                            }
                        }
                    }
                    case "Controller" -> {
                        String base = dargs.isEmpty() ? "" : stringValue(dargs.getFirst());
                        if (!dargs.isEmpty() && dargs.getFirst().type.equals("object")) {
                            base = or(stringValue(opts.get("path")), "");
                        }
                        base = base == null ? "" : base;
                        facts.symbol("nest-controller", name, path, line).data("path", base);
                        nestRoutes(node, name, base);
                        facts.add("tech_stack", tech("NestJS", null, "framework", path, line));
                    }
                    case "Entity" -> {
                        String table = dargs.isEmpty() ? null : stringValue(dargs.getFirst());
                        if (!dargs.isEmpty() && dargs.getFirst().type.equals("object")) {
                            table = stringValue(opts.get("name"));
                        }
                        facts.symbol("jpa-entity", name, path, line).data("table", or(table, name).toUpperCase(Locale.ROOT)).data("orm", "typeorm");
                    }
                    case "Table" -> { // sequelize-typescript
                        String table = !opts.isEmpty() ? stringValue(opts.get("tableName")) : null;
                        facts.symbol("jpa-entity", name, path, line).data("table", or(table, name).toUpperCase(Locale.ROOT)).data("orm", "sequelize");
                    }
                    case "Injectable" -> facts.symbol("injectable", name, path, line);
                    default -> {
                        // not a decorator the inventory uses
                    }
                }
            }
            TsNode heritage = null;
            for (TsNode c : node.children) {
                if (c.type.equals("class_heritage")) {
                    heritage = c;
                    break;
                }
            }
            if (EXTENDS_MODEL.matcher(txt(heritage)).find()) {
                facts.symbol("jpa-entity", name, path, line).data("table", name.toUpperCase(Locale.ROOT)).data("orm", "sequelize");
            }
        }

        private void angularComponent(String name, Map<String, TsNode> opts, int line) {
            String selector = or(stringValue(opts.get("selector")), "");
            String owner = null;
            if (opts.containsKey("templateUrl")) {
                String tpl = stringValue(opts.get("templateUrl"));
                if (tpl != null && !tpl.isEmpty()) {
                    String target = TextUtil.normpath(TextUtil.joinPath(TextUtil.dirname(path), tpl));
                    facts.ref("renders", "file:" + path, name, target, List.of("path"), path, line);
                }
            } else if (opts.containsKey("template")) {
                Built html = eval(opts.get("template"), null);
                owner = Fact.makeId("screen", path + "#" + name);
                facts.add("screens", Doc.of(Schemas.SCREEN,
                        "id", owner,
                        "name", name,
                        "technology", "angular",
                        "file", path,
                        "fields_count", (long) (html != null ? WebAnalyzer.countFields(html.text) : 0),
                        "evidence", new ArrayList<>(List.of(new Evidence(path, line, null)))));
                if (html != null) {
                    mark(opts.get("template"));
                    Set<String> tags = new TreeSet<>();
                    Matcher m = TAG.matcher(html.text);
                    while (m.find()) {
                        tags.add(m.group(1));
                    }
                    for (String tag : tags) {
                        facts.ref("includes", "screen:" + owner, name, tag, List.of("angular-selector"), path, line).reportUnresolved(false);
                    }
                }
            }
            facts.symbol("angular-component", name, path, line).owner(owner);
            for (String sel : selector.split(",", -1)) {
                sel = TextUtil.strip(sel);
                if (!sel.isEmpty()) {
                    facts.symbol("angular-selector", sel, path, line).owner(owner);
                }
            }
            facts.add("tech_stack", tech("Angular", null, "framework", path, line));
        }

        private void bootstraps(String name, int line) {
            facts.ref("bootstraps", "file:" + path, path, name, List.of("angular-component"), path, line).reportUnresolved(false);
        }

        private void nestRoutes(TsNode cls, String clsName, String base) {
            TsNode body = cls.field("body");
            if (body == null) {
                return;
            }
            for (TsNode m : body.namedChildren()) {
                if (!m.type.equals("method_definition")) {
                    continue;
                }
                String mname = txt(m.field("name"));
                for (TsNode dec : decorators(m)) {
                    Decorator d = decorator(dec);
                    String verb = NEST_VERBS.get(d.name());
                    if (verb == null) {
                        continue;
                    }
                    String sub = d.args().isEmpty() ? "" : or(stringValue(d.args().getFirst()), "");
                    List<String> parts = new ArrayList<>();
                    for (String x : List.of(base, sub)) {
                        String s = TextUtil.strip(x, "/");
                        if (!s.isEmpty()) {
                            parts.add(s);
                        }
                    }
                    String url = "/" + String.join("/", parts);
                    service(verb + " " + url, "nest-route", clsName + "." + mname, url, m.line());
                }
            }
        }

        /** NestJS {@code @Cron}/{@code @Interval}/{@code @Timeout} on a method. */
        private void memberDecorators(TsNode node) {
            String mname = txt(node.field("name"));
            TsNode cls = node.parent != null ? node.parent.parent : null;
            String cname = cls != null ? txt(cls.field("name")) : "";
            for (TsNode dec : decorators(node)) {
                Decorator d = decorator(dec);
                String dn = d.name();
                List<TsNode> dargs = d.args();
                if (!(dn.equals("Cron") || dn.equals("Interval") || dn.equals("Timeout")) || dargs.isEmpty()) {
                    continue;
                }
                TsNode first = dargs.getFirst();
                TsNode opts = null;
                for (TsNode a : dargs) {
                    if (a.type.equals("object")) {
                        opts = a;
                        break;
                    }
                }
                String given = opts != null ? stringValue(pairs(opts).get("name")) : null;
                if (dn.equals("Interval") && dargs.size() > 1) { // @Interval('name', ms)
                    given = stringValue(dargs.get(0));
                    first = dargs.get(1);
                }
                String sched;
                if (dn.equals("Cron")) {
                    String raw = stringValue(first);
                    if (raw == null) {
                        raw = NEST_CRON.getOrDefault(TextUtil.afterLast(txt(first), "."), txt(first));
                    }
                    String c5 = cron5(raw);
                    sched = c5 != null ? c5 : raw;
                } else if (dn.equals("Interval")) {
                    sched = every(txt(first));
                } else {
                    sched = null;
                }
                String handler = !cname.isEmpty() ? cname + "." + mname : mname;
                job(or(given, handler), sched, handler, node.line(), "NestJS @" + dn);
            }
        }

        // ---- AngularJS ------------------------------------------------------------------

        private void angularjsRegistration(String kind, String name, List<TsNode> args, int line) {
            facts.symbol("angularjs-" + kind, name, path, line);
            String kebab = KEBAB.matcher(name).replaceAll("$1-$2").toLowerCase(Locale.ROOT);
            boolean component = kind.equals("component") || kind.equals("directive");
            if (component && !kebab.equals(name)) {
                facts.symbol("angularjs-" + kind, kebab, path, line); // <order-list>
            }
            if (component && args.size() >= 2) {
                TsNode body = args.get(1);
                List<TsNode> objs = body.type.equals("object") ? List.of(body) : iter(body, Set.of("object"));
                for (TsNode o : objs) {
                    Map<String, TsNode> p = pairs(o);
                    if (p.containsKey("templateUrl")) {
                        String tpl = stringValue(p.get("templateUrl"));
                        if (tpl != null && !tpl.isEmpty()) {
                            facts.ref("renders", "file:" + path, name, tpl, List.of("path"), path, o.line());
                        }
                        break;
                    }
                }
            }
            facts.add("tech_stack", tech("AngularJS", null, "framework", path, line));
        }

        private void angularjsRoute(String route, TsNode obj, int line, boolean state) {
            Map<String, TsNode> p = pairs(obj);
            String tpl = p.containsKey("templateUrl") ? stringValue(p.get("templateUrl")) : null;
            String url = state && p.containsKey("url") ? stringValue(p.get("url")) : route;
            String shown = or(url, route);
            if (tpl != null && !tpl.isEmpty()) {
                facts.ref("forwards", "file:" + path, path, tpl, List.of("path"), path, line).data("route", shown);
            } else if (p.containsKey("component")) {
                String comp = stringValue(p.get("component"));
                if (comp != null && !comp.isEmpty()) {
                    facts.ref("forwards", "file:" + path, path, comp, List.of("angularjs-component"), path, line).data("route", shown);
                }
            }
            String ctrl = p.containsKey("controller") ? stringValue(p.get("controller")) : null;
            if (ctrl != null && !ctrl.isEmpty()) {
                facts.ref("uses", "file:" + path, path, TextUtil.strip(TextUtil.beforeFirst(ctrl, " as ")), List.of("angularjs-controller"), path,
                        line).reportUnresolved(false);
            }
        }

        // ---- file level -----------------------------------------------------------------

        private void fileLevel() {
            if (modules.contains("@angular/core")) {
                facts.add("tech_stack", tech("Angular", null, "framework", path, null));
            }
            if (path.endsWith(".ts") || path.endsWith(".tsx") || path.endsWith(".mts") || path.endsWith(".cts")) {
                facts.add("tech_stack", tech("TypeScript", null, "language", path, null));
            }
            if (MAIN_MODULE.matcher(text).find()) {
                entry(path, "node-script", 1, "require.main === module");
            }
        }
    }
}
