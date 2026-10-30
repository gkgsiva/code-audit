package com.legacyaudit.engine.metrics;

import com.legacyaudit.engine.analyzers.BasicAnalyzer;
import com.legacyaudit.engine.analyzers.TextUtil;
import com.legacyaudit.engine.analyzers.ts.TsNode;
import com.legacyaudit.engine.analyzers.ts.TsTree;
import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Schemas;
import com.legacyaudit.model.json.PyJson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.treesitter.TSParser;
import org.treesitter.TreeSitterC;
import org.treesitter.TreeSitterCSharp;
import org.treesitter.TreeSitterCpp;
import org.treesitter.TreeSitterJava;
import org.treesitter.TreeSitterTsx;
import org.treesitter.TreeSitterTypescript;

/**
 * Code units (functions, methods, paragraphs, procedures, scripts) and their cyclomatic
 * complexity: 1 + the number of decision points, counted per language.
 *
 * <ul>
 *   <li>Java: {@code if}, loops, {@code case} labels (not {@code default}), {@code catch},
 *       {@code ?:}, {@code &&}, {@code ||}.
 *   <li>TypeScript/JavaScript: per function, method or arrow function (a short arrow function
 *       used as an argument, {@code x => x.id}, is part of its enclosing unit): the same decision
 *       points as Java, plus {@code ??}.
 *   <li>C/C++/Pro*C: the same, with {@code case} statements and range {@code for}.
 *   <li>C#: per method, constructor, operator, local function or property accessor with a body:
 *       the same as Java plus {@code foreach}, {@code ??} and switch-expression arms (not the
 *       {@code _} discard).
 *   <li>VB6/VB.NET: per {@code Sub}, {@code Function}, {@code Property} or {@code Operator}:
 *       {@code If}/{@code ElseIf}, {@code For}, {@code While}/{@code Until}, {@code Case} (not
 *       {@code Case Else}), {@code Catch}, {@code IIf(}/{@code If(}, {@code AndAlso}/
 *       {@code OrElse}, and {@code And}/{@code Or} in a condition.
 *   <li>COBOL: per paragraph or section: {@code IF}, {@code WHEN} (not {@code WHEN OTHER}),
 *       {@code UNTIL}, {@code AND}, {@code OR}, and the conditional phrases {@code AT END},
 *       {@code INVALID KEY}, {@code ON SIZE ERROR}, {@code ON EXCEPTION}, {@code ON OVERFLOW}.
 *   <li>PL/SQL: per procedure, function, trigger or package body: {@code IF}, {@code ELSIF},
 *       {@code WHEN}, {@code LOOP}/{@code WHILE}/{@code FOR} loops.
 *   <li>Shell and other scripts: one unit per file: {@code if}, {@code elif}, loops,
 *       {@code case} branches, {@code &&}, {@code ||}.
 * </ul>
 */
public final class Units {

    private static final ThreadLocal<TSParser> JAVA = TsTree.parsers(TreeSitterJava::new);
    private static final ThreadLocal<TSParser> C = TsTree.parsers(TreeSitterC::new);
    private static final ThreadLocal<TSParser> CPP = TsTree.parsers(TreeSitterCpp::new);
    private static final ThreadLocal<TSParser> TS = TsTree.parsers(TreeSitterTypescript::new);
    private static final ThreadLocal<TSParser> TSX = TsTree.parsers(TreeSitterTsx::new);
    private static final ThreadLocal<TSParser> CS = TsTree.parsers(TreeSitterCSharp::new);

    static final Set<String> JAVA_UNITS = Set.of("method_declaration", "constructor_declaration", "compact_constructor_declaration");
    static final Set<String> JAVA_DECISIONS = Set.of("if_statement", "for_statement", "enhanced_for_statement", "while_statement",
            "do_statement", "catch_clause", "ternary_expression");
    static final Set<String> C_UNITS = Set.of("function_definition");
    static final Set<String> C_DECISIONS = Set.of("if_statement", "for_statement", "for_range_loop", "while_statement", "do_statement",
            "catch_clause", "conditional_expression");
    static final Set<String> TS_UNITS = Set.of("function_declaration", "generator_function_declaration", "method_definition",
            "function_expression", "arrow_function");
    static final Set<String> TS_DECISIONS = Set.of("if_statement", "for_statement", "for_in_statement", "while_statement", "do_statement",
            "catch_clause", "ternary_expression", "switch_case");
    static final Set<String> CS_UNITS = Set.of("method_declaration", "constructor_declaration", "destructor_declaration",
            "operator_declaration", "conversion_operator_declaration", "local_function_statement", "accessor_declaration");
    static final Set<String> CS_DECISIONS = Set.of("if_statement", "for_statement", "foreach_statement", "while_statement", "do_statement",
            "catch_clause", "conditional_expression");
    private static final Set<String> LOGICAL = Set.of("&&", "||", "??");
    private static final Set<String> BODIES = Set.of("class_body", "enum_body", "interface_body");

