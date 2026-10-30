package com.legacyaudit.reports;

import com.legacyaudit.engine.analyzers.TextUtil;
import com.legacyaudit.engine.components.Kinds;
import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.engine.rules.Py;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.json.PyJson;
import com.legacyaudit.reports.jinja.Values;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Screen flows and batch flows built from the scan facts alone.
 *
 * <p>A <b>screen flow</b> follows one screen: where the user comes from, the services it calls
 * (the ones that load it and the ones its forms and links submit to), the tables those services
 * read and write (create, read, update, delete, from the SQL each service reaches), the external
 * systems they call, and the screens the user goes to next.
 *
 * <p>A <b>batch flow</b> follows one batch: an Autosys box with its jobs, or a job that runs on
 * its own (Autosys, cron, JCL, Spring Batch). It shows what starts it (schedule, condition,
 * watched file), its steps in order and what each runs, the tables and files it reads and writes,
 * what runs before and after it, and the failure handling found in the scheduler definition.
 *
 * <p>Both are deterministic: every node and arrow is a fact, with evidence. The functional report
 * adds the Copilot agent's plain-language description on top ({@code enrichment/functional.json});
 * the technical report shows them as they are. {@code columns} and {@code edges} feed the deck,
 * {@code mermaid} feeds the HTML pages.
 */
public final class Flows {

    private Flows() {}

    static final int MAX_PER_COLUMN = 12;
    static final String CRUD_ORDER = "CRUD";
    static final Map<String, String> SQL_CRUD = Map.of(
            "SELECT", "R", "INSERT", "C", "UPDATE", "U", "DELETE", "D", "MERGE", "CU", "TRUNCATE", "D", "UPSERT", "CU");
    static final Map<String, String> CRUD_WORDS = Map.of("C", "creates", "R", "reads", "U", "updates", "D", "deletes");
    /** Node kind to Mermaid class and the colour token the deck uses. */
    public static final Map<String, String> KIND_STYLE = Map.of(
            "screen", "screen", "from", "muted", "next", "muted", "service", "service", "table", "table", "system", "system",
            "trigger", "trigger", "job", "job", "run", "service", "file", "system");
    static final String MERMAID_CLASSES = "  classDef screen fill:#dbe9fb,stroke:#2a78d6,color:#0b0b0b\n"
            + "  classDef service fill:#ece3fb,stroke:#7c4dcc,color:#0b0b0b\n"
            + "  classDef table fill:#dcf3dc,stroke:#0ca30c,color:#0b0b0b\n"
            + "  classDef system fill:#fde7d9,stroke:#ec835a,color:#0b0b0b\n"
            + "  classDef trigger fill:#fff1cc,stroke:#d69a00,color:#0b0b0b\n"
            + "  classDef job fill:#d8f1f0,stroke:#13867f,color:#0b0b0b\n"
            + "  classDef muted fill:#f0efec,stroke:#898781,color:#0b0b0b";
    private static final Map<String, String> SCHEDULER_WORD = Map.of(
            "autosys", "Autosys job", "cron", "Cron job", "jcl", "JCL job", "spring-batch", "Spring Batch job",
            "node-cron", "Node.js scheduled job");
    private static final List<String> DAYS = List.of("mo", "tu", "we", "th", "fr", "sa", "su");
    public static final List<String> DAY_NAMES = List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun");

    public record Node(String key, String label, String kind, String detail) {
        Node(String key, String label, String kind) {
            this(key, label, kind, "");
        }
    }

    /** One column of a flow: its title and nodes. */
    public record Col(String title, List<Node> nodes) {}

    /** One arrow: from key, to key, label. */
    public record Edge(String from, String to, String label) {}

    /**
     * A screen or batch flow. Components keep the Python names the templates read
     * ({@code on_failure}).
     */
    public record Flow(
            String id,
            String kind,
            String name,
            String slug,
            String component,
            String summary,
            List<Col> columns,
            List<Edge> edges,
            List<Map<String, Object>> tables,
            List<Map<String, Object>> systems,
            List<Map<String, Object>> services,
            List<Map<String, Object>> steps,
            List<String> before,
            List<String> after,
            Map<String, Object> schedule,
            List<String> on_failure,
            Values.Tuple evidence,
            String note,
            List<String> files) {

        public String mermaid() {
            return toMermaid(columns, edges);
        }

        /** The flow as a deck block: columns of nodes and the arrows between them. */
        public Map<String, Object> deckBlock() {
            List<Object> cols = new ArrayList<>();
            for (Col c : columns) {
                List<Object> nodes = new ArrayList<>();
                for (Node n : c.nodes()) {
                    nodes.add(D.of("key", n.key(), "label", n.label(), "kind", KIND_STYLE.getOrDefault(n.kind(), n.kind())));
                }
                cols.add(D.of("title", c.title(), "nodes", nodes));
            }
            List<Object> es = new ArrayList<>();
            for (Edge e : edges) {
                es.add(D.of("from", e.from(), "to", e.to(), "label", e.label()));
            }
            return D.of("type", "flow", "title", name, "columns", cols, "edges", es, "mermaid", mermaid());
        }
    }

    public static String toMermaid(List<Col> columns, List<Edge> edges) {
        Map<String, String> ids = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>(List.of("flowchart LR"));
        for (Col c : columns) {
            for (Node n : c.nodes()) {
                ids.put(n.key(), "n" + ids.size());
                String shown = n.kind().equals("service") || n.kind().equals("screen") ? n.detail() : "";
                String text = Diagrams.label(n.label() + (shown.isEmpty() ? "" : " · " + shown));
                String shape = switch (n.kind()) {
                    case "table" -> "[(" + text + ")]";
                    case "system" -> "([" + text + "])";
                    case "trigger" -> "{{" + text + "}}";
                    default -> "[" + text + "]";
                };
                lines.add("  " + ids.get(n.key()) + shape + ":::" + KIND_STYLE.getOrDefault(n.kind(), "muted"));
            }
        }
        for (Edge e : edges) {
            if (ids.containsKey(e.from()) && ids.containsKey(e.to())) {
                String arrow = e.label().isEmpty() ? "-->" : "-->|" + Diagrams.label(e.label()) + "|";
                lines.add("  " + ids.get(e.from()) + " " + arrow + " " + ids.get(e.to()));
            }
        }
        lines.add(MERMAID_CLASSES);
        return String.join("\n", lines);
    }

