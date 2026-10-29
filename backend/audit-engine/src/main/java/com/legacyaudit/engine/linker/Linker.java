package com.legacyaudit.engine.linker;

import static com.legacyaudit.engine.linker.IntegrationText.truthy;

import com.legacyaudit.engine.analyzers.Facts;
import com.legacyaudit.engine.analyzers.Ref;
import com.legacyaudit.engine.analyzers.Symbol;
import com.legacyaudit.engine.analyzers.TextUtil;
import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Schemas;
import com.legacyaudit.model.json.PyJson;
import com.legacyaudit.model.json.PyOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The linker: runs after every file has been analysed (or reloaded from the cache).
 *
 * <ol>
 *   <li>Merge per-file facts (integrations, libraries, tech, jobs, DB objects).
 *   <li>Resolve by-name references against symbols and file paths into {@code CodeEdge}s;
 *       references that should resolve but do not become {@code unresolved-reference} findings.
 *   <li>Cross-link: screens reached from services (partials vs screens), SQL to tables (CRUD) and
 *       procedures, services to the tables and integrations they reach, batch jobs to services,
 *       COBOL sub-programs pruned from the service count, facts to components.
 * </ol>
 *
 * <p>Everything here is recomputed on every run, so new inputs (an Autosys export, a DDL dump)
 * link to source files whose analysis came from the cache. {@link Classify} then sets each
 * component's type and roles.
 */
public final class Linker {

    static final Set<String> NAV_EDGES = Set.of("forwards", "renders", "submits", "links");
    static final Set<String> REACH_EDGES = Set.of("calls", "uses", "invokes", "runs", "triggers", "includes");
    static final Set<String> PAGE_TECH = Set.of("jsp", "jsf", "html", "velocity", "freemarker", "aspx", "razor");
    static final List<String> VIEW_EXTENSIONS = List.of(".jsp", ".jspx", ".xhtml", ".html", ".vm", ".ftl");
    // Single-page application templates (decision 130): a screen when a route reaches it.
    static final Set<String> SPA_TECH = Set.of("angular", "angularjs");
    static final Set<String> API_SERVICE_KINDS = Set.of("node-route", "nest-route");
    static final List<String> MODULE_EXTENSIONS = List.of(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".mts", "/index.ts", "/index.js", "");
    static final int REACH_DEPTH = 4;
    static final List<String> PROJECT_FILES = List.of(".csproj", ".vbproj");
    private static final Logger LOG = Logger.getLogger(Linker.class.getName());

    /** What the linker returns: the entity lists, and counts for the run log. */
    public record Result(Map<String, List<Doc>> entities, int unresolved, int edges, List<String> warnings) {}

    private record Route(List<String> segments, String verb, String id) {}

    private static final class Index {
        Map<String, FileRecord> records = new LinkedHashMap<>();
        Map<String, List<String>> suffix = new HashMap<>(); // path suffix (at '/' boundaries), lower case -> paths
        Map<String, List<String>> basename = new HashMap<>(); // lower-case file name -> paths
        Map<String, List<String>> stem = new HashMap<>(); // lower-case name without extension -> paths
        Map<List<String>, List<Symbol>> symbols = new HashMap<>();
        Map<List<String>, List<Symbol>> symbolsCi = new HashMap<>();
        Map<String, String> pages = new HashMap<>(); // path -> screen id
        Map<String, List<String>> urls = new HashMap<>(); // service url -> service ids
        List<Symbol> resolvers = new ArrayList<>();
        List<Route> apiRoutes = new ArrayList<>();
    }

    private static Index buildIndex(List<FileRecord> records, List<Symbol> symbols, List<Doc> services) {
        Index ix = new Index();
        for (FileRecord r : records) {
            ix.records.put(r.path, r);
        }
        for (String p : ix.records.keySet()) {
            String[] parts = p.toLowerCase(Locale.ROOT).split("/", -1);
            for (int i = 0; i < parts.length; i++) {
                ix.suffix.computeIfAbsent(String.join("/", java.util.Arrays.copyOfRange(parts, i, parts.length)), k -> new ArrayList<>()).add(p);
            }
            String last = parts[parts.length - 1];
            ix.basename.computeIfAbsent(last, k -> new ArrayList<>()).add(p);
            ix.stem.computeIfAbsent(TextUtil.beforeLast(last, "."), k -> new ArrayList<>()).add(p);
        }
        for (Symbol s : symbols) {
            ix.symbols.computeIfAbsent(List.of(s.kind, s.name), k -> new ArrayList<>()).add(s);
            ix.symbolsCi.computeIfAbsent(List.of(s.kind, s.name.toLowerCase(Locale.ROOT)), k -> new ArrayList<>()).add(s);
            if (s.kind.equals("page") && truthy(s.owner)) {
                ix.pages.put(s.file, s.owner);
            }
        }
        for (Doc svc : services) {
            String url = svc.str("url");
            if (url != null && !url.isEmpty()) {
                ix.urls.computeIfAbsent(url, k -> new ArrayList<>()).add(svc.id());
                if (API_SERVICE_KINDS.contains(svc.str("kind"))) {
                    String name = svc.str("name");
                    String verb = name.contains(" ") ? TextUtil.beforeFirst(name, " ") : "ANY";
                    ix.apiRoutes.add(new Route(urlSegments(url), verb, svc.id()));
                }
            }
        }
        ix.apiRoutes.sort(PyOrder.by(r -> List.of(r.segments(), r.verb(), r.id())));
        for (Symbol s : symbols) {
            if (s.kind.equals("view-resolver")) {
                ix.resolvers.add(s);
            }
        }
        return ix;
    }

    /**
     * Path segments of a route or of a URL built in code, parameters as {@code {}}:
     * {@code /api/orders/:id} and {@code /api/orders/${id}} ({@code /api/orders/?}) give
     * {@code ['api', 'orders', '{}']}.
     */
    public static List<String> urlSegments(String url) {
        List<String> out = new ArrayList<>();
        for (String seg : url.toLowerCase(Locale.ROOT).split("/", -1)) {
            if (seg.isEmpty()) {
                continue;
            }
            if (seg.startsWith(":") || seg.startsWith("{") || seg.startsWith("*") || seg.contains("?") || seg.contains("$")) {
                out.add("{}");
            } else {
                out.add(seg);
            }
        }
        return out;
    }

