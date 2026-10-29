package com.legacyaudit.engine.analyzers;

import static com.legacyaudit.engine.analyzers.BuildAnalyzer.tech;
import static com.legacyaudit.engine.analyzers.Integrations.integration;
import static com.legacyaudit.engine.analyzers.ts.TsNode.txt;

import com.legacyaudit.engine.analyzers.DynSql.Built;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.treesitter.TSParser;
import org.treesitter.TreeSitterC;
import org.treesitter.TreeSitterCpp;

/**
 * C and C++ sources (tree-sitter-c / tree-sitter-cpp): functions, {@code main()} (a
 * {@code c-executable} service named after the file), includes, called functions,
 * {@code system()}/{@code exec*}/{@code popen} commands, file I/O, sockets, IBM MQ MQI calls,
 * libcurl and gSOAP web service calls, and SQL (database client API calls and SQL text built in
 * buffers, see {@link CSql}).
 *
 * <p>The Pro*C analyzer blanks {@code EXEC SQL} blocks and calls {@link #analyzeC} on the rest.
 */
public final class CAnalyzer implements Analyzer {

    private static final ThreadLocal<TSParser> C_PARSER = TsTree.parsers(TreeSitterC::new);
    private static final ThreadLocal<TSParser> CPP_PARSER = TsTree.parsers(TreeSitterCpp::new);

    static final Set<String> EXEC_FUNCS = Set.of("system", "popen", "execl", "execlp", "execle", "execv", "execvp", "execve", "_popen", "_system",
            "spawnl", "spawnlp", "spawnv", "spawnvp", "CreateProcess");
    static final Set<String> FILE_FUNCS = Set.of("fopen", "fopen64", "freopen", "open", "creat", "_open");
    static final Set<String> MQ_PRODUCE = Set.of("MQPUT", "MQPUT1");
    static final Set<String> MQ_CONSUME = Set.of("MQGET");
    static final Set<String> MQI = Set.of("MQCONN", "MQCONNX", "MQOPEN", "MQPUT", "MQPUT1", "MQGET", "MQCLOSE", "MQDISC", "MQINQ", "MQSET",
            "MQBEGIN", "MQCMIT", "MQBACK");
    static final Set<String> LIBC = Set.of("printf", "fprintf", "sprintf", "snprintf", "scanf", "sscanf", "fscanf", "puts", "fputs", "fgets",
            "gets", "getc", "fgetc", "putc", "fputc", "fread", "fwrite", "fclose", "fflush", "fseek", "ftell", "rewind", "feof", "ferror",
            "perror", "malloc", "calloc", "realloc", "free", "memset", "memcpy", "memmove", "memcmp", "strcpy", "strncpy", "strcat", "strncat",
            "strcmp", "strncmp", "strlen", "strchr", "strrchr", "strstr", "strtok", "strdup", "atoi", "atol", "atof", "strtol", "strtoul",
            "strtod", "exit", "abort", "getenv", "setenv", "time", "localtime", "strftime", "mktime", "sleep", "close", "read", "write",
            "toupper", "tolower", "isdigit", "isalpha", "isspace", "sizeof", "assert", "va_start", "va_end", "errno", "strerror", "qsort",
            "htons", "htonl", "ntohs", "ntohl", "unlink", "rename", "remove", "fork", "wait", "waitpid", "signal", "pclose", "rand", "srand",
            "abs", "isalnum", "memchr");
    static final Pattern SYSTEM_HEADERS = Rx.of("^(std|sys/|netinet/|arpa/|linux/|bits/|c\\+\\+|win|cmq)", Rx.I);
    private static final Pattern SCRIPT_COMMAND = Rx.of("^\\s*[\\w./$-]+\\.(?:k?sh|bash|csh|pl|py)(?:\\s|$)");
    private static final Pattern OPEN_WRITE = Rx.of("O_WRONLY|O_RDWR|O_CREAT|O_APPEND");
    private static final Pattern CPP_HEADER = Rx.of("^\\s*(class|namespace|template)\\b", Rx.M);
    private static final Set<String> MQ_HEADERS = Set.of("cmqc.h", "cmqxc.h", "cmqcfc.h");
    private static final Set<String> COPY_LIKE = Set.of("strcpy", "strncpy", "memcpy", "sprintf", "snprintf");
    private static final Set<String> RESOLVERS = Set.of("gethostbyname", "inet_addr", "getaddrinfo", "inet_pton");
    private static final Set<String> SOCKET_CALLS = Set.of("connect", "bind", "listen", "accept", "socket");
    private static final Set<String> SHELLS = Set.of("sh", "ksh", "bash", "perl");

    @Override
    public String name() {
        return "c_cpp";
    }

    @Override
    public String version() {
        return "4"; // 4: libcurl and gSOAP web service calls
    }

