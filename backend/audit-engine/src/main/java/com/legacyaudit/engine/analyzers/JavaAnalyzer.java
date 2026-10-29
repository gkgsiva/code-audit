package com.legacyaudit.engine.analyzers;

import static com.legacyaudit.engine.analyzers.BuildAnalyzer.tech;
import static com.legacyaudit.engine.analyzers.Integrations.integration;
import static com.legacyaudit.engine.analyzers.ts.TsNode.txt;

import com.legacyaudit.engine.analyzers.ts.TsNode;
import com.legacyaudit.engine.analyzers.ts.TsTree;
import com.legacyaudit.engine.inventory.Rx;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.treesitter.TSParser;
import org.treesitter.TreeSitterJava;

/**
 * Java sources (tree-sitter-java): classes, methods, annotations, imports, entry points, SQL in
 * strings (JDBC, JdbcTemplate, Hibernate/JPA, Spring Data), endpoints (HTTP/SOAP, sockets, FTP,
 * MQ/JMS) and services exposed by annotations (Spring MVC, JAX-WS, JAX-RS, EJB, Struts 2).
 * Spring XML configuration is handled by the spring analyzer.
 */
public final class JavaAnalyzer implements Analyzer {

    private static final ThreadLocal<TSParser> PARSER = TsTree.parsers(TreeSitterJava::new);

    static final Map<String, String> JDBC_METHODS = WebAnalyzer.ordered(
            "prepareStatement", "jdbc", "prepareCall", "jdbc", "executeQuery", "jdbc", "executeUpdate", "jdbc", "execute", "jdbc",
            "addBatch", "jdbc", "nativeSQL", "jdbc");
    static final Set<String> TEMPLATE_METHODS = Set.of("query", "queryForObject", "queryForList", "queryForMap", "queryForRowSet",
            "queryForInt", "queryForLong", "update", "batchUpdate", "execute", "call");
    static final Map<String, String> JMS_TEMPLATE_METHODS = WebAnalyzer.ordered(
            "send", "producer", "convertAndSend", "producer", "receive", "consumer", "receiveAndConvert", "consumer",
            "receiveSelected", "consumer");
    static final Set<String> HQL_METHODS = Set.of("createQuery", "find", "iterate", "bulkUpdate");
    static final Set<String> NATIVE_METHODS = Set.of("createSQLQuery", "createNativeQuery");
    static final Map<String, String> MAPPING_ANNOTATIONS = new LinkedHashMap<>();

    static {
        MAPPING_ANNOTATIONS.put("RequestMapping", null);
        MAPPING_ANNOTATIONS.put("GetMapping", "GET");
        MAPPING_ANNOTATIONS.put("PostMapping", "POST");
        MAPPING_ANNOTATIONS.put("PutMapping", "PUT");
        MAPPING_ANNOTATIONS.put("DeleteMapping", "DELETE");
        MAPPING_ANNOTATIONS.put("PatchMapping", "PATCH");
    }

    static final Set<String> JAXRS_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");
    static final Set<String> EJB_LIFECYCLE = Set.of("ejbCreate", "ejbRemove", "ejbActivate", "ejbPassivate", "ejbLoad", "ejbStore",
            "setSessionContext", "setEntityContext", "unsetEntityContext", "setMessageDrivenContext");
    static final Set<String> SPRING_DATA_BASES = Set.of("JpaRepository", "CrudRepository", "PagingAndSortingRepository", "Repository",
            "MongoRepository", "JpaSpecificationExecutor", "ListCrudRepository");
    static final List<String> SOAP_IMPORTS = List.of("org.apache.axis", "javax.xml.rpc", "javax.xml.ws", "jakarta.xml.ws",
            "org.springframework.ws", "org.apache.cxf");
    static final Set<String> REST_VERBS = JAXRS_METHODS;
    static final Map<String, String> REST_TEMPLATE = WebAnalyzer.ordered(
            "getForObject", "GET", "getForEntity", "GET", "postForObject", "POST", "postForEntity", "POST", "postForLocation", "POST",
            "put", "PUT", "delete", "DELETE", "patchForObject", "PATCH", "headForHeaders", "HEAD", "optionsForAllow", "OPTIONS",
            "exchange", "ANY", "execute", "ANY");
    static final Set<String> WS_TEMPLATE_METHODS = Set.of("marshalSendAndReceive", "sendSourceAndReceiveToResult", "sendSourceAndReceive",
            "sendAndReceive");
    static final Map<String, String> HTTP_CLIENT_TYPES = WebAnalyzer.ordered(
            "HttpGet", "GET", "HttpPost", "POST", "HttpPut", "PUT", "HttpDelete", "DELETE", "HttpPatch", "PATCH", "HttpHead", "HEAD",
            "HttpOptions", "OPTIONS", "GetMethod", "GET", "PostMethod", "POST", "PutMethod", "PUT", "DeleteMethod", "DELETE");
    // Java desktop windows (decision 141): a top-level window is a screen, a panel a partial.
    static final Set<String> SWING_WINDOWS = Set.of("JFrame", "JDialog", "JInternalFrame", "JWindow", "JApplet", "JRibbonFrame");
    static final Set<String> AWT_WINDOWS = Set.of("Frame", "Dialog", "Applet", "Window");
    static final Set<String> SWING_PANELS = Set.of("JPanel", "JComponent", "JScrollPane", "JLayeredPane", "JDesktopPane");
    static final Set<String> AWT_PANELS = Set.of("Panel", "Canvas");
    static final Pattern DESKTOP_INPUTS = Rx.of(
            "^(JTextField|JTextArea|JPasswordField|JFormattedTextField|JComboBox|JCheckBox|"
            + "JRadioButton|JList|JTable|JSpinner|JSlider|JTree|JEditorPane|JTextPane|JDateChooser|"
            + "TextField|TextArea|Choice|Checkbox|List|ComboBox|CheckBox|RadioButton|DatePicker|"
            + "ListView|TableView|PasswordField|Spinner|Slider|ChoiceBox)$");
    static final Pattern JAVAX_EE = Rx.of(
            "^javax\\.(servlet|ejb|persistence|jms|mail|faces|ws|xml\\.ws|xml\\.bind|annotation|"
            + "transaction|validation|inject|enterprise|resource|security\\.jacc|websocket)\\b");

    static final int MAX_VARIANTS = 5;
    static final Set<String> STRING_METHODS = Set.of("format", "formatted", "valueOf", "concat", "append", "toString", "trim");
    static final Pattern FORMAT_SPEC = Rx.of("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([a-zA-Z%])");
    static final Set<String> COND_STATEMENTS = Set.of("if_statement", "while_statement", "for_statement", "enhanced_for_statement",
            "do_statement", "switch_expression", "switch_statement", "catch_clause", "lambda_expression");
    static final Set<String> BUILDER_TYPES = Set.of("StringBuilder", "StringBuffer", "StringJoiner");
    static final Set<String> CLASS_TYPES = Set.of("class_declaration", "interface_declaration", "enum_declaration", "record_declaration",
            "annotation_type_declaration");
    static final Set<String> NESTED_TYPES = Set.of("class_declaration", "interface_declaration", "enum_declaration", "record_declaration");

    private static final Pattern UI_MAIN = Rx.of("setVisible\\(\\s*true|SwingUtilities\\.invoke|EventQueue\\.invoke|\\blaunch\\(");
    private static final Pattern DERIVED = Rx.of("(find|read|get|query|count|delete|exists|stream)\\w*By");
    private static final Pattern REPOSITORY = Rx.of("Repository\\s*<\\s*(\\w+)");
    private static final Pattern TEMPLATE_OBJ = Rx.of("(?i)template|jdbc");
    private static final Pattern JMS_OBJ = Rx.of("(?i)jms");
    private static final Pattern REST_OBJ = Rx.of("(?i)rest");
    private static final Pattern WS_OBJ = Rx.of("(?i)template|webservice");
    private static final Pattern NAME_CHARS = Rx.of("^[\\w.]+$");
    private static final Pattern GET_PORT = Rx.of("^get\\w*Port$");
    private static final Pattern REQUEST_METHOD = Rx.of("setRequestMethod\\(\\s*\"(\\w+)\"");
    private static final Pattern PRODUCER = Rx.of("createProducer|createSender|\\.send\\(|convertAndSend");
    private static final Pattern CONSUMER = Rx.of("createConsumer|createReceiver|\\.receive\\(|onMessage\\(");
    private static final Pattern URLS = Rx.of("(jdbc:[^\\s\"']+|[a-z]+://[^\\s\"'<>]+)", Rx.I);

    @Override
    public String name() {
        return "java";
    }

    @Override
    public String version() {
        return "6";
    }