    /**
     * A call's path matches a route when they are equal segment by segment (parameters match
     * anything), or the call is the route's tail (the base URL came from configuration).
     */
    static boolean matchRoute(List<String> call, List<String> route) {
        if (call.isEmpty() || call.size() > route.size()) {
            return false;
        }
        List<String> tail = route.subList(route.size() - call.size(), route.size());
        if (call.size() < route.size() && call.getFirst().equals("{}")) {
            return false;
        }
        for (int i = 0; i < call.size(); i++) {
            String a = call.get(i);
            String b = tail.get(i);
            if (!(a.equals(b) || a.equals("{}") || b.equals("{}"))) {
                return false;
            }
        }
        return true;
    }

    private final boolean groupIntegrations;
    private final List<FileRecord> records;
    private final List<String> copybookDirs = new ArrayList<>();
    private final Map<String, FileRecord> byPath = new LinkedHashMap<>();
    private final Map<String, List<Doc>> entities = new LinkedHashMap<>();
    private final List<Symbol> symbols = new ArrayList<>();
    private final List<Ref> refs = new ArrayList<>();
    private final Map<String, Doc> edges = new LinkedHashMap<>();
    private List<Doc> findings = new ArrayList<>();
    private final List<String> stepWarnings = new ArrayList<>();
    private int unresolved;
    private List<Doc> jobs = new ArrayList<>();
    private Index ix;

    private Linker(List<FileRecord> records, Iterable<Facts> facts, Iterable<String> copybookPaths, boolean groupIntegrations) {
        this.groupIntegrations = groupIntegrations;
        this.records = records;
        for (String d : copybookPaths) {
            String s = TextUtil.strip(d, "/");
            if (!s.isEmpty()) {
                copybookDirs.add(("/" + s + "/").toLowerCase(Locale.ROOT));
            }
        }
        for (FileRecord r : records) {
            byPath.put(r.path, r);
        }
        for (Facts f : facts) {
            f.entities.forEach((entity, items) -> entities.computeIfAbsent(entity, k -> new ArrayList<>()).addAll(items));
            symbols.addAll(f.symbols);
            refs.addAll(f.refs);
        }
        ix = buildIndex(records, List.of(), List.of());
    }

    public static Result link(List<FileRecord> records, Iterable<Facts> facts, Iterable<String> copybookPaths, boolean groupIntegrations) {
        return new Linker(records, facts, copybookPaths, groupIntegrations).run();
    }

    // ---- helpers ----------------------------------------------------------------------------

    String comp(String path) {
        FileRecord rec = byPath.get(path == null ? "" : path);
        return rec != null ? rec.component : null;
    }

    /**
     * Best candidate for a reference made in file {@code near}: one under a {@code prefer}
     * folder first (in order), then same component, then the longest shared folder prefix, then
     * alphabetical.
     */
    private String pick(List<String> paths, String near, List<String> prefer) {
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        if (paths.size() == 1) {
            return paths.getFirst();
        }
        String comp = comp(near);
        String[] nearDir = near.split("/", -1);
        List<String> pref = prefer == null ? List.of() : prefer;
        String best = null;
        List<Object> bestKey = null;
        for (String p : paths) {
            String folder = "/" + p.toLowerCase(Locale.ROOT);
            int rank = pref.size();
            for (int i = 0; i < pref.size(); i++) {
                if (folder.contains(pref.get(i))) {
                    rank = i;
                    break;
                }
            }
            int same = comp != null && comp.equals(comp(p)) ? 1 : 0;
            String[] parts = p.split("/", -1);
            int common = 0;
            for (int i = 0; i < Math.min(parts.length, nearDir.length); i++) {
                if (!parts[i].equals(nearDir[i])) {
                    break;
                }
                common++;
            }
            List<Object> key = List.of((long) rank, (long) -same, (long) -common, p);
            if (bestKey == null || PyOrder.compare(key, bestKey) < 0) {
                best = p;
                bestKey = key;
            }
        }
        return best;
    }

    private String path(String target, String near) {
        String t = TextUtil.lstrip(TextUtil.normpath(TextUtil.beforeFirst(TextUtil.beforeFirst(target, "?"), "#")), "/").toLowerCase(Locale.ROOT);
        if (t.isEmpty() || t.equals(".")) {
            return null;
        }
        return pick(ix.suffix.get(t), near, null);
    }

    private String nodeForFile(String path, boolean page) {
        if (page && ix.pages.containsKey(path)) {
            return ix.pages.get(path);
        }
        return byPath.get(path).id;
    }

    private String nodeForSymbol(Symbol s) {
        if (truthy(s.owner)) {
            return s.owner;
        }
        if (s.kind.equals("tiles-definition")) {
            return "tiles:" + s.name;
        }
        FileRecord rec = byPath.get(s.file);
        return rec != null ? rec.id : "file:" + s.file;
    }

    private Symbol symbol(String kind, String name, String near, List<String> prefer) {
        List<Symbol> cands = ix.symbols.get(List.of(kind, name));
        if (cands == null || cands.isEmpty()) {
            cands = ix.symbolsCi.get(List.of(kind, name.toLowerCase(Locale.ROOT)));
        }
        if (cands == null || cands.isEmpty()) {
            return null;
        }
        List<Symbol> owned = cands.stream().filter(c -> truthy(c.owner)).toList(); // a fact (service, job, ...) beats a bare mention
        List<Symbol> use = owned.isEmpty() ? cands : owned;
        String best = pick(use.stream().map(c -> c.file).toList(), near, prefer);
        for (Symbol c : use) {
            if (Objects.equals(c.file, best)) {
                return c;
            }
        }
        return null;
    }

    private String source(Ref ref) {
        String src = ref.source;
        int colon = src.indexOf(':');
        String kind = colon < 0 ? src : src.substring(0, colon);
        String rest = colon < 0 ? "" : src.substring(colon + 1);
        if (kind.equals("file")) {
            FileRecord rec = byPath.get(rest);
            return rec != null ? rec.id : src;
        }
        if (kind.equals("class")) {
            Symbol s = symbol("java-class", rest, ref.file, null);
            if (s != null) {
                return nodeForSymbol(s);
            }
            FileRecord rec = byPath.get(ref.file);
            return rec != null ? rec.id : src;
        }
        if (kind.equals("bean")) {
            Symbol s = symbol("service", src, ref.file, null);
            if (s != null && truthy(s.owner)) {
                return s.owner;
            }
            FileRecord rec = byPath.get(ref.file);
            return rec != null ? rec.id : src;
        }
        if ((kind.equals("service") || kind.equals("screen") || kind.equals("batch_job")) && rest.startsWith(kind + ":")) {
            return rest;
        }
        String name = kind.equals("service") ? rest : src; // a service named by its handler
        Symbol s = symbol("service", name, ref.file, null);
        return s != null && truthy(s.owner) ? s.owner : src;
    }

