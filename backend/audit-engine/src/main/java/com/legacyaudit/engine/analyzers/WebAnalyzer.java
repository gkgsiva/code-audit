package com.legacyaudit.engine.analyzers;

import static com.legacyaudit.engine.analyzers.BuildAnalyzer.tech;
import static com.legacyaudit.engine.analyzers.TextUtil.truthy;

import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Schemas;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java web tier: Struts 1/2 configuration, {@code web.xml}, Tiles, JSF {@code faces-config.xml}
 * and pages (JSP, JSF facelets, HTML, Velocity, FreeMarker). A page is a screen;
 * {@code .jspf}/{@code .tag} files are partials.
 */
public final class WebAnalyzer implements Analyzer {

    public static final Map<String, String> FRAMEWORK_SERVLETS = ordered(
            "org.apache.struts.action.ActionServlet", "Struts 1",
            "org.springframework.web.servlet.DispatcherServlet", "Spring MVC",
            "javax.faces.webapp.FacesServlet", "JSF",
            "jakarta.faces.webapp.FacesServlet", "JSF",
            "org.apache.axis.transport.http.AxisServlet", "Apache Axis",
            "org.apache.cxf.transport.servlet.CXFServlet", "Apache CXF",
            "com.sun.xml.ws.transport.http.servlet.WSServlet", "JAX-WS RI",
            "org.springframework.ws.transport.http.MessageDispatcherServlet", "Spring-WS",
            "org.apache.tiles.web.startup.TilesServlet", "Tiles",
            "org.apache.axis2.transport.http.AxisServlet", "Apache Axis2",
            "com.sun.jersey.spi.container.servlet.ServletContainer", "Jersey (JAX-RS)",
            "org.glassfish.jersey.servlet.ServletContainer", "Jersey (JAX-RS)",
            "org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher", "RESTEasy (JAX-RS)",
            "org.apache.wink.server.internal.servlet.RestServlet", "Apache Wink (JAX-RS)");
    public static final Map<String, String> FRAMEWORK_FILTERS = ordered(
            "org.apache.struts2.dispatcher.FilterDispatcher", "Struts 2",
            "org.apache.struts2.dispatcher.ng.filter.StrutsPrepareAndExecuteFilter", "Struts 2",
            "org.apache.struts2.dispatcher.filter.StrutsPrepareAndExecuteFilter", "Struts 2",
            "org.springframework.web.filter.DelegatingFilterProxy", "Spring Security");
    public static final Map<String, String> FRAMEWORK_LISTENERS = ordered(
            "org.springframework.web.context.ContextLoaderListener", "Spring Framework");

    public static final Set<String> PAGE_LANGS = Set.of("jsp", "html", "velocity", "freemarker");