    static String crud(Collection<String> letters) {
        StringBuilder sb = new StringBuilder();
        for (char c : CRUD_ORDER.toCharArray()) {
            if (letters.contains(String.valueOf(c))) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String crudWords(String letters) {
        List<String> words = new ArrayList<>();
        for (char c : letters.toCharArray()) {
            String w = CRUD_WORDS.get(String.valueOf(c));
            if (w != null) {
                words.add(w);
            }
        }
        if (words.size() > 1) {
            return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.getLast();
        }
        return words.isEmpty() ? "uses" : words.get(0);
    }

    private static Set<String> letters(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (char c : s.toCharArray()) {
            out.add(String.valueOf(c));
        }
        return out;
    }

    private static int cmp(String a, String b) {
        return PyJson.compareCodePoints(a, b);
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    /** Lookups shared by both kinds of flow. */
    static final class Index {
        final RunData rd;
        final Map<String, Map<String, Object>> services = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> screens = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> sql = new LinkedHashMap<>();
        final Map<String, List<String>> servicesByFile = new LinkedHashMap<>();
        /** SQL id to table to CRUD letters, from the linker's CRUD on each table. */
        final Map<String, Map<String, Set<String>>> sqlOps = new LinkedHashMap<>();
        final Map<String, String> tableIds = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> integrations = new LinkedHashMap<>();
        final Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        final Map<String, List<Map<String, Object>>> into = new LinkedHashMap<>();

        Index(RunData rd) {
            this.rd = rd;
            for (Map<String, Object> s : rd.items("services")) {
                services.put(D.str(s, "id"), s);
            }
            for (Map<String, Object> s : rd.items("screens")) {
                screens.put(D.str(s, "id"), s);
            }
            for (Map<String, Object> s : rd.items("sql_statements")) {
                sql.put(D.str(s, "id"), s);
            }
            for (Map<String, Object> s : rd.items("services")) {
                if (D.t(s, "file") && !"batch-job".equals(D.get(s, "kind"))) {
                    servicesByFile.computeIfAbsent(D.str(s, "file"), k -> new ArrayList<>()).add(D.str(s, "id"));
                }
            }
            for (Map<String, Object> o : rd.items("db_objects")) {
                Object t = D.get(o, "object_type");
                if (t != null && !"table".equals(t) && !"view".equals(t)) {
                    continue;
                }
                String name = D.str(o, "name").toUpperCase(java.util.Locale.ROOT);
                tableIds.putIfAbsent(name, D.str(o, "id"));
                for (Map.Entry<String, Object> e : D.map(o, "crud").entrySet()) {
                    for (Object ref : e.getValue() instanceof List<?> l ? l : List.of()) {
                        sqlOps.computeIfAbsent(Py.str(ref), k -> new LinkedHashMap<>())
                                .computeIfAbsent(name, k -> new LinkedHashSet<>()).add(e.getKey());
                    }
                }
            }
            for (Map<String, Object> i : rd.items("integrations")) {
                integrations.put(D.str(i, "type") + " " + D.str(i, "target"), i);
            }
            for (Map<String, Object> e : rd.items("dependencies")) {
                if (Py.truthy(D.get(e, "resolved", true))) {
                    out.computeIfAbsent(D.str(e, "source"), k -> new ArrayList<>()).add(e);
                    into.computeIfAbsent(D.str(e, "target"), k -> new ArrayList<>()).add(e);
                }
            }
        }

        /**
         * Services behind a node: itself when it is a service, else the services defined in the
         * file (a COBOL program that sends a BMS map).
         */
        List<String> owners(String node) {
            if (services.containsKey(node)) {
                return List.of(node);
            }
            Map<String, Object> item = rd.byId.getOrDefault(node, Map.of());
            if (!"source".equals(D.get(item, "category"))) { // web.xml, struts-config.xml: not the caller
                return List.of();
            }
            return new ArrayList<>(servicesByFile.getOrDefault(D.s(item, "path"), List.of()));
        }

        Map<String, Set<String>> tablesOf(List<String> sids) {
            Map<String, Set<String>> res = new LinkedHashMap<>();
            for (String sid : sids) {
                Map<String, Object> svc = services.getOrDefault(sid, Map.of());
                Set<String> wanted = new LinkedHashSet<>();
                for (String t : D.strings(svc, "tables")) {
                    wanted.add(t.toUpperCase(java.util.Locale.ROOT));
                }
                for (String q : D.strings(svc, "sql")) {
                    Map<String, Set<String>> ops = sqlOps.get(q);
                    Map<String, Object> st = sql.getOrDefault(q, Map.of());
                    if (ops != null && !ops.isEmpty()) {
                        ops.forEach((t, ls) -> {
                            if (wanted.contains(t)) {
                                res.computeIfAbsent(t, k -> new LinkedHashSet<>()).addAll(ls);
                            }
                        });
                    } else {
                        Set<String> ls = letters(SQL_CRUD.getOrDefault(Py.str(D.or(D.get(st, "type"), "")).toUpperCase(java.util.Locale.ROOT), ""));
                        for (String t : D.strings(st, "tables")) {
                            String up = t.toUpperCase(java.util.Locale.ROOT);
                            if (wanted.contains(up) && !ls.isEmpty()) {
                                res.computeIfAbsent(up, k -> new LinkedHashSet<>()).addAll(ls);
                            }
                        }
                    }
                }
                for (String t : wanted) {
                    res.putIfAbsent(t, new LinkedHashSet<>());
                }
            }
            return res;
        }

        List<Map<String, Object>> systemsOf(List<String> sids) {
            Map<String, Map<String, Object>> seen = new LinkedHashMap<>();
            for (String sid : sids) {
                for (String call : D.strings(services.getOrDefault(sid, Map.of()), "external_calls")) {
                    if (call.startsWith("DB ")) {
                        continue; // the database is shown as its tables
                    }
                    Map<String, Object> it = integrations.get(call);
                    int sp = call.indexOf(' ');
                    String typ = sp < 0 ? call : call.substring(0, sp);
                    String target = sp < 0 ? "" : call.substring(sp + 1);
                    seen.putIfAbsent(call, D.of("type", typ, "target", target,
                            "direction", D.t(it, "direction") ? D.str(it, "direction") : "unknown", "id", D.get(it, "id")));
                }
            }
            return sortSystems(seen.values());
        }

        List<Map<String, Object>> tableRows(Map<String, Set<String>> tables) {
            List<String> names = new ArrayList<>(tables.keySet());
            names.sort(Flows::cmp);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String t : names) {
                rows.add(D.of("name", t, "crud", crud(tables.get(t)), "id", tableIds.get(t)));
            }
            return rows;
        }
    }

    static List<Map<String, Object>> sortSystems(Collection<Map<String, Object>> systems) {
        List<Map<String, Object>> out = new ArrayList<>(systems);
        out.sort((a, b) -> {
            int c = cmp(D.str(a, "type"), D.str(b, "type"));
            return c != 0 ? c : cmp(D.str(a, "target"), D.str(b, "target"));
        });
        return out;
    }

    private static String serviceLabel(Map<String, Object> svc) {
        String name = D.s(svc, "name");
        int n = name.codePointCount(0, name.length());
        return n <= 48 ? name : "…" + name.substring(name.offsetByCodePoints(0, n - 47));
    }

    private static String screenLabel(RunData rd, String sid) {
        return D.last(rd.label(sid), "/");
    }

    private static String systemLabel(Map<String, Object> s) {
        String t = D.str(s, "target");
        int n = t.codePointCount(0, t.length());
        String target = n <= 40 ? t : "…" + t.substring(t.offsetByCodePoints(0, n - 39));
        return D.str(s, "type") + " " + target;
    }

    private static List<Node> cap(List<Node> nodes, List<String> note, String what) {
        if (nodes.size() > MAX_PER_COLUMN) {
            note.add("Showing " + MAX_PER_COLUMN + " of " + nodes.size() + " " + what + ".");
            return new ArrayList<>(nodes.subList(0, MAX_PER_COLUMN));
        }
        return nodes;
    }

    private static boolean hasKey(List<Node> nodes, String key) {
        for (Node n : nodes) {
            if (n.key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static List<Col> nonEmpty(List<Col> cols) {
        List<Col> out = new ArrayList<>();
        for (Col c : cols) {
            if (!c.nodes().isEmpty()) {
                out.add(c);
            }
        }
        return out;
    }

    // ---- screens --------------------------------------------------------------------------------

    public static List<Flow> screenFlows(RunData rd) {
        Index ix = new Index(rd);
        Map<String, Map<String, Object>> real = new LinkedHashMap<>();
        ix.screens.forEach((sid, s) -> {
            if (!D.t(s, "partial")) {
                real.put(sid, s);
            }
        });

        class Screens {
            List<String> ofService(String sid, int depth) {
                List<String> out = new ArrayList<>(D.strings(ix.services.getOrDefault(sid, Map.of()), "screens"));
                if (depth < 1) { // a service forwarding to another service (Struts action chaining)
                    for (Map<String, Object> e : ix.out.getOrDefault(sid, List.of())) {
                        if ("forwards".equals(D.get(e, "kind")) && ix.services.containsKey(D.str(e, "target"))) {
                            out.addAll(ofService(D.str(e, "target"), depth + 1));
                        }
                    }
                }
                List<String> res = new ArrayList<>();
                for (String s : D.unique(out)) {
                    if (real.containsKey(s)) {
                        res.add(s);
                    }
                }
                return res;
            }
        }
        Screens scr = new Screens();

        Map<String, List<List<String>>> actions = new LinkedHashMap<>(); // screen -> [(service, how)]
        Map<String, List<String>> loaders = new LinkedHashMap<>();
        Map<String, List<String>> nexts = new LinkedHashMap<>();
        for (String sid : real.keySet()) {
            List<List<String>> acts = new ArrayList<>();
            List<String> nxt = new ArrayList<>();
            for (Map<String, Object> e : ix.out.getOrDefault(sid, List.of())) {
                String kind = D.str(e, "kind");
                String target = D.str(e, "target");
                if (kind.equals("submits") || kind.equals("links")) {
                    if (ix.services.containsKey(target)) {
                        acts.add(List.of(target, kind.equals("submits") ? "submits" : "opens"));
                    } else if (real.containsKey(target) && !target.equals(sid)) {
                        nxt.add(target);
                    }
                }
            }
            List<String> load = new ArrayList<>();
            for (Map<String, Object> e : ix.into.getOrDefault(sid, List.of())) {
                if (List.of("renders", "forwards", "reads", "sends").contains(D.str(e, "kind"))) {
                    load.addAll(ix.owners(D.str(e, "source")));
                }
            }
            ix.services.forEach((svcId, svc) -> {
                if (D.strings(svc, "screens").contains(sid)) {
                    load.add(svcId);
                }
            });
            Set<String> acted = new HashSet<>();
            acts.forEach(a -> acted.add(a.get(0)));
            List<String> loadOut = new ArrayList<>();
            for (String s : D.unique(load)) {
                if (!acted.contains(s)) {
                    loadOut.add(s);
                }
            }
            for (List<String> a : acts) {
                for (String s : scr.ofService(a.get(0), 0)) {
                    if (!s.equals(sid)) {
                        nxt.add(s);
                    }
                }
            }
            actions.put(sid, D.unique(acts));
            loaders.put(sid, loadOut);
            nexts.put(sid, D.unique(nxt));
        }
        Map<String, List<String>> comes = new LinkedHashMap<>();
        nexts.forEach((sid, nxt) -> {
            for (String n : nxt) {
                comes.computeIfAbsent(n, k -> new ArrayList<>()).add(sid);
            }
        });

        List<String> order = new ArrayList<>(real.keySet());
        order.sort(Comparator.comparing((String s) -> screenLabel(rd, s).toLowerCase(java.util.Locale.ROOT), Flows::cmp));
        List<Flow> flows = new ArrayList<>();
        for (String sid : order) {
            Map<String, Object> scrItem = real.get(sid);
            List<String> note = new ArrayList<>();
            List<String> svcIds = new ArrayList<>(loaders.get(sid));
            actions.get(sid).forEach(a -> svcIds.add(a.get(0)));
            Map<String, Set<String>> tables = ix.tablesOf(svcIds);
            List<Map<String, Object>> systems = ix.systemsOf(svcIds);
            Node me = new Node(sid, screenLabel(rd, sid), "screen", D.s(scrItem, "technology"));
            List<Node> fromNodes = new ArrayList<>();
            for (String s : comes.getOrDefault(sid, List.of())) {
                fromNodes.add(new Node("from:" + s, screenLabel(rd, s), "from"));
            }
            fromNodes = cap(fromNodes, note, "previous screens");
            List<Node> svcNodes = new ArrayList<>();
            for (String s : loaders.get(sid)) {
                svcNodes.add(new Node(s, serviceLabel(ix.services.get(s)), "service", "loads the screen"));
            }
            for (List<String> a : actions.get(sid)) {
                svcNodes.add(new Node(a.get(0), serviceLabel(ix.services.get(a.get(0))), "service", a.get(1)));
            }
            svcNodes = cap(svcNodes, note, "services");
            List<Map<String, Object>> tableRows = ix.tableRows(tables);
            List<Node> tableNodes = new ArrayList<>();
            for (Map<String, Object> t : tableRows) {
                tableNodes.add(new Node("t:" + t.get("name"), D.str(t, "name"), "table", D.str(t, "crud")));
            }
            tableNodes = cap(tableNodes, note, "tables");
            List<Node> sysNodes = new ArrayList<>();
            for (Map<String, Object> s : systems) {
                sysNodes.add(new Node("x:" + s.get("type") + s.get("target"), systemLabel(s), "system"));
            }
            sysNodes = cap(sysNodes, note, "systems");
            List<Node> nextNodes = new ArrayList<>();
            for (String s : nexts.get(sid)) {
                nextNodes.add(new Node("next:" + s, screenLabel(rd, s), "next"));
            }
            nextNodes = cap(nextNodes, note, "next screens");
            List<Edge> edges = new ArrayList<>();
            for (String s : comes.getOrDefault(sid, List.of())) {
                edges.add(new Edge("from:" + s, sid, ""));
            }
            for (String s : loaders.get(sid)) {
                edges.add(new Edge(sid, s, "loaded by"));
            }
            for (List<String> a : actions.get(sid)) {
                edges.add(new Edge(sid, a.get(0), a.get(1)));
            }
            Set<String> shownSvcs = new HashSet<>();
            svcNodes.forEach(n -> shownSvcs.add(n.key()));
            for (String s : svcIds) {
                if (!shownSvcs.contains(s)) {
                    continue;
                }
                ix.tablesOf(List.of(s)).forEach((t, ls) -> edges.add(new Edge(s, "t:" + t, crud(ls))));
                for (Map<String, Object> x : ix.systemsOf(List.of(s))) {
                    edges.add(new Edge(s, "x:" + x.get("type") + x.get("target"), ""));
                }
                for (String n : scr.ofService(s, 0)) {
                    if (!n.equals(sid) && nexts.get(sid).contains(n)) {
                        edges.add(new Edge(s, "next:" + n, "shows"));
                    }
                }
            }
            for (String n : nexts.get(sid)) {
                if (edges.stream().noneMatch(e -> e.to().equals("next:" + n))) {
                    edges.add(new Edge(sid, "next:" + n, "link"));
                }
            }
            List<Col> columns = nonEmpty(List.of(new Col("Comes from", fromNodes), new Col("Screen", List.of(me)),
                    new Col("Services", svcNodes), new Col("Tables", tableNodes), new Col("External systems", sysNodes),
                    new Col("Goes to", nextNodes)));
            int nTables = tableRows.size();
            String summary = "The screen " + me.label() + " uses " + svcIds.size() + " service(s)"
                    + (nTables > 0 ? " and touches " + nTables + " table(s)" : " and touches no table found by the scan")
                    + (systems.isEmpty() ? "" : "; it calls " + systems.size() + " external system(s)") + ".";
            Map<String, String> how = new LinkedHashMap<>();
            actions.get(sid).forEach(a -> how.put(a.get(0), a.get(1)));
            List<Map<String, Object>> services = new ArrayList<>();
            for (String s : svcIds) {
                Map<String, Object> svc = ix.services.get(s);
                services.add(D.of("id", s, "name", D.get(svc, "name"), "kind", D.get(svc, "kind"),
                        "role", loaders.get(sid).contains(s) ? "loads the screen" : how.get(s), "url", D.get(svc, "url"),
                        "handler", D.get(svc, "handler")));
            }
            List<String> before = new ArrayList<>();
            comes.getOrDefault(sid, List.of()).forEach(s -> before.add(screenLabel(rd, s)));
            List<String> after = new ArrayList<>();
            nexts.get(sid).forEach(s -> after.add(screenLabel(rd, s)));
            flows.add(new Flow(sid, "screen", me.label(), RunData.slug("screen-" + me.label() + "-" + tail(sid, 6)),
                    D.sOrNull(scrItem, "component"), summary, columns, edges, tableRows, systems, services, new ArrayList<>(),
                    before, after, new LinkedHashMap<>(), new ArrayList<>(), rd.firstEvidence(scrItem), String.join(" ", note),
                    new ArrayList<>()));
        }
        return flows;
    }

    // ---- batch ----------------------------------------------------------------------------------

    /** Members in run order: predecessors first, then by name. */
    private static List<Map<String, Object>> ordered(List<Map<String, Object>> members) {
        Set<String> names = new HashSet<>();
        members.forEach(m -> names.add(D.str(m, "name")));
        List<Map<String, Object>> done = new ArrayList<>();
        List<Map<String, Object>> left = new ArrayList<>(members);
        left.sort((a, b) -> cmp(D.str(a, "name"), D.str(b, "name")));
        while (!left.isEmpty()) {
            Set<String> placed = new HashSet<>();
            done.forEach(d -> placed.add(D.str(d, "name")));
            Map<String, Object> nxt = null;
            for (Map<String, Object> m : left) {
                boolean ready = true;
                for (String p : D.strings(m, "predecessors")) {
                    if (!placed.contains(p) && names.contains(p)) {
                        ready = false;
                        break;
                    }
                }
                if (ready) {
                    nxt = m;
                    break;
                }
            }
            if (nxt == null) {
                nxt = left.get(0);
            }
            done.add(nxt);
            left.remove(nxt);
        }
        return done;
    }

    /**
     * Days of the week (0 = Monday), start times and a plain sentence, from a cron line or the
     * Autosys schedule attributes. Unknown shapes keep the raw text.
     */
    public static Map<String, Object> parseSchedule(String schedule, String scheduler) {
        Map<String, Object> out = D.of("days", new ArrayList<>(), "times", new ArrayList<>(), "text", "",
                "raw", schedule == null ? "" : schedule);
        if (schedule == null || schedule.isEmpty()) {
            out.put("text", "Not scheduled in the inputs provided (run on demand, or by a scheduler whose "
                    + "definitions were not provided).");
            return out;
        }
        if (scheduler.equals("node-cron") && schedule.startsWith("every ")) {
            out.put("text", "Runs " + schedule + ".");
            return out;
        }
        if (scheduler.equals("cron") || scheduler.equals("node-cron")) {
            List<String> parts = TextUtil.words(schedule);
            if (parts.size() >= 5) {
                String minute = parts.get(0);
                String hour = parts.get(1);
                String dom = parts.get(2);
                String mon = parts.get(3);
                String dow = parts.get(4);
                List<Long> days = cronDays(dow);
                List<String> times = cronTimes(minute, hour);
                out.put("days", days);
                out.put("times", times);
                String when = daysText(days);
                if (!dom.equals("*")) {
                    when = "on day " + dom + " of " + (mon.equals("*") ? "every month" : "month " + mon);
                    out.put("monthly", dom);
                }
                out.put("text", "Runs " + when + (times.isEmpty() ? "" : " at " + String.join(", ", times)) + ".");
                return out;
            }
        }
        if (scheduler.equals("autosys")) {
            Map<String, String> attrs = new LinkedHashMap<>();
            for (String p : schedule.split(";", -1)) {
                int c = p.indexOf(':');
                String k = TextUtil.strip(c < 0 ? p : p.substring(0, c));
                String v = TextUtil.strip(c < 0 ? "" : p.substring(c + 1));
                if (!k.isEmpty()) {
                    attrs.put(k, v);
                }
            }
            List<String> given = new ArrayList<>();
            for (String x : attrs.getOrDefault("days_of_week", "").split(",", -1)) {
                given.add(Rx.head(TextUtil.strip(x), 2).toLowerCase(java.util.Locale.ROOT));
            }
            List<Long> days = new ArrayList<>();
            for (int i = 0; i < DAYS.size(); i++) {
                if (given.contains(DAYS.get(i))) {
                    days.add((long) i);
                }
            }
            if (TextUtil.strip(attrs.getOrDefault("days_of_week", "")).toLowerCase(java.util.Locale.ROOT).equals("all")) {
                days = new ArrayList<>(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L));
            }
            List<String> times = new ArrayList<>();
            for (String t : attrs.getOrDefault("start_times", "").split(",", -1)) {
                if (!TextUtil.strip(t).isEmpty()) {
                    times.add(TextUtil.strip(TextUtil.strip(t), "\""));
                }
            }
            if (!attrs.getOrDefault("start_mins", "").isEmpty()) {
                times = new ArrayList<>(List.of("every hour at minute " + attrs.get("start_mins")));
            }
            out.put("days", days);
            out.put("times", times);
            String when = days.isEmpty() ? "" : daysText(days);
            if (!attrs.getOrDefault("run_calendar", "").isEmpty()) {
                when = "on the days of calendar " + attrs.get("run_calendar");
                out.put("calendar", attrs.get("run_calendar"));
            }
            String text = "Runs " + (when.isEmpty() ? "when started" : when) + (times.isEmpty() ? "" : " at " + String.join(", ", times));
            if (!attrs.getOrDefault("exclude_calendar", "").isEmpty()) {
                text += ", except calendar " + attrs.get("exclude_calendar");
            }
            out.put("text", text + ".");
            return out;
        }
        out.put("text", "Schedule: " + schedule + ".");
        return out;
    }

    private static Long pyInt(String s) {
        String t = TextUtil.strip(s);
        if (!t.matches("[+-]?\\d+")) {
            return null;
        }
        return Long.parseLong(t.startsWith("+") ? t.substring(1) : t);
    }

    private static List<Long> cronDays(String dow) {
        if (dow.equals("*") || dow.equals("?")) {
            return new ArrayList<>(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L));
        }
        Set<Long> days = new TreeSet<>();
        List<String> names = new ArrayList<>();
        DAY_NAMES.forEach(d -> names.add(d.substring(0, 3).toLowerCase(java.util.Locale.ROOT))); // Monday first
        for (int i = 0; i < names.size(); i++) { // MON-FRI -> 1-5 (cron numbers, Sunday = 0)
            dow = Rx.of("\\b" + names.get(i) + "\\b", Rx.I).matcher(dow).replaceAll(String.valueOf((i + 1) % 7));
        }
        for (String part : dow.split(",", -1)) {
            String rng = part.split("/", -1)[0];
            List<Long> vals = new ArrayList<>();
            boolean bad = false;
            if (rng.contains("-")) {
                int k = rng.indexOf('-');
                Long a = pyInt(rng.substring(0, k));
                Long b = pyInt(rng.substring(k + 1));
                if (a == null || b == null) {
                    bad = true;
                } else {
                    for (long v = a; v <= b; v++) {
                        vals.add(v);
                    }
                }
            } else {
                Long a = pyInt(rng);
                if (a == null) {
                    bad = true;
                } else {
                    vals.add(a);
                }
            }
            if (bad) {
                String head = Rx.head(rng, 3).toLowerCase(java.util.Locale.ROOT);
                if (names.contains(head)) {
                    days.add((long) names.indexOf(head));
                }
                continue;
            }
            for (long v : vals) {
                days.add(Math.floorMod(v - 1, 7L)); // cron: 0 or 7 = Sunday, 1 = Monday
            }
        }
        return new ArrayList<>(days);
    }

    private static List<String> cronTimes(String minute, String hour) {
        if (hour.equals("*") && TextUtil.isDigits(minute)) {
            return new ArrayList<>(List.of("every hour at minute " + minute));
        }
        boolean allDigits = true;
        for (String h : hour.split(",", -1)) {
            allDigits &= TextUtil.isDigits(h);
        }
        if (allDigits && TextUtil.isDigits(minute)) {
            List<String> out = new ArrayList<>();
            for (String h : hour.split(",", -1)) {
                out.add(String.format("%02d:%02d", Long.parseLong(h), Long.parseLong(minute)));
            }
            return out;
        }
        return hour.equals("*") ? new ArrayList<>() : new ArrayList<>(List.of(hour + ":" + minute));
    }

    private static String daysText(List<Long> days) {
        if (days.size() == 7) {
            return "every day";
        }
        if (days.equals(List.of(0L, 1L, 2L, 3L, 4L))) {
            return "every weekday (Mon to Fri)";
        }
        if (days.equals(List.of(5L, 6L))) {
            return "at weekends";
        }
        if (days.isEmpty()) {
            return "on no fixed day";
        }
        List<String> names = new ArrayList<>();
        days.forEach(d -> names.add(DAY_NAMES.get(d.intValue())));
        return "every " + String.join(", ", names);
    }

    private static final Set<String> PROGRAM_SERVICES = Set.of("c-executable", "cobol-program");
    private static final Set<String> EVENT_ENTRY = Set.of("message-listener", "listener", "jms-listener", "mq-listener");
    private static final Set<String> PROGRAM_ENTRY = Set.of("main", "scheduler", "spring-batch", "windows-service");
    private static final Set<String> ONLINE = Set.of("web", "online-mainframe", "frontend", "api", "desktop");
    private static final String NO_SCHEDULE = "No schedule found: no Autosys JIL, JCL or crontab entry in the inputs starts it. "
            + "It may be run by hand or by a scheduler whose export was not provided.";
    private static final Map<String, String> UNIT_WORD = Map.of("script", "Script", "program", "Batch program", "listener", "Listener");
    private static final Pattern TYPE_NAME = Rx.of("\\b[A-Z][a-z]\\w+");

    /** Shared state of {@link #batchFlows}: what runs what. */
    private static final class Batch {
        final RunData rd;
        final Index ix;
        final Map<String, List<String>> runs = new LinkedHashMap<>();
        final Map<String, List<String>> callers = new LinkedHashMap<>();
        final Map<String, String> fileIds = new LinkedHashMap<>();
        final Map<String, List<String>> mains = new LinkedHashMap<>();
        final Map<List<String>, List<String>> byStem = new LinkedHashMap<>();

        Batch(RunData rd, Index ix) {
            this.rd = rd;
            this.ix = ix;
            for (Map<String, Object> e : rd.items("dependencies")) {
                if (Diagrams.RUN_EDGES.contains(D.str(e, "kind")) && Py.truthy(D.get(e, "resolved", true))) {
                    runs.computeIfAbsent(D.str(e, "source"), k -> new ArrayList<>()).add(D.str(e, "target"));
                }
            }
            for (Map<String, Object> f : rd.items("inventory")) {
                if (D.t(f, "id")) {
                    fileIds.put(D.str(f, "path"), D.str(f, "id"));
                }
            }
            runs.forEach((src, targets) -> targets.forEach(t -> callers.computeIfAbsent(t, k -> new ArrayList<>()).add(src)));
            for (Map<String, Object> ep : rd.items("entry_points")) {
                if (PROGRAM_ENTRY.contains(D.s(ep, "kind")) && D.t(ep, "component") && D.t(ep, "file")) {
                    mains.computeIfAbsent(D.str(ep, "component"), k -> new ArrayList<>()).add(D.str(ep, "file"));
                }
            }
            for (Map<String, Object> rec : rd.items("inventory")) {
                String path = D.str(rec, "path");
                if ("source".equals(D.get(rec, "category")) && rd.fileComponent.get(path) != null && !rd.fileComponent.get(path).isEmpty()) {
                    String stem = D.last(path, "/");
                    int dot = stem.indexOf('.');
                    stem = dot < 0 ? stem : stem.substring(0, dot);
                    byStem.computeIfAbsent(List.of(rd.fileComponent.get(path), stem), k -> new ArrayList<>()).add(path);
                }
            }
        }

        List<String> runsOf(String n) {
            return runs.getOrDefault(n, List.of());
        }

        /** (label, kind) of what a job runs, one hop on through scripts. */
        List<List<String>> runTargets(String jid) {
            List<List<String>> out = new ArrayList<>();
            for (String t : runsOf(jid)) {
                if (t.startsWith("batch_job:")) {
                    out.add(List.of(rd.label(t), "job"));
                    continue;
                }
                out.add(List.of(D.last(rd.label(t), "/"), "run"));
                for (String t2 : runsOf(t)) {
                    out.add(List.of(D.last(rd.label(t2), "/"), t2.startsWith("batch_job:") ? "job" : "run"));
                }
            }
            return D.unique(out);
        }

        /** Source files a job or script runs, two hops on (a script, then its programs). */
        List<String> filesRun(String start) {
            List<String> out = new ArrayList<>();
            List<String> frontier = List.of(start);
            for (int k = 0; k < 3; k++) {
                List<String> nxt = new ArrayList<>();
                for (String n : frontier) {
                    for (String t : runsOf(n)) {
                        if (t.startsWith("batch_job:")) {
                            continue;
                        }
                        Map<String, Object> item = rd.byId.getOrDefault(t, Map.of());
                        Object p = D.or(D.get(item, "path"), D.get(item, "file"));
                        if (Py.truthy(p)) {
                            out.add(Py.str(p));
                        }
                        nxt.add(t);
                        if (D.t(item, "file") && fileIds.containsKey(D.str(item, "file"))) {
                            nxt.add(fileIds.get(D.str(item, "file")));
                        }
                    }
                }
                frontier = nxt;
            }
            return D.unique(out);
        }

        /** Jobs that start this one, directly or through a script they run. */
        List<String> startedBy(String jid) {
            List<String> out = new ArrayList<>();
            for (String src : callers.getOrDefault(jid, List.of())) {
                if (src.startsWith("batch_job:")) {
                    out.add(rd.label(src));
                    continue;
                }
                for (String src2 : callers.getOrDefault(src, List.of())) {
                    if (src2.startsWith("batch_job:")) {
                        out.add(rd.label(src2) + " (via " + D.last(rd.label(src), "/") + ")");
                    }
                }
            }
            return D.unique(out);
        }

        /**
         * Files of the job's component named by what registers it: {@code InvoiceJobs} in
         * {@code RecurringJob.AddOrUpdate<InvoiceJobs>(...)} is {@code Jobs/InvoiceJobs.cs}.
         */
        List<String> namedFiles(Map<String, Object> j, Object file, Object line) {
            String comp = D.s(j, "component");
            if (comp.isEmpty()) {
                return List.of();
            }
            List<String> parts = new ArrayList<>();
            parts.add(D.s(j, "command"));
            parts.addAll(D.strings(j, "programs"));
            String text = String.join(" ", parts);
            Path path = Py.truthy(file) ? rd.absPath(Py.str(file)) : null;
            if (path != null && Py.truthy(line)) {
                try {
                    long n = (long) D.num(line);
                    List<String> lines = Comments.headLines(path, (int) n);
                    text += " " + (n >= 1 && n <= lines.size() ? lines.get((int) n - 1) : "");
                } catch (java.io.IOException | RuntimeException e) {
                    // unreadable: the command alone
                }
            }
            List<String> out = new ArrayList<>();
            Matcher m = TYPE_NAME.matcher(text);
            List<String> words = new ArrayList<>();
            while (m.find()) {
                words.add(m.group());
            }
            for (String word : D.unique(words)) { // type names
                for (String p : byStem.getOrDefault(List.of(comp, word), List.of())) {
                    if (!p.equals(Py.truthy(file) ? Py.str(file) : null)) {
                        out.add(p);
                    }
                }
            }
            return out;
        }

        /**
         * Source of a job that runs no file the scan linked (Hangfire, Quartz.NET, node-cron, Windows
         * Task Scheduler): the program's {@code main} in the job's component when the job runs an
         * executable, then the file that defines the job.
         */
        List<String> definedFiles(List<Map<String, Object>> members) {
            List<String> out = new ArrayList<>();
            for (Map<String, Object> j : members) {
                boolean runsExe = D.s(j, "command").toLowerCase(java.util.Locale.ROOT).contains(".exe");
                for (String p : D.strings(j, "programs")) {
                    runsExe |= p.toLowerCase(java.util.Locale.ROOT).endsWith(".exe");
                }
                if (runsExe && D.t(j, "component")) {
                    List<String> m = new ArrayList<>(mains.getOrDefault(D.str(j, "component"), List.of()));
                    m.sort(Flows::cmp);
                    out.addAll(m);
                }
                Values.Tuple fl = rd.firstEvidence(j);
                out.addAll(namedFiles(j, fl.get(0), fl.get(1)));
                if (Py.truthy(fl.get(0))) {
                    out.add(Py.str(fl.get(0)));
                }
            }
            return D.unique(out);
        }
    }

    public static List<Flow> batchFlows(RunData rd) {
        Index ix = new Index(rd);
        Batch bx = new Batch(rd, ix);
        List<Map<String, Object>> jobs = rd.items("batch_jobs");
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        jobs.forEach(j -> byName.put(D.str(j, "name"), j));
        Map<String, List<Map<String, Object>>> members = new LinkedHashMap<>();
        for (Map<String, Object> j : jobs) {
            if (D.t(j, "box") && byName.containsKey(D.str(j, "box"))) {
                members.computeIfAbsent(D.str(j, "box"), k -> new ArrayList<>()).add(j);
            }
        }
        Map<String, List<String>> successors = new LinkedHashMap<>();
        for (Map<String, Object> j : jobs) {
            for (String p : D.strings(j, "predecessors")) {
                successors.computeIfAbsent(p, k -> new ArrayList<>()).add(D.str(j, "name"));
            }
        }
        Map<String, List<String>> watched = watchedFiles(rd);
        Set<String> attached = new HashSet<>();

        List<Map<String, Object>> units = new ArrayList<>();
        for (Map<String, Object> j : jobs) {
            boolean member = D.t(j, "box") && byName.containsKey(D.str(j, "box"));
            if (!member && !"file-watcher".equals(D.get(j, "kind"))) {
                units.add(j);
            }
        }
        for (Map<String, Object> j : jobs) {
            boolean member = D.t(j, "box") && byName.containsKey(D.str(j, "box"));
            if ("file-watcher".equals(D.get(j, "kind")) && !member) {
                units.add(j);
            }
        }
        units.sort((a, b) -> {
            int c = cmp(D.s(a, "scheduler"), D.s(b, "scheduler"));
            return c != 0 ? c : cmp(D.str(a, "name"), D.str(b, "name"));
        });
        List<Flow> flows = new ArrayList<>();
        for (Map<String, Object> unit : units) {
            String unitName = D.str(unit, "name");
            List<String> note = new ArrayList<>();
            boolean isBox = "box".equals(D.get(unit, "kind"));
            List<Map<String, Object>> stepsSrc = isBox ? ordered(members.getOrDefault(unitName, List.of())) : List.of(unit);
            Map<String, Object> sched = parseSchedule(D.sOrNull(unit, "schedule"), D.s(unit, "scheduler"));
            List<String> starters = bx.startedBy(D.str(unit, "id"));
            if (!starters.isEmpty() && !D.t(unit, "schedule")) {
                sched.put("text", "Started by " + String.join(", ", starters) + ".");
                sched.put("started_by", starters);
            }
            List<Node> trig = new ArrayList<>();
            for (String who : starters) {
                trig.add(new Node("trigger:by:" + who, "started by " + who, "trigger"));
            }
            if (D.t(unit, "schedule")) {
                String text = Py.str(sched.get("text"));
                trig.add(new Node("trigger:schedule", text.endsWith(".") ? text.substring(0, text.length() - 1) : text, "trigger"));
            }
            for (String p : D.strings(unit, "predecessors")) {
                trig.add(new Node("trigger:after:" + p, "after " + p, "trigger"));
            }
            List<Node> stepNodes = new ArrayList<>();
            List<Node> runNodes = new ArrayList<>();
            List<Node> fileNodes = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();
            List<String> jobSids = new ArrayList<>();
            List<Map<String, Object>> steps = new ArrayList<>();
            List<String> failure = new ArrayList<>();
            if (isBox) {
                for (String f : D.strings(unit, "on_failure")) {
                    failure.add(unitName + ": " + failureWords(f));
                }
            }
            String prev = null;
            List<String> flowFiles = new ArrayList<>();
            int i = 0;
            for (Map<String, Object> j : stepsSrc) {
                i++;
                String jn = D.str(j, "name");
                flowFiles.addAll(bx.filesRun(D.str(j, "id")));
                String jkey = "job:" + jn;
                String kind = D.s(j, "kind").isEmpty() ? "job" : D.s(j, "kind");
                if (kind.equals("file-watcher")) {
                    List<String> files = watched.getOrDefault(D.str(j, "id"), List.of());
                    String label = files.isEmpty() ? "Wait for a file (" + jn + ")" : "Wait for file " + files.get(0);
                    stepNodes.add(new Node(jkey, label, "trigger", jn));
                    for (String f : files) {
                        String fkey = "file:" + f;
                        if (!hasKey(fileNodes, fkey)) {
                            fileNodes.add(new Node(fkey, "FILE " + f, "file", "in"));
                        }
                        edges.add(new Edge(fkey, jkey, "arrives"));
                    }
                } else {
                    stepNodes.add(new Node(jkey, jn, "job", D.s(j, "scheduler")));
                }
                Map<String, String> conds = new LinkedHashMap<>();
                for (String[] c : Diagrams.conditions(D.s(j, "condition"))) {
                    conds.put(c[0], conds.containsKey(c[0]) ? conds.get(c[0]) + " or " + c[1] : c[1]);
                }
                boolean linked = false;
                for (String p : D.strings(j, "predecessors")) {
                    if (stepsSrc.stream().anyMatch(s -> p.equals(D.str(s, "name")))) {
                        edges.add(new Edge("job:" + p, jkey, conds.getOrDefault(p, "")));
                        linked = true;
                        String c = conds.getOrDefault(p, "");
                        if (c.equals("failure")) {
                            failure.add("If " + p + " fails, " + jn + " runs.");
                        } else if (c.contains("failure")) {
                            failure.add(jn + " runs after " + p + " whether it succeeds or fails.");
                        }
                    }
                }
                if (!linked && prev != null && !isBox) {
                    edges.add(new Edge(prev, jkey, "then"));
                }
                if (i == 1 || !linked) {
                    for (Node tn : trig) {
                        if (tn.key().startsWith("trigger:schedule") || !isBox) {
                            edges.add(new Edge(tn.key(), jkey, ""));
                        }
                    }
                }
                List<List<String>> runTo = bx.runTargets(D.str(j, "id"));
                for (List<String> rt : runTo) {
                    String rkey = "run:" + rt.get(0);
                    if (!hasKey(runNodes, rkey)) {
                        runNodes.add(new Node(rkey, rt.get(0), rt.get(1)));
                    }
                    edges.add(new Edge(jkey, rkey, "runs"));
                }
                for (String step : D.strings(j, "steps")) {
                    String skey = "step:" + jn + ":" + step;
                    runNodes.add(new Node(skey, step, "run", "step"));
                    edges.add(new Edge(jkey, skey, "step"));
                }
                if (!D.t(j, "steps")) {
                    List<String> progs = D.strings(j, "programs");
                    for (String prog : progs.subList(0, Math.min(6, progs.size()))) {
                        String label = D.last(prog, ".");
                        String rkey = "run:" + label;
                        if (!hasKey(runNodes, rkey)) {
                            runNodes.add(new Node(rkey, label, "run", "program"));
                        }
                        edges.add(new Edge(jkey, rkey, "runs"));
                    }
                }
                for (String ds : D.strings(j, "datasets")) {
                    String fkey = "file:" + ds;
                    if (!hasKey(fileNodes, fkey)) {
                        fileNodes.add(new Node(fkey, ds, "file", "dataset"));
                    }
                    edges.add(new Edge(jkey, fkey, ""));
                }
                for (String f : D.strings(j, "on_failure")) {
                    failure.add(jn + ": " + failureWords(f));
                }
                String sid = Fact.makeId("service", "batch-job", D.str(j, "id"));
                if (ix.services.containsKey(sid)) {
                    jobSids.add(sid);
                }
                List<String> runLabels = new ArrayList<>();
                runTo.forEach(rt -> runLabels.add(rt.get(0)));
                steps.add(D.of("order", (long) i, "name", jn, "kind", kind, "id", D.str(j, "id"), "description", D.s(j, "description"),
                        "command", D.s(j, "command"), "condition", D.s(j, "condition"), "runs", runLabels,
                        "steps", new ArrayList<>(D.list(j, "steps")), "programs", new ArrayList<>(D.list(j, "programs")),
                        "datasets", new ArrayList<>(D.list(j, "datasets")), "machine", D.s(j, "machine"),
                        "service_id", ix.services.containsKey(sid) ? sid : null, "evidence", rd.firstEvidence(j)));
                prev = jkey;
            }
            if (flowFiles.isEmpty()) {
                flowFiles = bx.definedFiles(stepsSrc);
                attached.addAll(flowFiles);
            }
            Map<String, Set<String>> tables = ix.tablesOf(jobSids);
            List<Map<String, Object>> tableRows = ix.tableRows(tables);
            List<Map<String, Object>> systems = ix.systemsOf(jobSids);
            List<Node> tableNodes = new ArrayList<>();
            for (Map<String, Object> t : tableRows) {
                tableNodes.add(new Node("t:" + t.get("name"), D.str(t, "name"), "table", D.str(t, "crud")));
            }
            for (Map<String, Object> s : systems) {
                String key = "x:" + s.get("type") + s.get("target");
                if ("FILE".equals(s.get("type")) && hasKey(fileNodes, "file:" + s.get("target"))) {
                    continue;
                }
                fileNodes.add(new Node(key, systemLabel(s), "system", D.str(s, "direction")));
            }
            for (Map<String, Object> st : steps) {
                if (st.get("service_id") == null) {
                    continue;
                }
                String src = "job:" + st.get("name");
                String svc = Py.str(st.get("service_id"));
                ix.tablesOf(List.of(svc)).forEach((t, ls) -> edges.add(new Edge(src, "t:" + t, crud(ls))));
                for (Map<String, Object> x : ix.systemsOf(List.of(svc))) {
                    String key = "x:" + x.get("type") + x.get("target");
                    if ("inbound".equals(x.get("direction"))) {
                        edges.add(new Edge(key, src, "in"));
                    } else {
                        edges.add(new Edge(src, key, "outbound".equals(x.get("direction")) ? "out" : ""));
                    }
                }
            }
            Set<String> names = new HashSet<>();
            steps.forEach(s -> names.add(D.str(s, "name")));
            names.add(unitName);
            List<String> before = new ArrayList<>();
            for (String p : D.strings(unit, "predecessors")) {
                if (!names.contains(p)) {
                    before.add(p);
                }
            }
            Set<String> afterSet = new TreeSet<>(Flows::cmp);
            for (String s : names) {
                for (String n : successors.getOrDefault(s, List.of())) {
                    if (!names.contains(n) && byName.containsKey(n)) {
                        afterSet.add(n);
                    }
                }
            }
            List<Node> stepCol = cap(stepNodes, note, "steps");
            List<Node> runCol = cap(runNodes, note, "programs and steps");
            List<Node> tableCol = cap(tableNodes, note, "tables");
            List<Node> fileCol = cap(fileNodes, note, "files and systems");
            List<Col> columns = nonEmpty(List.of(new Col("Starts when", trig),
                    new Col(isBox || steps.size() > 1 ? "Steps" : "Job", stepCol), new Col("Runs", runCol),
                    new Col("Tables", tableCol), new Col("Files and systems", fileCol)));
            String kindWord = isBox ? "Box" : SCHEDULER_WORD.getOrDefault(D.s(unit, "scheduler"), "Job");
            String summary = kindWord + " " + unitName + (isBox ? " has " + steps.size() + " step(s). " : ". ") + sched.get("text")
                    + (tableRows.isEmpty() ? "" : " It touches " + tableRows.size() + " table(s).");
            String component = D.sOrNull(unit, "component");
            if (component == null) {
                for (Map<String, Object> s : stepsSrc) {
                    if (D.t(s, "component")) {
                        component = D.str(s, "component");
                        break;
                    }
                }
            }
            Map<String, Object> schedule = new LinkedHashMap<>(sched);
            schedule.put("scheduler", D.s(unit, "scheduler"));
            schedule.put("description", D.s(unit, "description"));
            List<Map<String, Object>> services = new ArrayList<>();
            for (String s : jobSids) {
                services.add(D.of("id", s, "name", D.get(ix.services.get(s), "name")));
            }
            flows.add(new Flow(D.str(unit, "id"), "batch", unitName, RunData.slug("batch-" + unitName + "-" + tail(D.str(unit, "id"), 6)),
                    component, summary, columns, edges, tableRows, systems, services, steps, before, new ArrayList<>(afterSet), schedule,
                    D.unique(failure), rd.firstEvidence(unit), String.join(" ", note), D.unique(flowFiles)));
        }
        flows.addAll(unscheduledFlows(rd, ix, bx, jobs, attached));
        return flows;
    }

    // ---- batch work that no scheduler definition starts -----------------------------------------

    /**
     * Batch work the scan found but no scheduler definition covers (decisions 102 and 116): one
     * flow per entry point of a batch component, or one per component; listeners on their own.
     */
    private static List<Flow> unscheduledFlows(RunData rd, Index ix, Batch bx, List<Map<String, Object>> jobs, Set<String> attached) {
        Set<String> covered = new HashSet<>();
        ArrayDeque<String> todo = new ArrayDeque<>();
        jobs.forEach(j -> todo.push(D.str(j, "id")));
        while (!todo.isEmpty()) {
            String n = todo.pop();
            for (String t : bx.runsOf(n)) {
                if (covered.add(t)) {
                    todo.push(t);
                }
            }
        }
        Map<String, Map<String, Object>> files = new LinkedHashMap<>();
        rd.items("inventory").forEach(f -> files.put(D.str(f, "path"), f));
        Set<String> coveredFiles = new HashSet<>();
        for (String c : covered) {
            Map<String, Object> item = rd.byId.getOrDefault(c, Map.of());
            Object p = D.or(D.get(item, "path"), D.get(item, "file"));
            coveredFiles.add(Py.truthy(p) ? Py.str(p) : null);
        }
        coveredFiles.addAll(attached); // a job's own source (see definedFiles) is covered too
        Map<String, Map<String, Object>> comps = new LinkedHashMap<>();
        rd.items("components").forEach(c -> comps.put(D.str(c, "name"), c));
        Map<String, Set<String>> roles = new LinkedHashMap<>();
        comps.forEach((n, c) -> {
            Set<String> r = new HashSet<>(D.strings(c, "roles"));
            r.add(Py.str(D.or(D.get(c, "type"), "")));
            roles.put(n, r);
        });
        Set<String> batchComps = new HashSet<>();
        Set<String> online = new HashSet<>();
        roles.forEach((n, r) -> {
            if (r.contains("batch")) {
                batchComps.add(n);
            }
            if (r.stream().anyMatch(ONLINE::contains)) {
                online.add(n);
            }
        });
        Set<String> counted = new HashSet<>(D.strings(rd.summary, "unscheduled_batches"));
        Map<String, Object> entryMap = D.map(rd.summary, "unscheduled_batch_entries");

        List<Map<String, Object>> units = new ArrayList<>();
        Set<String> seenFiles = new HashSet<>();
        List<String> paths = new ArrayList<>(files.keySet());
        paths.sort(Flows::cmp);
        for (String path : paths) {
            Map<String, Object> f = files.get(path);
            String comp = rd.fileComponent.get(path);
            if (!"shell".equals(D.get(f, "language")) || comp == null || !batchComps.contains(comp)) {
                continue;
            }
            if (online.contains(comp) && !Kinds.inBatchFolder(path)) {
                continue; // deploy and admin scripts of an online application
            }
            String fid = D.s(f, "id");
            if (covered.contains(fid) || !bx.callers.getOrDefault(fid, List.of()).isEmpty()) {
                continue; // started by a job, or run by another script or program
            }
            units.add(D.of("id", fid, "name", D.last(path, "/"), "kind", "script", "file", path, "line", null, "component", comp));
            seenFiles.add(path);
        }
        Set<String> runByScript = new HashSet<>(); // programs a top-level script runs are its steps
        for (Map<String, Object> u : units) {
            runByScript.addAll(bx.runsOf(D.str(u, "id")));
        }
        List<Map<String, Object>> svcs = new ArrayList<>(ix.services.values());
        svcs.sort((a, b) -> cmp(D.str(a, "name"), D.str(b, "name")));
        for (Map<String, Object> svc : svcs) {
            String id = D.str(svc, "id");
            if (!PROGRAM_SERVICES.contains(D.s(svc, "kind")) || covered.contains(id)) {
                continue;
            }
            if (runByScript.contains(id) || coveredFiles.contains(D.get(svc, "file") instanceof String sf ? sf : null)) {
                continue;
            }
            units.add(D.of("id", id, "name", D.str(svc, "name"), "kind", "program", "file", D.get(svc, "file"),
                    "line", rd.firstEvidence(svc).get(1), "component", D.get(svc, "component"), "service_id", id));
            seenFiles.add(D.s(svc, "file"));
        }
        Set<String> runFiles = new HashSet<>();
        for (String t : runByScript) {
            Map<String, Object> item = rd.byId.getOrDefault(t, Map.of());
            Object p = D.or(D.get(item, "file"), D.get(item, "path"));
            runFiles.add(Py.truthy(p) ? Py.str(p) : null);
        }
        List<Map<String, Object>> eps = new ArrayList<>(rd.items("entry_points"));
        eps.sort((a, b) -> cmp(D.str(a, "name"), D.str(b, "name")));
        for (Map<String, Object> ep : eps) {
            String kind = D.s(ep, "kind");
            String file = D.get(ep, "file") instanceof String s ? s : null;
            if (seenFiles.contains(file) || coveredFiles.contains(file) || runFiles.contains(file)) {
                continue;
            }
            if (!batchComps.contains(D.get(ep, "component") instanceof String s ? s : null)) {
                continue;
            }
            if (EVENT_ENTRY.contains(kind) || PROGRAM_ENTRY.contains(kind)) {
                units.add(D.of("id", D.str(ep, "id"), "name", D.last(D.str(ep, "name"), "."),
                        "kind", EVENT_ENTRY.contains(kind) ? "listener" : "program", "file", D.get(ep, "file"),
                        "line", D.get(ep, "line"), "component", D.get(ep, "component")));
                seenFiles.add(D.s(ep, "file"));
            }
        }

        Map<String, List<String>> sqlByFile = new LinkedHashMap<>();
        for (Map<String, Object> q : rd.items("sql_statements")) {
            if (D.t(q, "file")) {
                sqlByFile.computeIfAbsent(D.str(q, "file"), k -> new ArrayList<>()).add(D.str(q, "id"));
            }
        }
        Map<String, List<Map<String, Object>>> integByFile = new LinkedHashMap<>();
        for (Map<String, Object> it : rd.items("integrations")) {
            for (Map<String, Object> ev : D.maps(it, "evidence")) {
                if (D.t(ev, "file")) {
                    integByFile.computeIfAbsent(D.str(ev, "file"), k -> new ArrayList<>()).add(it);
                }
            }
        }
        Unscheduled un = new Unscheduled(rd, ix, bx, sqlByFile, integByFile);

        List<Flow> flows = new ArrayList<>();
        Node noSchedule = new Node("trigger:none", "no schedule found", "muted");
        Map<String, List<Map<String, Object>>> groups = new TreeMap<>(Flows::cmp);
        for (Map<String, Object> u : units) {
            if (!"listener".equals(u.get("kind"))) {
                groups.computeIfAbsent(D.s(u, "component"), k -> new ArrayList<>()).add(u);
            }
        }
        for (String name : counted) {
            groups.putIfAbsent(name, new ArrayList<>()); // counted batch components with no entry found: still listed
        }
        for (Map.Entry<String, List<Map<String, Object>>> g : groups.entrySet()) {
            String compName = g.getKey();
            List<Map<String, Object>> mems = g.getValue();
            Map<String, Object> info = comps.get(compName);
            if (info == null) { // programs outside any component: one flow each
                for (Map<String, Object> u : mems) {
                    flows.add(un.build(D.str(u, "id"), D.str(u, "name"), null, List.of(u), null, noSchedule, NO_SCHEDULE, "none", false));
                }
                continue;
            }
            boolean isCounted = counted.contains(compName);
            if (isCounted && entryMap.containsKey(compName)) {
                mems = new ArrayList<>();
                for (Map<String, Object> u : D.maps(entryMap.get(compName))) {
                    mems.add(new LinkedHashMap<>(u)); // the count's own entry points
                }
            }
            Node head = new Node("batch:" + compName, compName, "job", "batch component");
            if (isCounted && mems.size() >= 2) { // one batch per entry point (decision 168)
                for (Map<String, Object> u : mems) {
                    flows.add(un.build(D.str(u, "id"), compName + ": " + D.str(u, "name"), compName, List.of(u), head, noSchedule,
                            NO_SCHEDULE, "none", true));
                }
                continue;
            }
            String name = isCounted ? compName : compName + ": not started by any job";
            flows.add(un.build(D.str(info, "id"), name, compName, mems, head, noSchedule, NO_SCHEDULE, "none", isCounted));
        }
        for (Map<String, Object> u : units) {
            if ("listener".equals(u.get("kind"))) {
                Node trig = new Node("trigger:message", "when a message arrives (listener)", "trigger");
                flows.add(un.build(D.str(u, "id"), D.str(u, "name"), D.sOrNull(u, "component"), List.of(u), null, trig,
                        "Starts when a message arrives on the queue it listens to.", "event", false));
            }
        }
        return flows;
    }

    private static final class Unscheduled {
        final RunData rd;
        final Index ix;
        final Batch bx;
        final Map<String, List<String>> sqlByFile;
        final Map<String, List<Map<String, Object>>> integByFile;

        Unscheduled(RunData rd, Index ix, Batch bx, Map<String, List<String>> sqlByFile,
                Map<String, List<Map<String, Object>>> integByFile) {
            this.rd = rd;
            this.ix = ix;
            this.bx = bx;
            this.sqlByFile = sqlByFile;
            this.integByFile = integByFile;
        }

        Map<String, Set<String>> tablesOfFile(String path) {
            Map<String, Set<String>> out = new LinkedHashMap<>();
            for (String q : sqlByFile.getOrDefault(path, List.of())) {
                Map<String, Set<String>> ops = ix.sqlOps.get(q);
                Map<String, Object> st = ix.sql.getOrDefault(q, Map.of());
                if (ops != null && !ops.isEmpty()) {
                    ops.forEach((t, ls) -> out.computeIfAbsent(t, k -> new LinkedHashSet<>()).addAll(ls));
                } else {
                    Set<String> ls = letters(SQL_CRUD.getOrDefault(Py.str(D.or(D.get(st, "type"), "")).toUpperCase(java.util.Locale.ROOT), ""));
                    for (String t : D.strings(st, "tables")) {
                        out.computeIfAbsent(t.toUpperCase(java.util.Locale.ROOT), k -> new LinkedHashSet<>()).addAll(ls);
                    }
                }
            }
            return out;
        }

        List<Map<String, Object>> systemsOfFile(String path) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> i : integByFile.getOrDefault(path, List.of())) {
                if (!"DB".equals(i.get("type"))) {
                    out.add(D.of("type", i.get("type"), "target", i.get("target"),
                            "direction", D.t(i, "direction") ? D.str(i, "direction") : "unknown", "id", D.get(i, "id")));
                }
            }
            return out;
        }

        String pathOf(String node) {
            Map<String, Object> item = rd.byId.getOrDefault(node, Map.of());
            return Py.str(D.or(D.or(D.get(item, "path"), D.get(item, "file")), ""));
        }

        /**
         * One flow: {@code head} (the batch component) starts each member (script, program,
         * listener), which runs files and programs and touches tables and systems.
         */
        Flow build(String fid, String name, String component, List<Map<String, Object>> members, Node head, Node trigger, String when,
                String scheduler, boolean isCounted) {
            List<String> note = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();
            List<Node> unitNodes = new ArrayList<>();
            List<Node> runNodes = new ArrayList<>();
            Map<String, Set<String>> tableAcc = new LinkedHashMap<>();
            Map<String, Map<String, Object>> sysAcc = new LinkedHashMap<>();
            List<String> sids = new ArrayList<>();
            List<Map<String, Object>> steps = new ArrayList<>();
            List<String> ownPaths = new ArrayList<>();
            if (head != null) {
                edges.add(new Edge(trigger.key(), head.key(), ""));
            }
            java.util.function.BiConsumer<String, Map<String, Set<String>>> addTables = (src, tabs) -> tabs.forEach((t, ls) -> {
                tableAcc.computeIfAbsent(t, k -> new LinkedHashSet<>()).addAll(ls);
                edges.add(new Edge(src, "t:" + t, crud(ls)));
            });
            java.util.function.BiConsumer<String, List<Map<String, Object>>> addSystems = (src, systems) -> {
                for (Map<String, Object> x : systems) {
                    String key = "x:" + x.get("type") + x.get("target");
                    sysAcc.putIfAbsent(key, x);
                    if ("inbound".equals(x.get("direction"))) {
                        edges.add(new Edge(key, src, "in"));
                    } else {
                        edges.add(new Edge(src, key, "outbound".equals(x.get("direction")) ? "out" : ""));
                    }
                }
            };
            long order = 0;
            for (Map<String, Object> u : members) {
                String uname = D.str(u, "name");
                String ukey = "unit:" + uname;
                if (!hasKey(unitNodes, ukey)) {
                    unitNodes.add(new Node(ukey, uname, "job", D.str(u, "kind")));
                }
                edges.add(new Edge((head != null ? head : trigger).key(), ukey, head != null ? "starts" : ""));
                ownPaths.add(D.s(u, "file"));
                if (D.t(u, "service_id")) {
                    String sid = D.str(u, "service_id");
                    sids.add(sid);
                    addTables.accept(ukey, ix.tablesOf(List.of(sid)));
                    addSystems.accept(ukey, ix.systemsOf(List.of(sid)));
                } else {
                    addTables.accept(ukey, tablesOfFile(D.s(u, "file")));
                    addSystems.accept(ukey, systemsOfFile(D.s(u, "file")));
                }
                // what it runs: the script's commands, or what the program's file reaches (2 hops)
                List<String> start = new ArrayList<>(List.of(D.str(u, "id")));
                if (D.get(u, "file") instanceof String uf && bx.fileIds.containsKey(uf)) {
                    start.add(bx.fileIds.get(uf));
                }
                List<String> reached = new ArrayList<>();
                for (String s0 : start) {
                    for (String t : bx.runsOf(s0)) {
                        reached.add(t);
                        if (t.startsWith("file:")) {
                            for (String t2 : bx.runsOf(t)) {
                                if (!t2.startsWith("service:")) {
                                    reached.add(t2);
                                }
                            }
                        }
                    }
                }
                List<String> unitRuns = new ArrayList<>();
                Map<String, Object> unitStep = D.of("order", order, "name", uname, "kind", u.get("kind"), "id", u.get("id"),
                        "description", "", "command", "", "condition", "", "runs", unitRuns, "steps", new ArrayList<>(),
                        "programs", new ArrayList<>(), "datasets", new ArrayList<>(), "machine", "", "service_id", D.get(u, "service_id"),
                        "evidence", RunData.tuple(D.get(u, "file"), D.get(u, "line")));
                steps.add(unitStep);
                for (String t : D.unique(reached)) {
                    Map<String, Object> item = rd.byId.get(t);
                    if (t.startsWith("file:") && "source".equals(D.get(item, "category")) && !"script".equals(u.get("kind"))) {
                        ownPaths.add(pathOf(t)); // a helper module of the program: part of it
                        addSystems.accept(ukey, systemsOfFile(pathOf(t)));
                        continue;
                    }
                    order++;
                    String label = D.last(rd.label(t), "/");
                    String rkey = "run:" + label;
                    String rkind = t.startsWith("batch_job:") ? "job" : t.startsWith("service:") ? "service" : "run";
                    if (!hasKey(runNodes, rkey)) {
                        runNodes.add(new Node(rkey, label, rkind));
                    }
                    edges.add(new Edge(ukey, rkey, "runs"));
                    unitRuns.add(label);
                    String sid = t.startsWith("batch_job:") ? Fact.makeId("service", "batch-job", t) : t;
                    if (ix.services.containsKey(sid)) {
                        sids.add(sid);
                        addTables.accept(rkey, ix.tablesOf(List.of(sid)));
                        addSystems.accept(rkey, ix.systemsOf(List.of(sid)));
                    } else if (t.startsWith("file:")) {
                        addTables.accept(rkey, tablesOfFile(pathOf(t)));
                        addSystems.accept(rkey, systemsOfFile(pathOf(t)));
                    }
                    String stepKind = rkind.equals("job") ? "job" : rkind.equals("service") ? "program" : "script or file";
                    steps.add(D.of("order", order, "name", label, "kind", stepKind, "id", t, "description", "", "command", "",
                            "condition", "", "runs", new ArrayList<>(), "steps", new ArrayList<>(), "programs", new ArrayList<>(),
                            "datasets", new ArrayList<>(), "machine", "", "service_id", ix.services.containsKey(sid) ? sid : null,
                            "evidence", item != null && !item.isEmpty() ? rd.firstEvidence(item) : RunData.tuple(null, null)));
                }
                order++;
            }
            List<Map<String, Object>> tableRows = ix.tableRows(tableAcc);
            List<Node> tableNodes = new ArrayList<>();
            for (Map<String, Object> t : tableRows) {
                tableNodes.add(new Node("t:" + t.get("name"), D.str(t, "name"), "table", D.str(t, "crud")));
            }
            List<Map<String, Object>> systems = sortSystems(sysAcc.values());
            List<Node> sysNodes = new ArrayList<>();
            for (Map<String, Object> x : systems) {
                sysNodes.add(new Node("x:" + x.get("type") + x.get("target"), systemLabel(x), "system", D.str(x, "direction")));
            }
            List<String> runPaths = new ArrayList<>();
            for (Map<String, Object> st : steps) {
                List<?> e = (List<?>) st.get("evidence");
                if (!UNIT_WORD.containsKey(Py.str(st.get("kind"))) && Py.truthy(e.get(0))) {
                    runPaths.add(Py.str(e.get(0)));
                }
            }
            Set<String> kinds = new LinkedHashSet<>();
            members.forEach(u -> kinds.add(D.str(u, "kind")));
            String unitTitle = kinds.size() == 1 && members.size() == 1 ? UNIT_WORD.get(kinds.iterator().next()) : "Scripts and programs";
            List<Col> cols = new ArrayList<>();
            cols.add(new Col("Starts when", List.of(trigger)));
            if (head != null) {
                cols.add(new Col("Batch", List.of(head)));
            }
            cols.add(new Col(unitTitle, cap(unitNodes, note, "scripts and programs")));
            cols.add(new Col("Runs", cap(runNodes, note, "programs and files")));
            cols.add(new Col("Tables", cap(tableNodes, note, "tables")));
            cols.add(new Col("Files and systems", cap(sysNodes, note, "files and systems")));
            List<Col> columns = nonEmpty(cols);
            boolean listener = scheduler.equals("event");
            String summary;
            if (listener) {
                summary = "Listener " + name + (runNodes.isEmpty() ? "." : " runs " + runNodes.size() + " program(s) or file(s).");
            } else {
                List<String> first = new ArrayList<>();
                for (Map<String, Object> u : members.subList(0, Math.min(4, members.size()))) {
                    first.add(D.str(u, "name"));
                }
                String entry = String.join(", ", first) + (members.size() > 4 ? " and " + (members.size() - 4) + " more" : "");
                summary = "Batch " + name + (members.isEmpty() ? " (no entry script or program found)" : " starts with " + entry)
                        + (runNodes.isEmpty() ? "." : " and runs " + runNodes.size() + " program(s) or file(s).");
            }
            summary += (tableRows.isEmpty() ? "" : " It touches " + tableRows.size() + " table(s).")
                    + (listener ? "" : " No scheduler definition starts it.");
            List<Map<String, Object>> services = new ArrayList<>();
            for (String s : D.unique(sids)) {
                services.add(D.of("id", s, "name", D.get(ix.services.get(s), "name")));
            }
            Map<String, Object> schedule = D.of("days", new ArrayList<>(), "times", new ArrayList<>(), "text", when, "raw", "",
                    "scheduler", scheduler, "description", "", "unscheduled", !listener, "counted", isCounted);
            List<String> filesOut = new ArrayList<>();
            for (String p : ownPaths) {
                if (!p.isEmpty()) {
                    filesOut.add(p);
                }
            }
            filesOut.addAll(runPaths);
            Values.Tuple evidence = members.isEmpty() ? RunData.tuple(null, null)
                    : RunData.tuple(D.get(members.get(0), "file"), D.get(members.get(0), "line"));
            return new Flow(fid, "batch", name, RunData.slug("batch-" + name + "-" + tail(fid, 6)), component, summary, columns,
                    D.unique(edges), tableRows, systems, services, steps, new ArrayList<>(), new ArrayList<>(), schedule,
                    new ArrayList<>(), evidence, String.join(" ", note), D.unique(filesOut));
        }
    }

    private static final Map<String, String> FAILURE_WORDS = Map.of(
            "alarm_if_fail", "raises an alarm when it fails",
            "alarm_if_terminated", "raises an alarm when it is terminated",
            "n_retrys", "is retried {v} time(s) automatically",
            "max_run_alarm", "raises an alarm when it runs longer than {v} minutes",
            "term_run_time", "is stopped after {v} minutes",
            "box_failure", "the box fails when: {v}",
            "send_notification", "sends a notification",
            "notification_msg", "notification text: {v}");

    /** 'n_retrys: 1' becomes 'is retried 1 time(s) automatically (n_retrys: 1)'. */
    static String failureWords(String item) {
        int c = item.indexOf(':');
        String key = c < 0 ? item : item.substring(0, c);
        String value = TextUtil.strip(c < 0 ? "" : item.substring(c + 1));
        String words = FAILURE_WORDS.get(TextUtil.strip(key));
        if (words == null || value.equals("0") || value.equals("n") || value.equals("N")) {
            return item;
        }
        return words.replace("{v}", value) + " (" + item + ")";
    }

    /** File-watcher job id to watched files (the inbound FILE integration it declared). */
    private static Map<String, List<String>> watchedFiles(RunData rd) {
        Map<String, List<String>> byJob = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Map<String, Object> j : rd.items("batch_jobs")) {
            if ("file-watcher".equals(D.get(j, "kind"))) {
                names.put(D.str(j, "name"), D.str(j, "id"));
            }
        }
        for (Map<String, Object> i : rd.items("integrations")) {
            Object job = D.get(D.map(i, "details"), "job");
            if ("FILE".equals(D.get(i, "type")) && job instanceof String js && names.containsKey(js)) {
                byJob.computeIfAbsent(names.get(js), k -> new ArrayList<>()).add(D.str(i, "target"));
            }
        }
        return byJob;
    }

