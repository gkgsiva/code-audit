package com.legacyaudit.engine.analyzers;

import static com.legacyaudit.engine.analyzers.BuildAnalyzer.tech;

import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Category;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Schemas;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The XML files that describe or deploy web services.
 *
 * <ul>
 *   <li>WSDL 1.1 and 2.0: service, ports and their address, port type operations, namespace
 *       (a {@code web_services} fact with {@code side: contract});
 *   <li>Apache Axis 1 deployment descriptors ({@code server-config.wsdd}, {@code deploy.wsdd})
 *       and Apache Axis2 {@code services.xml}: the SOAP operations offered;
 *   <li>JAX-WS RI {@code sun-jaxws.xml} and Apache CXF {@code jaxws:}/{@code jaxrs:} Spring XML:
 *       where each implementation class is published, and CXF clients;
 *   <li>{@code web.xml} servlets of web service frameworks and the URL they are mapped to.
 * </ul>
 *
 * The builders the other analyzers use are in {@link WebSvc}.
 */
public final class WebServicesAnalyzer implements Analyzer {

    private static final List<String> WSDL_NS = List.of("schemas.xmlsoap.org/wsdl", "www.w3.org/ns/wsdl");
    private static final Pattern WSDL_ROOT = Rx.of("<(\\w+:)?(definitions|description)\\b");
    private static final Pattern METHOD_SEP = Rx.of("[\\s,]+");

    /** web.xml servlet class, then the framework of the services it publishes. */
    public static final Map<String, String> SERVLETS = Map.of(
            "org.apache.axis.transport.http.AxisServlet", "Apache Axis",
            "org.apache.axis2.transport.http.AxisServlet", "Apache Axis2",
            "org.apache.cxf.transport.servlet.CXFServlet", "Apache CXF",
            "com.sun.jersey.spi.container.servlet.ServletContainer", "JAX-RS",
            "org.glassfish.jersey.servlet.ServletContainer", "JAX-RS",
            "org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher", "JAX-RS",
            "org.apache.wink.server.internal.servlet.RestServlet", "JAX-RS",
            "com.sun.xml.ws.transport.http.servlet.WSServlet", "JAX-WS",
            "org.springframework.ws.transport.http.MessageDispatcherServlet", "Spring-WS");