    private String resolve(Ref ref) {
        String target = ref.target;
        List<String> prefer = ref.targetKinds.contains("cobol-copybook") ? copybookDirs : null;
        for (String kind : ref.targetKinds) {
            String node = null;
            switch (kind) {
                case "path" -> {
                    String p = path(target, ref.file);
                    node = p != null ? nodeForFile(p, true) : null;
                }
                case "module" -> { // a relative JavaScript/TypeScript import, no extension
                    for (String ext : MODULE_EXTENSIONS) {
                        if (ix.records.containsKey(target + ext)) {
                            node = nodeForFile(target + ext, false);
                            break;
                        }
                    }
                }
                case "file-name" -> {
                    String p = pick(ix.basename.get(target.toLowerCase(Locale.ROOT)), ref.file, prefer);
                    node = p != null ? nodeForFile(p, false) : null;
                }
                case "view" -> node = view(target, ref.file);
                case "url" -> {
                    List<String> ids = ix.urls.get(target);
                    if (ids == null || ids.isEmpty()) {
                        ids = ix.urls.get(target + ".do");
                    }
                    node = ids != null && !ids.isEmpty() ? ids.getFirst() : null;
                    if (node == null && "api".equals(ref.data.get("match"))) {
                        node = apiRoute(target, ref.data.getOrDefault("method", "ANY"));
                    }
                }
                default -> {
                    Symbol s = symbol(kind, target, ref.file, prefer);
                    node = s != null ? nodeForSymbol(s) : null;
                }
            }
            if (node != null && !node.isEmpty()) {
                return node;
            }
        }
        if (ref.targetKinds.contains("file-name")) { // last resort: same name, any extension
            String p = pick(ix.stem.get(target.toLowerCase(Locale.ROOT)), ref.file, prefer);
            if (p != null) {
                return nodeForFile(p, false);
            }
        }
        return null;
    }

    /**
     * The Node.js route a front-end call reaches: same segments (parameters match), or the
     * route's tail; the same HTTP method and the longest exact match win.
     */
    private String apiRoute(String url, String method) {
        List<String> call = urlSegments(url);
        List<Object> best = null;
        for (Route r : ix.apiRoutes) {
            if (!matchRoute(call, r.segments())) {
                continue;
            }
            List<Object> score = List.of(r.verb().equals(method) || r.verb().equals("ANY") ? 0L : 1L, (long) (r.segments().size() - call.size()),
                    r.id());
            if (best == null || PyOrder.compare(score, best) < 0) {
                best = score;
            }
        }
        return best != null ? (String) best.get(2) : null;
    }

    private String view(String view, String near) {
        for (Symbol r : ix.resolvers) {
            if (truthy(r.data.get("tiles"))) {
                Symbol s = symbol("tiles-definition", view, near, null);
                if (s != null) {
                    return nodeForSymbol(s);
                }
                continue;
            }
            String p = path(r.data.getOrDefault("prefix", "") + view + r.data.getOrDefault("suffix", ""), near);
            if (p != null) {
                return nodeForFile(p, true);
            }
        }
        Symbol s = symbol("tiles-definition", view, near, null);
        if (s != null) {
            return nodeForSymbol(s);
        }
        String low = view.toLowerCase(Locale.ROOT);
        if (VIEW_EXTENSIONS.stream().anyMatch(low::endsWith)) {
            String p = path(view, near);
            return p != null ? nodeForFile(p, true) : null;
        }
        for (String ext : VIEW_EXTENSIONS) {
            String p = path(view + ext, near);
            if (p != null) {
                return nodeForFile(p, true);
            }
        }
        return null;
    }

    private void edge(String source, String target, String kind, Ref ref, boolean resolved) {
        String eid = Fact.makeId("edge", kind, source, target);
        Evidence ev = new Evidence(ref.file, ref.line, null);
        Doc prev = edges.get(eid);
        if (prev == null) {
            edges.put(eid, Doc.of(Schemas.CODE_EDGE, "id", eid, "source", source, "target", target, "kind", kind, "resolved", resolved,
                    "evidence", new ArrayList<>(List.of(ev))));
        } else {
            List<Evidence> evs = prev.list("evidence");
            if (evs.size() < 20 && !evs.contains(ev)) {
                evs.add(ev);
            }
        }
    }

    private List<Doc> typed(String name) {
        return entities.getOrDefault(name, List.of());
    }

    // ---- main -------------------------------------------------------------------------------