    private static final Pattern FIELD_TAGS = Rx.of(
            "<\\s*(?:"
            + "html:(?:text|password|textarea|select|checkbox|radio|file|multibox)"
            + "|form:(?:input|password|select|textarea|checkbox|checkboxes|radiobutton|radiobuttons)"
            + "|s:(?:textfield|password|select|textarea|checkbox|radio|file|datetimepicker)"
            + "|h:(?:inputText|inputSecret|inputTextarea|selectOneMenu|selectManyMenu|selectOneListbox|"
            + "selectManyListbox|selectOneRadio|selectBooleanCheckbox|selectManyCheckbox)"
            + "|p:(?:inputText|password|inputTextarea|selectOneMenu|calendar|autoComplete)"
            + "|mat-(?:select|checkbox|radio-group|slide-toggle|button-toggle-group|slider|chip-grid)"
            + "|p-(?:dropdown|calendar|checkbox|inputNumber|autoComplete|multiSelect|radioButton)"
            + "|select|textarea"
            + ")\\b", Rx.I);
    // Angular templates: control flow, bindings, forms; AngularJS: ng-* directives.
    private static final Pattern ANGULAR_MARK = Rx.of(
            "\\*ng(If|For|Switch)\\b|\\[\\(ngModel\\)\\]|formControlName|\\[formGroup\\]|routerLink\\b|"
            + "<router-outlet|@(if|for|switch)\\s*\\(|\\(click\\)=|\\{\\{[^}]*\\|\\s*async");
    private static final Pattern ANGULARJS_MARK = Rx.of("\\bng-(app|controller|model|repeat|click|view|if|show|submit)\\b");
    private static final Pattern NG_APP = Rx.of("\\b(ng-app|data-ng-app)\\b|<ui-view|\\bng-view\\b", Rx.I);
    private static final Pattern CUSTOM_TAG = Rx.of("<([a-z][a-z0-9]*-[a-z0-9-]+)\\b");
    private static final Pattern INPUT = Rx.of("<\\s*input\\b([^>]*)>", Rx.I);
    private static final Pattern INPUT_TYPE = Rx.of("type\\s*=\\s*[\"']?(\\w+)", Rx.I);
    private static final Pattern FORM_ACTION = Rx.of(
            "<\\s*(?:html:form|s:form|form:form|form)\\b[^>]*?\\baction\\s*=\\s*[\"']([^\"']+)", Rx.I);
    private static final Pattern INCLUDE_DIRECTIVE = Rx.of("<%@\\s*include\\s+file\\s*=\\s*[\"']([^\"']+)", Rx.I);
    private static final Pattern INCLUDE_ACTION = Rx.of(
            "<\\s*(?:jsp:include|c:import|tiles:insert|tiles:insertTemplate|ui:include|ui:composition"
            + "|ui:decorate)\\b[^>]*?\\b(?:page|url|template|src)\\s*=\\s*[\"']([^\"'$#]+)", Rx.I);
    private static final Pattern TILES_DEF = Rx.of(
            "<\\s*tiles:insert(?:Definition)?\\b[^>]*?\\b(?:definition|name)\\s*=\\s*[\"']([^\"'$]+)", Rx.I);
    private static final Pattern HREF_ACTION = Rx.of(
            "\\bhref\\s*=\\s*[\"']([^\"'?#$<]+?\\.(?:do|action|htm|html|jsp|xhtml|jsf))", Rx.I);
    private static final Pattern VELOCITY_INCLUDE = Rx.of("#(?:parse|include)\\s*\\(\\s*[\"']([^\"']+)", Rx.I);
    private static final Pattern FTL_INCLUDE = Rx.of("<#(?:include|import)\\s+[\"']([^\"']+)", Rx.I);
    private static final Pattern JSF_MARK = Rx.of("xmlns:h\\s*=|<f:view|<h:form|java\\.sun\\.com/jsf|xmlns\\.jcp\\.org/jsf", Rx.I);
    private static final Pattern DO_ACTION = Rx.of("\\.(do|action)$");
    private static final Pattern DO_ACTION_HTM = Rx.of("\\.(do|action|htm)$");
    private static final Set<String> NOT_FIELDS = Set.of("hidden", "submit", "button", "reset", "image");
    private static final Set<String> NG_BUILTIN = Set.of("router-outlet", "ng-container", "ng-template", "ng-content");

    @Override
    public String name() {
        return "web";
    }

    @Override
    public String version() {
        return "3";
    }

    static Map<String, String> ordered(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Input fields of a page or template (hidden inputs and buttons excluded). */
    public static int countFields(String text) {
        int fields = 0;
        Matcher f = FIELD_TAGS.matcher(text);
        while (f.find()) {
            fields++;
        }
        Matcher m = INPUT.matcher(text);
        while (m.find()) {
            Matcher t = INPUT_TYPE.matcher(m.group(1));
            if (!t.find() || !NOT_FIELDS.contains(t.group(1).toLowerCase(Locale.ROOT))) {
                fields++;
            }
        }
        return fields;
    }

    /** jsf, angular, angularjs or the file's language. */
    public static String pageTechnology(String path, String language, String text) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (("jsp".equals(language) || "html".equals(language))
                && (lower.endsWith(".xhtml") || lower.endsWith(".jsf") || JSF_MARK.matcher(text).find())) {
            return "jsf";
        }
        if ("html".equals(language)) {
            if (lower.endsWith(".component.html") || ANGULAR_MARK.matcher(text).find()) {
                return "angular";
            }
            if (ANGULARJS_MARK.matcher(text).find()) {
                return "angularjs";
            }
        }
        return language;
    }