    @Override
    public String name() {
        return "webservices";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public boolean supports(FileRecord file) {
        return file.language.equals("xml") && file.category != Category.BINARY && file.category != Category.TEST;
    }

    static boolean isWsdl(String path, String text) {
        if (path.toLowerCase(Locale.ROOT).endsWith(".wsdl")) {
            return true;
        }
        String head = Rx.head(text, 4000);
        return WSDL_ROOT.matcher(head).find() && WSDL_NS.stream().anyMatch(head::contains);
    }

    private static String ns(Xml.El e) {
        return e.ns == null ? "" : e.ns;
    }

    private static String or(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        Facts facts = new Facts();
        String text = ctx.textOrEmpty();
        facts.coverage.lines = TextUtil.count(text, '\n') + 1;
        String path = file.path;
        String base = TextUtil.afterLast(path.toLowerCase(Locale.ROOT), "/");
        boolean wsdl = isWsdl(path, text);
        boolean cxf = text.contains("cxf.apache.org/jax");
        boolean wanted = wsdl || base.endsWith(".wsdd") || base.equals("services.xml") || base.equals("sun-jaxws.xml") || base.equals("web.xml")
                || cxf;
        if (!wanted) {
            return facts;
        }
        Xml.El root;
        try {
            root = Xml.parse(text);
        } catch (Xml.ParseError err) {
            facts.coverage.gap(1, facts.coverage.lines, "XML parse error");
            return facts;
        }
        if (wsdl) {
            wsdl(root, path, facts);
        } else if (base.endsWith(".wsdd")) {
            wsdd(root, path, facts);
        } else if (base.equals("services.xml")) {
            axis2(root, path, facts);
        } else if (base.equals("sun-jaxws.xml")) {
            for (Xml.El ep : root.iter("endpoint")) {
                String impl = ep.get("implementation");
                if (impl != null && !impl.isEmpty()) {
                    endpoint(facts, path, ep.line, impl, ep.get("url-pattern", ""), "JAX-WS");
                }
            }
        } else if (base.equals("web.xml")) {
            webXml(root, path, facts);
        }
        if (cxf) {
            cxf(root, path, facts);
        }
        return facts;
    }

    // ---- WSDL -------------------------------------------------------------------------------

    private static void wsdl(Xml.El root, String path, Facts facts) {
        String ns = root.get("targetNamespace");
        List<Doc> ops = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Xml.El> types = new ArrayList<>(root.iter("portType"));
        types.addAll(root.iter("interface"));
        for (Xml.El pt : types) {
            for (Xml.El op : pt.findall("operation")) {
                String name = op.get("name");
                if (name != null && !name.isEmpty() && seen.add(name)) {
                    ops.add(WebSvc.operation(name, null, null, null, path, op.line));
                }
            }
        }
        Set<String> spaces = new HashSet<>();
        for (Xml.El e : root.iter()) {
            spaces.add(ns(e));
        }
        boolean soap = spaces.stream().anyMatch(s -> s.contains("soap"));
        boolean httpOnly = !soap && spaces.stream().anyMatch(s -> s.endsWith("wsdl/http/") || s.endsWith("wsdl/http"));
        List<Xml.El> services = root.iter("service");
        if (services.isEmpty()) {
            List<Xml.El> portTypes = root.iter("portType");
            String name = portTypes.isEmpty() ? null : portTypes.getFirst().get("name");
            contract(facts, path, or(name, TextUtil.stem(path)), null, ns, ops, root.line, httpOnly);
            return;
        }
        for (Xml.El svc : services) {
            String name = or(svc.get("name"), TextUtil.stem(path));
            String address = null;
            for (Xml.El el : svc.iter()) {
                if (el.tag.equals("address") && or(el.get("location"), null) != null) {
                    address = el.get("location");
                    break;
                }
                if (el.tag.equals("endpoint") && or(el.get("address"), null) != null) {
                    address = el.get("address");
                    break;
                }
            }
            contract(facts, path, name, address, ns, ops, svc.line, httpOnly);
        }
    }

    private static void contract(Facts facts, String path, String name, String address, String ns, List<Doc> ops, Integer line,
            boolean httpOnly) {
        String url = address != null && !address.isEmpty() ? Integrations.maskUserinfo(address) : null;
        List<Doc> copies = new ArrayList<>();
        for (Doc o : ops) {
            copies.add(o.copy());
        }
        facts.add("web_services", Doc.of(Schemas.WEB_SERVICE,
                "id", Fact.makeId("websvc-use", "contract", path, name),
                "name", name,
                "style", httpOnly ? "REST" : "SOAP",
                "side", "contract",
                "frameworks", new ArrayList<>(List.of("WSDL")),
                "url", url,
                "wsdl", path,
                "namespace", ns,
                "files", new ArrayList<>(List.of(path)),
                "operations", copies,
                "evidence", new ArrayList<>(List.of(new Evidence(path, line, null)))));
    }

    // ---- Axis 1 and Axis2 -------------------------------------------------------------------

    private static void wsdd(Xml.El root, String path, Facts facts) {
        if (!root.tag.equals("deployment")) {
            return;
        }
        for (Xml.El svc : root.findall("service")) {
            String name = svc.get("name");
            if (name == null || name.isEmpty() || !svc.get("provider", "").toLowerCase(Locale.ROOT).startsWith("java:")) {
                continue;
            }
            Map<String, String> params = new HashMap<>();
            for (Xml.El p : svc.findall("parameter")) {
                params.put(p.get("name", ""), p.get("value", ""));
            }
            String cls = or(params.get("className"), or(params.get("beanJndiName"), name));
            List<String> methods = new ArrayList<>();
            for (String m : METHOD_SEP.split(TextUtil.strip(params.getOrDefault("allowedMethods", "*")), -1)) {
                if (!m.isEmpty()) {
                    methods.add(m);
                }
            }
            if (methods.isEmpty()) {
                methods.add("*");
            }
            for (String m : methods) {
                WebSvc.offeredService(facts, name + "." + m, "soap-operation", path, svc.line, !m.equals("*") ? cls + "." + m : cls,
                        "/services/" + name, "SOAP", "Apache Axis", name);
            }
            String wsdl = params.get("wsdlFile");
            if (wsdl != null && !wsdl.isEmpty()) {
                facts.symbol("ws-wsdl", name, path, svc.line).data("wsdl", wsdl);
            }
        }
        facts.add("tech_stack", tech("Apache Axis", null, "framework", path, root.line));
    }

    private static void axis2(Xml.El root, String path, Facts facts) {
        List<Xml.El> svcs = root.tag.equals("service") ? List.of(root) : root.iter("service");
        if (svcs.isEmpty()) {
            return;
        }
        for (Xml.El svc : svcs) {
            String name = or(svc.get("name"), TextUtil.basename(TextUtil.dirname(TextUtil.dirname(path))));
            String cls = "";
            for (Xml.El p : svc.findall("parameter")) {
                String pn = p.get("name");
                if ("ServiceClass".equals(pn) || "SpringBeanName".equals(pn)) {
                    cls = p.textOf();
                }
            }
            List<String> ops = new ArrayList<>();
            for (Xml.El o : svc.findall("operation")) {
                String on = o.get("name", "");
                if (!on.isEmpty()) {
                    ops.add(on);
                }
            }
            if (ops.isEmpty()) {
                ops.add("*");
            }
            for (String m : ops) {
                WebSvc.offeredService(facts, name + "." + m, "soap-operation", path, svc.line,
                        !m.equals("*") ? or(cls, name) + "." + m : or(cls, name), "/services/" + name, "SOAP", "Apache Axis2", name);
            }
        }
        facts.add("tech_stack", tech("Apache Axis2", null, "framework", path, root.line));
    }

    // ---- where implementation classes are published -----------------------------------------

    private static void endpoint(Facts facts, String path, Integer line, String cls, String url, String framework) {
        facts.symbol("ws-endpoint", cls, path, line).data("url", url).data("framework", framework);
    }

    private static void webXml(Xml.El root, String path, Facts facts) {
        record Servlet(String cls, Integer line) {}
        Map<String, Servlet> names = new LinkedHashMap<>();
        for (Xml.El s : root.iter("servlet")) {
            String cls = TextUtil.strip(or(s.childText("servlet-class"), ""));
            String sname = TextUtil.strip(or(s.childText("servlet-name"), ""));
            if (SERVLETS.containsKey(cls)) {
                names.put(sname, new Servlet(cls, s.line));
            }
        }
        for (Xml.El m : root.iter("servlet-mapping")) {
            String sname = TextUtil.strip(or(m.childText("servlet-name"), ""));
            Servlet servlet = names.get(sname);
            if (servlet == null) {
                continue;
            }
            for (Xml.El u : m.findall("url-pattern")) {
                String pattern = u.textOf();
                String url = TextUtil.rstrip(TextUtil.rstrip(pattern, "*"), "/");
                facts.symbol("ws-servlet", SERVLETS.get(servlet.cls()), path, u.line != null ? u.line : servlet.line())
                        .data("url", url.isEmpty() ? "/" : url);
            }
        }
    }

    private static void cxf(Xml.El root, String path, Facts facts) {
        Map<String, String> beans = new HashMap<>();
        for (Xml.El b : root.iter("bean")) {
            String id = b.get("id");
            if (id != null && !id.isEmpty()) {
                beans.put(id, b.get("class", ""));
            }
        }
        java.util.function.UnaryOperator<String> resolve =
                ref -> ref.startsWith("#") ? beans.getOrDefault(TextUtil.lstrip(ref, "#"), "") : ref;
        boolean used = false;
        for (Xml.El el : root.iter()) {
            String ns = ns(el);
            if (ns.contains("cxf.apache.org/jaxws") && (el.tag.equals("endpoint") || el.tag.equals("server"))) {
                used = true;
                List<String> impls = new ArrayList<>();
                impls.add(resolve.apply(el.get("implementor", "")));
                impls.add(el.get("implementorClass", ""));
                for (Xml.El sb : el.iter("serviceBean")) {
                    for (Xml.El b : sb.iter("bean")) {
                        impls.add(b.get("class", ""));
                    }
                    for (Xml.El r : sb.iter("ref")) {
                        impls.add(resolve.apply("#" + r.get("bean", "")));
                    }
                }
                String serviceClass = el.get("serviceClass");
                if (serviceClass != null && !serviceClass.isEmpty()) {
                    impls.add(serviceClass);
                }
                for (String cls : distinct(impls)) {
                    endpoint(facts, path, el.line, cls, el.get("address", ""), "Apache CXF");
                }
            } else if (ns.contains("cxf.apache.org/jaxrs") && el.tag.equals("server")) {
                used = true;
                List<String> impls = new ArrayList<>();
                for (Xml.El sb : el.iter("serviceBeans")) {
                    for (Xml.El b : sb.iter("bean")) {
                        impls.add(b.get("class", ""));
                    }
                    for (Xml.El r : sb.iter("ref")) {
                        impls.add(resolve.apply("#" + r.get("bean", "")));
                    }
                }
                for (String cls : distinct(impls)) {
                    endpoint(facts, path, el.line, cls, el.get("address", ""), "Apache CXF");
                }
            } else if (ns.contains("cxf.apache.org/jaxws") && el.tag.equals("client")) {
                used = true;
                String svc = or(el.get("serviceClass", ""), el.get("id", "client"));
                facts.add("web_services", WebSvc.called("SOAP", TextUtil.afterLast(svc, "."), "Apache CXF", path, el.line,
                        or(el.get("address"), null), null, null, null, or(el.get("wsdlLocation"), null), null));
            }
        }
        if (used) {
            facts.add("tech_stack", tech("Apache CXF", null, "framework", path, root.line));
        }
    }

    /**
     * The non-empty names once each. Python iterates a set here, whose order changes from run to
     * run; the order of first appearance is used instead.
     */
    private static Set<String> distinct(List<String> names) {
        Set<String> out = new LinkedHashSet<>();
        for (String n : names) {
            if (!n.isEmpty()) {
                out.add(n);
            }
        }
        return out;
    }
}
