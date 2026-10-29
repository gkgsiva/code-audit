package com.legacyaudit.engine.analyzers;

import static com.legacyaudit.engine.analyzers.BuildAnalyzer.tech;
import static com.legacyaudit.engine.analyzers.Integrations.integration;
import static com.legacyaudit.engine.analyzers.TextUtil.or;
import static com.legacyaudit.engine.analyzers.TextUtil.truthy;

import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Category;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Schemas;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring XML configuration and XML-mapped data access: bean definitions, XML URL mappings and
 * view resolvers (Spring MVC), JMS / IBM MQ wiring, remoting, Spring Security, Spring Batch
 * jobs, Web Flow, Hibernate mappings and configuration, MyBatis / iBATIS mappers and
 * {@code persistence.xml}. Annotations are handled by the java analyzer.
 */
public final class SpringAnalyzer implements Analyzer {

    private static final Set<String> VIEW_RESOLVERS = Set.of("InternalResourceViewResolver", "UrlBasedViewResolver",
            "VelocityViewResolver", "FreeMarkerViewResolver", "TilesViewResolver", "ResourceBundleViewResolver");
    // Bean properties holding SQL (JdbcCursorItemReader, JdbcPagingItemReader, MappingSqlQuery...).
    private static final Set<String> SQL_PROPERTIES = Set.of("sql", "query", "queryString", "selectSql", "updateSql", "insertSql");
    private static final Set<String> VIEW_PROPERTIES = Set.of("formView", "successView", "viewName", "cancelView", "errorView", "pages");
    private static final Set<String> LISTENER_CONTAINERS = Set.of("DefaultMessageListenerContainer", "SimpleMessageListenerContainer");
    private static final Set<String> DYNAMIC_TAGS = Set.of("if", "choose", "when", "otherwise", "where", "foreach", "trim", "set", "bind",
            "dynamic", "isNotNull", "isNull", "isNotEmpty", "isEmpty", "isEqual", "isNotEqual", "iterate", "isPropertyAvailable",
            "isParameterPresent");
    private static final Map<String, String> DIALECTS = WebAnalyzer.ordered(
            "oracle", "Oracle",
            "db2", "DB2",
            "sqlserver", "SQL Server",
            "sybase", "Sybase",
            "mysql", "MySQL",
            "postgres", "PostgreSQL",
            "informix", "Informix",
            "h2", "H2",
            "hsql", "HSQLDB");
    private static final Set<String> STATEMENT_TAGS = Set.of("select", "insert", "update", "delete", "statement", "procedure");
    private static final Set<String> OPTIONAL_TAGS = Set.of("if", "when", "otherwise", "foreach");

    private static final Pattern MARKERS = Rx.of(
            "<(beans|flow|mapper|sqlMap|hibernate-mapping|hibernate-configuration|persistence|job)\\b|springframework");
    private static final Pattern NAME_SPLIT = Rx.of("[,; ]+");
    private static final Pattern RMI = Rx.of("rmi://([^/:]+)(?::(\\d+))?");
    private static final Pattern IBATIS_VAR = Rx.of("\\$\\w+\\$");
    private static final Pattern BIND_PARAMS = Rx.of("#\\{[^}]*\\}|#[\\w.]+(:\\w+)?#");
    private static final Pattern TEXT_SUBST = Rx.of("\\$\\{[^}]*\\}|\\$[\\w.]+\\$");

    @Override
    public String name() {
        return "spring";
    }

    @Override
    public String version() {
        return "3";
    }

    /** A property of a bean: "value", "ref" or "list", its value and its element. */
    private record Prop(String kind, String value, Xml.El el) {}

    private static Map<String, Prop> props(Xml.El bean) {
        Map<String, Prop> out = new LinkedHashMap<>();
        for (Xml.El p : bean.findall("property")) {
            Map<String, String> a = p.attrs();
            String name = a.getOrDefault("name", "");
            if (a.containsKey("value")) {
                out.put(name, new Prop("value", a.get("value"), p));
            } else if (a.containsKey("ref")) {
                out.put(name, new Prop("ref", a.get("ref"), p));
            } else {
                Xml.El v = p.find("value");
                Xml.El r = p.find("ref");
                if (v != null) {
                    out.put(name, new Prop("value", v.textOf(), p));
                } else if (r != null) {
                    out.put(name, new Prop("ref", or(r.get("bean"), r.get("local", "")), p));
                } else {
                    out.put(name, new Prop("list", "", p));
                }
            }
        }
        return out;
    }