    private Result run() {
        List<Doc> libraries = MavenVersions.resolve(new ArrayList<>(typed("libraries")), symbols);
        libraries = Aggregate.mergeLibraries(Aggregate.mergeNpmVersions(libraries));
        Set<String> modules = new HashSet<>();
        for (Symbol s : symbols) {
            if (s.kind.equals("maven-pom")) {
                modules.add(s.name);
            }
        }
        libraries = libraries.stream().filter(lib -> !modules.contains((lib.get("group") == null ? "None" : lib.str("group")) + ":" + lib.str("artifact")))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<Doc> techIn = new ArrayList<>(typed("tech_stack"));
        techIn.addAll(Aggregate.techsFromLibraries(libraries));
        List<Doc> tech = Aggregate.mergeTech(techIn);
        List<Doc> integrations = Aggregate.mergeIntegrations(typed("integrations"));
        Map<String, Map<String, String>> partners = new HashMap<>();
        for (Symbol s : symbols) {
            if (s.kind.equals("cft-partner")) {
                partners.put(s.name, s.data);
            }
        }
        for (Doc it : integrations) {
            Map<String, String> p = partners.get(String.valueOf(it.map("details").getOrDefault("partner", "")));
            if (it.str("type").equals("CFT") && p != null && !p.isEmpty()) {
                for (String key : List.of("host", "nspart")) {
                    if (truthy(p.get(key))) {
                        it.map("details").putIfAbsent(key, p.get(key));
                    }
                }
            }
        }
        List<Doc> jobs = Aggregate.mergeBatchJobs(typed("batch_jobs"));
        List<Doc> dbObjects = Aggregate.mergeDbObjects(typed("db_objects"));
        List<Doc> services = Aggregate.dedupe(typed("services"));
        mountRoutes(services);
        List<Doc> screens = Aggregate.mergeScreens(typed("screens"));
        List<Doc> entryPoints = Aggregate.dedupe(typed("entry_points"));
        List<Doc> sql = Aggregate.dedupe(typed("sql_statements"));
        findings = Aggregate.dedupe(typed("findings"));

        // Batch jobs are services (boxes are containers; file watchers only wait).
        Map<String, String> jobService = new LinkedHashMap<>();
        for (Doc job : jobs) {
            String kind = job.str("kind");
            if (kind.equals("box") || kind.equals("file-watcher")) {
                continue;
            }
            String sid = Fact.makeId("service", "batch-job", job.id());
            jobService.put(sid, job.id());
            List<Evidence> evs = job.list("evidence");
            String handler = truthy(job.get("command")) ? job.str("command") : String.join(",", job.<String>list("programs"));
            services.add(Doc.of(Schemas.SERVICE,
                    "id", sid,
                    "name", job.str("name"),
                    "kind", "batch-job",
                    "file", evs.isEmpty() ? null : evs.getFirst().file(),
                    "handler", handler.isEmpty() ? null : handler,
                    "evidence", new ArrayList<>(evs.subList(0, Math.min(1, evs.size())))));
        }

        ix = buildIndex(records, symbols, services);
        linkRefs();
        hqlEntities(sql);
        List<Doc> edgeList = new ArrayList<>(edges.values());

        Map<String, List<Doc>> incoming = new HashMap<>();
        Map<String, List<Doc>> outgoing = new LinkedHashMap<>();
        index(edgeList, incoming, outgoing);

        screens = classifyScreens(screens, services, incoming, outgoing);
        List<Doc> kept = pruneCobol(services, incoming);
        if (kept.size() != services.size()) {
            edgeList = retargetPruned(services, kept, edgeList);
            incoming = new HashMap<>();
            outgoing = new LinkedHashMap<>();
            index(edgeList, incoming, outgoing);
        }
        services = kept;
        dbObjects = linkSql(sql, dbObjects);
        jobComponents(jobs, outgoing);
        reach(services, jobService, sql, integrations, outgoing);
        components(services, screens, sql, jobs, entryPoints, integrations);
        entryPoints = projectEntries(entryPoints);
        Map<String, String> byId = new HashMap<>();
        for (FileRecord r : records) {
            byId.put(r.id, r.path);
        }
        List<Doc> webServices = new ArrayList<>();
        try {
            webServices = WebServices.build(Aggregate.dedupe(typed("web_services")), services, integrations, symbols, edgeList, this::comp,
                    node -> node.startsWith("file:") ? node.substring(5) : byId.get(node));
        } catch (RuntimeException err) { // one step failing must not lose the whole run (decision 178)
            stepFailed("Web services list", err, "it is empty in this run");
        }
        if (groupIntegrations) { // one row per external service (decision 177)
            Aggregate.Grouped grouped = null;
            try {
                grouped = Aggregate.groupByService(integrations);
            } catch (RuntimeException err) {
                stepFailed("Grouping integrations by service", err, "one row per address is kept");
            }
            if (grouped != null) {
                integrations = grouped.rows();
                for (Doc svc : services) {
                    TreeSet<String> calls = new TreeSet<>(PyJson::compareCodePoints);
                    for (Object c : svc.list("external_calls")) {
                        calls.add(grouped.labels().getOrDefault((String) c, (String) c));
                    }
                    svc.set("external_calls", new ArrayList<>(calls));
                }
                for (Doc ws : webServices) {
                    LinkedHashSet<String> ids = new LinkedHashSet<>();
                    for (Object i : ws.list("integrations")) {
                        ids.add(grouped.ids().getOrDefault((String) i, (String) i));
                    }
                    ws.set("integrations", new ArrayList<>(ids));
                }
            }
        }

        Map<String, List<Doc>> out = new LinkedHashMap<>();
        out.put("entry_points", sorted(entryPoints, x -> List.of(x.str("file"), num(x.get("line")), x.str("name"))));
        out.put("services", sorted(services, x -> List.of(x.str("kind"), x.str("name"), or(x.str("file")))));
        out.put("screens", sorted(screens, x -> List.of(x.bool("partial"), or(x.str("file")), x.str("name"))));
        out.put("sql_statements", sorted(sql, x -> List.of(x.str("file"), num(x.get("line")), x.str("fingerprint"))));
        out.put("db_objects", sorted(dbObjects, x -> List.of(x.str("object_type"), x.str("name"))));
        out.put("integrations", sorted(integrations, x -> List.of(x.str("type"), x.str("target"), x.str("direction"))));
        out.put("web_services", new ArrayList<>(webServices));
        out.put("dependencies", sorted(edgeList, x -> List.of(x.str("kind"), x.str("source"), x.str("target"))));
        out.put("batch_jobs", sorted(jobs, x -> List.of(x.str("scheduler"), x.str("name"))));
        out.put("libraries", sorted(libraries, x -> List.of(x.str("language"), or(x.str("group")), x.str("artifact"), or(x.str("version")))));
        out.put("tech_stack", new ArrayList<>(tech));
        out.put("findings", sorted(findings, x -> List.of(x.str("rule_id"), or(x.str("file")), num(x.get("line")))));
        return new Result(out, unresolved, edgeList.size(), stepWarnings);
    }

    private static String or(String s) {
        return s == null ? "" : s;
    }

    private static long num(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }

    private static List<Doc> sorted(List<Doc> items, java.util.function.Function<Doc, Object> key) {
        List<Doc> out = new ArrayList<>(items);
        out.sort(PyOrder.by(key));
        return out;
    }

    private static void index(List<Doc> edgeList, Map<String, List<Doc>> incoming, Map<String, List<Doc>> outgoing) {
        for (Doc e : edgeList) {
            if (e.bool("resolved")) {
                incoming.computeIfAbsent(e.str("target"), k -> new ArrayList<>()).add(e);
                outgoing.computeIfAbsent(e.str("source"), k -> new ArrayList<>()).add(e);
            }
        }
    }