    /** A page reference: "/x" is webapp-relative; else relative to the page folder. */
    static String relTarget(String baseFile, String target) {
        if (target.startsWith("/")) {
            return target;
        }
        return TextUtil.normpath(TextUtil.joinPath(TextUtil.dirname(baseFile), target));
    }

    @Override
    public boolean supports(FileRecord file) {
        String base = TextUtil.afterLast(file.path, "/").toLowerCase(Locale.ROOT);
        if (PAGE_LANGS.contains(file.language)) {
            return true;
        }
        return "xml".equals(file.language)
                && (Set.of("web.xml", "struts.xml", "faces-config.xml", "struts-default.xml").contains(base)
                        || base.startsWith("struts-config") || base.contains("tiles"));
    }

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        Facts facts = new Facts();
        String text = ctx.textOrEmpty();
        facts.coverage.lines = TextUtil.lineCount(text);
        if (PAGE_LANGS.contains(file.language)) {
            page(file, text, facts);
            return facts;
        }
        Xml.El root;
        try {
            root = Xml.parse(text);
        } catch (Xml.ParseError err) {
            facts.coverage.gap(1, facts.coverage.lines, "XML parse error: " + err.getMessage());
            return facts;
        }
        switch (root.tag) {
            case "struts-config" -> struts1(file.path, root, facts);
            case "struts" -> struts2(file.path, root, facts);
            case "web-app" -> webXml(file.path, root, facts);
            case "tiles-definitions", "component-definitions" -> tiles(file.path, root, facts);
            case "faces-config" -> faces(file.path, root, facts);
            default -> { }
        }
        return facts;
    }

    // ---- Struts -----------------------------------------------------------------------------

    static void forwardRef(Facts facts, String source, String label, String path, String target, Integer line) {
        target = TextUtil.strip(target);
        if (target.isEmpty()) {
            return;
        }
        String clean = TextUtil.beforeFirst(target, "?");
        String last = TextUtil.afterLast(clean, "/");
        if (clean.endsWith(".do") || clean.endsWith(".action") || (!last.contains(".") && clean.startsWith("/"))) {
            facts.ref("forwards", source, label, DO_ACTION.matcher(clean).replaceAll(""), List.of("struts-action"), path, line);
        } else if (!clean.contains("/") && clean.contains(".")
                && !(clean.endsWith(".jsp") || clean.endsWith(".html") || clean.endsWith(".vm") || clean.endsWith(".ftl"))) {
            facts.ref("forwards", source, label, clean, List.of("tiles-definition"), path, line);
        } else {
            facts.ref("forwards", source, label, clean, List.of("path"), path, line);
        }
    }

    private static Doc service(String sid, String name, String kind, String path, String handler, String url, List<String> inputs, Integer line) {
        return Doc.of(Schemas.SERVICE,
                "id", sid,
                "name", name,
                "kind", kind,
                "file", path,
                "handler", handler,
                "url", url,
                "inputs", new ArrayList<>(inputs),
                "evidence", new ArrayList<>(List.of(new Evidence(path, line, null))));
    }

    private void struts1(String path, Xml.El root, Facts facts) {
        facts.add("tech_stack", tech("Struts 1", null, "framework", path, root.line));
        for (Xml.El fb : root.iter("form-bean")) {
            if (truthy(fb.get("type"))) {
                facts.ref("uses", "file:" + path, path, fb.get("type"), List.of("java-class"), path, fb.line);
            }
        }
        Xml.El gf = root.find("global-forwards");
        if (gf != null) {
            for (Xml.El fwd : gf.iter("forward")) {
                forwardRef(facts, "file:" + path, path, path, fwd.get("path", ""), fwd.line);
            }
        }
        for (Xml.El action : root.iter("action")) {
            String apath = action.get("path", "");
            if (apath.isEmpty()) {
                continue;
            }
            Integer line = action.line;
            String sid = Fact.makeId("service", "struts-action", path, apath);
            String handler = truthy(action.get("type")) ? action.get("type")
                    : truthy(action.get("forward")) ? "forward:" + action.get("forward") : null;
            facts.add("services", service(sid, apath, "struts-action", path, handler, apath,
                    truthy(action.get("name")) ? List.of(action.get("name")) : List.of(), line));
            facts.symbol("struts-action", apath, path, line).owner(sid);
            String source = "service:" + sid;
            if (truthy(action.get("type"))) {
                facts.ref("calls", source, apath, action.get("type"), List.of("java-class"), path, line);
            }
            for (String key : List.of("forward", "include", "input")) {
                if (truthy(action.get(key))) {
                    forwardRef(facts, source, apath, path, action.get(key), line);
                }
            }
            for (Xml.El fwd : action.iter("forward")) {
                forwardRef(facts, source, apath, path, fwd.get("path", ""), fwd.line);
            }
        }
    }

    private void struts2(String path, Xml.El root, Facts facts) {
        facts.add("tech_stack", tech("Struts 2", null, "framework", path, root.line));
        for (Xml.El pkg : root.iter("package")) {
            String ns = TextUtil.rstrip(pkg.get("namespace", "/"), "/");
            for (Xml.El action : pkg.iter("action")) {
                String name = action.get("name", "");
                String apath = ns + "/" + name;
                Integer line = action.line;
                String sid = Fact.makeId("service", "struts-action", path, apath);
                String handler = action.get("class");
                if (truthy(handler) && truthy(action.get("method"))) {
                    handler += "." + action.get("method");
                }
                facts.add("services", service(sid, apath, "struts-action", path, handler, apath, List.of(), line));
                facts.symbol("struts-action", apath, path, line).owner(sid);
                if (truthy(action.get("class"))) {
                    facts.ref("calls", "service:" + sid, apath, action.get("class"), List.of("java-class"), path, line);
                }
                for (Xml.El result : action.iter("result")) {
                    String target = TextUtil.or(result.textOf(), result.get("location", ""));
                    String type = result.get("type");
                    if ("redirectAction".equals(type) || "chain".equals(type)) {
                        target = ns + "/" + target + ".action";
                    }
                    forwardRef(facts, "service:" + sid, apath, path, target, result.line);
                }
            }
        }
    }

    private void tiles(String path, Xml.El root, Facts facts) {
        facts.add("tech_stack", tech("Tiles", null, "framework", path, root.line));
        for (Xml.El d : root.iter("definition")) {
            String name = d.get("name", "");
            Integer line = d.line;
            facts.symbol("tiles-definition", name, path, line);
            String source = "tiles:" + name;
            List<String> targets = new ArrayList<>();
            targets.add(TextUtil.or(TextUtil.or(d.get("path"), d.get("template")), ""));
            for (Xml.El p : d.iter("put")) {
                if (p.get("value", "").startsWith("/")) {
                    targets.add(p.get("value", ""));
                }
            }
            for (Xml.El p : d.iter("put-attribute")) {
                if (p.get("value", "").startsWith("/")) {
                    targets.add(p.get("value", ""));
                }
            }
            for (String target : targets) {
                if (!target.isEmpty()) {
                    facts.ref("includes", source, name, target, List.of("path"), path, line);
                }
            }
            if (truthy(d.get("extends"))) {
                facts.ref("includes", source, name, d.get("extends"), List.of("tiles-definition"), path, line);
            }
        }
    }

    private void faces(String path, Xml.El root, Facts facts) {
        facts.add("tech_stack", tech("JSF", root.get("version"), "framework", path, root.line));
        for (Xml.El bean : root.iter("managed-bean")) {
            String cls = bean.childText("managed-bean-class");
            String name = bean.childText("managed-bean-name");
            if (truthy(cls) && truthy(name)) {
                facts.symbol("jsf-bean", name, path, bean.line).data("class", cls);
                facts.ref("uses", "file:" + path, path, cls, List.of("java-class"), path, bean.line);
            }
        }
        for (Xml.El view : root.iter("to-view-id")) {
            facts.ref("forwards", "file:" + path, path, view.textOf(), List.of("path"), path, view.line);
        }
    }

    // ---- web.xml ----------------------------------------------------------------------------

    private void webXml(String path, Xml.El root, Facts facts) {
        String version = root.get("version");
        if (truthy(version)) {
            facts.add("tech_stack", tech("Servlet API", version, "runtime", path, root.line));
        }
        Map<String, List<String>> mappings = new LinkedHashMap<>();
        for (Xml.El m : root.iter("servlet-mapping")) {
            String name = TextUtil.or(m.childText("servlet-name"), "");
            List<String> urls = mappings.computeIfAbsent(name, k -> new ArrayList<>());
            for (Xml.El u : m.iter("url-pattern")) {
                urls.add(u.textOf());
            }
        }
        for (Xml.El s : root.iter("servlet")) {
            String name = TextUtil.or(s.childText("servlet-name"), "");
            String cls = s.childText("servlet-class");
            String jsp = s.childText("jsp-file");
            Integer line = s.line;
            if (cls != null && FRAMEWORK_SERVLETS.containsKey(cls)) {
                facts.add("tech_stack", tech(FRAMEWORK_SERVLETS.get(cls), null, "framework", path, line));
                facts.symbol("front-controller", name, path, line)
                        .data("class", cls)
                        .data("patterns", String.join(",", mappings.getOrDefault(name, List.of())));
                continue;
            }
            String sid = Fact.makeId("service", "servlet", path, name);
            String url = String.join(", ", mappings.getOrDefault(name, List.of()));
            facts.add("services", service(sid, name, "servlet", path, truthy(cls) ? cls : jsp, url.isEmpty() ? null : url, List.of(), line));
            if (truthy(cls)) {
                facts.ref("calls", "service:" + sid, name, cls, List.of("java-class"), path, line);
            }
            if (truthy(jsp)) {
                forwardRef(facts, "service:" + sid, name, path, jsp, line);
            }
        }
        record Kind(String kind, String tag, String clsTag, Map<String, String> known) {}
        for (Kind k : List.of(new Kind("filter", "filter", "filter-class", FRAMEWORK_FILTERS),
                new Kind("listener", "listener", "listener-class", FRAMEWORK_LISTENERS))) {
            for (Xml.El el : root.iter(k.tag())) {
                String cls = el.childText(k.clsTag());
                if (!truthy(cls)) {
                    continue;
                }
                Integer line = el.line;
                if (k.known().containsKey(cls)) {
                    facts.add("tech_stack", tech(k.known().get(cls), null, "framework", path, line));
                    continue;
                }
                facts.add("entry_points", Doc.of(Schemas.ENTRY_POINT,
                        "id", Fact.makeId("entry", path, cls, k.kind()),
                        "name", cls,
                        "kind", k.kind(),
                        "file", path,
                        "line", line,
                        "evidence", new ArrayList<>(List.of(new Evidence(path, line, null)))));
                facts.ref("uses", "file:" + path, path, cls, List.of("java-class"), path, line);
            }
        }
        List<Xml.El> targets = new ArrayList<>(root.iter("welcome-file"));
        targets.addAll(root.iter("location"));
        for (Xml.El el : targets) {
            String target = el.textOf();
            if (!target.isEmpty()) {
                facts.ref("forwards", "file:" + path, path, target.startsWith("/") ? target : "/" + target, List.of("path"), path, el.line)
                        .reportUnresolved(el.tag.equals("location"));
            }
        }
        if (hasDescendant(root, "security-constraint") && hasDescendant(root, "login-config")) {
            List<Xml.El> auth = root.iter("auth-method");
            String a = auth.isEmpty() ? null : auth.getFirst().textOf();
            facts.add("tech_stack", tech("Container security (" + TextUtil.or(a, "unknown") + ")", null, "auth", path, null));
        }
    }

    private static boolean hasDescendant(Xml.El root, String tag) {
        for (Xml.El e : root.iter()) {
            if (e != root && e.tag.equals(tag)) {
                return true;
            }
        }
        return false;
    }

    // ---- pages ------------------------------------------------------------------------------

    private void page(FileRecord file, String text, Facts facts) {
        String path = file.path;
        String lower = path.toLowerCase(Locale.ROOT);
        String techName = pageTechnology(path, file.language, text);
        boolean partial = lower.endsWith(".jspf") || lower.endsWith(".tag") || lower.endsWith(".inc") || lower.endsWith(".tagx");
        int fields = countFields(text);
        Matcher actionM = FORM_ACTION.matcher(text);
        String action = actionM.find() ? actionM.group(1) : null;
        String sid = Fact.makeId("screen", path);
        facts.add("screens", Doc.of(Schemas.SCREEN,
                "id", sid,
                "name", path,
                "technology", techName,
                "file", path,
                "action", action,
                "fields_count", fields,
                "partial", partial,
                "evidence", new ArrayList<>(List.of(new Evidence(path, 1, null)))));
        facts.symbol("page", path, path, 1).owner(sid);
        String source = "screen:" + sid;
        TextUtil.LineIndex lines = new TextUtil.LineIndex(text);
        if (techName.equals("angular") || techName.equals("angularjs")) {
            spaTemplate(path, sid, text, lines, facts);
        }
        for (Pattern rx : List.of(INCLUDE_DIRECTIVE, INCLUDE_ACTION, VELOCITY_INCLUDE, FTL_INCLUDE)) {
            Matcher m = rx.matcher(text);
            while (m.find()) {
                String g = m.group(1);
                facts.ref("includes", source, path, g.startsWith("/") ? g : relTarget(path, g), List.of("path"), path, lines.line(m.start()))
                        .data("relative_to", path);
            }
        }
        Matcher t = TILES_DEF.matcher(text);
        while (t.find()) {
            facts.ref("includes", source, path, t.group(1), List.of("tiles-definition"), path, lines.line(t.start()));
        }
        Matcher f = FORM_ACTION.matcher(text);
        while (f.find()) {
            nav(facts, source, path, f.group(1), lines.line(f.start()), "submits");
        }
        Matcher h = HREF_ACTION.matcher(text);
        while (h.find()) {
            nav(facts, source, path, h.group(1), lines.line(h.start()), "links");
        }
    }

    /**
     * Angular/AngularJS template: the sub-components it places and, for the page that starts
     * the application, a shell mark.
     */
    private void spaTemplate(String path, String sid, String text, TextUtil.LineIndex lines, Facts facts) {
        if (NG_APP.matcher(text).find()) {
            facts.symbol("spa-shell", path, path, 1).owner(sid);
        }
        Set<String> seen = new HashSet<>();
        Matcher m = CUSTOM_TAG.matcher(text);
        while (m.find()) {
            String tag = m.group(1);
            if (seen.contains(tag) || NG_BUILTIN.contains(tag)) {
                continue;
            }
            seen.add(tag);
            facts.ref("includes", "screen:" + sid, path, tag, List.of("angular-selector", "angularjs-component", "angularjs-directive"),
                    path, lines.line(m.start())).quiet();
        }
    }

    private void nav(Facts facts, String source, String path, String target, int line, String kind) {
        if (target.contains("://") || target.startsWith("javascript:") || target.startsWith("mailto:")) {
            return;
        }
        String clean = TextUtil.beforeFirst(target, "?");
        if (clean.endsWith(".jsp") || clean.endsWith(".html") || clean.endsWith(".xhtml") || clean.endsWith(".jsf")) {
            facts.ref(kind, source, path, relTarget(path, clean), List.of("path"), path, line).quiet().data("relative_to", path);
            return;
        }
        String name = DO_ACTION_HTM.matcher(clean).replaceAll("");
        if (!name.startsWith("/")) {
            name = "/" + TextUtil.afterLast(name, "/");
        }
        facts.ref(kind, source, path, name, List.of("struts-action", "url"), path, line).quiet();
    }
}
