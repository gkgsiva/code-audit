package com.legacyaudit.engine.linker;

import static com.legacyaudit.engine.linker.Aggregate.ev;
import static com.legacyaudit.engine.linker.Aggregate.evidence;
import static com.legacyaudit.engine.linker.IntegrationText.truthy;

import com.legacyaudit.engine.analyzers.Symbol;
import com.legacyaudit.engine.analyzers.TextUtil;
import com.legacyaudit.engine.analyzers.WebSvc;
import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.Schemas;
import com.legacyaudit.model.json.PyJson;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One row per web service (decision 133).
 *
 * <p><b>Offered</b> web services come from the {@code Service} facts that are web service
 * operations ({@code rest-operation}, {@code soap-operation}, Node.js and NestJS routes, Spring
 * handlers that return data): operations of the same web service ({@code Service.web_service},
 * else the class or router file) in one component are one row. Where the code is published (CXF
 * {@code jaxws:endpoint}, {@code sun-jaxws.xml}, web.xml servlets) gives the row its address,
 * and front-end or other code calling a route is listed in {@code callers}.
 *
 * <p><b>Called</b> web services come from the client call sites the analyzers found and from the
 * HTTP and SOAP integrations (identity providers are login, not web services): one row per SOAP
 * endpoint address, per REST host, or per client name when the address is only known at run
 * time. A client whose name matches one with an address joins it.
 *
 * <p><b>WSDL files</b> describe a service the application offers when an offered SOAP service
 * has the same name ({@code CustomerService}, {@code CustomerServicePort},
 * {@code CustomerServiceImpl} are one name) or most of its operations; else one it calls (same
 * name or address); else, alone, a called service when its address names another host, an
 * offered one otherwise. A WSDL that describes an offered service is not an outbound
 * integration: its address is where the application is called, so such integrations become
 * inbound.
 */
public final class WebServices {

    static final Map<String, String> WS_KINDS = Map.of("rest-operation", "REST", "soap-operation", "SOAP", "node-route", "REST",
            "nest-route", "REST");
    static final Map<String, String> KIND_FRAMEWORK = Map.of("node-route", "Express", "nest-route", "NestJS");
    static final Set<String> DECLARED_CLIENTS = Set.of("Feign", "MicroProfile REST Client", "Retrofit");
    private static final List<String> SUFFIXES = List.of("soapbindingstub", "bindingstub", "soapbinding", "binding", "stub", "locator",
            "httpsoap11endpoint", "httpsoap12endpoint", "soap11", "soap12", "soapport", "porttype", "port", "soap", "implservice", "impl",
            "service", "endpoint", "proxy", "client", "ws");
    private static final Pattern DISPLAY_SUFFIXES = Rx.of("(SoapBindingStub|BindingStub|Stub|Locator|PortType|Port)$");
    private static final Pattern NOT_ALNUM = Rx.of("[^a-z0-9]");
    private static final Pattern NET_FILE = Rx.of("\\.(asmx|svc)$", Rx.I);
    private static final Pattern CONFIG_KEY = Rx.of("^\\$\\{([^}:]+)(?::[^}]*)?\\}");
    static final int MAX_EVIDENCE = 50;
    static final String ADDRESS_ONLY = "address in code or configuration";

    private WebServices() {}

