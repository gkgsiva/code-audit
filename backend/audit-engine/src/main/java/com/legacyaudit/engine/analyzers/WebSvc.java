package com.legacyaudit.engine.analyzers;

import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.Schemas;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web services (REST and SOAP): the builders every analyzer uses. Services the application
 * offers are service facts; services it calls are {@code web_services} facts with
 * {@code side: called}, one per call site (decision 133).
 */
public final class WebSvc {

    private WebSvc() {}

    public static final List<String> HTTP_VERBS = List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    /** web.xml servlet class: framework of the services it publishes. */
    public static final Map<String, String> SERVLETS = WebAnalyzer.ordered(
            "org.apache.axis.transport.http.AxisServlet", "Apache Axis",
            "org.apache.axis2.transport.http.AxisServlet", "Apache Axis2",
            "org.apache.cxf.transport.servlet.CXFServlet", "Apache CXF",
            "com.sun.jersey.spi.container.servlet.ServletContainer", "JAX-RS",
            "org.glassfish.jersey.servlet.ServletContainer", "JAX-RS",
            "org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher", "JAX-RS",
            "org.apache.wink.server.internal.servlet.RestServlet", "JAX-RS",
            "com.sun.xml.ws.transport.http.servlet.WSServlet", "JAX-WS",
            "org.springframework.ws.transport.http.MessageDispatcherServlet", "Spring-WS");

    private static final Pattern PLACEHOLDER_PREFIX = Rx.of("^(\\$\\{[^}]*\\}|\\{\\{[^}]*\\}\\}|\\?(?=/))+");
    private static final Pattern PATH_END = Rx.of("[#]|\\?(?=\\w+=)");
    private static final Pattern URL_PATH_END = Rx.of("[#]|\\?(?=\\w+=)|\\?wsdl$", Rx.I);

    /** (scheme://host[:port] or null, path) of a URL found in code. */
    public static String[] splitUrl(String text) {
        text = Integrations.maskUserinfo(TextUtil.strip(TextUtil.strip(text), "'\"`"));
        if (!text.contains("://")) {
            // a base URL from configuration or a variable: ${payment.url}/charges, ?/orders
            text = PLACEHOLDER_PREFIX.matcher(text).replaceFirst("");
            return new String[] {null, splitFirst(PATH_END, text)};
        }
        String scheme = TextUtil.beforeFirst(text, "://");
        String rest = TextUtil.afterFirst(text, "://");
        int slash = rest.indexOf('/');
        String netloc = slash >= 0 ? rest.substring(0, slash) : rest;
        String tail = slash >= 0 ? rest.substring(slash + 1) : "";
        String path = TextUtil.rstrip(splitFirst(URL_PATH_END, tail.isEmpty() ? "" : "/" + tail), "/");
        if (netloc.contains("?") || netloc.contains("$") || netloc.contains("{") || netloc.contains("%s")) {
            return new String[] {null, path};
        }
        String host = PyUrl.split(scheme + "://" + netloc).hostname();
        String s = scheme.toLowerCase(Locale.ROOT);
        if (host == null || host.isEmpty() || !(s.equals("http") || s.equals("https")) || Integrations.IGNORED_HOSTS.matcher(host).find()) {
            return new String[] {null, path};
        }
        return new String[] {s + "://" + TextUtil.afterLast(netloc, "@").toLowerCase(Locale.ROOT), path};
    }

    private static String splitFirst(Pattern rx, String text) {
        Matcher m = rx.matcher(text);
        return m.find() ? text.substring(0, m.start()) : text;
    }

    public static Doc operation(String name, String method, String path, String handler, String file, Integer line) {
        return Doc.of(Schemas.WEB_OPERATION,
                "name", name,
                "method", method,
                "path", path,
                "handler", handler,
                "file", file,
                "line", line);
    }

    public static String restOpName(String method, String path) {
        return (method == null || method.isEmpty() ? "ANY" : method).toUpperCase(Locale.ROOT) + " " + (path == null || path.isEmpty() ? "/" : path);
    }

    /** One call site of another system's web service ({@code url} as written; {@code op} the SOAP operation). */
    public static Doc called(String style, String name, String framework, String file, Integer line, String url, String op, String method,
            String handler, String wsdl, String namespace) {
        String base = null;
        String path = "";
        if (url != null && !url.isEmpty()) {
            String[] bp = splitUrl(url);
            base = bp[0];
            path = bp[1];
        }
        List<Doc> ops = new ArrayList<>();
        boolean hasMethod = method != null && !method.isEmpty();
        if (style.equals("REST") && ((url != null && !url.isEmpty()) || hasMethod)) {
            ops.add(operation(restOpName(method, path.isEmpty() ? null : path), (hasMethod ? method : "ANY").toUpperCase(Locale.ROOT),
                    path.isEmpty() ? null : path, handler, file, line));
        } else if (op != null && !op.isEmpty()) {
            ops.add(operation(op, null, path.isEmpty() ? null : path, handler, file, line));
        }
        String full = base != null && !base.isEmpty() ? base + path : (url == null || url.isEmpty() ? null : url);
        return Doc.of(Schemas.WEB_SERVICE,
                "id", Fact.makeId("websvc-use", "called", style, file, line, name, op == null ? "" : op, method == null ? "" : method, url),
                "name", name,
                "style", style,
                "side", "called",
                "frameworks", new ArrayList<>(List.of(framework)),
                "url", full,
                "wsdl", wsdl,
                "namespace", namespace,
                "files", new ArrayList<>(List.of(file)),
                "callers", new ArrayList<>(List.of(file)),
                "operations", ops,
                "evidence", new ArrayList<>(List.of(new Evidence(file, line, null))));
    }

    /** A web service operation the application offers, as a service fact. */
    public static void offeredService(Facts facts, String name, String kind, String file, Integer line, String handler, String url,
            String protocol, String framework, String webService) {
        String sid = Fact.makeId("service", kind, file, name);
        facts.add("services", Doc.of(Schemas.SERVICE,
                "id", sid,
                "name", name,
                "kind", kind,
                "file", file,
                "handler", handler,
                "url", url,
                "protocol", protocol,
                "framework", framework,
                "web_service", webService,
                "evidence", new ArrayList<>(List.of(new Evidence(file, line, null)))));
        facts.symbol("service", handler, file, line).owner(sid);
        if ((file.endsWith(".wsdd") || file.endsWith(".xml")) && handler.contains(".")) {
            String last = TextUtil.afterLast(handler, ".");
            String cls = !last.isEmpty() && Character.isUpperCase(last.charAt(0)) ? handler : TextUtil.beforeLast(handler, ".");
            facts.ref("calls", "service:" + sid, name, cls, List.of("java-class"), file, line);
        }
    }
}