    private void stepFailed(String step, RuntimeException err, String fallback) {
        String type = err instanceof com.legacyaudit.engine.analyzers.PyError pe ? pe.type() : err.getClass().getSimpleName();
        LOG.log(Level.WARNING, step + " failed (" + type + ": " + err.getMessage() + ")", err);
        stepWarnings.add(step + " could not be built (" + type + ": " + err.getMessage() + "); " + fallback
                + ". The rest of the scan is complete. Please report this message with the run log.");
    }

    /**
     * Node.js routes get the prefix they are mounted under: {@code app.use('/api/orders',
     * ordersRouter)} for the routes of that router (same file, or the imported module), and
     * NestJS's {@code app.setGlobalPrefix('api')} for the controllers of that component.
     */
    private void mountRoutes(List<Doc> services) {
        Map<String, String> mounts = new HashMap<>();
        Map<String, String> routers = new HashMap<>();
        Map<String, String> nest = new HashMap<>();
        for (Symbol s : symbols) {
            if (s.kind.equals("node-route-mount")) {
                mounts.put(s.name, s.data.getOrDefault("prefix", ""));
            } else if (s.kind.equals("node-router") && truthy(s.owner)) {
                routers.put(s.owner, s.name);
            } else if (s.kind.equals("nest-global-prefix")) {
                nest.put(comp(s.file), s.name);
            }
        }
        for (Doc svc : services) {
            String prefix = "";
            String file = svc.str("file");
            if (svc.str("kind").equals("node-route") && truthy(file)) {
                String stem = TextUtil.beforeLast(file, ".");
                prefix = firstTruthy(mounts.get(routers.getOrDefault(svc.id(), "")), mounts.get(stem),
                        stem.endsWith("/index") ? mounts.get(stem.substring(0, stem.length() - "/index".length())) : "");
            } else if (svc.str("kind").equals("nest-route")) {
                prefix = nest.getOrDefault(comp(file), "");
                prefix = prefix == null ? "" : prefix;
            }
            String url = svc.str("url");
            if (prefix.isEmpty() || !truthy(url)) {
                continue;
            }
            List<String> parts = new ArrayList<>();
            for (String x : List.of(prefix, url)) {
                String s = TextUtil.strip(x, "/");
                if (!s.isEmpty()) {
                    parts.add(s);
                }
            }
            String full = "/" + String.join("/", parts);
            String verb = TextUtil.beforeFirst(svc.str("name"), " ");
            svc.set("url", full);
            svc.set("name", verb + " " + full);
        }
    }

