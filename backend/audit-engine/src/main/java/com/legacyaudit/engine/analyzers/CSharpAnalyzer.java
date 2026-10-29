package com.legacyaudit.engine.analyzers;

import static com.legacyaudit.engine.analyzers.BuildAnalyzer.tech;
import static com.legacyaudit.engine.analyzers.DynSql.choice;
import static com.legacyaudit.engine.analyzers.DynSql.concat;
import static com.legacyaudit.engine.analyzers.DynSql.empty;
import static com.legacyaudit.engine.analyzers.DynSql.hole;
import static com.legacyaudit.engine.analyzers.DynSql.lit;
import static com.legacyaudit.engine.analyzers.Integrations.integration;
import static com.legacyaudit.engine.analyzers.ts.TsNode.txt;

import com.legacyaudit.engine.analyzers.DynSql.Built;
import com.legacyaudit.engine.analyzers.DynSql.Tracker;
import com.legacyaudit.engine.analyzers.ts.TsNode;
import com.legacyaudit.engine.analyzers.ts.TsTree;
import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Category;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Schemas;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.treesitter.TSParser;
import org.treesitter.TreeSitterCSharp;

/**
 * C# (tree-sitter-c-sharp): Windows Forms, WPF, ASP.NET, WCF, Windows services and data access
 * (decision 136). One parse per file finds:
 *
 * <ul>
 *   <li><b>screens</b>: Windows Forms forms ({@code : Form}; fields from the designer's controls)
 *       and user controls (partials); {@code new X().Show()}/{@code ShowDialog()} and
 *       {@code var f = new X(); f.Show()} are navigation between screens;
 *   <li><b>services</b>: ASP.NET MVC actions, Web API actions with their {@code [Route]}/
 *       {@code [HttpGet(...)]} paths, ASMX {@code [WebMethod]} and WCF
 *       {@code [OperationContract]} operations;
 *   <li><b>entry points and jobs</b>: {@code static Main}, Windows services and hosted services,
 *       Hangfire {@code RecurringJob.AddOrUpdate} and Quartz.NET cron triggers as batch jobs;
 *   <li><b>database access</b>: ADO.NET commands and adapters, Dapper, Entity Framework raw SQL
 *       and LINQ on a {@code DbSet} (kept as readable ORM text), entity-to-table mappings; SQL
 *       built in code is rebuilt ({@code +}, {@code +=}, interpolation, {@code StringBuilder},
 *       {@code string.Format}, {@code ?:}); stored procedures; connection strings (masked);
 *   <li><b>integrations</b>: URLs, connection strings, MSMQ queues, SMTP servers, files;
 *   <li><b>tech stack</b>: C# and what the {@code using} namespaces show.
 * </ul>
 */
public final class CSharpAnalyzer implements Analyzer {

    private static final ThreadLocal<TSParser> PARSER = TsTree.parsers(TreeSitterCSharp::new);

    private static final Set<String> DAPPER = Set.of("Query", "QueryFirst", "QueryFirstOrDefault", "QuerySingle", "QuerySingleOrDefault",
            "QueryMultiple", "QueryAsync", "QueryFirstAsync", "QueryFirstOrDefaultAsync", "QuerySingleAsync", "QuerySingleOrDefaultAsync",
            "QueryMultipleAsync", "Execute", "ExecuteAsync", "ExecuteScalar", "ExecuteScalarAsync", "ExecuteReader");
    private static final Set<String> EF_RAW = Set.of("FromSqlRaw", "FromSql", "FromSqlInterpolated", "ExecuteSqlRaw", "ExecuteSqlRawAsync",
            "ExecuteSqlInterpolated", "ExecuteSqlInterpolatedAsync", "ExecuteSqlCommand", "ExecuteSqlCommandAsync", "SqlQuery", "SqlQueryRaw",
            "ExecuteSql", "ExecuteSqlAsync");
    private static final Set<String> EF_READ = Set.of("Where", "First", "FirstOrDefault", "Single", "SingleOrDefault", "ToList", "ToListAsync",
            "Find", "FindAsync", "Any", "AnyAsync", "Count", "CountAsync", "Include", "OrderBy", "OrderByDescending", "Select", "AsNoTracking",
            "FirstOrDefaultAsync", "SingleOrDefaultAsync", "ToArray", "Sum", "Max", "Min");
    private static final Map<String, String> EF_WRITE = Map.ofEntries(Map.entry("Add", "INSERT"), Map.entry("AddRange", "INSERT"),
            Map.entry("AddAsync", "INSERT"), Map.entry("Update", "UPDATE"), Map.entry("UpdateRange", "UPDATE"), Map.entry("Remove", "DELETE"),
            Map.entry("RemoveRange", "DELETE"), Map.entry("ExecuteDelete", "DELETE"), Map.entry("ExecuteDeleteAsync", "DELETE"),
            Map.entry("ExecuteUpdate", "UPDATE"), Map.entry("ExecuteUpdateAsync", "UPDATE"));
    private static final Pattern CONTEXT_VAR = Rx.of("^(?:this\\.)?_?(db|ctx|context|dbContext|\\w*Context|\\w*Db)$", Rx.I);
    private static final Pattern COMMAND_TYPES = Rx.of("^(Sql|OleDb|Odbc|Oracle|MySql|Npgsql|DB2|iDB2|SQLite|Sqlite|Ase)(Command|DataAdapter)$");
    private static final Pattern NEWLINE = Rx.of("^(System\\.)?Environment\\.NewLine$");
    private static final Pattern INPUTS = Rx.of("^(TextBox|ComboBox|CheckBox|RadioButton|ListBox|CheckedListBox|MaskedTextBox|"
            + "DateTimePicker|MonthCalendar|RichTextBox|NumericUpDown|TrackBar|DataGridView|DataGrid|ListView)$");
    private static final Map<String, String> BASES = Map.of("Form", "form", "UserControl", "usercontrol", "ServiceBase", "windows-service",
            "BackgroundService", "background-service", "Controller", "mvc", "ApiController", "api", "ControllerBase", "api", "WebService", "asmx",
            "DbContext", "dbcontext", "IHostedService", "background-service");
    private static final Map<String, String> HTTP = Map.of("HttpGet", "GET", "HttpPost", "POST", "HttpPut", "PUT", "HttpDelete", "DELETE",
            "HttpPatch", "PATCH");
    private static final Set<String> COND = Set.of("if_statement", "switch_section", "for_statement", "foreach_statement", "while_statement",
            "do_statement", "catch_clause", "conditional_expression", "switch_expression_arm", "lambda_expression");
    private static final Set<String> COND_STOP = Set.of("constructor_declaration", "accessor_declaration", "local_function_statement",
            "global_statement");
    private static final Set<String> FILE_READ = Set.of("ReadAllText", "ReadAllLines", "ReadAllBytes", "ReadLines", "OpenRead", "OpenText");
    private static final Set<String> FILE_WRITE = Set.of("WriteAllText", "WriteAllLines", "WriteAllBytes", "AppendAllText", "AppendAllLines",
            "OpenWrite", "Create", "AppendText");
    private static final Set<String> STRINGS = Set.of("string_literal", "verbatim_string_literal", "raw_string_literal",
            "interpolated_string_expression");
    private static final Set<String> CALLS = Set.of("invocation_expression", "object_creation_expression");
    private static final Set<String> TYPES = Set.of("class_declaration", "struct_declaration", "record_declaration", "interface_declaration");
    private static final Set<String> BUILDING = Set.of("Format", "Concat", "Append", "AppendLine", "AppendFormat", "Join");
    private static final Pattern SP = Rx.of("CommandType\\.StoredProcedure|commandType:\\s*CommandType\\.StoredProcedure");
    private static final Pattern PROC_NAME = Rx.of("^[A-Za-z_][\\w$#]*(\\.[A-Za-z_][\\w$#]*){0,2}$");
    private static final Pattern URL = Rx.of("\\b(?:https?|ftps?|sftp|ldaps?|smtp)://[^\\s\"'<>()\\\\{}]+", Rx.I);
    private static final Pattern SOAP = Rx.of("\\?wsdl|\\.asmx|SoapHttpClientProtocol", Rx.I);
    private static final Pattern DBSET = Rx.of("DbSet<\\s*[\\w.]+\\s*>\\s+(\\w+)");
    private static final Pattern WEB_HOST = Rx.of("WebApplication\\.Create|CreateHostBuilder|Host\\.Create");
    private static final Pattern DESKTOP_MAIN = Rx.of("Application\\.Run\\b|\\.ShowDialog\\(|\\.Show\\(|new\\s+App\\s*\\(");
    private static final Pattern INTERFACE_NAME = Rx.of("^I[A-Z]");
    private static final Pattern ASPNET_CORE = Rx.of("Microsoft\\.AspNetCore");
    private static final Pattern IDENT = Rx.of("^[A-Za-z_]\\w*$");
    private static final Pattern FILE_MODE = Rx.of("FileMode\\.(Create|Append|OpenOrCreate|CreateNew)");
    private static final Pattern HOST_NAME = Rx.of("^[\\w.-]+$");
    private static final Pattern FORMAT_ITEM = Rx.of("\\{(\\d+)(?:[,:][^}]*)?\\}");
    private static final Pattern CRON_HELPER = Rx.of("^Cron\\.(\\w+)\\s*\\(([^)]*)\\)");
    private static final Pattern WITH_IDENTITY = Rx.of("WithIdentity\\(\\s*\"([^\"]+)\"");
    private static final Map<String, String> ESCAPES = Map.of("\\n", " ", "\\r", " ", "\\t", " ", "\\\\", "\\", "\\\"", "\"", "\\'", "'", "\\0", "");