    private static String value(Map<String, Prop> props, String name, String otherwise) {
        Prop p = props.get(name);
        return p == null ? otherwise : p.value();
    }

    private static String value(Map<String, Prop> props, String name) {
        return value(props, name, "");
    }

    private static String ctorValue(Xml.El bean) {
        for (Xml.El c : bean.findall("constructor-arg")) {
            if (c.attrs().containsKey("value")) {
                return c.get("value");
            }
            Xml.El v = c.find("value");
            if (v != null) {
                return v.textOf();
            }
        }
        return null;
    }

    private static String shortName(String cls) {
        return TextUtil.afterLast(cls, ".");
    }

    @Override
    public boolean supports(FileRecord file) {
        return "xml".equals(file.language) && (file.category == Category.CONFIG || file.category == Category.SOURCE || file.category == Category.OTHER);
    }

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        Facts facts = new Facts();
        String text = ctx.textOrEmpty();
        facts.coverage.lines = TextUtil.lineCount(text);
        if (!MARKERS.matcher(Rx.head(text, 3000)).find()) {
            return facts;
        }
        Xml.El root;
        try {
            root = Xml.parse(text);
        } catch (Xml.ParseError err) {
            facts.coverage.gap(1, facts.coverage.lines, "XML parse error: " + err.getMessage());
            return facts;
        }
        String path = file.path;
        String dialect = ctx.config().sqlDialect();
        switch (root.tag) {
            case "beans" -> beans(path, root, facts, dialect);
            case "job" -> {
                if (root.ns.contains("batch")) {
                    beans(path, root, facts, dialect);
                }
            }
            case "flow" -> flow(path, root, facts);
            case "mapper" -> mybatis(path, root, facts, dialect, "mybatis");
            case "sqlMap" -> mybatis(path, root, facts, dialect, "ibatis");
            case "hibernate-mapping" -> hbm(path, root, facts, dialect);
            case "hibernate-configuration" -> {
                facts.add("tech_stack", tech("Hibernate", null, "framework", path, root.line));
                for (Xml.El p : root.iter("property")) {
                    if (p.get("name", "").endsWith("dialect")) {
                        dialect(path, p.textOf(), p.line, facts);
                    }
                }
            }
            case "persistence" -> {
                facts.add("tech_stack", tech("JPA", root.get("version"), "framework", path, root.line));
                for (Xml.El prov : root.iter("provider")) {
                    facts.add("tech_stack", tech(shortName(prov.textOf()), null, "framework", path, prov.line));
                }
                for (Xml.El p : root.iter("property")) {
                    if (p.get("name", "").endsWith("dialect")) {
                        dialect(path, p.get("value", ""), p.line, facts);
                    }
                }
            }
            default -> { }
        }
        return facts;
    }

    private void dialect(String path, String value, Integer line, Facts facts) {
        String low = value.toLowerCase(Locale.ROOT);
        for (var e : DIALECTS.entrySet()) {
            if (low.contains(e.getKey())) {
                facts.add("tech_stack", tech(e.getValue(), null, "database", path, line));
                return;
            }
        }
    }

    // ---- beans ------------------------------------------------------------------------------

    private static final Prop NONE = new Prop("", "", null);

    private static Prop either(Map<String, Prop> props, String a, String b) {
        Prop p = props.get(a);
        return p != null ? p : props.get(b);
    }

    private void beans(String path, Xml.El root, Facts facts, String dialect) {
        facts.add("tech_stack", tech("Spring Framework (XML configuration)", null, "framework", path, root.line));
        Map<String, Xml.El> beans = new LinkedHashMap<>();
        for (Xml.El b : root.iter("bean")) {
            List<String> keys = new ArrayList<>();
            keys.add(b.get("id", ""));
            keys.addAll(List.of(NAME_SPLIT.split(b.get("name", ""), -1)));
            for (String key : keys) {
                if (!key.isEmpty()) {
                    beans.put(key, b);
                }
            }
        }
        Map<String, String> queues = new LinkedHashMap<>();
        for (var e : beans.entrySet()) {
            Xml.El b = e.getValue();
            String cls = b.get("class", "");
            String sh = shortName(cls);
            if (sh.equals("MQQueue") || sh.equals("ActiveMQQueue") || cls.endsWith("Queue")) {
                String q = or(ctorValue(b), value(props(b), "baseQueueName"));
                if (truthy(q)) {
                    queues.put(e.getKey(), TextUtil.afterLast(q, "///"));
                }
            }
        }

        for (Xml.El b : root.iter("bean")) {
            String bid = or(b.get("id"), b.get("name", ""));
            String cls = b.get("class", "");
            Integer line = b.line;
            String sh = shortName(cls);
            Map<String, Prop> props = props(b);
            if (!bid.isEmpty()) {
                facts.symbol("spring-bean", bid, path, line).data("class", cls);
            }
            if (!cls.isEmpty() && !bid.isEmpty()) {
                facts.ref("uses", "bean:" + path + "#" + bid, bid, cls, List.of("java-class"), path, line).quiet();
            }
            // MVC
            if (VIEW_RESOLVERS.contains(sh)) {
                facts.symbol("view-resolver", sh, path, line)
                        .data("prefix", value(props, "prefix"))
                        .data("suffix", value(props, "suffix"))
                        .data("tiles", sh.contains("Tiles") ? "true" : "");
            }
            if (sh.equals("SimpleUrlHandlerMapping")) {
                urlMappings(path, b, beans, facts);
            }
            if (bid.startsWith("/") || b.get("name", "").startsWith("/")) {
                String url = b.get("name", "").startsWith("/") ? b.get("name") : bid;
                handler(path, or(url, bid), b, facts);
            }
            for (var e : props.entrySet()) {
                if (!SQL_PROPERTIES.contains(e.getKey())) {
                    continue;
                }
                Prop p = e.getValue();
                if (p.kind().equals("value") && SqlTools.looksLikeSql(p.value())) {
                    int l = p.el().line != null ? p.el().line : line != null && line != 0 ? line : 1;
                    facts.add("sql_statements", SqlTools.sqlFact(path, l, p.value(), "spring-xml", dialect));
                }
            }
            for (var e : props.entrySet()) {
                if (!VIEW_PROPERTIES.contains(e.getKey())) {
                    continue;
                }
                Prop p = e.getValue();
                if (p.kind().equals("value") && !p.value().isEmpty() && !p.value().startsWith("redirect:")) {
                    facts.ref("renders", "bean:" + path + "#" + bid, or(bid, cls), p.value(), List.of("view"), path, p.el().line);
                }
            }
            // JMS / MQ
            if (sh.equals("JmsTemplate") || sh.equals("JmsTemplate102")) {
                Prop dest = either(props, "defaultDestination", "defaultDestinationName");
                if (dest != null) {
                    String q = dest.kind().equals("ref") ? queues.getOrDefault(dest.value(), dest.value()) : dest.value();
                    facts.add("integrations", integration("MQ", q, "unknown", path, line, "producer", "JMS", null));
                }
            }
            if (LISTENER_CONTAINERS.contains(sh)) {
                Prop dest = either(props, "destination", "destinationName");
                if (dest != null) {
                    String q = dest.kind().equals("ref") ? queues.getOrDefault(dest.value(), dest.value()) : dest.value();
                    facts.add("integrations", integration("MQ", q, "unknown", path, line, "consumer", "JMS", null));
                }
                Prop listener = props.get("messageListener");
                if (listener != null && listener.kind().equals("ref") && beans.containsKey(listener.value())) {
                    String lcls = beans.get(listener.value()).get("class", "");
                    facts.ref("uses", "bean:" + path + "#" + bid, bid, lcls, List.of("java-class"), path, line);
                }
            }
            if (sh.equals("MQQueueConnectionFactory") || sh.equals("MQConnectionFactory") || sh.equals("MQXAConnectionFactory")) {
                String qm = value(props, "queueManager");
                String host = value(props, "hostName");
                String port = value(props, "port");
                String target = qm + (host.isEmpty() ? "" : "@" + host) + (port.isEmpty() ? "" : ":" + port);
                if (!target.isEmpty()) {
                    facts.add("integrations", integration("MQ-QMGR", target, "outbound", path, line, null, null,
                            Map.of("channel", value(props, "channel"))));
                }
                facts.add("tech_stack", tech("IBM MQ (JMS)", null, "middleware", path, line));
            }
            // Remoting
            if (Set.of("HttpInvokerProxyFactoryBean", "RmiProxyFactoryBean", "JaxWsPortProxyFactoryBean", "JaxRpcPortProxyFactoryBean",
                    "HessianProxyFactoryBean").contains(sh)) {
                Prop up = either(props, "serviceUrl", "wsdlDocumentUrl");
                String url = up != null ? up.value() : "";
                if (sh.equals("JaxWsPortProxyFactoryBean") || sh.equals("JaxRpcPortProxyFactoryBean")) {
                    String iface = value(props, "serviceInterface");
                    String sname = value(props, "serviceName");
                    String endpoint = or(value(props, "endpointAddress"), url);
                    String name = or(or(or(sname, TextUtil.afterLast(iface, ".")), bid), sh);
                    facts.add("web_services", WebSvc.called("SOAP", name, "Spring JAX-WS proxy", path, line, emptyNull(endpoint), null, null, null,
                            emptyNull(value(props, "wsdlDocumentUrl")), emptyNull(value(props, "namespaceUri"))));
                }
                if (url.startsWith("rmi:")) {
                    Matcher m = RMI.matcher(url);
                    if (m.lookingAt()) {
                        facts.add("integrations", integration("SOCKET", m.group(1) + ":" + or(m.group(2), "1099"), "outbound", path, line, null,
                                "RMI", null));
                    }
                } else {
                    String[] c = Integrations.classifyUrl(url, sh.contains("Jax"));
                    if (c != null) {
                        facts.add("integrations", integration(c[0], c[1], "outbound", path, line, null, sh.replace("ProxyFactoryBean", ""), null));
                    }
                }
            }
            if (Set.of("HttpInvokerServiceExporter", "RmiServiceExporter", "HessianServiceExporter", "SimpleJaxWsServiceExporter").contains(sh)) {
                String name = or(value(props, "serviceName", bid), bid);
                String sid = Fact.makeId("service", "remoting", path, name);
                facts.add("services", Doc.of(Schemas.SERVICE,
                        "id", sid,
                        "name", name,
                        "kind", "remoting",
                        "file", path,
                        "handler", sh,
                        "evidence", new ArrayList<>(List.of(new Evidence(path, line, null)))));
            }
            // Security
            if (sh.equals("DefaultSpringSecurityContextSource") || sh.equals("LdapContextSource")) {
                ldap(path, or(ctorValue(b), value(props, "url")), line, facts);
            }
            if (sh.equals("RequestHeaderAuthenticationFilter")) {
                String header = value(props, "principalRequestHeader", "SM_USER");
                facts.add("tech_stack", tech("Header pre-authentication (" + header + ")", null, "auth", path, line));
            }
        }

        for (Xml.El el : root.iter()) {
            String ns = el.ns;
            Integer line = el.line;
            if (el.tag.equals("listener") && ns.contains("jms") && truthy(el.get("destination"))) {
                String q = el.get("destination");
                facts.add("integrations", integration("MQ", queues.getOrDefault(q, q), "unknown", path, line, "consumer", "JMS", null));
                String ref = el.get("ref");
                if (ref != null && beans.containsKey(ref)) {
                    facts.ref("uses", "file:" + path, path, beans.get(ref).get("class", ""), List.of("java-class"), path, line);
                }
            } else if (el.tag.equals("http") && ns.contains("security")) {
                facts.add("tech_stack", tech("Spring Security", null, "auth", path, line));
            } else if (el.tag.equals("form-login") && ns.contains("security")) {
                facts.add("tech_stack", tech("Spring Security (form login)", null, "auth", path, line));
            } else if ((el.tag.equals("ldap-server") || el.tag.equals("ldap-authentication-provider")) && ns.contains("security")) {
                facts.add("tech_stack", tech("Spring Security (LDAP)", null, "auth", path, line));
                if (truthy(el.get("url"))) {
                    ldap(path, el.get("url"), line, facts);
                }
            } else if (el.tag.equals("job") && (ns.contains("batch") || el == root)) {
                batchJob(path, el, beans, facts);
            } else if (el.tag.equals("component-scan")) {
                facts.symbol("component-scan", el.get("base-package", ""), path, line);
            } else if (el.tag.equals("view-controller") && ns.contains("mvc")) {
                facts.ref("renders", "file:" + path, el.get("path", path), el.get("view-name", TextUtil.strip(el.get("path", ""), "/")),
                        List.of("view"), path, line);
            }
        }
    }

    private static String emptyNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private void ldap(String path, String url, Integer line, Facts facts) {
        for (String u : TextUtil.words(url)) {
            String[] c = Integrations.classifyUrl(u);
            if (c != null) {
                facts.add("integrations", integration("LDAP", c[1], "outbound", path, line));
            }
        }
    }

    private void handler(String path, String url, Xml.El bean, Facts facts) {
        String cls = bean.get("class", "");
        String sid = Fact.makeId("service", "spring-handler", path, url);
        facts.add("services", Doc.of(Schemas.SERVICE,
                "id", sid,
                "name", url,
                "kind", "spring-handler",
                "file", path,
                "handler", emptyNull(cls),
                "url", url,
                "evidence", new ArrayList<>(List.of(new Evidence(path, bean.line, null)))));
        if (!cls.isEmpty()) {
            facts.ref("calls", "service:" + sid, url, cls, List.of("java-class"), path, bean.line);
        }
        String bid = or(bean.get("id"), bean.get("name", ""));
        facts.symbol("service", "bean:" + path + "#" + bid, path, bean.line).owner(sid);
    }

    private record Mapping(String url, String beanId, Integer line) {}

    private void urlMappings(String path, Xml.El mapping, Map<String, Xml.El> beans, Facts facts) {
        List<Mapping> pairs = new ArrayList<>();
        for (Xml.El prop : mapping.iter("prop")) {
            pairs.add(new Mapping(prop.get("key", ""), prop.textOf(), prop.line));
        }
        for (Xml.El p : mapping.findall("property")) {
            String n = p.get("name");
            if ("mappings".equals(n) || "urlMap".equals(n)) {
                Xml.El v = p.find("value");
                if (v != null) {
                    for (String ln : TextUtil.pySplitlines(v.textOf())) {
                        if (ln.contains("=")) {
                            pairs.add(new Mapping(TextUtil.strip(TextUtil.beforeFirst(ln, "=")), TextUtil.strip(TextUtil.afterFirst(ln, "=")), v.line));
                        }
                    }
                }
                for (Xml.El e : p.iter("entry")) {
                    pairs.add(new Mapping(e.get("key", ""), or(e.get("value-ref"), e.get("value", "")), e.line));
                }
            }
        }
        for (Mapping m : pairs) {
            if (m.url().isEmpty()) {
                continue;
            }
            Xml.El bean = beans.get(m.beanId());
            String cls = bean != null ? bean.get("class", "") : "";
            String sid = Fact.makeId("service", "spring-handler", path, m.url());
            facts.add("services", Doc.of(Schemas.SERVICE,
                    "id", sid,
                    "name", m.url(),
                    "kind", "spring-handler",
                    "file", path,
                    "handler", or(cls, m.beanId()),
                    "url", m.url(),
                    "evidence", new ArrayList<>(List.of(new Evidence(path, m.line(), null)))));
            if (!cls.isEmpty()) {
                facts.ref("calls", "service:" + sid, m.url(), cls, List.of("java-class"), path, m.line());
            }
            facts.symbol("service", "bean:" + path + "#" + m.beanId(), path, m.line()).owner(sid);
        }
    }

    private void batchJob(String path, Xml.El job, Map<String, Xml.El> beans, Facts facts) {
        String name = job.get("id", "");
        Integer line = job.line;
        List<String> steps = new ArrayList<>();
        List<String> programs = new ArrayList<>();
        String jid = Fact.makeId("batch_job", "spring-batch", name);
        for (Xml.El step : job.iter("step")) {
            String sid = step.get("id", "");
            if (!sid.isEmpty()) {
                steps.add(sid);
            }
            for (Xml.El el : step.iter()) {
                for (String key : List.of("ref", "reader", "writer", "processor")) {
                    String ref = el.get(key);
                    if (truthy(ref) && beans.containsKey(ref)) {
                        String cls = beans.get(ref).get("class", "");
                        if (!cls.isEmpty() && !programs.contains(cls)) {
                            programs.add(cls);
                            facts.ref("calls", "batch_job:" + jid, name, cls, List.of("java-class"), path, el.line).quiet();
                        }
                    }
                }
            }
        }
        facts.add("batch_jobs", Doc.of(Schemas.BATCH_JOB,
                "id", jid,
                "name", name,
                "scheduler", "spring-batch",
                "steps", steps,
                "programs", programs,
                "evidence", new ArrayList<>(List.of(new Evidence(path, line, null)))));
        facts.symbol("spring-batch-job", name, path, line).owner(jid);
        facts.add("tech_stack", tech("Spring Batch", null, "framework", path, line));
    }

    // ---- web flow ---------------------------------------------------------------------------

    private void flow(String path, Xml.El root, Facts facts) {
        String name = TextUtil.beforeLast(TextUtil.afterLast(path, "/"), ".");
        String sid = Fact.makeId("service", "webflow", path, name);
        facts.add("services", Doc.of(Schemas.SERVICE,
                "id", sid,
                "name", name,
                "kind", "webflow",
                "file", path,
                "evidence", new ArrayList<>(List.of(new Evidence(path, root.line, null)))));
        facts.add("tech_stack", tech("Spring Web Flow", null, "framework", path, root.line));
        for (Xml.El vs : root.iter("view-state")) {
            String view = or(vs.get("view"), vs.get("id", ""));
            if (!view.isEmpty() && !view.startsWith("externalRedirect:") && !view.startsWith("flowRedirect:")) {
                facts.ref("renders", "service:" + sid, name, view, List.of("view", "path"), path, vs.line);
            }
        }
    }

    // ---- data access mappings ---------------------------------------------------------------

    private void mybatis(String path, Xml.El root, Facts facts, String dialect, String kind) {
        facts.add("tech_stack", tech(kind.equals("mybatis") ? "MyBatis" : "iBATIS", null, "framework", path, root.line));
        String ns = root.get("namespace", "");
        for (Xml.El el : root) {
            if (!STATEMENT_TAGS.contains(el.tag)) {
                continue;
            }
            String raw = el.joinedText();
            boolean dynamic = el.iter().stream().anyMatch(c -> DYNAMIC_TAGS.contains(c.tag))
                    || raw.contains("${")
                    || (kind.equals("ibatis") && IBATIS_VAR.matcher(raw).find());
            String sqlText = BIND_PARAMS.matcher(raw).replaceAll("?");
            int[] holes = new int[2];
            String before = sqlText;
            sqlText = TextUtil.sub(TEXT_SUBST, sqlText, m -> {
                holes[SqlTools.holeKind(before.substring(0, m.start())).equals("value") ? 0 : 1]++;
                return "?";
            });
            int optional = 0;
            for (Xml.El c : el.iter()) {
                if (OPTIONAL_TAGS.contains(c.tag) || c.tag.startsWith("is")) {
                    optional++;
                }
            }
            Doc fact = SqlTools.sqlFact(path, el.line != null && el.line != 0 ? el.line : 1, sqlText, kind, dialect, dynamic, false);
            List<Evidence> evidence = fact.list("evidence");
            Evidence first = evidence.getFirst();
            evidence.set(0, new Evidence(first.file(), first.line(), ns + "." + el.get("id", "")));
            fact.set("value_holes", holes[0]);
            fact.set("structural_holes", holes[1]);
            fact.set("optional_fragments", optional);
            if (optional > 0) {
                fact.set("built_with", new ArrayList<>(List.of(kind + "-dynamic-tags")));
            }
            facts.add("sql_statements", fact);
        }
    }

    private void hbm(String path, Xml.El root, Facts facts, String dialect) {
        facts.add("tech_stack", tech("Hibernate", null, "framework", path, root.line));
        String pkg = root.get("package", "");
        for (Xml.El c : root.iter("class")) {
            String name = c.get("name", "");
            String sh = shortName(name);
            String fqn = !pkg.isEmpty() && !name.contains(".") ? pkg + "." + name : name;
            facts.symbol("jpa-entity", sh, path, c.line)
                    .data("table", or(c.get("table"), sh).toUpperCase(Locale.ROOT))
                    .data("fqn", fqn);
        }
        for (Xml.El q : root.iter("query")) {
            facts.add("sql_statements", SqlTools.sqlFact(path, q.line != null && q.line != 0 ? q.line : 1, q.textOf(), "hibernate", null, false, true));
        }
        for (Xml.El q : root.iter("sql-query")) {
            facts.add("sql_statements", SqlTools.sqlFact(path, q.line != null && q.line != 0 ? q.line : 1, q.joinedText(), "native", dialect));
        }
    }
}