    private static String firstTruthy(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private void linkRefs() {
        for (Ref ref : refs) {
            String source = source(ref);
            String target = resolve(ref);
            if (target != null) {
                if ("true".equals(ref.data.get("reverse"))) {
                    edge(target, source, ref.kind, ref, true);
                } else {
                    edge(source, target, ref.kind, ref, true);
                }
                continue;
            }
            if (!ref.reportUnresolved) {
                continue;
            }
            unresolved++;
            edge(source, "unresolved:" + ref.target, ref.kind, ref, false);
            findings.add(Doc.of(Schemas.FINDING,
                    "id", Fact.makeId("finding", "unresolved-reference", ref.file, ref.line, ref.kind, ref.target),
                    "rule_id", "unresolved-reference",
                    "severity", "info",
                    "category", "linking",
                    "file", ref.file,
                    "line", ref.line,
                    "message", ref.sourceLabel + " " + ref.kind + " '" + ref.target + "', which was not found in the scanned files (looked for: "
                            + String.join(", ", ref.targetKinds) + ")",
                    "evidence", new ArrayList<>(List.of(new Evidence(ref.file, ref.line, null)))));
        }
    }

    // ---- screens ----------------------------------------------------------------------------

    private List<Doc> classifyScreens(List<Doc> screens, List<Doc> services, Map<String, List<Doc>> incoming, Map<String, List<Doc>> outgoing) {
        Set<String> serviceIds = new HashSet<>();
        services.forEach(s -> serviceIds.add(s.id()));
        java.util.function.Function<String, List<String>> navigated = node -> {
            List<String> out = new ArrayList<>();
            for (Doc e : incoming.getOrDefault(node, List.of())) {
                if (NAV_EDGES.contains(e.str("kind"))) {
                    out.add(e.str("source"));
                }
            }
            return out;
        };
        // Tiles definitions: the pages a navigated definition puts in, when no other definition
        // uses them (layouts and headers are shared, so they stay partials).
        Map<String, List<String>> tilesSources = new HashMap<>();
        Map<String, Set<String>> includedByDefs = new LinkedHashMap<>();
        for (var en : outgoing.entrySet()) {
            if (en.getKey().startsWith("tiles:")) {
                for (Doc e : en.getValue()) {
                    if (e.str("kind").equals("includes")) {
                        includedByDefs.computeIfAbsent(e.str("target"), k -> new LinkedHashSet<>()).add(en.getKey());
                    }
                }
            }
        }
        for (var en : includedByDefs.entrySet()) {
            if (en.getValue().size() == 1) {
                List<String> srcs = navigated.apply(en.getValue().iterator().next());
                if (!srcs.isEmpty()) {
                    tilesSources.put(en.getKey(), srcs);
                }
            }
        }
        Map<String, List<String>> byService = new HashMap<>();
        List<Doc> out = new ArrayList<>();
        List<Doc> shells = new ArrayList<>();
        Set<String> routedComps = new HashSet<>();
        Set<String> shellIds = new HashSet<>();
        for (Symbol s : symbols) {
            if (s.kind.equals("spa-shell") && truthy(s.owner)) {
                shellIds.add(s.owner);
            }
        }
        for (Doc scr : screens) {
            String tech = scr.str("technology");
            if (SPA_TECH.contains(tech)) {
                // Routed (directly, or through the component class that renders it) = a screen;
                // the application's start page = a screen only when nothing is routed; any other
                // template is a sub-component (partial).
                List<String> nodes = new ArrayList<>();
                nodes.add(scr.id());
                for (Doc e : incoming.getOrDefault(scr.id(), List.of())) {
                    if (e.str("kind").equals("renders")) {
                        nodes.add(e.str("source"));
                    }
                }
                List<String> srcs = new ArrayList<>();
                boolean boot = shellIds.contains(scr.id());
                for (String n : nodes) {
                    for (Doc e : incoming.getOrDefault(n, List.of())) {
                        String k = e.str("kind");
                        if (k.equals("forwards") || k.equals("links") || k.equals("submits")) {
                            srcs.add(e.str("source"));
                        }
                        if (k.equals("bootstraps")) {
                            boot = true;
                        }
                    }
                }
                if (!srcs.isEmpty() && !boot) {
                    scr.set("partial", false);
                    scr.set("targeted_by", sortedSet(srcs));
                    routedComps.add(String.valueOf(comp(scr.str("file"))));
                } else {
                    scr.set("partial", true);
                    scr.set("targeted_by", sortedSet(sources(incoming.getOrDefault(scr.id(), List.of()))));
                    if (boot) {
                        shells.add(scr);
                    }
                }
                out.add(scr);
                continue;
            }
            if (!PAGE_TECH.contains(tech)) {
                List<String> srcs = navigated.apply(scr.id());
                scr.set("targeted_by", sortedSet(srcs));
                for (String s : srcs) {
                    if (serviceIds.contains(s)) {
                        byService.computeIfAbsent(s, k -> new ArrayList<>()).add(scr.id());
                    }
                }
                out.add(scr);
                continue;
            }
            List<String> srcs = new ArrayList<>(navigated.apply(scr.id()));
            srcs.addAll(tilesSources.getOrDefault(scr.id(), List.of()));
            boolean included = incoming.getOrDefault(scr.id(), List.of()).stream().anyMatch(e -> e.str("kind").equals("includes"));
            if (scr.bool("partial") || (srcs.isEmpty() && included)) {
                scr.set("partial", true);
                scr.set("targeted_by", sortedSet(sources(incoming.getOrDefault(scr.id(), List.of()))));
                out.add(scr);
                continue;
            }
            if (srcs.isEmpty() && tech.equals("html")) {
                continue; // a static HTML page nothing points to is not a screen
            }
            scr.set("targeted_by", sortedSet(srcs));
            for (String s : srcs) {
                if (serviceIds.contains(s)) {
                    byService.computeIfAbsent(s, k -> new ArrayList<>()).add(scr.id());
                }
            }
            out.add(scr);
        }
        for (Doc scr : shells) {
            if (!routedComps.contains(String.valueOf(comp(scr.str("file"))))) {
                scr.set("partial", false); // a single-page application without routes: one screen
            }
        }
        for (Doc svc : services) {
            if (byService.containsKey(svc.id())) {
                svc.set("screens", sortedSet(byService.get(svc.id())));
            }
        }
        return out;
    }

    private static List<String> sources(List<Doc> edges) {
        List<String> out = new ArrayList<>();
        edges.forEach(e -> out.add(e.str("source")));
        return out;
    }

    static List<String> sortedSet(Collection<String> items) {
        TreeSet<String> s = new TreeSet<>(PyJson::compareCodePoints);
        for (String x : items) {
            if (x != null) {
                s.add(x);
            }
        }
        return new ArrayList<>(s);
    }

    /**
     * A COBOL program that is only CALLed / LINKed by other programs is a sub-program, not a
     * service; programs run by JCL or a scheduler, or owning a CICS transaction, stay.
     */
    private static List<Doc> pruneCobol(List<Doc> services, Map<String, List<Doc>> incoming) {
        List<Doc> out = new ArrayList<>();
        for (Doc svc : services) {
            String kind = svc.str("kind");
            if (kind.equals("cobol-program") || kind.equals("cics-transaction")) {
                Set<String> kinds = new HashSet<>();
                incoming.getOrDefault(svc.id(), List.of()).forEach(e -> kinds.add(e.str("kind")));
                boolean ownTransaction = kind.equals("cics-transaction") && !Objects.equals(svc.str("name"), svc.str("handler"));
                if (kinds.contains("calls") && !kinds.contains("runs") && !ownTransaction) {
                    continue;
                }
            }
            out.add(svc);
        }
        return out;
    }

    /** An HQL/JPQL query uses the entity classes it names (no import needed for that). */
    private void hqlEntities(List<Doc> sql) {
        for (Doc st : sql) {
            String file = st.str("file");
            if (!"hql".equals(st.str("dialect")) || !byPath.containsKey(file)) {
                continue;
            }
            for (Object t : st.list("tables")) {
                Symbol s = symbol("jpa-entity", TextUtil.afterLast((String) t, "."), file, null);
                if (s == null || s.file.equals(file) || !byPath.containsKey(s.file)) {
                    continue;
                }
                Ref ref = new Ref("uses", "file:" + file, file, s.name, List.of("jpa-entity"), file, st.integer("line"));
                edge(byPath.get(file).id, byPath.get(s.file).id, "uses", ref, true);
            }
        }
    }

    /**
     * Edges to or from a pruned sub-program point at its file instead, so none dangle and the
     * caller still reaches the sub-program's tables.
     */
    private List<Doc> retargetPruned(List<Doc> before, List<Doc> after, List<Doc> edgeList) {
        Set<String> kept = new HashSet<>();
        after.forEach(s -> kept.add(s.id()));
        Map<String, String> node = new HashMap<>();
        for (Doc s : before) {
            String file = s.str("file");
            if (!kept.contains(s.id()) && truthy(file) && byPath.containsKey(file)) {
                node.put(s.id(), byPath.get(file).id);
            }
        }
        if (node.isEmpty()) {
            return edgeList;
        }
        Map<String, Doc> out = new LinkedHashMap<>();
        for (Doc e : edgeList) {
            String src = node.getOrDefault(e.str("source"), e.str("source"));
            String tgt = node.getOrDefault(e.str("target"), e.str("target"));
            if (!src.equals(e.str("source")) || !tgt.equals(e.str("target"))) {
                e = e.copy().set("id", Fact.makeId("edge", e.str("kind"), src, tgt)).set("source", src).set("target", tgt);
            }
            Doc prev = out.get(e.id());
            if (prev == null) {
                out.put(e.id(), e);
            } else {
                List<Evidence> merged = new ArrayList<>(prev.<Evidence>list("evidence"));
                for (Evidence v : e.<Evidence>list("evidence")) {
                    if (!merged.contains(v)) {
                        merged.add(v);
                    }
                }
                prev.set("evidence", merged);
            }
        }
        return new ArrayList<>(out.values());
    }

    // ---- SQL --------------------------------------------------------------------------------

    private static final Pattern TARGET_RX = Rx.of(
            "^\\s*(?:INSERT\\s+(?:/\\*.*?\\*/\\s*)?INTO|UPDATE|DELETE\\s+(?:FROM\\s+)?|MERGE\\s+INTO)\\s+([\\w.$#\"]+)", Rx.I | Pattern.DOTALL);

    static String dmlTarget(Doc st) {
        Matcher m = TARGET_RX.matcher(st.str("text"));
        return m.lookingAt() ? m.group(1).replace("\"", "").toUpperCase(Locale.ROOT) : null;
    }

    static List<String> crud(String stype, String table, String target) {
        String up = table.toUpperCase(Locale.ROOT);
        boolean isTarget = target != null && (up.equals(target) || TextUtil.afterLast(up, ".").equals(TextUtil.afterLast(target, ".")));
        return switch (stype) {
            case "INSERT" -> List.of(isTarget ? "C" : "R");
            case "UPDATE" -> List.of(isTarget ? "U" : "R");
            case "DELETE" -> List.of(isTarget ? "D" : "R");
            case "MERGE" -> isTarget ? List.of("C", "U") : List.of("R");
            default -> List.of("R");
        };
    }

    private List<Doc> linkSql(List<Doc> sql, List<Doc> objects) {
        Map<String, Doc> byName = new HashMap<>();
        Map<String, List<Doc>> byShort = new HashMap<>();
        for (Doc o : objects) {
            String type = o.str("object_type");
            if (Set.of("table", "view", "synonym", "sequence").contains(type)) {
                String up = o.str("name").toUpperCase(Locale.ROOT);
                byName.put(up, o);
                byShort.computeIfAbsent(TextUtil.afterLast(up, "."), k -> new ArrayList<>()).add(o);
            }
        }
        Map<String, String> entityTables = new HashMap<>();
        for (Symbol s : symbols) {
            if (s.kind.equals("jpa-entity") && !s.data.containsKey("entity")) {
                entityTables.put(s.name, s.data.getOrDefault("table", s.name).toUpperCase(Locale.ROOT));
            }
        }
        // Entity Framework DbSet<T> properties name the entity class, whose own mapping
        // ([Table("X")]) may be in another file.
        for (Symbol s : symbols) {
            if (s.kind.equals("jpa-entity") && s.data.containsKey("entity")) {
                String t = entityTables.getOrDefault(s.data.get("entity"), s.data.getOrDefault("table", s.name));
                entityTables.put(s.name, t.toUpperCase(Locale.ROOT));
            }
        }
        Map<String, Doc> procs = new HashMap<>();
        for (Doc o : objects) {
            if (Set.of("package", "procedure", "function").contains(o.str("object_type"))) {
                String up = o.str("name").toUpperCase(Locale.ROOT);
                procs.put(up, o);
                procs.put(TextUtil.afterLast(up, "."), o);
            }
        }
        java.util.function.Function<String, Doc> table = name -> {
            String key = name.toUpperCase(Locale.ROOT);
            if (byName.containsKey(key)) {
                return byName.get(key);
            }
            List<Doc> shortList = byShort.getOrDefault(TextUtil.afterLast(key, "."), List.of());
            if (shortList.size() == 1) {
                return shortList.getFirst();
            }
            Doc obj = Doc.of(Schemas.DB_OBJECT, "id", Fact.makeId("db_object", "table", key), "name", key, "object_type", "table");
            byName.put(key, obj);
            byShort.computeIfAbsent(TextUtil.afterLast(key, "."), k -> new ArrayList<>()).add(obj);
            objects.add(obj);
            return obj;
        };
        // membership sets beside the lists: a busy table is referenced by thousands of statements
        Map<String, Set<String>> refsSeen = new HashMap<>();
        Map<String, Set<String>> crudSeen = new HashMap<>();
        java.util.function.BiConsumer<Doc, String> addRef = (obj, sid) -> {
            Set<String> seen = refsSeen.computeIfAbsent(obj.id(), k -> new HashSet<>(obj.<String>list("referenced_by")));
            if (seen.add(sid)) {
                obj.<String>list("referenced_by").add(sid);
            }
        };
        for (Doc st : sql) {
            if ("hql".equals(st.str("dialect"))) {
                TreeSet<String> mapped = new TreeSet<>(PyJson::compareCodePoints);
                for (Object t : st.list("tables")) {
                    String name = (String) t;
                    mapped.add(entityTables.getOrDefault(TextUtil.afterLast(name, "."),
                            entityTables.getOrDefault(name, name.toUpperCase(Locale.ROOT))));
                }
                st.set("tables", new ArrayList<>(mapped));
            }
            String target = dmlTarget(st);
            for (Object t : st.list("tables")) {
                Doc obj = table.apply((String) t);
                if (obj.list("evidence").isEmpty()) {
                    obj.set("evidence", new ArrayList<>(List.of(new Evidence(st.str("file"), st.integer("line"), null))));
                }
                addRef.accept(obj, st.id());
                for (String op : crud(st.str("type"), (String) t, target)) {
                    Map<String, Object> crud = obj.map("crud");
                    @SuppressWarnings("unchecked")
                    List<String> ids = (List<String>) crud.computeIfAbsent(op, k -> new ArrayList<String>());
                    Set<String> seen = crudSeen.computeIfAbsent(obj.id() + "\u0000" + op, k -> new HashSet<>(ids));
                    if (seen.add(st.id())) {
                        ids.add(st.id());
                    }
                }
            }
            for (Object p : st.list("procedures")) {
                String key = ((String) p).toUpperCase(Locale.ROOT);
                Doc proc = procs.get(key);
                if (proc == null) {
                    proc = procs.get(TextUtil.beforeFirst(key, "."));
                }
                if (proc == null) {
                    proc = procs.get(TextUtil.afterLast(key, "."));
                }
                if (proc != null) {
                    addRef.accept(proc, st.id());
                }
            }
        }
        return objects;
    }

    // ---- reach: what a service touches ------------------------------------------------------

    private Map<String, String> nodeFiles(List<Doc> services, Map<String, Doc> jobsById) {
        Map<String, String> out = new HashMap<>();
        for (FileRecord r : records) {
            out.put(r.id, r.path);
        }
        for (Doc svc : services) {
            if (truthy(svc.get("file"))) {
                out.put(svc.id(), svc.str("file"));
            }
        }
        for (var e : jobsById.entrySet()) {
            List<Evidence> evs = e.getValue().list("evidence");
            if (!evs.isEmpty()) {
                out.put(e.getKey(), evs.getFirst().file());
            }
        }
        return out;
    }

    private record Step(String node, int depth) {}

    private void reach(List<Doc> services, Map<String, String> jobService, List<Doc> sql, List<Doc> integrations, Map<String, List<Doc>> outgoing) {
        Map<String, Set<String>> sqlByFile = new HashMap<>();
        Map<String, List<String>> sqlIdsByFile = new HashMap<>();
        for (Doc st : sql) {
            sqlByFile.computeIfAbsent(st.str("file"), k -> new HashSet<>()).addAll(st.list("tables"));
            sqlIdsByFile.computeIfAbsent(st.str("file"), k -> new ArrayList<>()).add(st.id());
        }
        Map<String, Set<String>> integByFile = new HashMap<>();
        for (Doc it : integrations) {
            for (Object f : it.list("source_files")) {
                integByFile.computeIfAbsent((String) f, k -> new HashSet<>()).add(it.str("type") + " " + it.str("target"));
            }
        }
        Map<String, Doc> jobsById = new LinkedHashMap<>();
        for (Doc j : jobs) {
            jobsById.put(j.id(), j);
        }
        Map<String, String> nodeFile = nodeFiles(services, jobsById);
        for (Doc svc : services) {
            String start = jobService.getOrDefault(svc.id(), svc.id());
            Set<String> files = new HashSet<>();
            Deque<Step> queue = new ArrayDeque<>();
            queue.add(new Step(start, 0));
            Set<String> seen = new HashSet<>();
            seen.add(start);
            Doc job = jobsById.get(start);
            boolean inSource = job == null || Set.of("spring-batch", "jcl", "node-cron").contains(job.str("scheduler"));
            FileRecord rec = inSource ? byPath.get(or(svc.str("file"))) : null;
            if (rec != null) {
                files.add(rec.path);
                seen.add(rec.id);
                queue.add(new Step(rec.id, 0));
            }
            while (!queue.isEmpty()) {
                Step s = queue.poll();
                if (!s.node().equals(start) && nodeFile.containsKey(s.node())) {
                    files.add(nodeFile.get(s.node()));
                }
                if (s.depth() >= REACH_DEPTH) {
                    continue;
                }
                for (Doc e : outgoing.getOrDefault(s.node(), List.of())) {
                    if (REACH_EDGES.contains(e.str("kind")) && seen.add(e.str("target"))) {
                        queue.add(new Step(e.str("target"), s.depth() + 1));
                    }
                }
            }
            Set<String> tables = new HashSet<>();
            Set<String> calls = new HashSet<>();
            Set<String> sqlIds = new HashSet<>();
            for (String f : files) {
                tables.addAll(sqlByFile.getOrDefault(f, Set.of()));
                sqlIds.addAll(sqlIdsByFile.getOrDefault(f, List.of()));
                calls.addAll(integByFile.getOrDefault(f, Set.of()));
            }
            svc.set("tables", sortedSet(tables));
            svc.set("sql", sortedSet(sqlIds));
            svc.set("external_calls", sortedSet(calls));
        }
    }

    /** Jobs defined outside the source tree (JIL exports) belong to the component of what they run. */
    private void jobComponents(List<Doc> jobList, Map<String, List<Doc>> outgoing) {
        this.jobs = jobList;
        Map<String, String> fileComp = new HashMap<>();
        for (FileRecord r : records) {
            fileComp.put(r.id, r.component);
        }
        for (Doc job : jobList) {
            List<Evidence> evs = job.list("evidence");
            String comp = comp(evs.isEmpty() ? null : evs.getFirst().file());
            if (comp == null) {
                for (Doc e : outgoing.getOrDefault(job.id(), List.of())) {
                    String k = e.str("kind");
                    if (k.equals("invokes") || k.equals("runs") || k.equals("triggers")) {
                        comp = fileComp.get(e.str("target"));
                        if (comp == null || comp.isEmpty()) {
                            comp = ownerComponent(e.str("target"));
                        }
                        if (comp != null && !comp.isEmpty()) {
                            break;
                        }
                    }
                }
            }
            job.set("component", comp);
        }
    }

    private String ownerComponent(String node) {
        for (Symbol s : symbols) {
            if (node.equals(s.owner)) {
                return comp(s.file);
            }
        }
        return null;
    }

    // ---- components -------------------------------------------------------------------------

    private void components(List<Doc> services, List<Doc> screens, List<Doc> sql, List<Doc> jobList, List<Doc> entryPoints, List<Doc> integrations) {
        for (Doc svc : services) {
            if (!svc.str("kind").equals("batch-job")) {
                svc.set("component", comp(svc.str("file")));
            }
        }
        Map<String, String> jobComp = new HashMap<>();
        for (Doc j : jobList) {
            jobComp.put(Fact.makeId("service", "batch-job", j.id()), j.str("component"));
        }
        for (Doc svc : services) {
            if (svc.str("kind").equals("batch-job")) {
                svc.set("component", jobComp.get(svc.id()));
            }
        }
        for (Doc scr : screens) {
            scr.set("component", comp(scr.str("file")));
        }
        for (Doc st : sql) {
            st.set("component", comp(st.str("file")));
        }
        for (Doc ep : entryPoints) {
            ep.set("component", comp(ep.str("file")));
        }
        for (Doc it : integrations) {
            List<String> comps = new ArrayList<>();
            for (Object f : it.list("source_files")) {
                String c = comp((String) f);
                if (c != null && !c.isEmpty()) {
                    comps.add(c);
                }
            }
            it.set("components", sortedSet(comps));
        }
    }

    /**
     * A .NET project file marks a desktop application or Windows service (OutputType); when its
     * code also names the start-up (App.xaml, Main, a ServiceBase class), that is the same entry.
     */
    private static List<Doc> projectEntries(List<Doc> entries) {
        Set<List<String>> coded = new HashSet<>();
        for (Doc ep : entries) {
            if (!isProject(ep.str("file"))) {
                coded.add(java.util.Arrays.asList(ep.str("component"), ep.str("kind")));
            }
        }
        List<Doc> out = new ArrayList<>();
        for (Doc ep : entries) {
            if (!(isProject(ep.str("file")) && coded.contains(java.util.Arrays.asList(ep.str("component"), ep.str("kind"))))) {
                out.add(ep);
            }
        }
        return out;
    }

    private static boolean isProject(String file) {
        String low = file.toLowerCase(Locale.ROOT);
        return PROJECT_FILES.stream().anyMatch(low::endsWith);
    }
}