    /**
     * Rows (components) x tables, cells = CRUD letters, from the SQL each statement's component
     * runs. Returned sorted by the busiest tables.
     */
    public static Map<String, Object> crudMatrix(RunData rd) {
        Index ix = new Index(rd);
        Map<List<String>, Set<String>> cells = new LinkedHashMap<>();
        ix.sql.forEach((q, st) -> {
            String comp = D.t(st, "component") ? D.str(st, "component")
                    : Objects.requireNonNullElse(rd.fileComponent.get(D.s(st, "file")), "(none)");
            if (comp.isEmpty()) {
                comp = "(none)";
            }
            final String c = comp;
            Map<String, Set<String>> ops = ix.sqlOps.get(q);
            if (ops != null && !ops.isEmpty()) {
                ops.forEach((t, ls) -> cells.computeIfAbsent(List.of(c, t), k -> new LinkedHashSet<>()).addAll(ls));
            } else {
                Set<String> ls = letters(SQL_CRUD.getOrDefault(Py.str(D.or(D.get(st, "type"), "")).toUpperCase(java.util.Locale.ROOT), ""));
                for (String t : D.strings(st, "tables")) {
                    if (!ls.isEmpty()) {
                        cells.computeIfAbsent(List.of(c, t.toUpperCase(java.util.Locale.ROOT)), k -> new LinkedHashSet<>()).addAll(ls);
                    }
                }
            }
        });
        Map<String, Long> use = new LinkedHashMap<>();
        cells.forEach((k, ls) -> use.merge(k.get(1), (long) ls.size(), Long::sum));
        List<String> tables = new ArrayList<>(use.keySet());
        tables.sort((a, b) -> {
            int c = Long.compare(use.get(b), use.get(a));
            return c != 0 ? c : cmp(a, b);
        });
        Set<String> rowSet = new TreeSet<>(Flows::cmp);
        cells.keySet().forEach(k -> rowSet.add(k.get(0)));
        Map<String, Object> cellOut = new LinkedHashMap<>();
        cells.forEach((k, ls) -> cellOut.put(k.get(0) + "|" + k.get(1), crud(ls)));
        Map<String, Object> tableIds = new LinkedHashMap<>();
        tables.forEach(t -> tableIds.put(t, ix.tableIds.get(t)));
        return D.of("rows", new ArrayList<>(rowSet), "tables", tables, "cells", cellOut, "table_ids", tableIds);
    }