    @Override
    public String name() {
        return "csharp";
    }

    @Override
    public String version() {
        return "2"; // 2: framework and web service on .NET services
    }

    @Override
    public boolean supports(FileRecord file) {
        return file.language.equals("csharp") && file.category != Category.TEST && file.category != Category.GENERATED
                && file.category != Category.BINARY && !("/" + file.path).contains("/obj/");
    }

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        String text = ctx.textOrEmpty();
        Facts facts = new Facts();
        facts.coverage.lines = TextUtil.count(text, '\n') + 1;
        TsNode root = TsTree.parse(PARSER.get(), text);
        if (root.hasError) {
            JavaAnalyzer.errors(root, facts, "C#");
        }
        new Visitor(file, ctx, facts, text).run(root);
        return facts;
    }

    // ---- helpers on the syntax tree -------------------------------------------------------

    static int line(TsNode node) {
        return node.line();
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    static String removePrefix(String s, String prefix) {
        return s.startsWith(prefix) ? s.substring(prefix.length()) : s;
    }

    /** The value of a plain string literal (regular, verbatim or raw), or null. */
    static String string(TsNode node) {
        switch (node.type) {
            case "string_literal" -> {
                StringBuilder out = new StringBuilder();
                for (TsNode c : node.children) {
                    if (c.type.equals("string_literal_content")) {
                        out.append(txt(c));
                    } else if (c.type.equals("escape_sequence")) {
                        String e = txt(c);
                        out.append(ESCAPES.getOrDefault(e, e));
                    }
                }
                return out.toString();
            }
            case "verbatim_string_literal" -> {
                String t = txt(node);
                int n = t.codePointCount(0, t.length());
                if (n <= 3) {
                    return "";
                }
                return t.substring(t.offsetByCodePoints(0, 2), t.offsetByCodePoints(0, n - 1)).replace("\"\"", "\"");
            }
            case "raw_string_literal" -> {
                StringBuilder out = new StringBuilder();
                for (TsNode c : node.children) {
                    if (c.type.equals("raw_string_content")) {
                        out.append(txt(c));
                    }
                }
                return TextUtil.strip(out.toString());
            }
            default -> {
                return null;
            }
        }
    }

    static List<TsNode> args(TsNode call) {
        TsNode al = call.field("arguments");
        if (al == null) {
            for (TsNode c : call.children) {
                if (c.type.equals("argument_list")) {
                    al = c;
                    break;
                }
            }
        }
        if (al == null) {
            return List.of();
        }
        List<TsNode> out = new ArrayList<>();
        for (TsNode a : al.namedChildren()) {
            if (a.type.equals("argument") && !a.namedChildren().isEmpty()) {
                out.add(a.namedChildren().getLast());
            }
        }
        return out;
    }

    static TsNode namedArg(TsNode call, String name) {
        TsNode al = call.field("arguments");
        if (al == null) {
            return null;
        }
        for (TsNode a : al.namedChildren()) {
            List<TsNode> kids = a.namedChildren();
            if (a.type.equals("argument") && kids.size() == 2 && txt(kids.get(0)).equals(name)) {
                return kids.get(1);
            }
        }
        return null;
    }

    /** (receiver, method name) of an invocation. */
    record Callee(TsNode receiver, String name) {}

    static Callee callee(TsNode call) {
        TsNode fn = call.field("function");
        if (fn == null) {
            return new Callee(null, "");
        }
        if (fn.type.equals("member_access_expression")) {
            TsNode name = fn.field("name");
            String n = name != null && name.type.equals("generic_name") ? txt(name.children.getFirst()) : txt(name);
            return new Callee(fn.field("expression"), n);
        }
        if (fn.type.equals("generic_name")) {
            return new Callee(null, txt(fn.children.getFirst()));
        }
        return new Callee(null, txt(fn));
    }

    /** {@code System.Windows.Forms.TextBox} / {@code List<int>}: {@code TextBox} / {@code List}. */
    static String typeName(TsNode node) {
        String t = TextUtil.beforeFirst(txt(node), "<");
        return TextUtil.strip(TextUtil.afterLast(t, "."));
    }

    record Attr(String name, String arg) {}

    /** (name, first string argument) of each attribute on a declaration. */
    static List<Attr> attributes(TsNode node) {
        List<Attr> out = new ArrayList<>();
        for (TsNode al : node.children) {
            if (!al.type.equals("attribute_list")) {
                continue;
            }
            for (TsNode at : al.namedChildren()) {
                if (!at.type.equals("attribute")) {
                    continue;
                }
                String name = typeName(at.field("name"));
                if (name.endsWith("Attribute")) {
                    name = name.substring(0, name.length() - "Attribute".length());
                }
                String arg = null;
                TsNode argList = null;
                for (TsNode c : at.children) {
                    if (c.type.equals("attribute_argument_list")) {
                        argList = c;
                        break;
                    }
                }
                if (argList != null) {
                    for (TsNode a : argList.namedChildren()) {
                        String s = null;
                        for (TsNode x : a.namedChildren()) {
                            s = string(x);
                            if (s != null) {
                                break;
                            }
                        }
                        if (s != null && !TextUtil.beforeFirst(txt(a), "\"").contains("=")) {
                            arg = s;
                            break;
                        }
                    }
                }
                out.add(new Attr(name, arg));
            }
        }
        return out;
    }

    private static int pyInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new PyError("ValueError", "invalid literal for int() with base 10: '" + s + "'");
        }
    }

    /** A Hangfire/Quartz schedule: a cron string, or {@code Cron.Daily(2)} as readable text. */
    static String cronText(TsNode node) {
        if (node == null) {
            return null;
        }
        String s = string(node);
        if (s != null) {
            return s;
        }
        String t = txt(node);
        Matcher m = CRON_HELPER.matcher(t);
        if (m.lookingAt()) {
            List<String> args = new ArrayList<>();
            for (String a : m.group(2).split(",", -1)) {
                if (!TextUtil.strip(a).isEmpty()) {
                    args.add(TextUtil.strip(a));
                }
            }
            String kind = lower(m.group(1));
            String at = "";
            if (!args.isEmpty() && !args.getFirst().isEmpty() && args.getFirst().codePoints().allMatch(Character::isDigit) && kind.equals("daily")) {
                at = String.format(" at %02d:%02d", pyInt(args.get(0)), args.size() > 1 ? pyInt(args.get(1)) : 0);
            }
            return kind + at;
        }
        String head = Rx.head(t, 80);
        return head.isEmpty() ? null : head;
    }

    /** The nodes of those types under {@code node} (not looking inside a match). */
    static List<TsNode> iter(TsNode node, Set<String> kinds) {
        List<TsNode> out = new ArrayList<>();
        Deque<TsNode> stack = new ArrayDeque<>();
        stack.push(node);
        while (!stack.isEmpty()) {
            TsNode n = stack.pop();
            if (kinds.contains(n.type)) {
                out.add(n);
                continue;
            }
            for (int i = n.children.size() - 1; i >= 0; i--) {
                stack.push(n.children.get(i));
            }
        }
        return out;
    }

    /** Input controls the class (or its designer half, {@code InitializeComponent}) creates. */
    static int designerFields(TsNode cls) {
        int n = 0;
        for (TsNode c : iter(cls, Set.of("object_creation_expression"))) {
            if (INPUTS.matcher(typeName(c.field("type"))).lookingAt()) {
                n++;
            }
        }
        return n;
    }

    private static final class CsClass {
        final String name;
        final List<String> bases;
        final List<Attr> attrs;
        String kind = "";

        CsClass(String name, List<String> bases, List<Attr> attrs) {
            this.name = name;
            this.bases = bases;
            this.attrs = attrs;
        }
    }

    private static final class Method {
        final Tracker tracker;
        final Set<String> consumed = new HashSet<>();

        Method(Tracker tracker) {
            this.tracker = tracker;
        }
    }

    private record SqlKey(int line, String text) {}

    /** One file's walk; not thread-safe. */
    private static final class Visitor {
        private final String path;
        private final Facts facts;
        private final String text;
        private final String dialect;
        private final Tracker fields = new Tracker();
        private final Set<String> fieldsConsumed = new HashSet<>();
        private Method method;
        private final Set<String> builders = new HashSet<>();
        private final Map<String, String> varTypes = new HashMap<>();
        private final List<CsClass> classes = new ArrayList<>();
        private String screenId;
        private final Set<SqlKey> sqlSeen = new HashSet<>();
        private final Set<Integer> urlSeen = new HashSet<>();
        private final boolean storedProc;
        private final boolean soap;
        private final Set<String> dbsets = new HashSet<>();

        Visitor(FileRecord file, AnalyzerContext ctx, Facts facts, String text) {
            this.path = file.path;
            this.facts = facts;
            this.text = text;
            this.dialect = ctx.config().sqlDialect();
            this.storedProc = SP.matcher(text).find();
            this.soap = SOAP.matcher(text).find();
            Matcher m = DBSET.matcher(text);
            while (m.find()) {
                dbsets.add(m.group(1));
            }
        }

        // ---- driving ----------------------------------------------------------------------

        void run(TsNode root) {
            facts.add("tech_stack", tech("C#", null, "language", path, null));
            List<TsNode> top = new ArrayList<>();
            for (TsNode c : root.children) {
                if (c.type.equals("global_statement")) {
                    top.add(c);
                }
            }
            if (!top.isEmpty()) { // top-level statements (C# 9+): the program's Main
                if (!WEB_HOST.matcher(text).find()) {
                    entry("Program", "main", line(top.getFirst()), "top-level statements");
                }
                method = new Method(fields.copy());
                for (TsNode g : top) {
                    walk(g);
                }
                endMethod();
            }
            decls(root);
            leftovers(fields, fieldsConsumed, null);
        }

        private void decls(TsNode node) {
            for (TsNode c : node.children) {
                String t = c.type;
                if (t.equals("using_directive")) {
                    using(c);
                } else if (t.equals("namespace_declaration") || t.equals("file_scoped_namespace_declaration")) {
                    TsNode body = c.field("body");
                    decls(body != null ? body : c);
                } else if (t.equals("declaration_list")) {
                    decls(c);
                } else if (TYPES.contains(t)) {
                    cls(c);
                }
            }
        }

        private void using(TsNode node) {
            TsNode nsNode = null;
            for (TsNode c : node.namedChildren()) {
                if (c.type.equals("qualified_name") || c.type.equals("identifier")) {
                    nsNode = c;
                    break;
                }
            }
            String ns = txt(nsNode);
            if (ns.isEmpty()) {
                return;
            }
            for (DotnetAnalyzer.NamespaceTech nt : DotnetAnalyzer.NAMESPACE_TECH) {
                if (ns.equals(nt.namespace()) || ns.startsWith(nt.namespace() + ".")) {
                    facts.add("tech_stack", tech(nt.tech(), null, nt.category(), path, line(node)));
                    break;
                }
            }
            if (DotnetAnalyzer.DB_NAMESPACES.containsKey(ns)) {
                facts.add("tech_stack", tech(DotnetAnalyzer.DB_NAMESPACES.get(ns), null, "database", path, line(node)));
            }
        }

        // ---- classes ----------------------------------------------------------------------

        private void cls(TsNode node) {
            String name = txt(node.field("name"));
            List<String> bases = new ArrayList<>();
            for (TsNode c : node.children) {
                if (c.type.equals("base_list")) {
                    for (TsNode b : c.namedChildren()) {
                        bases.add(typeName(b));
                    }
                    break;
                }
            }
            CsClass cls = new CsClass(name, bases, attributes(node));
            for (String b : bases) {
                if (BASES.containsKey(b)) {
                    cls.kind = BASES.get(b);
                    break;
                }
            }
            if (node.type.equals("interface_declaration")) {
                cls.kind = "interface";
            }
            if (cls.attrs.stream().anyMatch(a -> a.name().equals("ApiController"))) {
                cls.kind = "api";
            }
            if (cls.kind.isEmpty() && lower(path).endsWith(".designer.cs") && designerFields(node) > 0) {
                cls.kind = "form"; // the designer half of a form: "partial class X" without its base
            }
            int line = line(node);
            classes.add(cls);
            facts.symbol("dotnet-class", name, path, line);
            String table = null;
            for (Attr a : cls.attrs) {
                if (a.name().equals("Table") && a.arg() != null && !a.arg().isEmpty()) {
                    table = a.arg();
                    break;
                }
            }
            if (table != null) {
                facts.symbol("jpa-entity", name, path, line).data("table", table.toUpperCase(Locale.ROOT)).data("orm", "ef");
            }
            String bl = String.join(", ", bases);
            if (cls.kind.equals("form") || cls.kind.equals("usercontrol")) {
                screenId = Fact.makeId("screen", "winforms", TextUtil.beforeLast(path, "/"), name);
                facts.add("screens", Doc.of(Schemas.SCREEN,
                        "id", screenId,
                        "name", name,
                        "technology", "winforms",
                        "file", path,
                        "fields_count", (long) designerFields(node),
                        "partial", cls.kind.equals("usercontrol"),
                        "evidence", new ArrayList<>(List.of(new Evidence(path, line, "class " + name + " : " + bl)))));
                facts.symbol("desktop-form", name, path, line).owner(screenId);
                facts.add("tech_stack", tech("Windows Forms", null, "framework", path, line));
            } else if (cls.kind.equals("windows-service") || cls.kind.equals("background-service")) {
                entry(name, "windows-service", line, "class " + name + " : " + bl);
            }
            TsNode body = node.field("body");
            if (body != null) {
                for (TsNode member : body.namedChildren()) {
                    switch (member.type) {
                        case "field_declaration" -> field(member);
                        case "property_declaration" -> property(member);
                        case "method_declaration", "constructor_declaration", "operator_declaration", "destructor_declaration" ->
                                methodDecl(member, cls);
                        case "class_declaration", "struct_declaration", "record_declaration", "interface_declaration" -> cls(member);
                        default -> {}
                    }
                }
            }
            classes.removeLast();
        }

        private void field(TsNode node) {
            for (TsNode c : node.namedChildren()) {
                if (c.type.equals("variable_declaration")) {
                    for (TsNode v : c.namedChildren()) {
                        if (v.type.equals("variable_declarator")) {
                            declarator(v, false, fields);
                        }
                    }
                    break;
                }
            }
        }

        private void property(TsNode node) {
            TsNode t = node.field("type");
            String name = txt(node.field("name"));
            if (t != null && t.type.equals("generic_name") && txt(t.children.getFirst()).equals("DbSet")) {
                TsNode targs = null;
                for (TsNode c : t.children) {
                    if (c.type.equals("type_argument_list")) {
                        targs = c;
                        break;
                    }
                }
                String entity = targs != null && !targs.namedChildren().isEmpty() ? typeName(targs.namedChildren().getFirst()) : name;
                facts.symbol("jpa-entity", name, path, line(node)).data("table", entity.toUpperCase(Locale.ROOT)).data("entity", entity)
                        .data("orm", "ef");
            }
            for (TsNode acc : node.namedChildren()) {
                if (acc.type.equals("accessor_list")) {
                    for (TsNode a : acc.namedChildren()) {
                        TsNode body = a.field("body");
                        if (body != null) {
                            body(body);
                        }
                    }
                }
            }
        }

        private void methodDecl(TsNode node, CsClass cls) {
            String name = txt(node.field("name"));
            int line = line(node);
            Set<String> mods = new HashSet<>();
            for (TsNode c : node.children) {
                if (c.type.equals("modifier")) {
                    mods.add(txt(c));
                }
            }
            List<Attr> attrs = attributes(node);
            TsNode body = node.field("body");
            if (body == null) {
                for (TsNode c : node.children) {
                    if (c.type.equals("arrow_expression_clause")) {
                        body = c;
                        break;
                    }
                }
            }
            if (name.equals("Main") && mods.contains("static") && node.type.equals("method_declaration")) {
                String kind = DESKTOP_MAIN.matcher(txt(body)).find() ? "desktop-app" : "main";
                entry(cls.name + ".Main", kind, line, "static Main");
            }
            serviceFor(name, cls, attrs, mods, line, node.type);
            if (body != null) {
                body(body);
            }
        }

        private void body(TsNode body) {
            method = new Method(fields.copy());
            walk(body);
            endMethod();
        }

        private void endMethod() {
            if (method == null) {
                return;
            }
            leftovers(method.tracker, method.consumed, fields);
            method = null;
        }

        private void serviceFor(String name, CsClass cls, List<Attr> attrs, Set<String> mods, int line, String nodeType) {
            Set<String> names = new HashSet<>();
            for (Attr a : attrs) {
                names.add(a.name());
            }
            String handler = cls.name + "." + name;
            if (names.contains("WebMethod") || names.contains("OperationContract")) {
                String how = names.contains("WebMethod") ? "ASMX" : "WCF";
                String svc = how.equals("WCF") && INTERFACE_NAME.matcher(cls.name).lookingAt()
                        ? cls.name.substring(cls.name.offsetByCodePoints(0, 1)) : cls.name;
                String url = svc + "." + (how.equals("ASMX") ? "asmx" : "svc") + "/" + name;
                String fw = how.equals("WCF") ? "WCF" : "ASP.NET Web Services (ASMX)";
                service(how + " " + svc + "." + name, "soap-operation", handler, url, line, fw, svc);
                facts.add("tech_stack", tech(fw, null, "framework", path, line));
                return;
            }
            if (!(cls.kind.equals("mvc") || cls.kind.equals("api")) || !nodeType.equals("method_declaration")) {
                return;
            }
            if (!mods.contains("public") || mods.contains("static") || names.contains("NonAction")) {
                return;
            }
            String verb = null;
            for (Attr a : attrs) {
                if (HTTP.containsKey(a.name())) {
                    verb = HTTP.get(a.name());
                    break;
                }
            }
            String route = null;
            for (Attr a : attrs) {
                if ((HTTP.containsKey(a.name()) || a.name().equals("Route")) && a.arg() != null) {
                    route = a.arg();
                    break;
                }
            }
            String prefix = null;
            for (Attr a : cls.attrs) {
                if ((a.name().equals("Route") || a.name().equals("RoutePrefix")) && a.arg() != null && !a.arg().isEmpty()) {
                    prefix = a.arg();
                    break;
                }
            }
            String ctrl = cls.name.endsWith("Controller") ? cls.name.substring(0, cls.name.length() - "Controller".length()) : cls.name;
            if (prefix == null) {
                prefix = cls.kind.equals("api") ? "api/" + ctrl : ctrl;
            }
            prefix = prefix.replace("[controller]", ctrl);
            String urlPath;
            if (route != null && (route.startsWith("/") || route.startsWith("~/"))) {
                urlPath = "/" + TextUtil.lstrip(route, "~/");
            } else {
                String tail = route != null ? route : (cls.kind.equals("api") ? "" : name);
                urlPath = "/" + TextUtil.strip(prefix, "/") + (!tail.isEmpty() ? "/" + tail : "");
            }
            urlPath = urlPath.replace("[action]", name);
            String kind = cls.kind.equals("api") ? "rest-operation" : "aspnet-mvc";
            String fw = ASPNET_CORE.matcher(text).find() ? "ASP.NET Core" : kind.equals("rest-operation") ? "ASP.NET Web API" : "ASP.NET MVC";
            String shown = verb != null ? verb : kind.equals("aspnet-mvc") ? "GET" : "ANY";
            service(shown + " " + urlPath, kind, handler, urlPath, line, kind.equals("rest-operation") ? fw : null, null);
            facts.add("tech_stack", tech(fw, null, "framework", path, line));
        }

        // ---- statements -------------------------------------------------------------------

        private boolean cond(TsNode node) {
            TsNode p = node.parent;
            while (p != null && !p.type.equals("method_declaration")) {
                if (COND.contains(p.type)) {
                    return true;
                }
                if (COND_STOP.contains(p.type)) {
                    return false;
                }
                p = p.parent;
            }
            return false;
        }

        private Tracker tracker() {
            return method != null ? method.tracker : fields;
        }

        private Set<String> consumed() {
            return method != null ? method.consumed : fieldsConsumed;
        }

        private void consume(String name, Built value) {
            consumed().add(name);
            if (fields.get(name) == value) {
                fieldsConsumed.add(name);
            }
        }

        private void walk(TsNode node) {
            String t = node.type;
            if (t.equals("local_function_statement") || t.equals("class_declaration")) {
                return;
            }
            switch (t) {
                case "variable_declarator" -> declarator(node, cond(node), tracker());
                case "assignment_expression" -> assignment(node);
                case "invocation_expression" -> invocation(node);
                case "object_creation_expression" -> creation(node);
                case "string_literal", "verbatim_string_literal", "raw_string_literal", "interpolated_string_expression" -> literal(node, true);
                default -> {
                    for (TsNode c : node.children) {
                        walk(c);
                    }
                }
            }
        }

        private static TsNode valueNode(TsNode declarator) {
            boolean seenEq = false;
            for (TsNode c : declarator.children) {
                if (seenEq) {
                    return c;
                }
                if (c.type.equals("=")) {
                    seenEq = true;
                }
            }
            return null;
        }

        private void declarator(TsNode node, boolean cond, Tracker tracker) {
            TsNode nameNode = node.field("name");
            if (nameNode == null) {
                nameNode = node.namedChildren().getFirst();
            }
            String name = lower(txt(nameNode));
            TsNode value = valueNode(node);
            if (value == null) {
                return;
            }
            if (value.type.equals("object_creation_expression") || value.type.equals("implicit_object_creation_expression")) {
                String tname = typeName(value.field("type"));
                if (tname.equals("StringBuilder")) {
                    builders.add(name);
                    List<TsNode> args = args(value);
                    Built first = args.isEmpty() ? null : eval(args.getFirst(), true);
                    tracker.assign(name, first != null ? first : empty(line(node)), line(node), cond, false);
                    return;
                }
                if (!tname.isEmpty()) {
                    varTypes.put(name, tname);
                }
            }
            Built b = eval(value, false);
            walkValue(value, b != null);
            if (b != null) {
                tracker.assign(name, b, line(node), cond, false);
            }
        }

        /** Calls inside a right-hand side; a string value is tracked, not reported here. */
        private void walkValue(TsNode value, boolean asValue) {
            if (asValue) {
                for (TsNode n : iter(value, CALLS)) {
                    if (n.type.equals("invocation_expression")) {
                        invocation(n);
                    } else {
                        creation(n);
                    }
                }
                for (TsNode n : iter(value, STRINGS)) {
                    literal(n, false);
                }
            } else {
                walk(value);
            }
        }

        private void assignment(TsNode node) {
            TsNode left = node.field("left");
            TsNode right = node.field("right");
            String op = txt(node.field("operator"));
            if (left == null || right == null) {
                return;
            }
            int line = line(node);
            String lname = removePrefix(txt(left), "this.");
            String last = TextUtil.afterLast(lname, ".");
            if (last.equals("CommandText")) {
                Built b = eval(right, true);
                if (b != null) {
                    emit(b, "ado.net", line, false, true, false);
                }
                walkValue(right, true);
                return;
            }
            if (last.equals("ConnectionString")) {
                Built b = eval(right, false);
                if (b != null) {
                    DotnetAnalyzer.addConnection(facts, b.text, path, line, "ado.net", "");
                }
                return;
            }
            Built b = eval(right, false);
            walkValue(right, b != null);
            if (!IDENT.matcher(lname).lookingAt()) {
                return;
            }
            String name = lower(lname);
            Tracker tr = tracker();
            boolean cond = cond(node);
            if (op.equals("+=") && b != null) {
                tr.assign(name, b, line, cond, tr.get(name) != null);
                return;
            }
            if (!op.equals("=")) {
                return;
            }
            if (right.type.equals("object_creation_expression") && typeName(right.field("type")).equals("StringBuilder")) {
                builders.add(name);
                List<TsNode> args = args(right);
                Built first = args.isEmpty() ? null : eval(args.getFirst(), true);
                tr.assign(name, first != null ? first : empty(line), line, cond, false);
                return;
            }
            // sql = sql + " AND ...": an append
            if (right.type.equals("binary_expression") && tr.get(name) != null) {
                TsNode leftmost = right;
                while (leftmost != null && leftmost.type.equals("binary_expression")) {
                    leftmost = leftmost.field("left");
                }
                if (leftmost != null && lower(txt(leftmost)).equals(name)) {
                    Built rest = evalWithoutHead(right, name);
                    if (rest != null) {
                        tr.assign(name, rest, line, cond, true);
                    }
                    return;
                }
            }
            if (b != null) {
                tr.assign(name, b, line, cond, false);
            } else if (tr.get(name) != null && !cond) {
                tr.remove(name);
            }
        }

        /** {@code a + b + c} where {@code a} is the variable itself: the text of {@code b + c}. */
        private Built evalWithoutHead(TsNode node, String name) {
            List<TsNode> parts = new ArrayList<>();
            TsNode n = node;
            while (n.type.equals("binary_expression") && txt(n.field("operator")).equals("+")) {
                parts.add(n.field("right"));
                n = n.field("left");
            }
            java.util.Collections.reverse(parts);
            Built before = tracker().get(name);
            Built out = empty(line(node));
            boolean known = false;
            for (TsNode p : parts) {
                Built v = eval(p, false);
                if (v == null) {
                    v = hole((before != null ? before.text : "") + out.text, line(p));
                } else {
                    known = true;
                }
                out = concat(out, v);
            }
            if (!known) {
                return null;
            }
            out.how.add("concatenation");
            return out;
        }

        private void invocation(TsNode node) {
            Callee c = callee(node);
            TsNode recv = c.receiver();
            String name = c.name();
            List<TsNode> args = args(node);
            int line = line(node);
            String rtext = txt(recv);
            String rname = lower(removePrefix(rtext, "this."));
            boolean handled = false;
            // StringBuilder chains: sb.Append(a).Append(b)
            List<TsNode> chain = new ArrayList<>();
            TsNode n = node;
            while (n != null && n.type.equals("invocation_expression")) {
                chain.add(n);
                n = callee(n).receiver();
            }
            String base = n != null ? lower(removePrefix(txt(n), "this.")) : "";
            if (builders.contains(base)) {
                for (int i = chain.size() - 1; i >= 0; i--) {
                    TsNode call = chain.get(i);
                    String verb = callee(call).name();
                    List<TsNode> cargs = args(call);
                    Built v = null;
                    if (verb.equals("AppendFormat") && !cargs.isEmpty()) {
                        v = format(cargs);
                    } else if ((verb.equals("Append") || verb.equals("AppendLine")) && !cargs.isEmpty()) {
                        v = eval(cargs.getFirst(), false);
                    }
                    if (verb.equals("AppendLine")) {
                        v = v != null ? concat(v, lit(" ", line)) : lit(" ", line);
                    }
                    if (v != null && (verb.equals("Append") || verb.equals("AppendLine") || verb.equals("AppendFormat"))) {
                        v.how.add("string-builder");
                        tracker().assign(base, v, line, cond(node), true);
                    }
                }
                return;
            }
            if (!args.isEmpty() && (EF_RAW.contains(name) || DAPPER.contains(name))) {
                Built b = eval(args.getFirst(), true);
                if (b != null) {
                    String source = EF_RAW.contains(name) ? "ef-raw" : "dapper";
                    TsNode sp = namedArg(node, "commandType");
                    if (sp != null && txt(sp).contains("StoredProcedure")) {
                        emit(b, source, line, false, true, true);
                    } else {
                        emit(b, source, line, false, false, false);
                    }
                    handled = true;
                }
            }
            if (recv != null && recv.type.equals("member_access_expression") && (EF_READ.contains(name) || EF_WRITE.containsKey(name))) {
                String ctxName = txt(recv.field("expression"));
                String prop = txt(recv.field("name"));
                if (dbsets.contains(prop) || CONTEXT_VAR.matcher(ctxName).lookingAt()) {
                    orm(EF_READ.contains(name) ? "SELECT" : EF_WRITE.get(name), prop, name, line);
                    handled = true;
                }
            }
            if (name.equals("Show") || name.equals("ShowDialog")) {
                String target = null;
                if (recv != null && recv.type.equals("object_creation_expression")) {
                    target = typeName(recv.field("type"));
                } else if (varTypes.containsKey(rname)) {
                    target = varTypes.get(rname);
                }
                if (target != null && !target.isEmpty() && !target.equals("MessageBox") && !target.equals("Form")) {
                    navigate(target, line);
                }
            }
            if (rtext.equals("RecurringJob") && name.equals("AddOrUpdate")) {
                hangfire(args, line);
            }
            if (name.equals("WithCronSchedule") || name.equals("CronSchedule")) {
                quartz(node, args, line);
            }
            if (rtext.equals("File") && !args.isEmpty() && (FILE_READ.contains(name) || FILE_WRITE.contains(name))) {
                Built b = eval(args.getFirst(), false);
                if (b != null && !TextUtil.strip(b.text, "? ").isEmpty()) {
                    file(b.text, FILE_WRITE.contains(name) ? "outbound" : "inbound", line, "File." + name);
                }
            }
            TsNode fn = node.field("function");
            if (fn != null && fn.type.equals("member_access_expression")) {
                TsNode expr = fn.field("expression");
                walk(expr != null ? expr : fn); // receivers: chained calls
            }
            for (TsNode a : args) {
                if (handled) {
                    walkValue(a, true);
                } else {
                    walk(a);
                    uses(a, line);
                }
            }
        }

        private void creation(TsNode node) {
            String tname = typeName(node.field("type"));
            List<TsNode> args = args(node);
            int line = line(node);
            if (COMMAND_TYPES.matcher(tname).lookingAt()) {
                if (!args.isEmpty()) {
                    Built b = eval(args.getFirst(), true);
                    if (b != null) {
                        emit(b, "ado.net", line, false, true, false);
                    }
                }
                for (TsNode init : node.children) {
                    if (!init.type.equals("initializer_expression")) {
                        continue;
                    }
                    for (TsNode a : init.namedChildren()) {
                        if (a.type.equals("assignment_expression") && txt(a.field("left")).equals("CommandText")) {
                            Built b = eval(a.field("right"), true);
                            if (b != null) {
                                emit(b, "ado.net", line, false, true, false);
                            }
                        }
                    }
                    break;
                }
                for (TsNode a : args) {
                    walkValue(a, true);
                }
                return;
            }
            if ((tname.equals("StreamReader") || tname.equals("StreamWriter") || tname.equals("FileStream")) && !args.isEmpty()) {
                Built b = eval(args.getFirst(), false);
                if (b != null && !TextUtil.strip(b.text, "? ").isEmpty() && (b.text.contains("\\") || b.text.contains("/") || b.text.contains("."))) {
                    boolean out = tname.equals("StreamWriter") || (tname.equals("FileStream") && FILE_MODE.matcher(txt(node)).find());
                    file(b.text, out ? "outbound" : "inbound", line, "new " + tname);
                }
            }
            if (tname.equals("MessageQueue") && !args.isEmpty()) {
                Built b = eval(args.getFirst(), false);
                if (b != null) {
                    facts.add("integrations", integration("MQ", b.text, "unknown", path, line, null, "MSMQ", null));
                }
            }
            if (tname.equals("SmtpClient") && !args.isEmpty()) {
                Built b = eval(args.getFirst(), false);
                if (b != null && HOST_NAME.matcher(b.text).lookingAt()) {
                    facts.add("integrations", integration("SMTP", b.text, "outbound", path, line));
                }
            }
            if (!tname.isEmpty() && !Set.of("StringBuilder", "List", "Dictionary", "Exception").contains(tname)
                    && !txt(node.field("type")).contains(".")) {
                facts.ref("uses", "file:" + path, path, tname, List.of("dotnet-class", "vb-class"), path, line).reportUnresolved(false);
            }
            for (TsNode c : node.children) {
                if (c.type.equals("argument_list") || c.type.equals("initializer_expression")) {
                    walk(c);
                }
            }
        }

        /** SQL handed to a procedure of the application: {@code Run(sql)}, {@code Exec("SELECT ...")}. */
        private void uses(TsNode arg, int line) {
            if (arg.type.equals("identifier")) {
                String name = lower(txt(arg));
                Built v = tracker().get(name);
                if (v != null && !consumed().contains(name) && SqlTools.looksLikeSql(TextUtil.strip(v.text), true)) {
                    consume(name, v);
                    emit(v, "cs-string", line, true, false, false);
                }
            } else if (arg.type.equals("binary_expression") || arg.type.equals("interpolated_string_expression")) {
                Built b = eval(arg, false);
                if (b != null) {
                    emit(b, "cs-string", line, true, false, false);
                }
            }
        }

        private void literal(TsNode node, boolean sql) {
            int line = line(node);
            String s = string(node);
            if (s == null) { // interpolated: its fixed text
                StringBuilder sb = new StringBuilder();
                for (TsNode c : node.children) {
                    if (c.type.equals("string_content")) {
                        sb.append(txt(c));
                    }
                }
                s = sb.toString();
            }
            if (s.contains("://") && !urlSeen.contains(node.startByte)) {
                urlSeen.add(node.startByte);
                Matcher u = URL.matcher(s);
                while (u.find()) {
                    String[] c = Integrations.classifyUrl(u.group(), soap);
                    if (c != null) {
                        facts.add("integrations", integration(c[0], c[1], "outbound", path, line));
                    }
                }
            } else if (s.contains(";") && s.contains("=")) {
                DotnetAnalyzer.addConnection(facts, s, path, line, "ado.net", "");
            }
            if (sql && SqlTools.looksLikeSql(TextUtil.strip(s), true)) {
                TsNode p = node.parent;
                TsNode call = p != null && p.parent != null ? p.parent.parent : null;
                if (call != null && call.type.equals("invocation_expression") && BUILDING.contains(callee(call).name())) {
                    return; // part of a text built elsewhere (reported where it is used)
                }
                if (p != null && (p.type.equals("argument") || p.type.equals("return_statement") || p.type.equals("arrow_expression_clause"))) {
                    Built b = eval(node, false);
                    emit(b != null ? b : lit(s, line), "cs-string", line, true, false, false);
                }
            }
        }

        // ---- values -----------------------------------------------------------------------

        private Built eval(TsNode node, boolean consume) {
            if (node == null) {
                return null;
            }
            String t = node.type;
            int line = line(node);
            String s = string(node);
            if (s != null) {
                return lit(s, line);
            }
            switch (t) {
                case "interpolated_string_expression" -> {
                    Built out = empty(line);
                    for (TsNode c : node.children) {
                        if (c.type.equals("string_content")) {
                            out = concat(out, lit(txt(c).replace("{{", "{").replace("}}", "}"), line));
                        } else if (c.type.equals("escape_sequence")) {
                            String e = txt(c);
                            out = concat(out, lit(Set.of("\\n", "\\r", "\\t").contains(e) ? " " : pySlice1(e), line));
                        } else if (c.type.equals("interpolation")) {
                            TsNode inner = null;
                            for (TsNode x : c.namedChildren()) {
                                if (!Set.of("interpolation_brace", "interpolation_alignment_clause", "interpolation_format_clause").contains(x.type)) {
                                    inner = x;
                                    break;
                                }
                            }
                            Built v = inner != null ? eval(inner, consume) : null;
                            out = concat(out, v != null ? v : hole(out.text, line));
                        }
                    }
                    out.how.add("interpolation");
                    return out;
                }
                case "parenthesized_expression" -> {
                    List<TsNode> named = node.namedChildren();
                    return eval(named.isEmpty() ? null : named.getFirst(), consume);
                }
                case "binary_expression" -> {
                    if (!txt(node.field("operator")).equals("+")) {
                        return null;
                    }
                    Built a = eval(node.field("left"), consume);
                    Built b = eval(node.field("right"), consume);
                    if (a == null && b == null) {
                        return null;
                    }
                    if (a == null) {
                        a = hole("", line);
                    }
                    if (b == null) {
                        b = hole(a.text, line);
                    }
                    Built out = concat(a, b);
                    out.how.add("concatenation");
                    return out;
                }
                case "conditional_expression" -> {
                    Built x = eval(node.field("consequence"), consume);
                    Built y = eval(node.field("alternative"), consume);
                    if (x != null && y != null) {
                        return choice(x, y);
                    }
                    return x != null ? x : y;
                }
                default -> {}
            }
            if (t.equals("identifier") || (t.equals("member_access_expression") && txt(node).startsWith("this."))) {
                String name = lower(removePrefix(txt(node), "this."));
                Built v = tracker().get(name);
                if (v != null && consume) {
                    consume(name, v);
                }
                return v;
            }
            if (t.equals("member_access_expression") && NEWLINE.matcher(txt(node)).lookingAt()) {
                return lit(" ", line);
            }
            if (t.equals("invocation_expression")) {
                Callee c = callee(node);
                String r = txt(c.receiver());
                String key = lower(removePrefix(r, "this."));
                if (c.name().equals("ToString") && builders.contains(key)) {
                    Built v = tracker().get(key);
                    if (v != null && consume) {
                        consume(key, v);
                    }
                    return v;
                }
                if (c.name().equals("Format") && (r.equals("string") || r.equals("String"))) {
                    return format(args(node));
                }
                if (c.name().equals("Concat") && (r.equals("string") || r.equals("String"))) {
                    Built out = empty(line);
                    boolean known = false;
                    for (TsNode arg : args(node)) {
                        Built part = eval(arg, consume);
                        known = known || part != null;
                        out = concat(out, part != null ? part : hole(out.text, line));
                    }
                    return known ? out : null;
                }
            }
            return null;
        }

        private static String pySlice1(String s) {
            return s.isEmpty() ? "" : s.substring(s.offsetByCodePoints(0, 1));
        }

        private Built format(List<TsNode> args) {
            if (args.isEmpty()) {
                return null;
            }
            Built fmt = eval(args.getFirst(), false);
            if (fmt == null) {
                return null;
            }
            List<Built> values = new ArrayList<>();
            for (TsNode a : args.subList(1, args.size())) {
                values.add(eval(a, false));
            }
            int line = fmt.line;
            Built out = empty(line);
            int pos = 0;
            Matcher m = FORMAT_ITEM.matcher(fmt.text);
            while (m.find()) {
                out = concat(out, lit(fmt.text.substring(pos, m.start()), line));
                int k = Integer.parseInt(m.group(1));
                Built v = k < values.size() ? values.get(k) : null;
                out = concat(out, v != null ? v : hole(out.text, line));
                pos = m.end();
            }
            out = concat(out, lit(fmt.text.substring(pos), line));
            out.how.add("string-format");
            return out;
        }

        // ---- results ----------------------------------------------------------------------

        private void emit(Built b, String source, int line, boolean strict, boolean procOk, boolean forceProc) {
            String text = TextUtil.strip(b.text);
            if (procOk && (storedProc || forceProc) && PROC_NAME.matcher(text).lookingAt() && !SqlTools.looksLikeSql(text)) {
                text = "CALL " + text;
                Built nb = new Built(text, b.line);
                nb.lines = new java.util.LinkedHashSet<>(b.lines);
                b = nb;
            }
            if (!SqlTools.looksLikeSql(text, strict)) {
                return;
            }
            if (!sqlSeen.add(new SqlKey(line, text))) {
                return;
            }
            Doc fact = SqlTools.sqlFact(path, line, text, source, dialect, b.dynamic(), false);
            DynSql.fill(fact, b);
            facts.add("sql_statements", fact);
        }

        private void orm(String verb, String entity, String how, int line) {
            String text = switch (verb) {
                case "SELECT" -> "SELECT /* " + how + " */ FROM " + entity;
                case "INSERT" -> "INSERT /* " + how + " */ INTO " + entity;
                case "UPDATE" -> "UPDATE " + entity + " /* " + how + " */";
                default -> "DELETE /* " + how + " */ FROM " + entity;
            };
            if (!sqlSeen.add(new SqlKey(line, text))) {
                return;
            }
            Doc fact = SqlTools.sqlFact(path, line, text, "entity-framework", null, false, true);
            fact.set("type", verb);
            fact.set("parsed", false);
            fact.set("reason", "Entity Framework " + how + ": the ORM generates the SQL at run time");
            facts.add("sql_statements", fact);
        }

        private void leftovers(Tracker tr, Set<String> consumed, Tracker inherited) {
            for (var e : new ArrayList<>(tr.current().entrySet())) {
                if (consumed.contains(e.getKey()) || (inherited != null && inherited.get(e.getKey()) == e.getValue())) {
                    continue;
                }
                emit(e.getValue(), "cs-string", e.getValue().line, true, false, false);
            }
        }

        private void service(String name, String kind, String handler, String url, int line, String framework, String webService) {
            String sid = Fact.makeId("service", kind, path, name);
            facts.add("services", Doc.of(Schemas.SERVICE,
                    "id", sid,
                    "name", name,
                    "kind", kind,
                    "file", path,
                    "handler", handler,
                    "url", url,
                    "framework", framework,
                    "web_service", webService,
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

        private void navigate(String target, int line) {
            facts.ref("forwards", screenId != null ? "screen:" + screenId : "file:" + path, path, target, List.of("desktop-form"), path, line)
                    .reportUnresolved(false);
        }

        private void file(String target, String direction, int line, String how) {
            facts.add("integrations", integration("FILE", TextUtil.strip(target), direction, path, line, null, how, null));
        }

        private void hangfire(List<TsNode> args, int line) {
            String name = !args.isEmpty() ? string(args.getFirst()) : null;
            List<TsNode> rest = name != null ? args.subList(1, args.size()) : args;
            TsNode job = rest.isEmpty() ? null : rest.getFirst();
            String cron = rest.size() > 1 ? cronText(rest.get(1)) : null;
            String handler = job != null ? Rx.head(TextUtil.strip(TextUtil.afterFirst(txt(job), "=>")), 80) : "";
            String jobName = name != null && !name.isEmpty() ? name : !handler.isEmpty() ? handler : "job:" + line;
            job(jobName, cron, handler, line, "Hangfire RecurringJob", "hangfire");
        }

        private void quartz(TsNode node, List<TsNode> args, int line) {
            TsNode stmt = node;
            while (stmt.parent != null && !stmt.parent.type.equals("block") && !stmt.parent.type.equals("global_statement")) {
                stmt = stmt.parent;
            }
            Matcher ident = WITH_IDENTITY.matcher(txt(stmt));
            String cron = args.isEmpty() ? null : cronText(args.getFirst());
            job(ident.find() ? ident.group(1) : "quartz:" + line, cron, "", line, "Quartz.NET cron trigger", "quartz.net");
        }

        private void job(String name, String schedule, String handler, int line, String how, String scheduler) {
            String jid = Fact.makeId("batch_job", scheduler, path, name);
            facts.add("batch_jobs", Doc.of(Schemas.BATCH_JOB,
                    "id", jid,
                    "name", name,
                    "scheduler", scheduler,
                    "description", "Scheduled in the code with " + how,
                    "schedule", schedule,
                    "command", path,
                    "programs", handler.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(handler)),
                    "evidence", new ArrayList<>(List.of(new Evidence(path, line, how)))));
            facts.symbol("cron-job", name, path, line).owner(jid);
        }
    }
}