    @Override
    public boolean supports(FileRecord file) {
        return "java".equals(file.language);
    }

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        String text = ctx.textOrEmpty();
        Facts facts = new Facts();
        facts.coverage.lines = TextUtil.lineCount(text);
        TsNode root = TsTree.parse(PARSER.get(), text);
        if (root.hasError) {
            errors(root, facts, "Java");
        }
        new Visitor(file, ctx, facts).run(root);
        return facts;
    }

    /** One coverage gap per syntax error ({@code label}: the language, as in "Java syntax error"). */
    static void errors(TsNode root, Facts facts, String label) {
        Deque<TsNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            TsNode n = stack.pop();
            if (n.type.equals("ERROR") || n.missing) {
                facts.coverage.gap(n.startRow + 1, n.endRow + 1, label + " syntax error");
                continue;
            }
            if (n.hasError) {
                for (TsNode c : n.children) {
                    stack.push(c);
                }
            }
        }
    }

    // ---- Python str helpers ----------------------------------------------------------------

    static boolean firstUpper(String s) {
        return !s.isEmpty() && Character.isUpperCase(s.charAt(0));
    }

    static boolean pyLower(String s) {
        boolean cased = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c) || Character.isTitleCase(c)) {
                return false;
            }
            if (Character.isLowerCase(c)) {
                cased = true;
            }
        }
        return cased;
    }

    static boolean pyUpper(String s) {
        boolean cased = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLowerCase(c) || Character.isTitleCase(c)) {
                return false;
            }
            if (Character.isUpperCase(c)) {
                cased = true;
            }
        }
        return cased;
    }

    static String last(String s, String sep) {
        return TextUtil.afterLast(s, sep);
    }

    static String or(String a, String b) {
        return TextUtil.or(a, b);
    }

    static boolean startsWithAny(String s, List<String> prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    // ---- rebuilt string values -------------------------------------------------------------

    /**
     * A string value rebuilt from code. {@code text} includes every fragment (optional ones too);
     * {@code minimal} leaves out fragments added only on some code paths.
     */
    static final class Expr {
        TsNode node;
        String text;
        boolean dynamic;
        int line;
        String source;
        boolean hql;
        String minimal;
        int valueHoles;
        int structuralHoles;
        int fragments = 1;
        int optional;
        Set<String> how = new LinkedHashSet<>();
        Set<Integer> lines = new LinkedHashSet<>();
        List<String> variants = new ArrayList<>();

        Expr(TsNode node, String text, boolean dynamic, int line) {
            this.node = node;
            this.text = text;
            this.dynamic = dynamic;
            this.line = line;
        }

        String base() {
            return minimal == null ? text : minimal;
        }

        /** dataclasses.replace: a shallow copy. */
        Expr copy() {
            Expr e = new Expr(node, text, dynamic, line);
            e.source = source;
            e.hql = hql;
            e.minimal = minimal;
            e.valueHoles = valueHoles;
            e.structuralHoles = structuralHoles;
            e.fragments = fragments;
            e.optional = optional;
            e.how = how;
            e.lines = lines;
            e.variants = variants;
            return e;
        }

        long span() {
            return ((long) node.startByte << 32) | (node.endByte & 0xffffffffL);
        }
    }

    static Expr lit(TsNode node, String text, int line) {
        Expr e = new Expr(node, text, false, line);
        e.lines.add(line);
        return e;
    }

    static Expr frag(TsNode node, String text, int line) {
        Expr e = new Expr(node, text, false, line);
        e.fragments = 0;
        return e;
    }

    static Expr hole(TsNode node, String before) {
        String kind = SqlTools.holeKind(before);
        int line = node.line();
        Expr e = new Expr(node, "?", true, line);
        e.valueHoles = kind.equals("value") ? 1 : 0;
        e.structuralHoles = kind.equals("structural") ? 1 : 0;
        e.fragments = 0;
        e.lines.add(line);
        return e;
    }

    static Expr concat(Expr a, Expr b, TsNode node) {
        return concat(a, b, node, false);
    }

    static Expr concat(Expr a, Expr b, TsNode node, boolean optional) {
        Expr out = new Expr(node, a.text + b.text, a.dynamic || b.dynamic || optional, a.line);
        out.source = a.source != null ? a.source : b.source;
        out.hql = a.hql || b.hql;
        out.minimal = a.base() + (optional ? "" : b.base());
        out.valueHoles = a.valueHoles + b.valueHoles;
        out.structuralHoles = a.structuralHoles + b.structuralHoles;
        out.fragments = a.fragments + b.fragments;
        out.optional = a.optional + b.optional + (optional ? b.fragments : 0);
        out.how = new LinkedHashSet<>(a.how);
        out.how.addAll(b.how);
        if (optional) {
            out.how.add("conditional");
        }
        out.lines = new LinkedHashSet<>(a.lines);
        out.lines.addAll(b.lines);
        List<String> v = new ArrayList<>();
        for (String x : a.variants) {
            v.add(x + b.text);
        }
        for (String x : b.variants) {
            v.add(a.text + x);
        }
        out.variants = head(v);
        return out;
    }

    static List<String> head(List<String> v) {
        return new ArrayList<>(v.subList(0, Math.min(MAX_VARIANTS, v.size())));
    }

    /** A class declared in the file. */
    static final class ClassInfo {
        final String name;
        final String fqn;
        final int line;
        Map<String, Map<String, String>> annotations = new LinkedHashMap<>();
        final List<String> supertypes = new ArrayList<>();
        final boolean isInterface;

        ClassInfo(String name, String fqn, int line, boolean isInterface) {
            this.name = name;
            this.fqn = fqn;
            this.line = line;
            this.isInterface = isInterface;
        }

        Set<String> superNames() {
            Set<String> out = new HashSet<>();
            for (String s : supertypes) {
                out.add(last(s, "."));
            }
            return out;
        }
    }

    static String unquote(String raw) {
        if (raw.startsWith("\"\"\"")) {
            return raw.length() >= 6 ? raw.substring(3, raw.length() - 3) : "";
        }
        String body = raw.length() >= 2 ? raw.substring(1, raw.length() - 1) : "";
        return body.replace("\\\"", "\"").replace("\\n", " ").replace("\\t", " ").replace("\\\\", "\\");
    }

    static String soapFramework(List<String> imports) {
        String[][] known = {{"org.apache.axis2", "Apache Axis2"}, {"org.apache.axis", "Apache Axis"}, {"org.apache.cxf", "Apache CXF"},
            {"org.springframework.ws", "Spring-WS"}};
        for (String[] k : known) {
            for (String i : imports) {
                if (i.startsWith(k[0])) {
                    return k[1];
                }
            }
        }
        return "JAX-WS client";
    }

    static String joinPath(String... parts) {
        List<String> kept = new ArrayList<>();
        for (String p : parts) {
            String s = TextUtil.strip(p, "/");
            if (!s.isEmpty()) {
                kept.add(s);
            }
        }
        return "/" + String.join("/", kept);
    }

    private static Map<String, String> get(Map<String, Map<String, String>> ann, String key) {
        return ann.getOrDefault(key, Map.of());
    }

    // ---- the visitor -----------------------------------------------------------------------

    /** A SOAP call whose address and operations are set on separate lines. */
    private static final class SoapCall {
        String url;
        final List<Map.Entry<String, Integer>> ops = new ArrayList<>();
        String name;
        final int line;
        final String fw;
        boolean use;

        SoapCall(String url, String name, int line, String fw) {
            this.url = url;
            this.name = name;
            this.line = line;
            this.fw = fw;
        }
    }

    private static final class Visitor {
        final FileRecord file;
        final String path;
        final AnalyzerContext ctx;
        final Facts facts;
        String pkg = "";
        final List<String> imports = new ArrayList<>();
        final List<ClassInfo> classes = new ArrayList<>();
        final Map<String, Expr> constants = new HashMap<>();
        final Map<Integer, Expr> exprs = new LinkedHashMap<>();
        Map<String, Integer> varKeys = new HashMap<>();
        final Set<Long> usedAsFragment = new HashSet<>();
        final Set<String> typeNames = new HashSet<>();
        final String dialect;
        ClassInfo curClass;
        String wsBase;
        final List<SoapCall> wsSoap = new ArrayList<>();
        final Set<Integer> qnameNs = new HashSet<>();

        Visitor(FileRecord file, AnalyzerContext ctx, Facts facts) {
            this.file = file;
            this.path = file.path;
            this.ctx = ctx;
            this.facts = facts;
            this.dialect = ctx.config().sqlDialect();
        }

        // ---- driving ------------------------------------------------------------------------

        void typeReferences(TsNode root) {
            Deque<TsNode> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                TsNode n = stack.pop();
                if (n.type.equals("type_identifier")) {
                    typeNames.add(n.text());
                } else if (n.type.equals("method_invocation") || n.type.equals("field_access")) {
                    TsNode obj = n.field("object");
                    if (obj != null && obj.type.equals("identifier") && firstUpper(obj.text())) {
                        typeNames.add(obj.text());
                    }
                }
                for (TsNode c : n.children) {
                    stack.push(c);
                }
            }
        }

        void run(TsNode root) {
            for (TsNode child : root.children) {
                if (child.type.equals("package_declaration")) {
                    pkg = child.namedChildren().isEmpty() ? "" : child.namedChildren().getFirst().text();
                } else if (child.type.equals("import_declaration")) {
                    imports.add(TextUtil.strip(child.text().replaceFirst("import", ""), " ;"));
                }
            }
            for (TsNode child : root.children) {
                if (CLASS_TYPES.contains(child.type)) {
                    klass(child, pkg);
                }
            }
            typeReferences(root);
            importsAndSignals();
            emitSql();
            wsFinish();
            classRefs();
        }

        // ---- classes ------------------------------------------------------------------------

        static TsNode modifiers(TsNode node) {
            for (TsNode c : node.children) {
                if (c.type.equals("modifiers")) {
                    return c;
                }
            }
            return null;
        }

        void klass(TsNode node, String prefix) {
            String name = txt(node.field("name"));
            String fqn = !prefix.isEmpty() ? prefix + "." + name : name;
            ClassInfo info = new ClassInfo(name, fqn, node.line(), node.type.equals("interface_declaration"));
            info.annotations = annotations(modifiers(node));
            for (String fld : List.of("superclass", "interfaces")) {
                TsNode sup = node.field(fld);
                if (sup != null) {
                    for (TsNode t : typeIds(sup)) {
                        info.supertypes.add(TextUtil.beforeFirst(t.text(), "<"));
                    }
                }
            }
            for (TsNode c : node.children) {
                if (c.type.equals("extends_interfaces")) {
                    for (TsNode t : typeIds(c)) {
                        info.supertypes.add(TextUtil.beforeFirst(t.text(), "<"));
                    }
                }
            }
            classes.add(info);
            facts.symbol("java-class", fqn, path, info.line);
            classLevel(info);
            desktop(info, node);
            TsNode body = node.field("body");
            if (body == null) {
                return;
            }
            // Fields first: constants are referenced by methods that may come earlier.
            for (TsNode member : body.children) {
                if (member.type.equals("field_declaration") || member.type.equals("constant_declaration")) {
                    field(member);
                }
            }
            for (TsNode member : body.children) {
                if (member.type.equals("method_declaration") || member.type.equals("constructor_declaration")) {
                    method(member, info);
                } else if (NESTED_TYPES.contains(member.type)) {
                    klass(member, fqn);
                } else if (!member.type.equals("field_declaration") && !member.type.equals("constant_declaration")) {
                    scan(member, null);
                }
            }
        }

        List<TsNode> typeIds(TsNode node) {
            List<TsNode> out = new ArrayList<>();
            Deque<TsNode> stack = new ArrayDeque<>();
            stack.push(node);
            while (!stack.isEmpty()) {
                TsNode n = stack.pop();
                if (n.type.equals("type_identifier") || n.type.equals("scoped_type_identifier")) {
                    out.add(n);
                    continue;
                }
                for (int i = n.children.size() - 1; i >= 0; i--) {
                    stack.push(n.children.get(i)); // the first child is taken first
                }
            }
            return out;
        }

        Map<String, Map<String, String>> annotations(TsNode mods) {
            Map<String, Map<String, String>> out = new LinkedHashMap<>();
            if (mods == null) {
                return out;
            }
            for (TsNode a : mods.children) {
                if (!a.type.equals("annotation") && !a.type.equals("marker_annotation")) {
                    continue;
                }
                String aname = last(txt(a.field("name")), ".");
                Map<String, String> args = new LinkedHashMap<>();
                TsNode arglist = a.field("arguments");
                if (arglist != null) {
                    for (TsNode arg : arglist.namedChildren()) {
                        if (arg.type.equals("element_value_pair")) {
                            args.put(txt(arg.field("key")), annotationValue(arg.field("value")));
                        } else {
                            args.put("value", annotationValue(arg));
                        }
                    }
                }
                out.put(aname, args);
            }
            return out;
        }

        String annotationValue(TsNode node) {
            if (node == null) {
                return "";
            }
            if (node.type.equals("element_value_array_initializer")) {
                List<String> vals = new ArrayList<>();
                for (TsNode c : node.namedChildren()) {
                    vals.add(annotationValue(c));
                }
                return vals.isEmpty() ? "" : vals.getFirst();
            }
            Expr e = eval(node, null);
            return e != null ? e.text : node.text();
        }

        void classLevel(ClassInfo info) {
            var ann = info.annotations;
            int line = info.line;
            if (ann.containsKey("SpringBootApplication")) {
                entry(info.fqn, "spring-boot-app", line, null);
                facts.add("tech_stack", tech("Spring Boot", null, "framework", path, line));
            }
            if (ann.containsKey("Entity")) {
                String table = or(get(ann, "Table").get("name"), info.name);
                facts.symbol("jpa-entity", info.name, path, line).data("table", table.toUpperCase(Locale.ROOT)).data("fqn", info.fqn);
            }
            Set<String> sup = info.superNames();
            if ((sup.contains("Job") || sup.contains("StatefulJob")) && imports.stream().anyMatch(i -> i.contains("quartz"))) {
                entry(info.fqn, "scheduler", line, "Quartz job");
            }
            if (sup.contains("TimerTask")) {
                entry(info.fqn, "scheduler", line, "java.util.TimerTask");
            }
            if (sup.contains("MessageListener") || ann.containsKey("MessageDriven")) {
                entry(info.fqn, "message-listener", line, null);
            }
            if (info.isInterface && sup.stream().anyMatch(SPRING_DATA_BASES::contains)) {
                facts.symbol("spring-data-repository", info.fqn, path, line);
            }
            if (sup.contains("HttpServlet") || sup.contains("GenericServlet")) {
                facts.symbol("servlet-class", info.fqn, path, line);
            }
        }

        /** Swing, AWT and JavaFX windows as screens; the windows they open as navigation. */
        void desktop(ClassInfo info, TsNode node) {
            Set<String> sup = info.superNames();
            boolean swing = imports.stream().anyMatch(i -> i.startsWith("javax.swing"));
            boolean javafx = imports.stream().anyMatch(i -> i.startsWith("javafx."));
            boolean awt = imports.stream().anyMatch(i -> i.startsWith("java.awt"));
            if (sup.contains("Application") && javafx) {
                entry(info.fqn, "desktop-app", info.line, "JavaFX Application");
                facts.add("tech_stack", tech("JavaFX", null, "framework", path, info.line));
                return;
            }
            Set<String> windows = new HashSet<>(SWING_WINDOWS);
            if (awt || !swing) {
                windows.addAll(AWT_WINDOWS);
            }
            Set<String> panels = new HashSet<>(SWING_PANELS);
            if (awt) {
                panels.addAll(AWT_PANELS);
            }
            boolean any = sup.stream().anyMatch(s -> windows.contains(s) || panels.contains(s));
            if (!any) {
                return;
            }
            String technology = sup.stream().anyMatch(s -> SWING_WINDOWS.contains(s) || SWING_PANELS.contains(s)) ? "swing" : "awt";
            int fields = 0;
            Set<String> targets = new TreeSet<>(SqlTools.PyJson_ORDER);
            Deque<TsNode> stack = new ArrayDeque<>();
            TsNode body = node.field("body");
            if (body != null) {
                stack.push(body);
            }
            while (!stack.isEmpty()) {
                TsNode n = stack.pop();
                if (n.type.equals("object_creation_expression")) {
                    String t = last(TextUtil.beforeFirst(txt(n.field("type")), "<"), ".");
                    if (DESKTOP_INPUTS.matcher(t).lookingAt()) {
                        fields++;
                    } else if (firstUpper(t) && !t.equals(info.name) && !t.startsWith("J") && !t.startsWith("Grid") && !t.startsWith("Border")
                            && !t.startsWith("Flow")) {
                        targets.add(t);
                    }
                }
                if (n.type.equals("class_declaration")) {
                    continue;
                }
                for (TsNode c : n.children) {
                    stack.push(c);
                }
            }
            String sid = Fact.makeId("screen", "java-desktop", info.fqn);
            TreeSet<String> sorted = new TreeSet<>(SqlTools.PyJson_ORDER);
            sorted.addAll(sup);
            boolean window = sup.stream().anyMatch(windows::contains);
            facts.add("screens", Doc.of(Schemas.SCREEN,
                    "id", sid,
                    "name", info.name,
                    "technology", technology,
                    "file", path,
                    "fields_count", fields,
                    "partial", !window,
                    "evidence", new ArrayList<>(List.of(new Evidence(path, info.line, info.name + " extends " + String.join(", ", sorted))))));
            facts.symbol("desktop-form", info.name, path, info.line).owner(sid);
            facts.add("tech_stack", tech(technology.equals("swing") ? "Java Swing" : "Java AWT", null, "framework", path, info.line));
            for (String t : targets) {
                facts.ref("forwards", "screen:" + sid, info.name, t, List.of("desktop-form"), path, info.line).quiet();
            }
        }

        void entry(String name, String kind, int line, String detail) {
            facts.add("entry_points", Doc.of(Schemas.ENTRY_POINT,
                    "id", Fact.makeId("entry", path, name, kind),
                    "name", name,
                    "kind", kind,
                    "file", path,
                    "line", line,
                    "evidence", new ArrayList<>(List.of(new Evidence(path, line, detail)))));
        }

        // ---- members ------------------------------------------------------------------------

        void field(TsNode node) {
            TsNode mods = modifiers(node);
            boolean isStatic = mods != null && mods.text().contains("static");
            for (TsNode decl : node.children) {
                if (!decl.type.equals("variable_declarator")) {
                    continue;
                }
                TsNode value = decl.field("value");
                String name = txt(decl.field("name"));
                if (value != null) {
                    Expr e = topExpr(value, null);
                    if (e != null) {
                        constants.put(name, e);
                    } else {
                        scan(value, null);
                    }
                }
                if (isStatic) {
                    typeNames.add(name);
                }
            }
        }

        void method(TsNode node, ClassInfo cls) {
            String name = txt(node.field("name"));
            int line = node.line();
            TsNode mods = modifiers(node);
            var ann = annotations(mods);
            String modsText = mods != null ? mods.text() : "";
            String params = txt(node.field("parameters"));
            if (name.equals("main") && modsText.contains("static") && params.contains("String")) {
                // A main() that opens a window starts a desktop application, not a batch program.
                boolean ui = UI_MAIN.matcher(txt(node.field("body"))).find();
                entry(cls.fqn, ui ? "desktop-app" : "main", line, null);
                facts.symbol("java-main", cls.fqn, path, line);
            }
            if (ann.containsKey("Scheduled")) {
                entry(cls.fqn + "." + name, "scheduler", line, "@Scheduled");
            }
            services(cls, name, line, ann, modsText, node);
            for (String k : List.of("Query", "NamedQuery", "NamedNativeQuery")) {
                String q = or(get(ann, k).get("value"), get(ann, k).get("query"));
                if (TextUtil.truthy(q)) {
                    boolean nativeQ = "true".equals(ann.get(k).get("nativeQuery")) || k.equals("NamedNativeQuery");
                    sql(q, line, nativeQ ? "native" : "jpql", false, !nativeQ, null);
                }
            }
            Set<String> bases = cls.superNames();
            boolean derived = DERIVED.matcher(name).lookingAt();
            if (cls.isInterface && bases.stream().anyMatch(SPRING_DATA_BASES::contains) && !ann.containsKey("Query") && derived) {
                derivedQuery(name, line);
            }
            TsNode body = node.field("body");
            if (body != null) {
                Map<String, Expr> local = new HashMap<>();
                varKeys = new HashMap<>();
                curClass = cls;
                scan(body, local);
            }
        }

        void services(ClassInfo cls, String name, int line, Map<String, Map<String, String>> ann, String modsText, TsNode node) {
            var ca = cls.annotations;
            boolean pub = modsText.contains("public") || cls.isInterface;
            String handler = cls.fqn + "." + name;
            // Web service clients declared as interfaces (Feign, MicroProfile, Retrofit)
            if (clientInterface(cls, line, ann, handler)) {
                return;
            }
            // Spring MVC (a REST operation when it returns data, not a view)
            if (ca.containsKey("Controller") || ca.containsKey("RestController")) {
                String rtype = txt(node.field("type"));
                boolean rest = ca.containsKey("RestController") || ca.containsKey("ResponseBody") || ann.containsKey("ResponseBody")
                        || rtype.startsWith("ResponseEntity") || rtype.startsWith("HttpEntity") || rtype.startsWith("Mono")
                        || rtype.startsWith("Flux");
                for (var e : MAPPING_ANNOTATIONS.entrySet()) {
                    String a = e.getKey();
                    if (ann.containsKey(a)) {
                        String sub = or(ann.get(a).get("value"), ann.get(a).getOrDefault("path", ""));
                        String method = or(or(e.getValue(), last(ann.get(a).getOrDefault("method", ""), ".")), "ANY");
                        String url = joinPath(basePath(ca), sub);
                        service(method + " " + url, "spring-handler", handler, url, line, rest ? "REST" : null, rest ? "Spring MVC" : null,
                                rest ? cls.name : null);
                        if (!rest) {
                            views(node, "service:" + handler);
                        }
                    }
                }
            }
            // JAX-RS
            if (ca.containsKey("Path") || ann.containsKey("Path")) {
                TreeSet<String> verbs = new TreeSet<>(SqlTools.PyJson_ORDER);
                for (String k : ann.keySet()) {
                    if (JAXRS_METHODS.contains(k)) {
                        verbs.add(k);
                    }
                }
                if (!verbs.isEmpty()) {
                    String url = joinPath(get(ca, "Path").getOrDefault("value", ""), get(ann, "Path").getOrDefault("value", ""));
                    service(verbs.first() + " " + url, "rest-operation", handler, url, line, "REST", "JAX-RS", cls.name);
                }
            }
            // JAX-WS
            if (ca.containsKey("WebService") && pub && !name.equals(cls.name)
                    && (ann.containsKey("WebMethod") || ca.keySet().stream().noneMatch(x -> x.contains("WebMethod")))
                    && !"true".equals(get(ann, "WebMethod").get("exclude"))) {
                var ws = ca.get("WebService");
                service(cls.name + "." + name, "soap-operation", handler, null, line, "SOAP", "JAX-WS",
                        or(or(ws.get("serviceName"), ws.get("name")), cls.name));
            }
            // Spring Web Services endpoints
            if (ca.containsKey("Endpoint")
                    && (ann.containsKey("PayloadRoot") || ann.containsKey("SoapAction") || ann.containsKey("Action"))
                    && imports.stream().anyMatch(i -> i.startsWith("org.springframework.ws"))) {
                String op = or(or(get(ann, "PayloadRoot").get("localPart"), last(get(ann, "SoapAction").getOrDefault("value", ""), "/")), name);
                service(cls.name + "." + op, "soap-operation", handler, null, line, "SOAP", "Spring-WS", cls.name);
            }
            // Apache Axis instant deployment (.jws): every public method of the class
            if (path.toLowerCase(Locale.ROOT).endsWith(".jws") && modsText.contains("public") && !modsText.contains("static")
                    && !name.equals(cls.name) && cls == classes.getFirst()) {
                String stem = TextUtil.beforeLast(last(path, "/"), ".");
                service(stem + "." + name, "soap-operation", handler, "/" + stem + ".jws", line, "SOAP", "Apache Axis (JWS)", stem);
            }
            // EJB (remote business methods)
            Set<String> sup = cls.superNames();
            if (pub && !EJB_LIFECYCLE.contains(name) && !name.equals(cls.name) && (ca.containsKey("Remote") || sup.contains("SessionBean"))
                    && !cls.isInterface) {
                service(cls.name + "." + name, "ejb-method", handler, null, line, null, null, null);
            }
            // Struts 2 annotations
            if (ann.containsKey("Action") && imports.stream().anyMatch(i -> i.contains("struts2"))) {
                String url = ann.get("Action").getOrDefault("value", name);
                service(url, "struts-action", handler, url, line, null, null, null);
            }
        }

        static String basePath(Map<String, Map<String, String>> ca) {
            var rm = get(ca, "RequestMapping");
            return or(rm.get("value"), rm.getOrDefault("path", ""));
        }

        /**
         * A method of a declarative REST client (Feign, MicroProfile REST Client, Retrofit): an
         * operation the application calls, never one it offers. True when it was one.
         */
        boolean clientInterface(ClassInfo cls, int line, Map<String, Map<String, String>> ann, String handler) {
            var ca = cls.annotations;
            if (ca.containsKey("FeignClient")) {
                var fc = ca.get("FeignClient");
                String client = or(or(fc.get("name"), fc.get("value")), cls.name);
                for (var e : MAPPING_ANNOTATIONS.entrySet()) {
                    String a = e.getKey();
                    if (ann.containsKey(a)) {
                        String sub = or(ann.get(a).get("value"), ann.get(a).getOrDefault("path", ""));
                        String method = or(or(e.getValue(), last(ann.get(a).getOrDefault("method", ""), ".")), "ANY");
                        String p = joinPath(fc.getOrDefault("path", ""), basePath(ca), sub);
                        wsCalled("REST", client, "Feign", line, fc.getOrDefault("url", "") + p, method, null, handler, null, null);
                    }
                }
                return true;
            }
            if (ca.containsKey("RegisterRestClient")) {
                var rc = ca.get("RegisterRestClient");
                TreeSet<String> verbs = new TreeSet<>(SqlTools.PyJson_ORDER);
                for (String k : ann.keySet()) {
                    if (JAXRS_METHODS.contains(k)) {
                        verbs.add(k);
                    }
                }
                if (!verbs.isEmpty()) {
                    String p = joinPath(get(ca, "Path").getOrDefault("value", ""), get(ann, "Path").getOrDefault("value", ""));
                    wsCalled("REST", or(rc.get("configKey"), cls.name), "MicroProfile REST Client", line, rc.getOrDefault("baseUri", "") + p,
                            verbs.first(), null, handler, null, null);
                }
                return true;
            }
            if (cls.isInterface && imports.stream().anyMatch(i -> i.startsWith("retrofit2"))) {
                TreeSet<String> verbs = new TreeSet<>(SqlTools.PyJson_ORDER);
                for (String k : ann.keySet()) {
                    if (JAXRS_METHODS.contains(k)) {
                        verbs.add(k);
                    }
                }
                for (String verb : verbs) {
                    wsCalled("REST", cls.name, "Retrofit", line, ann.get(verb).getOrDefault("value", ""), verb, null, handler, null, null);
                }
                return true;
            }
            return false;
        }

        void service(String name, String kind, String handler, String url, int line, String protocol, String framework, String webService) {
            String sid = Fact.makeId("service", kind, path, name);
            facts.add("services", Doc.of(Schemas.SERVICE,
                    "id", sid,
                    "name", name,
                    "kind", kind,
                    "file", path,
                    "handler", handler,
                    "url", url,
                    "protocol", protocol,
                    "framework", framework,
                    "web_service", webService,
                    "evidence", new ArrayList<>(List.of(new Evidence(path, line, null)))));
            facts.symbol("service", handler, path, line).owner(sid);
        }

        /** View names returned by a Spring handler: {@code return "x"} / {@code new ModelAndView("x")}. */
        void views(TsNode method, String source) {
            Deque<TsNode> stack = new ArrayDeque<>();
            stack.push(method);
            while (!stack.isEmpty()) {
                TsNode n = stack.pop();
                String view = null;
                if (n.type.equals("return_statement")) {
                    TsNode e = n.namedChildren().isEmpty() ? null : n.namedChildren().getFirst();
                    if (e != null && e.type.equals("string_literal")) {
                        view = unquote(e.text());
                    }
                } else if (n.type.equals("object_creation_expression") && txt(n.field("type")).endsWith("ModelAndView")) {
                    TsNode args = n.field("arguments");
                    TsNode first = args != null && !args.namedChildren().isEmpty() ? args.namedChildren().getFirst() : null;
                    if (first != null && first.type.equals("string_literal")) {
                        view = unquote(first.text());
                    }
                }
                if (TextUtil.truthy(view) && !view.startsWith("redirect:") && !view.startsWith("forward:")) {
                    facts.ref("renders", source, TextUtil.afterFirst(source, ":"), view, List.of("view"), path, n.line());
                }
                for (TsNode c : n.children) {
                    stack.push(c);
                }
            }
        }

        void derivedQuery(String name, int line) {
            String verb = name.startsWith("delete") ? "DELETE" : "SELECT";
            Matcher m = REPOSITORY.matcher(ctx.textOrEmpty());
            String entity = m.find() ? m.group(1) : "";
            Doc fact = SqlTools.sqlFact(path, line, verb + " /* derived: " + name + " */ FROM " + entity, "spring-data", null, false, true);
            fact.set("type", verb);
            fact.set("parsed", false);
            fact.set("reason", "Spring Data derived query: SQL is generated at runtime");
            facts.add("sql_statements", fact);
        }

        // ---- expressions --------------------------------------------------------------------

        static Expr lookup(Map<String, Expr> local, String name) {
            return local == null ? null : local.get(name);
        }

        /**
         * Evaluates a string expression: literals, concatenation, constants and (in a method)
         * local variables, StringBuilders, String.format, ternaries. Unknown parts become holes;
         * null when the expression is not text at all.
         */
        Expr eval(TsNode node, Map<String, Expr> local) {
            String t = node.type;
            int line = node.line();
            if (t.equals("string_literal") || t.equals("text_block") || t.equals("character_literal")) {
                return lit(node, unquote(node.text()), line);
            }
            if (t.equals("parenthesized_expression") && !node.namedChildren().isEmpty()) {
                return eval(node.namedChildren().getFirst(), local);
            }
            if (t.equals("binary_expression") && txt(node.field("operator")).equals("+")) {
                TsNode left = node.field("left");
                TsNode right = node.field("right");
                Expr le = left != null ? eval(left, local) : null;
                Expr re = right != null ? eval(right, local) : null;
                if (le == null && re == null) {
                    return null;
                }
                for (Expr part : new Expr[] {le, re}) {
                    if (part != null) {
                        usedAsFragment.add(part.span());
                    }
                }
                if (le == null) {
                    le = hole(left != null ? left : node, "");
                }
                if (re == null) {
                    re = hole(right != null ? right : node, le.text);
                }
                Expr out = concat(le, re, node);
                if (le.dynamic || re.dynamic) {
                    out.how.add("concatenation");
                }
                return out;
            }
            if (t.equals("identifier")) {
                String name = node.text();
                Expr l = lookup(local, name);
                return l != null ? l : constants.get(name);
            }
            if (t.equals("ternary_expression")) {
                TsNode a = node.field("consequence");
                TsNode b = node.field("alternative");
                Expr ea = a != null ? eval(a, local) : null;
                Expr eb = b != null ? eval(b, local) : null;
                if (ea == null || eb == null) {
                    return null;
                }
                Expr out = concat(frag(node, "", line), ea, node);
                out.dynamic = true;
                out.how.add("ternary");
                List<String> v = new ArrayList<>();
                v.add(eb.text);
                v.addAll(ea.variants);
                v.addAll(eb.variants);
                out.variants = head(v);
                out.fragments = ea.fragments + eb.fragments;
                out.lines.addAll(eb.lines);
                return out;
            }
            if (t.equals("object_creation_expression")) {
                String typ = last(TextUtil.beforeFirst(txt(node.field("type")), "<"), ".");
                if (BUILDER_TYPES.contains(typ)) {
                    TsNode args = node.field("arguments");
                    List<TsNode> argv = args != null ? args.namedChildren() : List.of();
                    Expr first = !argv.isEmpty() && !typ.equals("StringJoiner") ? eval(argv.getFirst(), local) : null;
                    if (first == null) {
                        Expr e = frag(node, "", line);
                        e.how.add("string-builder");
                        return e;
                    }
                    usedAsFragment.add(first.span());
                    Expr out = first.copy();
                    out.node = node;
                    out.line = line;
                    out.how = new LinkedHashSet<>(first.how);
                    out.how.add("string-builder");
                    out.lines = new LinkedHashSet<>(first.lines);
                    out.lines.add(line);
                    return out;
                }
                return null;
            }
            if (t.equals("method_invocation")) {
                return evalCall(node, local);
            }
            return null;
        }

        Expr evalCall(TsNode node, Map<String, Expr> local) {
            String name = txt(node.field("name"));
            TsNode obj = node.field("object");
            TsNode args = node.field("arguments");
            List<TsNode> argv = args != null ? args.namedChildren() : List.of();
            if (Set.of("toString", "trim", "strip", "intern").contains(name) && obj != null && argv.isEmpty()) {
                return eval(obj, local);
            }
            if (name.equals("valueOf") && txt(obj).equals("String") && argv.size() == 1) {
                return eval(argv.getFirst(), local);
            }
            if (name.equals("concat") && obj != null && argv.size() == 1) {
                Expr a = eval(obj, local);
                if (a == null) {
                    return null;
                }
                Expr b = eval(argv.getFirst(), local);
                if (b == null) {
                    b = hole(argv.getFirst(), a.text);
                }
                Expr out = concat(a, b, node);
                out.how.add("concatenation");
                return out;
            }
            TsNode fmtNode = null;
            List<TsNode> values = List.of();
            if (name.equals("format") && txt(obj).equals("String") && !argv.isEmpty()) {
                fmtNode = argv.getFirst();
                values = argv.subList(1, argv.size());
            } else if (name.equals("formatted") && obj != null) {
                fmtNode = obj;
                values = argv;
            }
            if (fmtNode != null) {
                Expr fmt = eval(fmtNode, local);
                if (fmt == null) {
                    return null;
                }
                return format(node, fmt, values, local);
            }
            return null;
        }

        Expr format(TsNode node, Expr fmt, List<TsNode> values, Map<String, Expr> local) {
            Expr out = new Expr(node, "", fmt.dynamic, fmt.line);
            out.fragments = 0;
            out.lines = new LinkedHashSet<>(fmt.lines);
            int pos = 0;
            int k = 0;
            Matcher m = FORMAT_SPEC.matcher(fmt.text);
            while (m.find()) {
                out = concat(out, frag(node, fmt.text.substring(pos, m.start()), fmt.line), node);
                if (m.group(1).equals("%")) {
                    out = concat(out, frag(node, "%", fmt.line), node);
                } else if (m.group(1).equals("n")) {
                    out = concat(out, frag(node, " ", fmt.line), node);
                } else {
                    Expr v = k < values.size() ? eval(values.get(k), local) : null;
                    out = concat(out, v != null ? v : hole(k < values.size() ? values.get(k) : node, out.text), node);
                    k++;
                }
                pos = m.end();
            }
            out = concat(out, frag(node, fmt.text.substring(pos), fmt.line), node);
            out.fragments = Math.max(out.fragments, 1);
            out.how.add("string-format");
            return out;
        }

        Expr topExpr(TsNode node, Map<String, Expr> local) {
            Expr e = eval(node, local);
            if (e == null || TextUtil.strip(e.text).isEmpty()) {
                return null;
            }
            if (local != null && !local.isEmpty()) {
                for (Expr v : local.values()) {
                    if (v == e) {
                        return e; // a tracked variable: already registered where it was declared
                    }
                }
            }
            exprs.put(node.startByte, e);
            return e;
        }

        /**
         * Remembers a local string/builder variable; its latest value replaces the earlier one in
         * the list of expressions (keyed where the variable got its first value).
         */
        void track(Map<String, Expr> local, String name, Expr e, Integer key) {
            local.put(name, e);
            if (key == null) {
                key = varKeys.getOrDefault(name, e.node.startByte);
            }
            varKeys.put(name, key);
            if (!TextUtil.strip(e.text).isEmpty()) {
                exprs.put(key, e);
            } else {
                exprs.remove(key);
            }
        }

        void update(Map<String, Expr> local, String name, Expr nw, boolean cond, boolean append) {
            Expr prev = local.get(name);
            if (prev == null) {
                track(local, name, nw, nw.node.startByte);
                return;
            }
            Expr out;
            if (append) {
                out = concat(prev, nw, nw.node, cond);
            } else if (!cond) {
                out = nw; // plain reassignment replaces the value
            } else { // reassigned on some paths only: keep both
                out = concat(prev, frag(nw.node, "", prev.line), prev.node);
                out.dynamic = true;
                out.how.add("reassignment");
                List<String> v = new ArrayList<>(prev.variants);
                v.add(nw.text);
                out.variants = head(v);
                out.fragments += nw.fragments;
                out.lines.addAll(nw.lines);
            }
            out.node = prev.node;
            out.line = prev.line;
            out.source = out.source != null ? out.source : prev.source;
            track(local, name, out, null);
        }

        /** String building statements ({@code sql += ...}, {@code sb.append(...)}, {@code s = ...}). True when handled. */
        boolean statement(TsNode n, Map<String, Expr> local, boolean cond) {
            TsNode expr = n.namedChildren().isEmpty() ? null : n.namedChildren().getFirst();
            if (expr == null) {
                return false;
            }
            if (expr.type.equals("assignment_expression")) {
                TsNode left = expr.field("left");
                TsNode right = expr.field("right");
                String op = txt(expr.field("operator"));
                if (left == null || right == null || !left.type.equals("identifier")) {
                    return false;
                }
                String name = left.text();
                Expr prev = local.get(name);
                Expr value = eval(right, local);
                if (value == null) {
                    if (prev == null) {
                        return false;
                    }
                    value = hole(right, prev.text);
                }
                if (op.equals("+=")) {
                    update(local, name, value, cond, true);
                } else if (op.equals("=")) {
                    if (prev != null && value != prev && value.text.startsWith(prev.text) && !prev.text.isEmpty()
                            && value.lines.containsAll(prev.lines)) {
                        // s = s + "..." is an append of the rest
                        Expr delta = new Expr(right, value.text.substring(prev.text.length()), value.dynamic, value.line);
                        delta.valueHoles = value.valueHoles - prev.valueHoles;
                        delta.structuralHoles = value.structuralHoles - prev.structuralHoles;
                        delta.fragments = Math.max(value.fragments - prev.fragments, 1);
                        delta.how = value.how;
                        Set<Integer> rest = new LinkedHashSet<>(value.lines);
                        rest.removeAll(prev.lines);
                        if (rest.isEmpty()) {
                            rest.add(value.line);
                        }
                        delta.lines = rest;
                        update(local, name, delta, cond, true);
                    } else {
                        update(local, name, value, cond, false);
                    }
                } else {
                    return false;
                }
                return true;
            }
            if (expr.type.equals("method_invocation")) {
                List<TsNode> chain = new ArrayList<>();
                TsNode cur = expr;
                while (cur != null && cur.type.equals("method_invocation")) {
                    chain.add(cur);
                    cur = cur.field("object");
                }
                if (cur == null || !cur.type.equals("identifier") || !local.containsKey(cur.text())) {
                    return false;
                }
                for (TsNode c : chain) {
                    String x = txt(c.field("name"));
                    if (!x.equals("append") && !x.equals("add") && !x.equals("setLength")) {
                        return false;
                    }
                }
                String name = cur.text();
                for (int i = chain.size() - 1; i >= 0; i--) { // innermost first
                    TsNode call = chain.get(i);
                    String cname = txt(call.field("name"));
                    TsNode args = call.field("arguments");
                    List<TsNode> argv = args != null ? args.namedChildren() : List.of();
                    if (cname.equals("setLength")) {
                        if (!cond) {
                            Expr empty = frag(call, "", call.line());
                            empty.how = new LinkedHashSet<>(List.of("string-builder"));
                            update(local, name, empty, false, false);
                        }
                        continue;
                    }
                    if (argv.isEmpty()) {
                        continue;
                    }
                    Expr v = eval(argv.getFirst(), local);
                    if (v == null) {
                        v = hole(argv.getFirst(), local.get(name).text);
                    }
                    Set<String> how = new LinkedHashSet<>(v.how);
                    how.add("string-builder");
                    v.how = how;
                    update(local, name, v, cond, true);
                }
                return true;
            }
            return false;
        }

        private record Item(TsNode node, boolean cond) {}

        /**
         * Walks a method body in source order: string expressions and the variables that build
         * SQL text, SQL call sites, endpoints. Code inside if/loop/switch/catch is conditional.
         */
        void scan(TsNode node, Map<String, Expr> local) {
            Deque<Item> stack = new ArrayDeque<>();
            stack.push(new Item(node, false));
            while (!stack.isEmpty()) {
                Item it = stack.pop();
                TsNode n = it.node();
                boolean cond = it.cond();
                String t = n.type;
                if (t.equals("expression_statement") && local != null && statement(n, local, cond)) {
                    for (TsNode c : n.namedChildren()) { // calls on the line (e.g. executeQuery) still count
                        callsIn(c, local);
                    }
                    continue;
                }
                if ((t.equals("string_literal") || t.equals("binary_expression")) && !exprs.containsKey(n.startByte)) {
                    Expr e = topExpr(n, local);
                    if (e != null) {
                        continue;
                    }
                }
                if (t.equals("local_variable_declaration") && local != null) {
                    List<Item> later = new ArrayList<>();
                    for (TsNode decl : n.children) {
                        if (decl.type.equals("variable_declarator")) {
                            TsNode v = decl.field("value");
                            if (v != null) {
                                Expr e = eval(v, local);
                                if (e != null) {
                                    track(local, txt(decl.field("name")), e, v.startByte);
                                    callsIn(v, local);
                                    continue;
                                }
                                later.add(new Item(v, cond));
                            }
                        }
                    }
                    for (Item x : later) {
                        stack.push(x);
                    }
                    continue;
                }
                if (t.equals("method_invocation")) {
                    call(n, local != null ? local : new HashMap<>());
                } else if (t.equals("object_creation_expression")) {
                    newObject(n);
                } else if (t.equals("type_identifier")) {
                    typeNames.add(n.text());
                }
                boolean inner = cond || COND_STATEMENTS.contains(t);
                TsNode test = t.equals("if_statement") || t.equals("while_statement") ? n.field("condition") : null;
                for (TsNode c : n.children) {
                    // pushed in reverse, so the first child is walked first
                    stack.push(new Item(c, c == test ? cond : inner));
                }
                reverseTop(stack, n.children.size());
            }
        }

        /** The last {@code count} pushes in the order Python's {@code stack.append(reversed(...))} leaves them. */
        private static void reverseTop(Deque<Item> stack, int count) {
            // Python appends reversed(children) to the end of a list and pops from the end, so the
            // first child is popped first. Pushing the children in order onto a deque head puts
            // the last child on top; reverse those entries.
            Item[] top = new Item[count];
            for (int i = 0; i < count; i++) {
                top[i] = stack.pop();
            }
            for (Item x : top) {
                stack.push(x);
            }
        }

        /** SQL call sites and object creations inside an expression that was evaluated. */
        void callsIn(TsNode node, Map<String, Expr> local) {
            Deque<TsNode> stack = new ArrayDeque<>();
            stack.push(node);
            while (!stack.isEmpty()) {
                TsNode n = stack.pop();
                if (n.type.equals("method_invocation")) {
                    call(n, local);
                } else if (n.type.equals("object_creation_expression")) {
                    newObject(n);
                }
                for (TsNode c : n.children) {
                    stack.push(c);
                }
            }
        }

        Expr argExpr(TsNode arg, Map<String, Expr> local) {
            if (arg.type.equals("identifier")) {
                String name = arg.text();
                Expr l = local.get(name);
                return l != null ? l : constants.get(name);
            }
            Expr known = exprs.get(arg.startByte);
            if (known != null) {
                return known;
            }
            return topExpr(arg, local);
        }

        void call(TsNode n, Map<String, Expr> local) {
            String name = txt(n.field("name"));
            if (STRING_METHODS.contains(name)) {
                return; // its arguments are pieces of text, evaluated with the expression
            }
            String obj = txt(n.field("object"));
            TsNode args = n.field("arguments");
            List<TsNode> argv = args != null ? args.namedChildren() : List.of();
            Expr first = !argv.isEmpty() ? argExpr(argv.getFirst(), local) : null;
            if (first != null && first.source == null) {
                if (NATIVE_METHODS.contains(name)) {
                    first.source = "native";
                } else if (HQL_METHODS.contains(name) && (!obj.isEmpty() || name.equals("createQuery"))) {
                    first.source = "hql";
                    first.hql = true;
                } else if (TEMPLATE_METHODS.contains(name) && TEMPLATE_OBJ.matcher(obj).find()) {
                    first.source = "jdbc-template";
                } else if (JDBC_METHODS.containsKey(name)) {
                    first.source = "jdbc";
                }
            }
            int line = n.line();
            wsCall(n, name, obj, argv, local, line);
            if (name.equals("accessQueue") && first != null) {
                List<String> o = new ArrayList<>();
                for (TsNode a : argv.subList(Math.min(1, argv.size()), Math.min(2, argv.size()))) {
                    o.add(a.text());
                }
                String opts = String.join(" ", o);
                String role = opts.contains("OUTPUT") ? "producer" : opts.contains("INPUT") || opts.contains("BROWSE") ? "consumer" : null;
                facts.add("integrations", integration("MQ", first.text, "unknown", path, line, role, "MQ Java", null));
            } else if (name.equals("createQueue") && first != null && NAME_CHARS.matcher(first.text).lookingAt()) {
                facts.add("integrations", integration("MQ", last(first.text, "///"), "unknown", path, line, jmsRole(), "JMS", null));
            } else if (JMS_TEMPLATE_METHODS.containsKey(name) && JMS_OBJ.matcher(obj).find() && first != null && !first.dynamic
                    && NAME_CHARS.matcher(first.text).lookingAt()) {
                facts.add("integrations", integration("MQ", last(first.text, "///"), "unknown", path, line, JMS_TEMPLATE_METHODS.get(name), "JMS",
                        null));
            } else if (name.equals("connect") && first != null && obj.toLowerCase(Locale.ROOT).contains("ftp")) {
                facts.add("integrations", integration("FTP", "ftp://" + first.text, "outbound", path, line));
            }
        }

        // ---- web service clients ------------------------------------------------------------

        void wsCalled(String style, String name, String framework, int line, String url, String method, String op, String handler, String wsdl,
                String namespace) {
            if (TextUtil.truthy(url) && !url.contains("://") && TextUtil.truthy(wsBase) && style.equals("REST")) {
                url = TextUtil.rstrip(wsBase, "/") + "/" + TextUtil.lstrip(url, "/");
            }
            facts.add("web_services", WebSvc.called(style, name, framework, path, line, url, op, method, handler, wsdl, namespace));
        }

        /**
         * The text of a URL argument: a string expression, new URL(x), new URI(x) or URI.create(x);
         * parts only known at run time are "?". Null when not text.
         */
        String urlArg(TsNode node, Map<String, Expr> local) {
            if (node == null) {
                return null;
            }
            if (node.type.equals("object_creation_expression")) {
                String typ = last(txt(node.field("type")), ".");
                TsNode args = node.field("arguments");
                if ((typ.equals("URL") || typ.equals("URI")) && args != null && !args.namedChildren().isEmpty()) {
                    return urlArg(args.namedChildren().getFirst(), local);
                }
                return null;
            }
            if (node.type.equals("method_invocation")) {
                String nm = txt(node.field("name"));
                if (nm.equals("create") || nm.equals("toURI")) {
                    TsNode args = node.field("arguments");
                    if (args != null && !args.namedChildren().isEmpty()) {
                        return urlArg(args.namedChildren().getFirst(), local);
                    }
                }
            }
            Expr e = argExpr(node, local);
            if (e != null) {
                return TextUtil.strip(e.text);
            }
            return node.type.equals("identifier") || node.type.equals("method_invocation") || node.type.equals("field_access") ? "?" : null;
        }

        private record Link(String name, List<TsNode> args) {}

        /** Calls chained after {@code n} ({@code n.request().post(...)}). */
        static List<Link> chainUp(TsNode n) {
            List<Link> out = new ArrayList<>();
            TsNode cur = n;
            while (cur.parent != null && cur.parent.type.equals("method_invocation") && cur.parent.field("object") == cur) {
                cur = cur.parent;
                TsNode args = cur.field("arguments");
                out.add(new Link(txt(cur.field("name")), args != null ? args.namedChildren() : List.of()));
            }
            return out;
        }

        /** Names of the calls {@code n} is chained on. */
        static List<String> chainDown(TsNode n) {
            List<String> out = new ArrayList<>();
            TsNode cur = n.field("object");
            while (cur != null && cur.type.equals("method_invocation")) {
                out.add(txt(cur.field("name")));
                cur = cur.field("object");
            }
            return out;
        }

        static String verb(List<String> names, List<List<TsNode>> argvs) {
            for (int i = 0; i < names.size(); i++) {
                String nm = names.get(i);
                if (REST_VERBS.contains(nm.toUpperCase(Locale.ROOT)) && (pyLower(nm) || pyUpper(nm))) {
                    return nm.toUpperCase(Locale.ROOT);
                }
                if (nm.equals("method") && argvs != null && !argvs.get(i).isEmpty()) {
                    String v = TextUtil.strip(last(argvs.get(i).getFirst().text(), "."), "\"").toUpperCase(Locale.ROOT);
                    if (REST_VERBS.contains(v)) {
                        return v;
                    }
                }
            }
            return null;
        }

        static String verb(List<Link> chain) {
            List<String> names = new ArrayList<>();
            List<List<TsNode>> args = new ArrayList<>();
            for (Link l : chain) {
                names.add(l.name());
                args.add(l.args());
            }
            return verb(names, args);
        }

        String clientName() {
            return curClass != null ? curClass.name : last(path, "/");
        }

        boolean importsStart(String... prefixes) {
            for (String i : imports) {
                for (String p : prefixes) {
                    if (i.startsWith(p)) {
                        return true;
                    }
                }
            }
            return false;
        }

        void wsCall(TsNode n, String name, String obj, List<TsNode> argv, Map<String, Expr> local, int line) {
            TsNode first = argv.isEmpty() ? null : argv.getFirst();
            String handler = curClass != null ? curClass.fqn : null;
            // Spring RestTemplate / RestOperations
            if (REST_TEMPLATE.containsKey(name) && REST_OBJ.matcher(obj).find() && first != null) {
                String verb = REST_TEMPLATE.get(name);
                if (name.equals("exchange") && argv.size() > 1) {
                    verb = last(argv.get(1).text(), ".").toUpperCase(Locale.ROOT);
                }
                String url = urlArg(first, local);
                if (url != null) {
                    wsCalled("REST", clientName(), "RestTemplate", line, url, REST_VERBS.contains(verb) ? verb : null, null, handler, null, null);
                }
                return;
            }
            // Base URL of a client builder (WebClient, RestClient, RestTemplateBuilder, Retrofit)
            if (name.equals("baseUrl") || name.equals("rootUri") || (name.equals("create") && (obj.equals("WebClient") || obj.equals("RestClient")))) {
                String url = urlArg(first, local);
                if (TextUtil.truthy(url) && url.contains("://")) {
                    wsBase = url;
                }
                return;
            }
            // Spring WebClient / RestClient: client.get().uri("/x")
            if (name.equals("uri") && first != null && importsStart("org.springframework.web.reactive", "org.springframework.web.client")) {
                String verb = or(verb(chainDown(n), null), "ANY");
                String url = urlArg(first, local);
                if (url != null) {
                    String fw = imports.stream().anyMatch(i -> i.endsWith("RestClient")) ? "RestClient" : "WebClient";
                    wsCalled("REST", clientName(), fw, line, url, verb, null, handler, null, null);
                }
                return;
            }
            // JAX-RS client: client.target("http://...").path("x").request().get()
            if (name.equals("target") && first != null && importsStart("javax.ws.rs.client", "jakarta.ws.rs.client")) {
                List<Link> chain = chainUp(n);
                String url = or(urlArg(first, local), "?");
                for (Link l : chain) {
                    if (l.name().equals("path") && !l.args().isEmpty()) {
                        url = TextUtil.rstrip(url, "/") + "/" + TextUtil.lstrip(or(urlArg(l.args().getFirst(), local), "?"), "/");
                    }
                }
                String verb = or(verb(chain), "ANY");
                wsCalled("REST", clientName(), "JAX-RS client", line, url, verb, null, handler, null, null);
                return;
            }
            // java.net.http.HttpClient: HttpRequest.newBuilder(URI.create(u)) / .uri(URI.create(u))
            if (((name.equals("newBuilder") && obj.endsWith("HttpRequest")) || name.equals("uri")) && first != null && importsStart("java.net.http")) {
                String url = urlArg(first, local);
                List<Link> chain = chainUp(n);
                if (url != null) {
                    wsCalled("REST", clientName(), "java.net.http.HttpClient", line, url, or(verb(chain), "GET"), null, handler, null, null);
                }
                return;
            }
            // OkHttp: new Request.Builder().url("...").post(body)
            if (name.equals("url") && first != null && imports.stream().anyMatch(i -> i.contains("okhttp"))) {
                String url = urlArg(first, local);
                List<Link> chain = chainUp(n);
                if (url != null) {
                    wsCalled("REST", clientName(), "OkHttp", line, url, or(verb(chain), "GET"), null, handler, null, null);
                }
                return;
            }
            boolean soap = importsStart(SOAP_IMPORTS.toArray(String[]::new));
            // Apache Axis dynamic call, CXF proxy factory, Spring WebServiceTemplate
            if (soap && (name.equals("setTargetEndpointAddress") || name.equals("setAddress") || name.equals("setDefaultUri"))) {
                String url = urlArg(first, local);
                if (TextUtil.truthy(url)) {
                    wsSoap.add(new SoapCall(url, null, line, soapFramework(imports)));
                }
                return;
            }
            if (soap && name.equals("setOperationName") && first != null) {
                String op = null;
                if (first.type.equals("object_creation_expression")) {
                    TsNode args = first.field("arguments");
                    List<TsNode> qa = args != null ? args.namedChildren() : List.of();
                    if (!qa.isEmpty()) {
                        Expr e = argExpr(qa.getLast(), local);
                        op = e != null ? e.text : null;
                    }
                } else {
                    Expr e = argExpr(first, local);
                    op = e != null ? e.text : null;
                }
                if (TextUtil.truthy(op)) {
                    if (wsSoap.isEmpty()) {
                        wsSoap.add(new SoapCall(null, null, line, soapFramework(imports)));
                    }
                    wsSoap.getLast().ops.add(Map.entry(op, line));
                }
                return;
            }
            if (soap && name.equals("setServiceClass") && first != null) {
                String text = first.text();
                if (text.endsWith(".class")) {
                    text = text.substring(0, text.length() - ".class".length());
                }
                String cls = last(text, ".");
                if (!wsSoap.isEmpty() && wsSoap.getLast().name == null) {
                    wsSoap.getLast().name = cls;
                } else {
                    wsSoap.add(new SoapCall(null, cls, line, soapFramework(imports)));
                }
                return;
            }
            if (WS_TEMPLATE_METHODS.contains(name) && WS_OBJ.matcher(obj).find() && importsStart("org.springframework.ws")) {
                String url = argv.size() > 1 ? urlArg(first, local) : null;
                if (TextUtil.truthy(url) && url.contains("://")) {
                    wsCalled("SOAP", last(TextUtil.rstrip(url, "/"), "/"), "Spring-WS", line, url, null, null, handler, null, null);
                } else {
                    SoapCall c = new SoapCall(null, null, line, "Spring-WS");
                    c.use = true;
                    wsSoap.add(c);
                }
                return;
            }
            // JAX-WS generated client: new XService().getXPort() / service.getPort(X.class)
            if (soap && GET_PORT.matcher(name).lookingAt()) {
                TsNode o = n.field("object");
                String svc;
                if (o != null && o.type.equals("object_creation_expression")) {
                    svc = last(txt(o.field("type")), ".");
                } else if (name.equals("getPort") && first != null) {
                    String text = first.text();
                    if (text.endsWith(".class")) {
                        text = text.substring(0, text.length() - ".class".length());
                    }
                    svc = last(text, ".");
                } else {
                    svc = name.substring(3);
                }
                if (!svc.isEmpty()) {
                    wsCalled("SOAP", svc, "JAX-WS client", line, null, null, null, handler, null, null);
                }
            }
        }

        void wsNew(String typ, List<TsNode> argv, int line) {
            if (typ.equals("QName") && argv.size() >= 2) {
                TsNode a = argv.getFirst();
                qnameNs.add(a.startByte);
                if (a.type.equals("identifier") && constants.containsKey(a.text())) {
                    qnameNs.add(constants.get(a.text()).node.startByte);
                }
                return;
            }
            String verb = HTTP_CLIENT_TYPES.get(typ);
            if (verb != null && !argv.isEmpty()) {
                String url = urlArg(argv.getFirst(), new HashMap<>());
                if (url != null && importsStart("org.apache.http", "org.apache.hc", "org.apache.commons.httpclient")) {
                    wsCalled("REST", clientName(), "Apache HttpClient", line, url, verb, null, null, null, null);
                }
                return;
            }
            if (typ.equals("URL") && !argv.isEmpty() && ctx.textOrEmpty().contains("openConnection")) {
                TsNode parent = argv.getFirst().parent != null ? argv.getFirst().parent.parent : null;
                TsNode gp = parent != null ? parent.parent : null;
                if (gp != null && gp.type.equals("argument_list")) {
                    return; // an argument (setTargetEndpointAddress(new URL(...)))
                }
                String url = urlArg(argv.getFirst(), new HashMap<>());
                String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
                if (TextUtil.truthy(url) && (lower.startsWith("http://") || lower.startsWith("https://"))) {
                    Matcher m = REQUEST_METHOD.matcher(ctx.textOrEmpty());
                    wsCalled("REST", clientName(), "HttpURLConnection", line, url, m.find() ? m.group(1).toUpperCase(Locale.ROOT) : null, null,
                            null, null, null);
                }
            }
        }

        /** Generated SOAP client classes: JAX-WS @WebServiceClient services and Apache Axis locators and stubs. */
        void wsClasses() {
            for (ClassInfo cls : classes) {
                var ann = cls.annotations;
                Set<String> sup = cls.superNames();
                if (ann.containsKey("WebServiceClient")) {
                    var w = ann.get("WebServiceClient");
                    String loc = TextUtil.truthy(w.get("wsdlLocation")) ? w.get("wsdlLocation") : null;
                    wsCalled("SOAP", or(w.get("name"), cls.name), "JAX-WS client", cls.line, loc != null && loc.contains("://") ? loc : null, null,
                            null, null, loc, w.get("targetNamespace"));
                } else if (sup.contains("Service") && importsStart("org.apache.axis.client")) {
                    String address = null;
                    for (var e : constants.entrySet()) {
                        if (e.getKey().endsWith("_address")) {
                            address = e.getValue().text;
                            break;
                        }
                    }
                    wsCalled("SOAP", cls.name, "Apache Axis", cls.line, address, null, null, null, null, null);
                } else if (sup.contains("Stub") && importsStart("org.apache.axis.client")) {
                    wsCalled("SOAP", cls.name, "Apache Axis", cls.line, null, null, null, null, null, null);
                }
            }
        }

        void wsFinish() {
            wsClasses();
            for (SoapCall call : wsSoap) {
                String url = call.url;
                if (!TextUtil.truthy(url) && call.ops.isEmpty() && !TextUtil.truthy(call.name) && !call.use) {
                    continue;
                }
                String name = call.name;
                if (!TextUtil.truthy(name)) {
                    name = TextUtil.truthy(url) && url.contains("/") ? TextUtil.beforeFirst(last(TextUtil.rstrip(url, "/"), "/"), "?") : "";
                }
                if (name.isEmpty()) {
                    name = clientName();
                }
                if (call.ops.isEmpty()) {
                    wsCalled("SOAP", name, call.fw, call.line, url, null, null, null, null, null);
                }
                for (var op : call.ops) {
                    wsCalled("SOAP", name, call.fw, op.getValue(), url, null, op.getKey(), null, null, null);
                }
            }
        }

        String jmsRole() {
            String text = ctx.textOrEmpty();
            boolean prod = PRODUCER.matcher(text).find();
            boolean cons = CONSUMER.matcher(text).find();
            if (prod && !cons) {
                return "producer";
            }
            if (cons && !prod) {
                return "consumer";
            }
            return null;
        }

        void newObject(TsNode n) {
            String typ = last(TextUtil.beforeFirst(txt(n.field("type")), "<"), ".");
            TsNode args = n.field("arguments");
            List<TsNode> argv = args != null ? args.namedChildren() : List.of();
            int line = n.line();
            List<Expr> vals = new ArrayList<>();
            for (TsNode a : argv) {
                vals.add(eval(a, null));
            }
            wsNew(typ, argv, line);
            if (typ.equals("Socket") && argv.size() >= 2) {
                String host = vals.get(0) != null && !vals.get(0).dynamic ? vals.get(0).text : "?";
                String port = argv.get(1).type.equals("decimal_integer_literal") ? argv.get(1).text() : "?";
                facts.add("integrations", integration("SOCKET", host + ":" + port, "outbound", path, line));
            } else if (typ.equals("ServerSocket") && !argv.isEmpty()) {
                String port = argv.getFirst().type.equals("decimal_integer_literal") ? argv.getFirst().text() : "?";
                facts.add("integrations", integration("SOCKET", "*:" + port, "inbound", path, line));
            } else if (typ.equals("MQQueueManager") && !vals.isEmpty() && vals.getFirst() != null && !vals.getFirst().dynamic) {
                facts.add("integrations", integration("MQ-QMGR", vals.getFirst().text, "outbound", path, line));
            }
        }

        // ---- results ------------------------------------------------------------------------

        void sql(String text, int line, String source, boolean dynamic, boolean hql, Expr expr) {
            Doc fact = SqlTools.sqlFact(path, line, text, source, dialect, dynamic, hql);
            if (expr != null) {
                DynSql.fill(fact, expr.base(), expr.fragments, expr.optional, expr.valueHoles, expr.structuralHoles, expr.how, expr.lines,
                        expr.variants);
            }
            facts.add("sql_statements", fact);
        }

        void emitSql() {
            boolean soap = importsStart(SOAP_IMPORTS.toArray(String[]::new));
            List<Expr> all = new ArrayList<>(exprs.values());
            all.sort((a, b) -> Integer.compare(a.node.startByte, b.node.startByte));
            for (Expr e : all) {
                if (usedAsFragment.contains(e.span()) && e.source == null) {
                    continue;
                }
                String text = TextUtil.strip(e.text);
                if (e.hql || ("hql".equals(e.source) && SqlTools.looksLikeHql(text))) {
                    sql(text, e.line, "hql", e.dynamic, true, e);
                } else if (SqlTools.looksLikeSql(text, e.source == null || e.source.isEmpty())) {
                    sql(text, e.line, e.source != null && !e.source.isEmpty() ? e.source : "java-string", e.dynamic, false, e);
                }
                if (qnameNs.contains(e.node.startByte)) {
                    continue; // a QName namespace, not an endpoint
                }
                if (text.contains("://") || text.toLowerCase(Locale.ROOT).startsWith("jdbc:")) {
                    Matcher m = URLS.matcher(text);
                    while (m.find()) {
                        String url = m.group(1);
                        if (url.toLowerCase(Locale.ROOT).startsWith("jdbc:")) {
                            String[] pt = Integrations.parseJdbc(url);
                            facts.add("integrations", integration("DB", pt[1], "outbound", path, e.line, null, "jdbc", null));
                            facts.add("tech_stack", tech(pt[0], null, "database", path, e.line));
                            continue;
                        }
                        String[] c = Integrations.classifyUrl(url, soap);
                        if (c != null) {
                            facts.add("integrations", integration(c[0], c[1], "outbound", path, e.line));
                        }
                    }
                }
            }
        }

        void importsAndSignals() {
            if (imports.stream().anyMatch(i -> JAVAX_EE.matcher(i).lookingAt())) {
                facts.add("tech_stack", tech("javax.* namespace (Jakarta EE migration)", null, "upgrade-signal", path, null));
            }
            if (imports.stream().anyMatch(i -> i.startsWith("javax.mail"))) {
                facts.add("tech_stack", tech("JavaMail", null, "library", path, null));
            }
            if (imports.stream().anyMatch(i -> i.startsWith("com.ibm.mq"))) {
                facts.add("tech_stack", tech("IBM MQ client", null, "middleware", path, null));
            }
        }

        void classRefs() {
            if (classes.isEmpty()) {
                return;
            }
            ClassInfo primary = classes.getFirst();
            String source = "class:" + primary.fqn;
            Set<String> seen = new HashSet<>();
            for (ClassInfo c : classes) {
                seen.add(c.fqn);
            }
            for (String imp : imports) {
                if (imp.startsWith("static ") || imp.endsWith(".*")) {
                    continue;
                }
                if (seen.add(imp)) {
                    facts.ref("uses", source, primary.fqn, imp, List.of("java-class"), path, null).quiet();
                }
            }
            Set<String> importedSimple = new HashSet<>();
            for (String i : imports) {
                importedSimple.add(last(i, "."));
            }
            TreeSet<String> names = new TreeSet<>(SqlTools.PyJson_ORDER);
            names.addAll(typeNames);
            for (String name : names) {
                if (importedSimple.contains(name) || !firstUpper(name)) {
                    continue;
                }
                String fqn = !pkg.isEmpty() ? pkg + "." + name : name;
                if (seen.add(fqn)) {
                    facts.ref("uses", source, primary.fqn, fqn, List.of("java-class"), path, null).quiet();
                }
            }
        }
    }
}