    /** One pair of tables used in the same statement, and in how many statements. */
    public record Pair(String a, String b, long n) {}

    /**
     * Pairs of tables used in the same SQL statement (joins, sub-queries), with how many statements;
     * a proxy for relationships when the DDL's keys are not parsed.
     */
    public static List<Pair> tablesUsedTogether(RunData rd, int limit) {
        Map<List<String>, Long> pairs = new LinkedHashMap<>();
        for (Map<String, Object> st : rd.items("sql_statements")) {
            Set<String> set = new TreeSet<>(Flows::cmp);
            D.strings(st, "tables").forEach(t -> set.add(t.toUpperCase(java.util.Locale.ROOT)));
            List<String> ts = new ArrayList<>(set);
            for (int i = 0; i < ts.size(); i++) {
                for (int k = i + 1; k < ts.size(); k++) {
                    pairs.merge(List.of(ts.get(i), ts.get(k)), 1L, Long::sum);
                }
            }
        }
        List<Map.Entry<List<String>, Long>> ranked = new ArrayList<>(pairs.entrySet());
        ranked.sort((x, y) -> {
            int c = Long.compare(y.getValue(), x.getValue());
            if (c != 0) {
                return c;
            }
            c = cmp(x.getKey().get(0), y.getKey().get(0));
            return c != 0 ? c : cmp(x.getKey().get(1), y.getKey().get(1));
        });
        List<Pair> out = new ArrayList<>();
        for (Map.Entry<List<String>, Long> e : ranked.subList(0, Math.min(limit, ranked.size()))) {
            out.add(new Pair(e.getKey().get(0), e.getKey().get(1), e.getValue()));
        }
        return out;
    }