    @Override
    public boolean supports(FileRecord file) {
        return file.language.equals("c") || file.language.equals("cpp");
    }

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        Facts facts = new Facts();
        String text = ctx.textOrEmpty();
        facts.coverage.lines = TextUtil.count(text, '\n') + 1;
        boolean cpp = file.language.equals("cpp") || (file.path.endsWith(".h") && CPP_HEADER.matcher(text).find());
        CSql csql = analyzeC(file.path, text, facts, cpp);
        emitCSql(file.path, csql, facts, ctx.config().sqlDialect());
        return facts;
    }

    /** Literal value of a string or concatenated string; null if not a literal. */
    static String str(TsNode node) {
        if (node == null) {
            return null;
        }
        if (node.type.equals("string_literal")) {
            StringBuilder sb = new StringBuilder();
            for (TsNode c : node.children) {
                if (c.type.equals("string_content") || c.type.equals("escape_sequence")) {
                    sb.append(c.text());
                }
            }
            return sb.toString();
        }
        if (node.type.equals("concatenated_string")) {
            StringBuilder sb = new StringBuilder();
            for (TsNode c : node.namedChildren()) {
                String p = str(c);
                if (p == null) {
                    return null;
                }
                sb.append(p);
            }
            return sb.toString();
        }
        return null;
    }

    private static String functionName(TsNode node) {
        TsNode decl = node.field("declarator");
        while (decl != null && !decl.type.equals("function_declarator")) {
            decl = decl.field("declarator");
        }
        if (decl == null) {
            return null;
        }
        TsNode name = decl.field("declarator");
        return name != null ? txt(name) : null;
    }

    /** Facts of one C/C++ text; returns the rebuilt SQL buffers for {@link #emitCSql}. */
    public static CSql analyzeC(String path, String text, Facts facts, boolean cpp) {
        TsNode root = TsTree.parse((cpp ? CPP_PARSER : C_PARSER).get(), text);
        if (root.hasError) {
            JavaAnalyzer.errors(root, facts, cpp ? "C++" : "C");
        }
        Walker walker = new Walker(path, facts);
        walker.run(root);
        walker.finish();
        CSql csql = new CSql();
        csql.run(root);
        return csql;
    }

    /**
     * SQL statements at database API call sites, then SQL-looking buffers and constants that
     * reach the database some other way (a wrapper function).
     */
    public static void emitCSql(String path, CSql csql, Facts facts, String dialect) {
        for (CSql.Call call : csql.calls) {
            Built built = call.built();
            if (built != null && SqlTools.looksLikeSql(built.text)) {
                Doc fact = SqlTools.sqlFact(path, call.line(), built.text, call.source(), dialect, built.dynamic(), false);
                facts.add("sql_statements", DynSql.fill(fact, built));
            } else { // the text comes from somewhere this file does not show
                Doc fact = SqlTools.sqlFact(path, call.line(), call.fn() + "(" + call.raw() + ")", call.source(), dialect, true, false);
                fact.set("type", "DYNAMIC");
                fact.set("structural_holes", 1L);
                fact.set("built_with", new ArrayList<>(List.of("variable")));
                facts.add("sql_statements", fact);
            }
        }
        List<Built> leftovers = new ArrayList<>(csql.leftovers());
        leftovers.sort((a, b) -> Integer.compare(a.line, b.line));
        for (Built built : leftovers) {
            if (SqlTools.looksLikeSql(built.text, true)) {
                Doc fact = SqlTools.sqlFact(path, built.line, built.text, "c-string", dialect, built.dynamic(), false);
                facts.add("sql_statements", DynSql.fill(fact, built));
            }
        }
    }

    /** One pass over the tree for functions, includes and calls; per file, not thread-safe. */
    private static final class Walker {
        private final String path;
        private final Facts facts;
        private final Map<String, Integer> functions = new LinkedHashMap<>();
        private final Map<String, Integer> calls = new LinkedHashMap<>();
        private final List<Map.Entry<String, Integer>> queues = new ArrayList<>();
        private final Set<String> mqCalls = new HashSet<>();
        private final List<Map.Entry<String, Integer>> hosts = new ArrayList<>();
        private final List<String> ports = new ArrayList<>();
        private final Set<String> socketCalls = new HashSet<>();

        Walker(String path, Facts facts) {
            this.path = path;
            this.facts = facts;
        }

        void run(TsNode root) {
            Deque<TsNode> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                TsNode n = stack.pop();
                switch (n.type) {
                    case "preproc_include" -> include(n);
                    case "function_definition" -> {
                        String name = functionName(n);
                        if (name != null && !name.isEmpty()) {
                            functions.putIfAbsent(name, n.line());
                        }
                    }
                    case "call_expression" -> call(n);
                    default -> {}
                }
                for (int i = n.children.size() - 1; i >= 0; i--) {
                    stack.push(n.children.get(i));
                }
            }
        }

        private void include(TsNode n) {
            TsNode target = n.field("path");
            if (target == null) {
                return;
            }
            int line = n.line();
            if (target.type.equals("string_literal")) {
                String name = str(target);
                name = name == null ? "" : name;
                facts.ref("includes", "file:" + path, path, TextUtil.basename(name), List.of("file-name"), path, line)
                        .reportUnresolved(!SYSTEM_HEADERS.matcher(name).lookingAt());
            } else if (MQ_HEADERS.contains(TextUtil.strip(txt(target), "<>").toLowerCase(Locale.ROOT))) {
                facts.add("tech_stack", tech("IBM MQ (MQI)", null, "middleware", path, line));
            }
        }

        private void call(TsNode n) {
            TsNode fn = n.field("function");
            String name = TextUtil.afterLast(TextUtil.afterLast(TextUtil.afterLast(txt(fn), "::"), "."), "->");
            TsNode argsNode = n.field("arguments");
            List<TsNode> args = argsNode != null ? argsNode.namedChildren() : List.of();
            int line = n.line();
            if (name.isEmpty()) {
                return;
            }
            calls.putIfAbsent(name, line);
            if (EXEC_FUNCS.contains(name) && !args.isEmpty()) {
                String cmd = str(args.getFirst());
                if (cmd != null && !cmd.isEmpty()) {
                    command(cmd, line);
                }
            } else if (FILE_FUNCS.contains(name) && !args.isEmpty()) {
                String fname = str(args.getFirst());
                String mode = args.size() > 1 ? str(args.get(1)) : null;
                if (fname != null && !fname.isEmpty()) {
                    String direction;
                    if (name.startsWith("fopen") || name.equals("freopen")) {
                        String m = mode == null || mode.isEmpty() ? "r" : mode;
                        direction = m.startsWith("r") && !(mode == null ? "" : mode).contains("+") ? "inbound" : "outbound";
                    } else {
                        String flags = args.size() > 1 ? txt(args.get(1)) : "";
                        direction = OPEN_WRITE.matcher(flags).find() || name.equals("creat") ? "outbound" : "inbound";
                    }
                    facts.add("integrations", integration("FILE", fname, direction, path, line, null, name, null));
                }
            } else if (COPY_LIKE.contains(name) && args.size() >= 2) {
                // A command line built with sprintf and run later: "<script> %s" formats.
                String fmt = args.size() > 2 || name.equals("sprintf") ? str(args.get(name.equals("snprintf") ? 2 : 1)) : null;
                if (fmt != null && !fmt.isEmpty() && SCRIPT_COMMAND.matcher(fmt).lookingAt()) {
                    command(fmt, line);
                }
                String dest = txt(args.getFirst());
                String src = !name.equals("sprintf") && !name.equals("snprintf") ? str(args.get(1)) : null;
                if (src != null && !src.isEmpty() && dest.endsWith("ObjectName")) {
                    queues.add(Map.entry(src, line));
                }
            } else if ((name.equals("MQCONN") || name.equals("MQCONNX")) && !args.isEmpty()) {
                mqCalls.add(name);
                String qmgr = str(args.getFirst());
                if (qmgr != null && !qmgr.isEmpty()) {
                    facts.add("integrations", integration("MQ-QMGR", qmgr, "outbound", path, line, null, "MQI", null));
                }
            } else if (MQI.contains(name)) {
                mqCalls.add(name);
            } else if (name.equals("curl_easy_setopt") && args.size() >= 3 && txt(args.get(1)).equals("CURLOPT_URL")) {
                String url = str(args.get(2));
                url = url == null || url.isEmpty() ? "?" : url;
                String base = WebSvc.splitUrl(url)[0];
                endpoint(url, line, false);
                facts.add("web_services", WebSvc.called("REST", base != null && !base.isEmpty() ? base : TextUtil.stem(path), "libcurl", path,
                        line, url, null, null, null, null, null));
            } else if (name.startsWith("soap_call_") && name.contains("__")) {
                String rest = name.substring("soap_call_".length());
                int sep = rest.indexOf("__");
                String ns = sep < 0 ? rest : rest.substring(0, sep);
                String op = sep < 0 ? "" : rest.substring(sep + 2);
                String endpoint = args.size() > 1 ? str(args.get(1)) : null;
                boolean hasEndpoint = endpoint != null && !endpoint.isEmpty();
                if (hasEndpoint) {
                    endpoint(endpoint, line, true);
                }
                String svc = hasEndpoint && endpoint.contains("/")
                        ? TextUtil.afterLast(TextUtil.rstrip(endpoint, "/"), "/")
                        : !ns.isEmpty() ? ns : op;
                facts.add("web_services", WebSvc.called("SOAP", svc, "gSOAP", path, line, endpoint, op, null, null, null, null));
            } else if (RESOLVERS.contains(name) && !args.isEmpty()) {
                String host = str(!name.equals("inet_pton") ? args.getFirst() : args.size() > 1 ? args.get(1) : args.getFirst());
                if (host != null && !host.isEmpty()) {
                    hosts.add(Map.entry(host, line));
                }
            } else if (name.equals("htons") && !args.isEmpty() && args.getFirst().type.equals("number_literal")) {
                ports.add(txt(args.getFirst()));
            }
            if (SOCKET_CALLS.contains(name)) {
                socketCalls.add(name);
                if (name.equals("connect") || name.equals("listen")) {
                    calls.put("__" + name + "_line", line);
                }
            }
        }

        private void endpoint(String url, int line, boolean soap) {
            String[] c = Integrations.classifyUrl(url, soap);
            if (c != null) {
                facts.add("integrations", integration(c[0], c[1], "outbound", path, line, null, soap ? "gSOAP" : "libcurl", null));
            }
        }

        private void command(String cmd, int line) {
            List<String> first = TextUtil.words(TextUtil.strip(cmd));
            if (first.isEmpty()) {
                return;
            }
            String prog = TextUtil.basename(first.getFirst());
            if (prog.toLowerCase(Locale.ROOT).equals("cftutil")) {
                Doc integ = CftAnalyzer.cftutilIntegration(String.join(" ", first.subList(1, first.size())), path, line);
                if (integ != null) {
                    facts.add("integrations", integ);
                }
                return;
            }
            if (SHELLS.contains(prog) && first.size() > 1) {
                prog = TextUtil.basename(first.get(1));
            }
            if (!prog.isEmpty() && !prog.startsWith("$") && !prog.startsWith("%")) {
                facts.ref("invokes", "file:" + path, path, prog, List.of("file-name", "c-executable"), path, line)
                        .reportUnresolved(prog.endsWith(".sh") || prog.endsWith(".ksh") || prog.endsWith(".pl"));
            }
        }

        void finish() {
            functions.forEach((name, line) -> facts.symbol("c-function", name, path, line));
            Map<String, Integer> sorted = new TreeMap<>(SqlTools.PyJson_ORDER);
            sorted.putAll(calls);
            sorted.forEach((name, line) -> {
                if (name.startsWith("__") || functions.containsKey(name) || LIBC.contains(name) || MQI.contains(name)) {
                    return;
                }
                // Calls to functions defined in another scanned file become edges; library calls
                // simply stay unresolved and are not reported.
                facts.ref("calls", "file:" + path, path, name, List.of("c-function"), path, line).reportUnresolved(false);
            });
            if (!queues.isEmpty()) {
                boolean produce = mqCalls.stream().anyMatch(MQ_PRODUCE::contains);
                boolean consume = mqCalls.stream().anyMatch(MQ_CONSUME::contains);
                String role = produce && !consume ? "producer" : consume && !produce ? "consumer" : null;
                for (var q : queues) {
                    facts.add("integrations", integration("MQ", q.getKey(), "unknown", path, q.getValue(), role, "MQI", null));
                }
            }
            if (socketCalls.contains("connect")) {
                Integer cline = calls.get("__connect_line");
                String port = ports.isEmpty() ? "?" : ports.getFirst();
                if (hosts.isEmpty()) {
                    facts.add("integrations", integration("SOCKET", "?:" + port, "outbound", path, cline));
                }
                for (var h : hosts) {
                    facts.add("integrations", integration("SOCKET", h.getKey() + ":" + port, "outbound", path, h.getValue()));
                }
            }
            if (socketCalls.contains("listen")) {
                String port = ports.isEmpty() ? "?" : ports.getLast();
                facts.add("integrations", integration("SOCKET", "*:" + port, "inbound", path, calls.get("__listen_line")));
            }
            if (functions.containsKey("main") || functions.containsKey("WinMain")) {
                Integer mline = functions.containsKey("main") ? functions.get("main") : functions.get("WinMain");
                String program = TextUtil.stem(path);
                String sid = Fact.makeId("service", "c-executable", path, program);
                facts.add("services", Doc.of(Schemas.SERVICE,
                        "id", sid,
                        "name", program,
                        "kind", "c-executable",
                        "file", path,
                        "handler", path + "#main",
                        "evidence", new ArrayList<>(List.of(new Evidence(path, mline, null)))));
                facts.add("entry_points", Doc.of(Schemas.ENTRY_POINT,
                        "id", Fact.makeId("entry", path, program, "main"),
                        "name", program,
                        "kind", "main",
                        "file", path,
                        "line", mline,
                        "evidence", new ArrayList<>(List.of(new Evidence(path, mline, null)))));
                facts.symbol("c-executable", program, path, mline).owner(sid);
                facts.symbol("c-main", program, path, mline).owner(sid);
            }
        }
    }
}