    /**
     * A web service name without the suffixes generated code adds: OrderService,
     * OrderServicePort, OrderServiceSoapBindingStub and OrderServiceLocator are one name.
     */
    public static String norm(String name) {
        String n = NOT_ALNUM.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("");
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String suf : SUFFIXES) {
                if (n.endsWith(suf) && n.length() > suf.length()) {
                    n = n.substring(0, n.length() - suf.length());
                    changed = true;
                }
            }
        }
        return n.isEmpty() ? name.toLowerCase(Locale.ROOT) : n;
    }

    /** Like {@link Aggregate#union} but empty and null values are left out. */
    static List<String> unionNonEmpty(List<?> a, Iterable<?> b) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object x : a) {
            out.add((String) x);
            seen.add((String) x);
        }
        for (Object x : b) {
            if (x != null && !((String) x).isEmpty() && seen.add((String) x)) {
                out.add((String) x);
            }
        }
        return out;
    }

    private record OpKey(String name, String handler) {}

    static void addOps(Doc ws, List<Doc> ops) {
        List<Doc> have = ws.list("operations");
        Set<OpKey> keys = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (Doc o : have) {
            keys.add(new OpKey(o.str("name"), o.str("handler")));
            names.add(o.str("name"));
        }
        for (Doc o : ops) {
            OpKey k = new OpKey(o.str("name"), o.str("handler"));
            if (keys.contains(k) || (names.contains(o.str("name")) && o.get("service_id") == null)) {
                continue;
            }
            keys.add(k);
            names.add(o.str("name"));
            have.add(o.copy());
        }
    }

    static String commonPath(List<String> paths) {
        List<List<String>> split = new ArrayList<>();
        for (String p : paths) {
            if (p != null && !p.isEmpty()) {
                List<String> segs = new ArrayList<>();
                for (String s : p.split("/", -1)) {
                    if (!s.isEmpty()) {
                        segs.add(s);
                    }
                }
                split.add(segs);
            }
        }
        if (split.isEmpty()) {
            return null;
        }
        List<String> first = split.getFirst();
        int n = 0;
        while (true) {
            final int k = n;
            if (first.size() <= k || first.get(k).startsWith(":") || first.get(k).startsWith("{")) {
                break;
            }
            if (!split.stream().allMatch(s -> s.size() > k && s.get(k).equals(first.get(k)))) {
                break;
            }
            n++;
        }
        for (int i = 0; i < n; i++) {
            if (NET_FILE.matcher(first.get(i)).find()) { // .NET: the service file is the address
                n = i + 1;
                break;
            }
        }
        return "/" + String.join("/", first.subList(0, n));
    }

    static String classOf(String handler) {
        if (handler == null || handler.isEmpty() || !handler.contains(".") || handler.contains("/") || handler.contains(":")) {
            return null;
        }
        String cls = TextUtil.afterLast(TextUtil.beforeLast(handler, "."), ".");
        return !cls.isEmpty() && Character.isUpperCase(cls.codePointAt(0)) ? cls : null;
    }

    static String stem(String path) {
        String base = TextUtil.afterLast(path == null ? "" : path, "/");
        base = base.contains(".") ? TextUtil.beforeFirst(base, ".") : base;
        return base.isEmpty() ? "routes" : base;
    }

    static String groupName(Doc svc) {
        if (truthy(svc.get("web_service"))) {
            return svc.str("web_service");
        }
        String cls = classOf(svc.str("handler"));
        if (cls != null && !svc.str("kind").equals("node-route")) {
            return cls;
        }
        return stem(svc.str("file"));
    }

    static Doc opOf(Doc svc, String style) {
        List<Evidence> evs = ev(svc);
        Integer line = evs.isEmpty() ? null : evs.getFirst().line();
        String name = svc.str("name");
        if (style.equals("REST")) {
            String method = name.contains(" ") ? TextUtil.beforeFirst(name, " ") : null;
            return Doc.of(Schemas.WEB_OPERATION, "name", name, "method", method, "path", svc.get("url"), "handler", svc.get("handler"),
                    "service_id", svc.id(), "file", svc.get("file"), "line", line);
        }
        String op = name.contains(".") ? TextUtil.afterLast(name, ".") : name;
        if (name.startsWith("SOAP /")) {
            op = "*";
        }
        return Doc.of(Schemas.WEB_OPERATION, "name", op, "path", svc.get("url"), "handler", svc.get("handler"), "service_id", svc.id(),
                "file", svc.get("file"), "line", line);
    }

    static String plural(long n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    /**
     * {@code host[:port]} of a base URL without its scheme or default port: http and https calls
     * to one host are one service.
     */
    public static String hostKey(String base) {
        String netloc = TextUtil.afterFirst(base, "://").toLowerCase(Locale.ROOT);
        for (String def : List.of(":80", ":443")) {
            if (netloc.endsWith(def)) {
                netloc = netloc.substring(0, netloc.length() - def.length());
            }
        }
        return netloc;
    }

    private static final class Builder {
        final List<Doc> raw;
        final List<Doc> services;
        final List<Doc> integrations;
        final List<Symbol> symbols;
        final List<Doc> edges;
        final Function<String, String> comp;
        final Function<String, String> nodeFile;
        final Map<List<String>, Doc> offered = new LinkedHashMap<>();
        final Map<List<String>, Doc> called = new LinkedHashMap<>();
        final Set<String> offeredWsdl = new HashSet<>();
        final Map<List<String>, String> firstBase = new LinkedHashMap<>();

        Builder(List<Doc> raw, List<Doc> services, List<Doc> integrations, List<Symbol> symbols, List<Doc> edges, Function<String, String> comp,
                Function<String, String> nodeFile) {
            this.raw = raw;
            this.services = services;
            this.integrations = integrations;
            this.symbols = symbols;
            this.edges = edges;
            this.comp = comp;
            this.nodeFile = nodeFile;
        }

        // ---- offered ----------------------------------------------------------------------

        void buildOffered() {
            Map<String, Doc> byService = new LinkedHashMap<>();
            for (Doc svc : services) {
                String style = truthy(svc.get("protocol")) ? svc.str("protocol") : WS_KINDS.get(svc.str("kind"));
                if (!"REST".equals(style) && !"SOAP".equals(style)) {
                    continue;
                }
                String name = groupName(svc);
                String component = svc.str("component");
                List<String> key = List.of("offered", style, component == null ? "" : component, name);
                Doc ws = offered.get(key);
                if (ws == null) {
                    ws = Doc.of(Schemas.WEB_SERVICE, "id", Fact.makeId("web_service", key.toArray()), "name", name, "style", style, "side",
                            "offered", "component", component);
                    offered.put(key, ws);
                }
                String fw = truthy(svc.get("framework")) ? svc.str("framework")
                        : KIND_FRAMEWORK.getOrDefault(svc.str("kind"), style);
                ws.set("frameworks", unionNonEmpty(ws.list("frameworks"), List.of(fw)));
                ws.set("files", unionNonEmpty(ws.list("files"), truthy(svc.get("file")) ? List.of(svc.str("file")) : List.of()));
                ws.set("evidence", evidence(ev(ws), ev(svc)));
                addOps(ws, List.of(opOf(svc, style)));
                byService.put(svc.id(), ws);
            }
            // Where the implementation classes are published (CXF, sun-jaxws.xml) and the web
            // service servlets of each component.
            Map<String, Symbol> endpoints = new LinkedHashMap<>();
            for (Symbol s : symbols) {
                if (s.kind.equals("ws-endpoint")) {
                    endpoints.put(s.name, s);
                }
            }
            Map<List<String>, String> servlets = new LinkedHashMap<>();
            for (Symbol s : symbols) {
                if (s.kind.equals("ws-servlet")) {
                    String c = comp.apply(s.file);
                    servlets.putIfAbsent(List.of(c == null ? "" : c, s.name), s.data.getOrDefault("url", ""));
                }
            }
            for (Doc ws : offered.values()) {
                TreeSet<String> classes = new TreeSet<>(PyJson::compareCodePoints);
                List<String> paths = new ArrayList<>();
                for (Doc o : ws.<Doc>list("operations")) {
                    if (truthy(o.get("handler"))) {
                        classes.add(TextUtil.beforeLast(o.str("handler"), "."));
                    }
                    if (truthy(o.get("path"))) {
                        paths.add(o.str("path"));
                    }
                }
                Symbol ep = null;
                for (String c : classes) {
                    if (endpoints.containsKey(c)) {
                        ep = endpoints.get(c);
                        break;
                    }
                }
                String url = paths.isEmpty() ? null : commonPath(paths);
                if (ep != null) {
                    ws.set("frameworks", unionNonEmpty(ws.list("frameworks"), List.of(ep.data.getOrDefault("framework", ""))));
                    ws.set("files", unionNonEmpty(ws.list("files"), List.of(ep.file)));
                    String address = ep.data.getOrDefault("url", "");
                    if (ws.str("style").equals("SOAP") || url == null || url.isEmpty()) {
                        url = !address.isEmpty() ? address : url;
                    } else if (!address.isEmpty()) {
                        List<String> parts = new ArrayList<>();
                        for (String p : List.of(address, url)) {
                            String s = TextUtil.strip(p, "/");
                            if (!s.isEmpty()) {
                                parts.add(s);
                            }
                        }
                        url = "/" + String.join("/", parts);
                    }
                }
                String component = ws.str("component");
                for (Object fw : ws.list("frameworks")) {
                    String prefix = servlets.get(List.of(component == null ? "" : component, (String) fw));
                    boolean hasUrl = url != null && !url.isEmpty();
                    if (prefix != null && !prefix.isEmpty() && hasUrl && !url.startsWith(prefix) && !prefix.equals("/")) {
                        url = TextUtil.rstrip(prefix, "/") + "/" + TextUtil.lstrip(url, "/");
                    } else if (prefix != null && !prefix.isEmpty() && !hasUrl && !prefix.equals("/")) {
                        url = prefix;
                    }
                }
                ws.set("url", url);
                ws.set("components", truthy(component) ? new ArrayList<>(List.of(component)) : new ArrayList<>());
            }
            // Callers: files whose calls resolve to one of the routes.
            for (Doc e : edges) {
                if (!e.str("kind").equals("calls") || !e.bool("resolved") || !byService.containsKey(e.str("target"))) {
                    continue;
                }
                String f = nodeFile.apply(e.str("source"));
                Doc ws = byService.get(e.str("target"));
                if (f != null && !f.isEmpty() && !ws.list("files").contains(f)) {
                    ws.set("callers", unionNonEmpty(ws.list("callers"), List.of(f)));
                }
            }
        }

        // ---- called -----------------------------------------------------------------------

        List<String> key(String style, String url, String name) {
            String[] bp = WebSvc.splitUrl(url == null ? "" : url);
            String base = bp[0];
            if (base != null && !base.isEmpty()) {
                if (style.equals("SOAP")) {
                    return List.of("called", "SOAP", base + bp[1].toLowerCase(Locale.ROOT));
                }
                // One REST row per host, http or https (decision 177); the id keeps the first base.
                List<String> k = List.of("called", "REST", hostKey(base));
                firstBase.putIfAbsent(k, base);
                return k;
            }
            return List.of("called", style, "name:" + norm(name));
        }

        /** {@code ${payment.url}/charges}: the address configured under that key. */
        void resolve(List<Doc> uses) {
            Map<String, String> byKey = new LinkedHashMap<>();
            for (Doc it : integrations) {
                Object k = it.map("details").get("key");
                if ((it.str("type").equals("HTTP") || it.str("type").equals("SOAP")) && truthy(k)) {
                    byKey.put(String.valueOf(k), it.str("target"));
                }
            }
            for (Doc w : uses) {
                String wurl = w.str("url") == null ? "" : w.str("url");
                Matcher m = CONFIG_KEY.matcher(wurl);
                if (m.find() && byKey.containsKey(m.group(1))) {
                    String addr = TextUtil.rstrip(byKey.get(m.group(1)), "/");
                    String url = addr + wurl.substring(m.end());
                    String base = WebSvc.splitUrl(url)[0];
                    w.set("url", url);
                    for (Doc o : w.<Doc>list("operations")) {
                        if (truthy(o.get("path")) && base != null && !base.isEmpty()) {
                            o.set("path", WebSvc.splitUrl(addr + o.str("path"))[1]);
                            o.set("name", (truthy(o.get("method")) ? o.str("method") : "ANY") + " " + o.str("path"));
                        }
                    }
                }
            }
        }

        void buildCalled() {
            List<Doc> uses = new ArrayList<>();
            for (Doc w : raw) {
                if (w.str("side").equals("called")) {
                    uses.add(w);
                }
            }
            resolve(uses);
            Map<String, List<String>> named = new LinkedHashMap<>();
            for (Doc w : uses) {
                List<String> k = key(w.str("style"), w.str("url"), w.str("name"));
                if (!k.get(2).startsWith("name:")) {
                    named.putIfAbsent(norm(w.str("name")), k);
                    if (w.str("style").equals("SOAP")) {
                        String path = WebSvc.splitUrl(w.str("url") == null ? "" : w.str("url"))[1];
                        String last = TextUtil.afterLast(TextUtil.rstrip(path, "/"), "/");
                        if (!last.isEmpty()) {
                            named.putIfAbsent(norm(last), k);
                        }
                    }
                }
            }
            Map<List<String>, Map<String, Integer>> names = new LinkedHashMap<>();
            for (Doc w : uses) {
                List<String> k = key(w.str("style"), w.str("url"), w.str("name"));
                if (k.get(2).startsWith("name:") && w.str("style").equals("SOAP")) {
                    k = named.getOrDefault(norm(w.str("name")), k);
                }
                Doc ws = calledRow(k, w.str("style"));
                ws.set("frameworks", unionNonEmpty(ws.list("frameworks"), w.list("frameworks")));
                ws.set("files", unionNonEmpty(ws.list("files"), w.list("files")));
                ws.set("callers", unionNonEmpty(ws.list("callers"), w.list("callers")));
                ws.set("evidence", evidence(ev(ws), ev(w)));
                if (!truthy(ws.get("wsdl"))) {
                    ws.set("wsdl", w.get("wsdl"));
                }
                if (!truthy(ws.get("namespace"))) {
                    ws.set("namespace", w.get("namespace"));
                }
                String wurl = w.str("url");
                if (wurl != null && !wurl.isEmpty() && wurl.contains("://") && !truthy(ws.get("url"))) {
                    String[] bp = WebSvc.splitUrl(wurl);
                    String u = (bp[0] == null ? "" : bp[0]) + (w.str("style").equals("SOAP") ? bp[1] : "");
                    ws.set("url", u.isEmpty() ? null : u);
                }
                addOps(ws, w.list("operations"));
                names.computeIfAbsent(k, x -> new LinkedHashMap<>()).merge(display(w), 1, Integer::sum);
            }
            Map<String, Doc> own = new LinkedHashMap<>();
            for (Doc ws : offered.values()) {
                if (truthy(ws.get("component"))) {
                    own.put(ws.str("component"), ws);
                }
            }
            for (Doc it : integrations) {
                String type = it.str("type");
                if ((!type.equals("HTTP") && !type.equals("SOAP")) || it.str("direction").equals("inbound")) {
                    continue;
                }
                if (IntegrationText.identityProvider(it) != null) {
                    continue;
                }
                List<Object> files = it.list("source_files");
                if (files.stream().allMatch(f -> ((String) f).toLowerCase(Locale.ROOT).endsWith(".wsdl"))) {
                    continue; // a WSDL address: the contract's own row has it
                }
                String[] bp = WebSvc.splitUrl(it.str("target"));
                String base = bp[0];
                String path = bp[1];
                if (base == null || base.isEmpty()) {
                    continue;
                }
                String label = TextUtil.beforeFirst(TextUtil.beforeFirst(TextUtil.afterFirst(base, "://"), "."), ":");
                if (own.containsKey(label) && uses.stream().noneMatch(w -> key(w.str("style"), w.str("url"), w.str("name")).get(2).equals(hostKey(base)))) {
                    continue; // the application's own API, deployed under its component's name
                }
                List<String> soapKey = List.of("called", "SOAP", base + path.toLowerCase(Locale.ROOT));
                List<String> k = called.containsKey(soapKey) || type.equals("SOAP") ? soapKey : List.of("called", "REST", hostKey(base));
                if (k.get(1).equals("REST")) { // a SOAP row is one service of the host, named by its path
                    firstBase.putIfAbsent(k, base);
                }
                Doc ws = calledRow(k, k.get(1).equals("SOAP") ? "SOAP" : "REST");
                ws.set("integrations", unionNonEmpty(ws.list("integrations"), List.of(it.id())));
                ws.set("files", unionNonEmpty(ws.list("files"), files));
                ws.set("evidence", evidence(ev(ws), ev(it)));
                if (!truthy(ws.get("url"))) {
                    ws.set("url", base + (k.get(1).equals("SOAP") ? path : ""));
                }
                if (k.get(1).equals("REST") && !path.isEmpty()
                        && ws.<Doc>list("operations").stream().noneMatch(o -> Objects.requireNonNullElse(o.str("path"), "").startsWith(path))) {
                    List<Evidence> evs = ev(it);
                    addOps(ws, List.of(Doc.of(Schemas.WEB_OPERATION, "name", "ANY " + path, "method", "ANY", "path", path, "file",
                            evs.isEmpty() ? null : evs.getFirst().file(), "line", evs.isEmpty() ? null : evs.getFirst().line())));
                }
                if (!names.containsKey(k)) {
                    String last = TextUtil.afterLast(TextUtil.rstrip(path, "/"), "/");
                    last = NET_FILE.matcher(last).replaceAll("");
                    Map<String, Integer> c = new LinkedHashMap<>();
                    c.put(k.get(1).equals("SOAP") && !last.isEmpty() ? last : base.split("://", -1)[1], 1);
                    names.put(k, c);
                }
            }
            for (var e : called.entrySet()) {
                Doc ws = e.getValue();
                ws.set("name", pickName(e.getKey(), names.getOrDefault(e.getKey(), Map.of()), ws));
                if (ws.list("frameworks").isEmpty()) {
                    ws.set("frameworks", new ArrayList<>(List.of(ADDRESS_ONLY)));
                }
            }
            // Integrations without a host (CICS INVOKE WEBSERVICE names) join the row of that name.
            for (Doc it : integrations) {
                if ((it.str("type").equals("HTTP") || it.str("type").equals("SOAP")) && !it.str("target").contains("://")) {
                    for (Doc ws : called.values()) {
                        if (truthy(ws.get("name")) && Rx.of("\\b" + Pattern.quote(ws.str("name").toUpperCase(Locale.ROOT)) + "\\b")
                                .matcher(it.str("target").toUpperCase(Locale.ROOT)).find()) {
                            ws.set("integrations", unionNonEmpty(ws.list("integrations"), List.of(it.id())));
                        }
                    }
                }
            }
        }

        Doc calledRow(List<String> k, String style) {
            Doc ws = called.get(k);
            if (ws == null) {
                String first = k.get(1).equals("REST") ? firstBase.get(k) : null;
                List<Object> idKey = new ArrayList<>(k.subList(0, 2));
                idKey.add(first != null && !first.isEmpty() ? first : k.get(2));
                ws = Doc.of(Schemas.WEB_SERVICE, "id", Fact.makeId("web_service", idKey.toArray()), "name", k.get(2), "style", style, "side",
                        "called");
                called.put(k, ws);
            } else if (style.equals("SOAP") && !ws.str("style").equals("SOAP")) {
                ws.set("style", "SOAP");
            }
            return ws;
        }

        static String display(Doc w) {
            String d = DISPLAY_SUFFIXES.matcher(w.str("name")).replaceAll("");
            return d.isEmpty() ? w.str("name") : d;
        }

        static String pickName(List<String> k, Map<String, Integer> names, Doc ws) {
            if (k.get(1).equals("REST") && !k.get(2).startsWith("name:")) {
                Set<Object> fws = new HashSet<>(ws.list("frameworks"));
                boolean anyDeclared = fws.stream().anyMatch(DECLARED_CLIENTS::contains);
                List<String> declared = new ArrayList<>();
                for (String n : names.keySet()) {
                    if (!n.isEmpty() && !fws.isEmpty() && anyDeclared) {
                        declared.add(n);
                    }
                }
                String host = TextUtil.afterFirst(k.get(2), "://");
                if (!declared.isEmpty() && DECLARED_CLIENTS.containsAll(fws)) {
                    declared.sort(PyJson::compareCodePoints);
                    return declared.getFirst();
                }
                return host;
            }
            if (names.isEmpty()) {
                return k.get(2).startsWith("name:") ? k.get(2).substring(5) : k.get(2);
            }
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(names.entrySet());
            entries.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(e -> -e.getValue())
                    .thenComparingInt(e -> e.getKey().codePointCount(0, e.getKey().length()))
                    .thenComparing(Map.Entry::getKey, PyJson::compareCodePoints));
            return entries.getFirst().getKey();
        }

        // ---- WSDL contracts ---------------------------------------------------------------

        void attachContracts() {
            Map<String, String> wsdd = new LinkedHashMap<>();
            for (Symbol s : symbols) {
                if (s.kind.equals("ws-wsdl")) {
                    wsdd.put(s.name, s.data.getOrDefault("wsdl", ""));
                }
            }
            for (Doc c : raw) {
                if (!c.str("side").equals("contract")) {
                    continue;
                }
                String cname = norm(c.str("name"));
                Set<String> cOps = new HashSet<>();
                c.<Doc>list("operations").forEach(o -> cOps.add(o.str("name").toLowerCase(Locale.ROOT)));
                String cwsdl = c.str("wsdl");
                String baseName = TextUtil.afterLast(cwsdl == null ? "" : cwsdl, "/").toLowerCase(Locale.ROOT);
                Doc match = null;
                for (Doc ws : offered.values()) {
                    if (!ws.str("style").equals("SOAP")) {
                        continue;
                    }
                    Set<String> ops = new HashSet<>();
                    ws.<Doc>list("operations").forEach(o -> ops.add(o.str("name").toLowerCase(Locale.ROOT)));
                    String hint = TextUtil.afterLast(wsdd.getOrDefault(ws.str("name"), ""), "/").toLowerCase(Locale.ROOT);
                    Set<String> both = new HashSet<>(cOps);
                    both.retainAll(ops);
                    if (norm(ws.str("name")).equals(cname) || (!baseName.isEmpty() && hint.equals(baseName))
                            || (!cOps.isEmpty() && both.size() * 2 >= cOps.size())) {
                        match = ws;
                        break;
                    }
                }
                if (match == null) {
                    List<String> ckey = key("SOAP", c.str("url"), c.str("name"));
                    for (var e : called.entrySet()) {
                        Doc ws = e.getValue();
                        String w = ws.str("wsdl");
                        String hint = TextUtil.afterLast(TextUtil.beforeFirst(w == null ? "" : w, "?"), "/").toLowerCase(Locale.ROOT);
                        if (e.getKey().equals(ckey) || norm(ws.str("name")).equals(cname) || (!baseName.isEmpty() && hint.equals(baseName))) {
                            match = ws;
                            break;
                        }
                    }
                }
                if (match == null) {
                    String base = WebSvc.splitUrl(c.str("url") == null ? "" : c.str("url"))[0];
                    String side = base != null && !base.isEmpty() ? "called" : "offered";
                    List<String> k = List.of(side, c.str("style"), "wsdl:" + (truthy(cwsdl) ? cwsdl : c.str("name")) + "#" + c.str("name"));
                    match = Doc.of(Schemas.WEB_SERVICE, "id", Fact.makeId("web_service", k.toArray()), "name", c.str("name"), "style",
                            c.str("style"), "side", side, "url", c.get("url"));
                    (side.equals("called") ? called : offered).put(k, match);
                    if (side.equals("offered")) {
                        match.set("component", comp.apply(cwsdl));
                    }
                }
                if (truthy(cwsdl)) {
                    match.set("wsdl", cwsdl);
                }
                if (!truthy(match.get("namespace"))) {
                    match.set("namespace", c.get("namespace"));
                }
                List<String> ckey = key(c.str("style"), c.str("url"), c.str("name"));
                for (Doc it : integrations) {
                    if (cwsdl != null && it.list("source_files").contains(cwsdl)
                            && key(c.str("style").equals("SOAP") ? "SOAP" : "REST", it.str("target"), "").equals(ckey)) {
                        match.set("integrations", unionNonEmpty(match.list("integrations"), List.of(it.id())));
                    }
                }
                match.set("frameworks", unionNonEmpty(match.list("frameworks"), List.of("WSDL")));
                match.set("files", unionNonEmpty(match.list("files"), truthy(cwsdl) ? List.of(cwsdl) : List.of()));
                match.set("evidence", evidence(ev(match), ev(c)));
                if (!truthy(match.get("url")) && truthy(c.get("url"))) {
                    match.set("url", c.get("url"));
                }
                if (match.str("side").equals("called") || match.<Doc>list("operations").stream().noneMatch(o -> truthy(o.get("service_id")))) {
                    addOps(match, c.list("operations"));
                }
                if (match.str("side").equals("offered") && truthy(cwsdl)) {
                    offeredWsdl.add(cwsdl);
                }
            }
        }

        // ---- finish -----------------------------------------------------------------------

        List<Doc> finish() {
            List<Doc> out = new ArrayList<>(offered.values());
            out.addAll(called.values());
            for (Doc ws : out) {
                Map<String, Integer> comps = new LinkedHashMap<>();
                List<Object> all = new ArrayList<>(ws.list("files"));
                all.addAll(ws.list("callers"));
                for (Object f : all) {
                    String c = comp.apply((String) f);
                    if (c != null && !c.isEmpty()) {
                        comps.merge(c, 1, Integer::sum);
                    }
                }
                if (ws.str("side").equals("called") || !truthy(ws.get("component"))) {
                    if (!truthy(ws.get("component"))) {
                        String best = null;
                        int bestN = 0;
                        for (var e : comps.entrySet()) {
                            if (e.getValue() > bestN) {
                                best = e.getKey();
                                bestN = e.getValue();
                            }
                        }
                        ws.set("component", best);
                    }
                }
                TreeSet<String> cs = new TreeSet<>(PyJson::compareCodePoints);
                cs.addAll(comps.keySet());
                if (truthy(ws.get("component"))) {
                    cs.add(ws.str("component"));
                }
                ws.set("components", new ArrayList<>(cs));
                ws.<Doc>list("operations").sort(Comparator.<Doc, String>comparing(o -> Objects.requireNonNullElse(o.str("path"), ""), PyJson::compareCodePoints)
                        .thenComparing(o -> o.str("name"), PyJson::compareCodePoints)
                        .thenComparing(o -> Objects.requireNonNullElse(o.str("handler"), ""), PyJson::compareCodePoints));
                ws.<String>list("callers").sort(PyJson::compareCodePoints);
                ws.<String>list("files").sort(PyJson::compareCodePoints);
                ws.set("description", describe(ws));
            }
            // A WSDL of an offered service names where the application is called, not a partner.
            for (Doc it : integrations) {
                List<Object> files = it.list("source_files");
                if ((it.str("type").equals("HTTP") || it.str("type").equals("SOAP"))
                        && Set.of("outbound", "unknown", "defined").contains(it.str("direction")) && !files.isEmpty()
                        && offeredWsdl.containsAll(files)) {
                    it.set("direction", "inbound");
                    IntegrationText.describeInto(it);
                }
            }
            out.sort(Comparator.<Doc>comparingInt(w -> w.str("side").equals("offered") ? 0 : 1)
                    .thenComparing(w -> w.str("style"), PyJson::compareCodePoints)
                    .thenComparing(w -> w.str("name").toLowerCase(Locale.ROOT), PyJson::compareCodePoints)
                    .thenComparing(Doc::id, PyJson::compareCodePoints));
            return out;
        }
    }

    static String describe(Doc ws) {
        long n = ws.list("operations").size();
        List<String> fws = new ArrayList<>();
        for (Object f : ws.list("frameworks")) {
            if (f != null && !((String) f).isEmpty()) {
                fws.add((String) f);
            }
        }
        String fw = fws.isEmpty() ? ws.str("style") : String.join(", ", fws);
        String url = ws.str("url");
        boolean hasUrl = url != null && !url.isEmpty();
        if (ws.str("side").equals("offered")) {
            if (ws.<Doc>list("operations").stream().noneMatch(o -> truthy(o.get("service_id")))) {
                String wsdl = truthy(ws.get("wsdl")) ? ws.str("wsdl") : "a WSDL file";
                return ws.str("style") + " service described by " + wsdl + " (" + plural(n, "operation")
                        + "); no implementation was found in the scanned code.";
            }
            String where = hasUrl ? " under " + url : "";
            long callers = ws.list("callers").size();
            String c = callers > 0 ? " Called by " + plural(callers, "file") + " of the scanned code." : "";
            return "Offers " + plural(n, ws.str("style") + " operation") + where + " (" + fw + ")." + c;
        }
        String at = hasUrl ? " at " + url : " (address set at run time or in configuration)";
        String ops = n > 0 ? ", " + plural(n, "operation") + " seen" : "";
        long places = !ws.list("callers").isEmpty() ? ws.list("callers").size() : ws.list("files").size();
        return "Calls the " + ws.str("name") + " " + ws.str("style") + " service" + at + " from " + plural(places, "file") + " (" + fw + ops
                + ").";
    }

    public static List<Doc> build(List<Doc> raw, List<Doc> services, List<Doc> integrations, List<Symbol> symbols, List<Doc> edges,
            Function<String, String> comp, Function<String, String> nodeFile) {
        Builder b = new Builder(raw, services, integrations, symbols, edges, comp, nodeFile);
        b.buildOffered();
        b.buildCalled();
        b.attachContracts();
        return b.finish();
    }
}