    public static List<Pair> tablesUsedTogether(RunData rd) {
        return tablesUsedTogether(rd, 40);
    }

    /** Tables as nodes, coloured, joined when they appear in the same statement. */
    public static String dataMapMermaid(RunData rd) {
        List<Pair> pairs = tablesUsedTogether(rd);
        Set<String> tables = new TreeSet<>(Flows::cmp);
        pairs.forEach(p -> {
            tables.add(p.a());
            tables.add(p.b());
        });
        Map<String, String> ids = new LinkedHashMap<>();
        for (String t : tables) {
            ids.put(t, "t" + ids.size());
        }
        List<String> lines = new ArrayList<>(List.of("flowchart LR"));
        ids.forEach((t, i) -> lines.add("  " + i + "[(" + Diagrams.label(t) + ")]:::table"));
        for (Pair p : pairs) {
            lines.add("  " + ids.get(p.a()) + " ---|" + Diagrams.label(p.n() + " statement(s)") + "| " + ids.get(p.b()));
        }
        lines.add(MERMAID_CLASSES);
        return String.join("\n", lines);
    }

    /** One row per scheduled batch: its start times on each day of the week. */
    public static List<Map<String, Object>> weekGrid(List<Flow> flows) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Flow f : flows) {
            if (!f.kind().equals("batch")) {
                continue;
            }
            Map<String, Object> sched = f.schedule();
            Set<Long> days = new HashSet<>();
            for (Object d : D.list(sched, "days")) {
                days.add(((Number) d).longValue());
            }
            String times = String.join(", ", D.strings(sched, "times"));
            if (times.isEmpty()) {
                times = D.t(sched, "calendar") ? "calendar" : "";
            }
            List<Object> cells = new ArrayList<>();
            for (long d = 0; d < 7; d++) {
                cells.add(D.t(sched, "monthly") ? "" : days.contains(d) ? times : "");
            }
            List<Object> hours = new ArrayList<>();
            for (String t : D.strings(sched, "times")) {
                Double h = hour(t);
                if (h != null) {
                    hours.add(h);
                }
            }
            rows.add(D.of("name", f.name(), "slug", f.slug(), "scheduler", D.get(sched, "scheduler", ""), "cells", cells,
                    "text", D.get(sched, "text", ""), "hours", hours));
        }
        return rows;
    }

    private static final Pattern HOUR = Pattern.compile("^(\\d{1,2}):(\\d{2})$");

    private static Double hour(String t) {
        Matcher m = HOUR.matcher(TextUtil.strip(t));
        return m.matches() ? Integer.parseInt(m.group(1)) + Integer.parseInt(m.group(2)) / 60.0 : null;
    }

    private static final Set<String> COMMENT_LANGS = Set.of(
            "shell", "sql", "plsql", "c", "cpp", "proc", "java", "cobol", "jcl", "perl", "python");

    /**
     * The header comment of each source file a batch runs ({@code {file, lines}}), read from the
     * scanned source when it is on this machine; what the programs say about themselves.
     */
    public static List<Map<String, Object>> codeComments(RunData rd, Flow f, int limit) {
        Map<String, String> langs = new LinkedHashMap<>();
        rd.items("inventory").forEach(i -> langs.put(D.str(i, "path"), D.s(i, "language")));
        List<Map<String, Object>> out = new ArrayList<>();
        for (String path : f.files()) {
            String lang = langs.getOrDefault(path, "");
            if (!COMMENT_LANGS.contains(lang)) {
                continue;
            }
            Path p = rd.absPath(path);
            if (p == null || !Files.isRegularFile(p)) {
                continue;
            }
            List<String> lines = Comments.headerComment(p, lang);
            if (!lines.isEmpty()) {
                out.add(D.of("file", path, "lines", lines));
            }
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    public static List<Map<String, Object>> codeComments(RunData rd, Flow f) {
        return codeComments(rd, f, 6);
    }
}