    private Units() {}

    static Doc unit(String name, String kind, int line, int endLine, int loc, int cyclomatic) {
        return Doc.of(Schemas.CODE_UNIT,
                "name", name,
                "kind", kind,
                "line", (long) line,
                "end_line", (long) endLine,
                "loc", (long) loc,
                "cyclomatic", (long) cyclomatic);
    }

    private static int nonblank(List<String> lines, int start, int end) {
        int count = 0;
        for (int i = Math.max(start - 1, 0); i < Math.min(end, lines.size()); i++) {
            if (!TextUtil.strip(lines.get(i)).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static boolean isLogical(TsNode node) {
        if (!node.type.equals("binary_expression")) {
            return false;
        }
        TsNode op = node.field("operator");
        return op != null && LOGICAL.contains(op.type);
    }

    /** Decision points under {@code node}, not descending into nested units or classes. */
    private static int decisions(TsNode node, Set<String> kinds, Set<String> units) {
        int count = 0;
        List<TsNode> stack = new ArrayList<>(node.children);
        while (!stack.isEmpty()) {
            TsNode n = stack.removeLast();
            if (units.contains(n.type) || BODIES.contains(n.type)) {
                continue;
            }
            if (kinds.contains(n.type) || isLogical(n)) {
                count++;
            } else if ((n.type.equals("switch_label") && !n.children.isEmpty() && n.children.getFirst().type.equals("case"))
                    || (n.type.equals("case_statement") && n.field("value") != null)) {
                count++; // Java "case X" / C "case X:"; a bare "default" adds no path.
            }
            stack.addAll(n.children);
        }
        return count;
    }

    /** Nodes of the given types, in source order. */
    private static List<TsNode> walk(TsNode node, Set<String> kinds) {
        List<TsNode> out = new ArrayList<>();
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

    private static String name(TsNode node) {
        if (node.type.equals("function_definition")) {
            TsNode decl = node.field("declarator");
            while (decl != null && !decl.type.equals("identifier") && !decl.type.equals("field_identifier")) {
                TsNode inner = decl.field("declarator");
                if (inner == null) {
                    TsNode found = null;
                    for (TsNode c : decl.children) {
                        if (c.type.equals("identifier") || c.type.equals("qualified_identifier")) {
                            found = c;
                            break;
                        }
                    }
                    decl = found;
                    break;
                }
                decl = inner;
            }
            return decl != null ? decl.text() : "?";
        }
        TsNode name = node.field("name");
        return name != null ? name.text() : "?";
    }

    /** Units of Java or C/C++ code (Pro*C callers pass the text with EXEC SQL blanked). */
    public static List<Doc> treeUnits(String code, String language) {
        String lang = language.equals("java") ? "java" : language.equals("cpp") ? "cpp" : "c";
        TsNode root = TsTree.parse((lang.equals("java") ? JAVA : lang.equals("cpp") ? CPP : C).get(), code);
        Set<String> unitKinds = lang.equals("java") ? JAVA_UNITS : C_UNITS;
        Set<String> kinds = lang.equals("java") ? JAVA_DECISIONS : C_DECISIONS;
        List<String> lines = TextUtil.splitLines(code);
        List<Doc> out = new ArrayList<>();
        for (TsNode node : walk(root, unitKinds)) {
            int start = node.startRow + 1;
            int end = node.endRow + 1;
            out.add(unit(name(node), lang.equals("java") ? "method" : "function", start, end, nonblank(lines, start, end),
                    1 + decisions(node, kinds, unitKinds)));
        }
        out.sort(Comparator.comparingLong(u -> (Long) u.get("line")));
        return out;
    }

    // ---- TypeScript / JavaScript ----------------------------------------------------------

    /**
     * Name of a TypeScript/JavaScript unit; null for an anonymous function that is part of its
     * enclosing unit (a callback passed as an argument).
     */
    private static String tsName(TsNode node) {
        TsNode name = node.field("name");
        if (name != null) {
            return name.text();
        }
        TsNode parent = node.parent;
        if (parent == null) {
            return null;
        }
        if (Set.of("variable_declarator", "public_field_definition", "pair", "assignment_expression").contains(parent.type)) {
            TsNode key = parent.field("name");
            if (key == null) {
                key = parent.field("key");
            }
            if (key == null) {
                key = parent.field("left");
            }
            if (key != null) {
                return key.text();
            }
        }
        if (parent.type.equals("array") && parent.parent != null) { // ['$scope', function (...) {}]
            parent = parent.parent;
        }
        if (parent.type.equals("arguments") && node.endRow - node.startRow >= 2) {
            // router.get('/x', async (req, res) => {...}) -> "get /x";
            // .controller('AccountsCtrl', [...]) -> "controller AccountsCtrl"
            TsNode call = parent.parent;
            TsNode fn = call != null ? call.field("function") : null;
            String label = fn != null ? fn.text() : "";
            label = TextUtil.afterLast(label, ".");
            if (label.isEmpty()) {
                label = "callback";
            }
            List<TsNode> named = parent.namedChildren();
            TsNode first = named.isEmpty() ? null : named.getFirst();
            if (first != null && first.type.equals("string") && first != node) {
                String t = first.text();
                return label + " " + (t.length() >= 2 ? t.substring(1, t.length() - 1) : "");
            }
            return label + " callback";
        }
        return null;
    }

    /**
     * Units of TypeScript or JavaScript code: named functions, methods, and arrow functions or
     * function expressions that are assigned or are multi-line callbacks.
     */
    public static List<Doc> tsUnits(String code, String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        boolean tsx = !(lower.endsWith(".ts") || lower.endsWith(".mts") || lower.endsWith(".cts"));
        TsNode root = TsTree.parse((tsx ? TSX : TS).get(), code);
        List<String> lines = TextUtil.splitLines(code);
        List<TsNode> nodes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Set<TsNode> keep = Collections.newSetFromMap(new IdentityHashMap<>());
        for (TsNode node : walk(root, TS_UNITS)) {
            String name = tsName(node);
            if (name != null) {
                nodes.add(node);
                names.add(name);
                keep.add(node);
            }
        }
        List<Doc> out = new ArrayList<>();
        for (int k = 0; k < nodes.size(); k++) {
            TsNode node = nodes.get(k);
            int start = node.startRow + 1;
            int end = node.endRow + 1;
            out.add(unit(names.get(k), node.type.equals("method_definition") ? "method" : "function", start, end, nonblank(lines, start, end),
                    1 + tsDecisions(node, keep)));
        }
        sortByLineAndName(out);
        return out;
    }

    private static void sortByLineAndName(List<Doc> units) {
        units.sort(Comparator.<Doc>comparingLong(u -> (Long) u.get("line"))
                .thenComparing((a, b) -> PyJson.compareCodePoints((String) a.get("name"), (String) b.get("name"))));
    }

    /**
     * Decision points of a unit, not counting those of the units nested in it (anonymous
     * one-line callbacks are part of the unit).
     */
    private static int tsDecisions(TsNode node, Set<TsNode> units) {
        int count = 0;
        List<TsNode> stack = new ArrayList<>(node.children);
        while (!stack.isEmpty()) {
            TsNode n = stack.removeLast();
            if (units.contains(n) || n.type.equals("class_body")) {
                continue;
            }
            if (TS_DECISIONS.contains(n.type) || isLogical(n)) {
                count++;
            }
            stack.addAll(n.children);
        }
        return count;
    }

    // ---- C# -------------------------------------------------------------------------------

    private static String t(TsNode n) {
        return n != null ? n.text() : "?";
    }

    private static String csName(TsNode node) {
        switch (node.type) {
            case "accessor_declaration" -> {
                TsNode prop = node.parent != null ? node.parent.parent : null;
                TsNode name = prop != null ? prop.field("name") : null;
                TsNode kw = null;
                for (TsNode c : node.children) {
                    if (!c.named) {
                        kw = c;
                        break;
                    }
                }
                return t(name) + "." + t(kw);
            }
            case "operator_declaration" -> {
                return "operator " + t(node.field("operator"));
            }
            case "conversion_operator_declaration" -> {
                return "operator " + t(node.field("type"));
            }
            case "destructor_declaration" -> {
                return "~" + t(node.field("name"));
            }
            default -> {
                return t(node.field("name"));
            }
        }
    }

    private static int csDecisions(TsNode node) {
        int count = 0;
        List<TsNode> stack = new ArrayList<>(node.children);
        while (!stack.isEmpty()) {
            TsNode n = stack.removeLast();
            if (CS_UNITS.contains(n.type) || n.type.equals("class_declaration") || n.type.equals("struct_declaration")) {
                continue;
            }
            List<TsNode> named = null;
            if (CS_DECISIONS.contains(n.type) || isLogical(n)
                    || (n.type.equals("switch_section") && !n.children.isEmpty() && n.children.getFirst().type.equals("case"))
                    || (n.type.equals("switch_expression_arm")
                            && !(!(named = n.namedChildren()).isEmpty() && named.getFirst().type.equals("discard")))) {
                count++;
            }
            stack.addAll(n.children);
        }
        return count;
    }

    /** Units of C# code; an accessor without a body ({@code get;}) is not one. */
    public static List<Doc> csUnits(String code) {
        TsNode root = TsTree.parse(CS.get(), code);
        List<String> lines = TextUtil.splitLines(code);
        List<Doc> out = new ArrayList<>();
        for (TsNode node : walk(root, CS_UNITS)) {
            TsNode body = node.field("body");
            if (body == null) {
                for (TsNode c : node.children) {
                    if (c.type.equals("block") || c.type.equals("arrow_expression_clause")) {
                        body = c;
                        break;
                    }
                }
            }
            if (body == null) {
                continue;
            }
            int start = node.startRow + 1;
            int end = node.endRow + 1;
            out.add(unit(csName(node), node.type.equals("local_function_statement") ? "function" : "method", start, end,
                    nonblank(lines, start, end), 1 + csDecisions(node)));
        }
        sortByLineAndName(out);
        return out;
    }

    // ---- VB6 / VB.NET ---------------------------------------------------------------------

    private static final Pattern VB_STRING = Rx.of("\"(?:[^\"]|\"\")*\"");
    private static final Pattern VB_DECISION = Rx.of("(?<!\\bEnd )(?<!#)\\bIf\\b(?!\\s*\\()|\\bElseIf\\b|\\bFor\\b|\\bWhile\\b|\\bUntil\\b"
            + "|\\bCase\\b(?!\\s+Else\\b)|\\bCatch\\b|\\bIIf\\s*\\(|\\bIf\\s*\\(|\\bAndAlso\\b|\\bOrElse\\b", Rx.I);
    private static final Pattern VB_NOT = Rx.of("^(?:End\\s+(?:If|While|Select)|Exit\\s+(?:For|While|Do)|Select\\s+Case|Next|Wend)\\b", Rx.I);
    private static final Pattern VB_CONDITION = Rx.of("^(?:Else)?If\\b|^(?:Do|Loop)\\s+(?:While|Until)\\b|^While\\b", Rx.I);
    private static final Pattern VB_AND_OR = Rx.of("\\b(?:And|Or)\\b", Rx.I);
    private static final Pattern VB_NO_BODY = Rx.of("^(?:\\w+\\s+)*(?:Declare|MustOverride)\\b", Rx.I);

    private static int count(Pattern rx, String text) {
        Matcher m = rx.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** Procedures of VB6 or VB.NET code ({@code Declare} and abstract members have no body). */
    public static List<Doc> vbUnits(String code) {
        List<String> raw = Arrays.asList(code.split("\n", -1));
        List<Doc> out = new ArrayList<>();
        String curName = null;
        String curKind = null;
        int curLine = 0;
        int curDecisions = 0;
        for (BasicAnalyzer.Logical ll : BasicAnalyzer.logicalLines(code, 0)) {
            int line = ll.line();
            String stmt = ll.code();
            Matcher m = BasicAnalyzer.PROC.matcher(stmt);
            if (m.find() && !VB_NO_BODY.matcher(stmt).find()) {
                String kind = m.group(1).toLowerCase(Locale.ROOT);
                String accessor = m.group(2) != null ? "." + m.group(2).toLowerCase(Locale.ROOT) : "";
                curName = m.group(3) + accessor;
                curKind = kind.equals("function") ? "function" : "method";
                curLine = line;
                curDecisions = 0;
                continue;
            }
            if (curName == null) {
                continue;
            }
            if (BasicAnalyzer.PROC_END.matcher(stmt).find()) {
                out.add(unit(curName, curKind, curLine, line, nonblank(raw, curLine, line), 1 + curDecisions));
                curName = null;
                continue;
            }
            String body = VB_STRING.matcher(stmt).replaceAll("\"\"");
            if (VB_NOT.matcher(body).find()) {
                continue;
            }
            int n = count(VB_DECISION, body);
            if (VB_CONDITION.matcher(body).find()) {
                n += count(VB_AND_OR, body);
            }
            curDecisions += n;
        }
        return out;
    }

    // ---- COBOL ----------------------------------------------------------------------------

    private static final Pattern COBOL_LABEL = Rx.of("^ {0,3}([A-Z0-9][A-Z0-9-]*)(\\s+SECTION)?\\s*\\.\\s*$", Rx.I);
    // COBOL words contain hyphens: "END-IF" is not an IF.
    private static final Pattern COBOL_DECISION = Rx.of("(?<![\\w-])(?:IF|UNTIL|AND|OR|AT\\s+END|INVALID\\s+KEY|ON\\s+SIZE\\s+ERROR|ON\\s+EXCEPTION"
            + "|ON\\s+OVERFLOW|WHEN(?!\\s+OTHER(?![\\w-])))(?![\\w-])", Rx.I);
    private static final Set<String> COBOL_NOT_LABELS = Set.of("END-IF", "END-EVALUATE", "END-PERFORM", "END-READ", "EXIT", "GOBACK");
    private static final Pattern QUOTED = Rx.of("'[^']*'|\"[^\"]*\"");
    private static final Pattern PROCEDURE_DIVISION = Rx.of("\\bPROCEDURE\\s+DIVISION\\b", Rx.I);

    private record Label(int index, String name, String kind) {}

    /**
     * Paragraphs and sections of the PROCEDURE DIVISION ({@code codeLines} from
     * {@code CobolAnalyzer.sourceLines}: code area only, comments blanked).
     */
    public static List<Doc> cobolUnits(List<String> codeLines) {
        int start = -1;
        for (int i = 0; i < codeLines.size(); i++) {
            if (PROCEDURE_DIVISION.matcher(codeLines.get(i)).find()) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return List.of();
        }
        List<Label> labels = new ArrayList<>();
        for (int i = start + 1; i < codeLines.size(); i++) {
            Matcher m = COBOL_LABEL.matcher(codeLines.get(i));
            if (m.find() && !COBOL_NOT_LABELS.contains(m.group(1).toUpperCase(Locale.ROOT))) {
                labels.add(new Label(i, m.group(1).toUpperCase(Locale.ROOT), m.group(2) != null ? "section" : "paragraph"));
            }
        }
        boolean codeBefore = false;
        if (!labels.isEmpty()) {
            for (int i = start + 1; i < labels.getFirst().index(); i++) {
                if (!TextUtil.strip(codeLines.get(i)).isEmpty()) {
                    codeBefore = true;
                    break;
                }
            }
        }
        if (labels.isEmpty() || codeBefore) {
            labels.addFirst(new Label(start, "PROCEDURE DIVISION", "paragraph"));
        }
        List<Doc> out = new ArrayList<>();
        for (int k = 0; k < labels.size(); k++) {
            Label l = labels.get(k);
            int j = k + 1 < labels.size() ? labels.get(k + 1).index() : codeLines.size();
            StringBuilder body = new StringBuilder();
            for (int x = l.index() + 1; x < j; x++) {
                if (x > l.index() + 1) {
                    body.append('\n');
                }
                body.append(QUOTED.matcher(codeLines.get(x)).replaceAll("''"));
            }
            out.add(unit(l.name(), l.kind(), l.index() + 1, j, nonblank(codeLines, l.index() + 1, j), 1 + count(COBOL_DECISION, body.toString())));
        }
        return out;
    }

    // ---- PL/SQL ---------------------------------------------------------------------------

    private static final Pattern PLSQL_UNIT = Rx.of("^\\s*(?:CREATE\\s+(?:OR\\s+REPLACE\\s+)?)?(?:(PACKAGE\\s+BODY)|(PROCEDURE)|(FUNCTION)|(TRIGGER))"
            + "\\s+([\\w.$#\"]+)", Rx.I | Rx.M);
    // Every loop (LOOP, WHILE .. LOOP, FOR .. LOOP) has one LOOP keyword besides its END LOOP.
    private static final Pattern PLSQL_DECISION = Rx.of("(?<!END\\s)\\b(?:IF|LOOP)\\b|\\bELSIF\\b|\\bWHEN\\b", Rx.I);
    private static final Pattern SQL_STRING = Rx.of("'(?:[^']|'')*'");
    private static final Pattern BEGIN = Rx.of("\\bBEGIN\\b", Rx.I);

    private record Head(int line, String kind, String name) {}

    /**
     * Procedures, functions, triggers and package bodies; inside a package body each
     * procedure/function is its own unit. Plain SQL scripts have none.
     */
    public static List<Doc> plsqlUnits(String code) {
        String text = SQL_STRING.matcher(code).replaceAll("''");
        List<String> lines = TextUtil.splitLines(text);
        List<Head> heads = new ArrayList<>();
        Matcher m = PLSQL_UNIT.matcher(text);
        while (m.find()) {
            if (m.group(1) != null) {
                continue; // package bodies are containers
            }
            String kind = m.group(2) != null ? "procedure" : m.group(3) != null ? "function" : "trigger";
            heads.add(new Head(TextUtil.count(text.substring(0, m.start()), '\n') + 1, kind,
                    TextUtil.strip(m.group(5), "\"").toUpperCase(Locale.ROOT)));
        }
        List<Doc> out = new ArrayList<>();
        for (int k = 0; k < heads.size(); k++) {
            Head h = heads.get(k);
            int line = h.line();
            int end = k + 1 < heads.size() ? heads.get(k + 1).line() - 1 : lines.size();
            Integer slash = null;
            for (int i = line; i <= end; i++) {
                if (i - 1 < lines.size() && TextUtil.strip(lines.get(i - 1)).equals("/")) {
                    slash = i;
                    break;
                }
            }
            end = slash != null ? slash - 1 : end;
            String body = String.join("\n", lines.subList(Math.min(line - 1, lines.size()), Math.min(Math.max(end, line - 1), lines.size())));
            // A declaration in a package spec has no body ("... RETURN x;" then the next one).
            if (!BEGIN.matcher(body).find()) {
                continue;
            }
            out.add(unit(h.name(), h.kind(), line, end, nonblank(lines, line, end), 1 + count(PLSQL_DECISION, body)));
        }
        return out;
    }

    // ---- Scripts --------------------------------------------------------------------------

    private static final Map<String, Pattern> SCRIPT_DECISION = Map.of(
            "shell", Rx.of("\\b(?:if|elif|while|until|for)\\b|&&|\\|\\||^\\s*[^\\s()#]+(?:\\|[^\\s()]+)*\\)\\s", Rx.M),
            "perl", Rx.of("\\b(?:if|elsif|unless|while|until|for|foreach)\\b|&&|\\|\\||\\band\\b|\\bor\\b"),
            "python", Rx.of("\\b(?:if|elif|while|for|except)\\b|\\band\\b|\\bor\\b"),
            "batch", Rx.of("\\b(?:if|for)\\b|&&|\\|\\|", Rx.I));
    private static final Pattern SCRIPT_STRING = Rx.of("'[^'\\n]*'|\"(?:[^\"\\\\\\n]|\\\\.)*\"");

    public static List<Doc> scriptUnit(String code, String language, String name) {
        Pattern rx = SCRIPT_DECISION.get(language);
        List<String> lines = TextUtil.splitLines(code);
        if (rx == null || lines.stream().allMatch(ln -> TextUtil.strip(ln).isEmpty())) {
            return List.of();
        }
        String body = SCRIPT_STRING.matcher(code).replaceAll("\"\"");
        return List.of(unit(name, "script", 1, Math.max(lines.size(), 1), nonblank(lines, 1, lines.size()), 1 + count(rx, body)));
    }
}
