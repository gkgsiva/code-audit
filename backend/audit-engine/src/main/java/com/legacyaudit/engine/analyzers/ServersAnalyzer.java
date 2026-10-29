package com.legacyaudit.engine.analyzers;

import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Category;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Schemas;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Application servers and web servers: which ones the application is built for or runs on,
 * from the files that only make sense on one product (decision 131): deployment descriptors,
 * server configuration, container images, start and admin scripts and vendor APIs imported by
 * Java code. Each is a tech-stack item with the file and line as evidence; server-specific
 * descriptors and vendor APIs are also findings whose texts carry {@code [[server-move]]},
 * {@code [[server-api]]} and {@code [[server-remediation]]}, filled in by the assess stage.
 */
public final class ServersAnalyzer implements Analyzer {

    static final String WEBLOGIC = "Oracle WebLogic Server";
    static final String WEBSPHERE = "IBM WebSphere Application Server";
    static final String LIBERTY = "IBM WebSphere Liberty";
    static final String JBOSS = "JBoss / WildFly";
    static final String TOMCAT = "Apache Tomcat";
    static final String GLASSFISH = "GlassFish / Payara";
    static final String JETTY = "Eclipse Jetty";
    static final String RESIN = "Caucho Resin";
    static final String OC4J = "Oracle OC4J / iAS";
    static final String APACHE = "Apache HTTP Server";
    static final String IHS = "IBM HTTP Server";
    static final String OHS = "Oracle HTTP Server";
    static final String NGINX = "Nginx";
    static final String IIS = "Microsoft IIS";
    static final String APP = "app-server";
    static final String WEB = "web-server";

    private record Sign(Pattern rx, String server, String what) {}

    // Deployment descriptors: file name -> (server, what it holds).
    private static final List<Sign> DESCRIPTORS = List.of(
            new Sign(Rx.of("^weblogic(-application|-ejb-jar|-webservices|-ra|-cmp-rdbms-jar)?\\.xml$"), WEBLOGIC,
                    "WebLogic deployment descriptor"),
            new Sign(Rx.of("^ibm-(web|application|ejb-jar|webservices|webservicesclient)-(bnd|ext)\\.(xml|xmi)$"), WEBSPHERE,
                    "WebSphere binding/extension file"),
            new Sign(Rx.of("^jboss(-web|-app|-deployment-structure|-ejb3|-service|-client|-all)?\\.xml$"), JBOSS,
                    "JBoss deployment descriptor"),
            new Sign(Rx.of("^[\\w.-]+-ds\\.xml$"), JBOSS, "JBoss datasource definition"),
            new Sign(Rx.of("^(glassfish|sun|payara)-(web|application|ejb-jar|resources)\\.xml$"), GLASSFISH,
                    "GlassFish/Payara deployment descriptor"),
            new Sign(Rx.of("^jetty(-web|-env)?\\.xml$"), JETTY, "Jetty configuration"),
            new Sign(Rx.of("^resin(-web)?\\.xml$"), RESIN, "Resin configuration"),
            new Sign(Rx.of("^(orion-web|orion-application|orion-ejb-jar|data-sources)\\.xml$"), OC4J, "OC4J deployment descriptor"),
            new Sign(Rx.of("^(tomcat-users|catalina)\\.(xml|properties|policy)$"), TOMCAT, "Tomcat configuration"));

    private static final List<Sign> DTD_VERSION = List.of(
            new Sign(Rx.of("-//BEA Systems, Inc\\.//DTD (?:Web|Application|EJB)[^/]*?(\\d+(?:\\.\\d+)+)//"), WEBLOGIC, null),
            new Sign(Rx.of("-//JBoss//DTD [^/]*?(\\d+(?:\\.\\d+)+)//"), JBOSS, null),
            new Sign(Rx.of("-//Sun Microsystems, Inc\\.//DTD (?:Application Server|Sun ONE Application Server) (\\d+(?:\\.\\d+)*)"),
                    GLASSFISH, null));

    private static final Pattern DOMAIN_VERSION = Rx.of("<(?:\\w+:)?domain-version>\\s*([\\d.]+)\\s*<");
    private static final Pattern LIBERTY_RX = Rx.of("<featureManager\\b|<server\\s+description=", Rx.I);
    private static final Pattern TOMCAT_SERVER = Rx.of("<Server\\b[^>]*\\bport\\s*=|<Connector\\b[^>]*\\bprotocol\\s*=|Catalina");
    private static final Pattern TOMCAT_CONTEXT = Rx.of("^\\s*(<\\?xml[^>]*>\\s*)?(<!--.*?-->\\s*)*<Context\\b", Pattern.DOTALL);
    private static final Pattern JBOSS_DOMAIN = Rx.of("urn:jboss:domain:(\\d+(?:\\.\\d+)?)");
    private static final Pattern WAS_TRADITIONAL = Rx.of("xmlns:process=\"http://www\\.ibm\\.com/websphere/appserver");
    private static final Pattern PLUGIN_CFG = Rx.of("<Config\\b[^>]*>.*?<ServerCluster\\b", Pattern.DOTALL);
    private static final Pattern HTTPD = Rx.of("^\\s*(LoadModule|ServerRoot|<VirtualHost|Listen\\s+\\d|DocumentRoot)\\b", Rx.M);
    private static final Pattern IHS_RX = Rx.of("IBM HTTP Server|mod_ibm_ssl|WebSpherePluginConfig|mod_was_ap\\d+", Rx.I);
    private static final Pattern OHS_RX = Rx.of("Oracle HTTP Server|mod_wl_ohs|weblogic_module|WebLogicHost|WebLogicCluster");
    private static final Pattern MOD_JK = Rx.of("\\bJkMount\\b|mod_jk|^\\s*worker\\.\\w+\\.type\\s*=\\s*ajp", Rx.M);
    private static final Pattern NGINX_RX = Rx.of("^\\s*(http|server|upstream)\\s*\\{|^\\s*proxy_pass\\s+\\S+;|^\\s*worker_processes\\b", Rx.M);
    private static final Pattern IIS_RX = Rx.of("<system\\.webServer\\b|<aspNetCore\\b");
    private static final Pattern DOCKER_FROM = Rx.of("^\\s*FROM\\s+(?:--platform=\\S+\\s+)?([^\\s]+)", Rx.M | Rx.I);
    private static final Pattern WAS_PLUGIN = Rx.of("WebSpherePluginConfig|mod_was_ap");

    // Container base images: (image name, product, category); the tag is the version when numeric.
    private static final List<Sign> IMAGES = List.of(
            new Sign(Rx.of("(^|/)tomcat$"), TOMCAT, APP),
            new Sign(Rx.of("(^|/)(jboss/)?wildfly$|(^|/)wildfly/wildfly$"), JBOSS, APP),
            new Sign(Rx.of("(^|/)jboss-eap[\\w-]*$|jboss-eap-\\d"), JBOSS, APP),
            new Sign(Rx.of("(^|/)(websphere-liberty|open-liberty)$"), LIBERTY, APP),
            new Sign(Rx.of("(^|/)websphere-traditional$"), WEBSPHERE, APP),
            new Sign(Rx.of("(^|/)weblogic$|middleware/weblogic$"), WEBLOGIC, APP),
            new Sign(Rx.of("(^|/)(payara/server-\\w+|glassfish)$"), GLASSFISH, APP),
            new Sign(Rx.of("(^|/)jetty$"), JETTY, APP),
            new Sign(Rx.of("(^|/)nginx$|nginxinc/nginx-unprivileged$"), NGINX, WEB),
            new Sign(Rx.of("(^|/)httpd$"), APACHE, WEB),
            new Sign(Rx.of("(^|/)node$"), "Node.js", "runtime"),
            new Sign(Rx.of("(^|/)(openjdk|eclipse-temurin|amazoncorretto|ibmjava|ibm-semeru-runtimes)$"), "Java", "runtime"));

    // Scripts that start or administer one product.
    private static final List<Sign> SCRIPT_SIGNS = List.of(
            new Sign(Rx.of("\\b(startWebLogic|stopWebLogic|startManagedWebLogic|setDomainEnv)\\.(sh|cmd)\\b|"
                    + "\\bweblogic\\.(Deployer|WLST|Server)\\b|\\bwlst\\.sh\\b"), WEBLOGIC, APP),
            new Sign(Rx.of("\\bwsadmin(\\.sh|\\.bat)?\\b|\\b(startServer|stopServer|startManager)\\.sh\\b|"
                    + "\\bAdminConfig\\.|\\bAdminApp\\."), WEBSPHERE, APP),
            new Sign(Rx.of("\\b(standalone|domain|jboss-cli)\\.(sh|bat)\\b|\\bJBOSS_HOME\\b"), JBOSS, APP),
            new Sign(Rx.of("\\bcatalina\\.(sh|bat)\\b|\\bCATALINA_(HOME|BASE)\\b"), TOMCAT, APP),
            new Sign(Rx.of("\\basadmin\\b"), GLASSFISH, APP),
            new Sign(Rx.of("\\bapachectl\\b|\\bhttpd\\s+-k\\b"), APACHE, WEB),
            new Sign(Rx.of("\\bnginx\\s+-s\\b"), NGINX, WEB));

    // Java imports of vendor APIs.
    private static final List<Sign> VENDOR_API = List.of(
            new Sign(Rx.of("^\\s*import\\s+(weblogic\\.[\\w.*]+)", Rx.M), WEBLOGIC, null),
            new Sign(Rx.of("^\\s*import\\s+(com\\.ibm\\.(websphere|ws|wsspi|ejs)\\.[\\w.*]+)", Rx.M), WEBSPHERE, null),
            new Sign(Rx.of("^\\s*import\\s+(org\\.jboss\\.(ejb3|annotation|security|naming|system|mx|as\\.\\w+|wsf)\\b[\\w.*]*)", Rx.M),
                    JBOSS, null),
            new Sign(Rx.of("^\\s*import\\s+(org\\.apache\\.catalina\\.[\\w.*]+)", Rx.M), TOMCAT, null));

    private static final Pattern VERSION_TAG = Rx.of("^v?(\\d+(?:\\.\\d+){0,3})");
    private static final Set<String> NAMES = Set.of("config.xml", "server.xml", "context.xml", "standalone.xml", "domain.xml",
            "plugin-cfg.xml", "web.config", "workers.properties", ".htaccess", "dockerfile", "containerfile");

    @Override
    public String name() {
        return "servers";
    }

    @Override
    public String version() {
        return "2";
    }

    /** {@code tomcat:9.0-jdk11} gives 9.0, {@code node:18-alpine} 18; null for latest. */
    static String versionTag(String image) {
        if (!TextUtil.afterLast(image, "/").contains(":")) {
            return null;
        }
        Matcher m = VERSION_TAG.matcher(TextUtil.afterLast(image, ":"));
        return m.lookingAt() ? m.group(1) : null;
    }

    private static String fileName(String path) {
        return TextUtil.afterLast(path, "/");
    }

    @Override
    public boolean supports(FileRecord file) {
        String name = fileName(file.path).toLowerCase(Locale.ROOT);
        if (file.category == Category.BINARY || file.category == Category.DOC || file.category == Category.TEST) {
            return false;
        }
        for (Sign s : DESCRIPTORS) {
            if (s.rx().matcher(name).lookingAt()) {
                return true;
            }
        }
        return NAMES.contains(name)
                || name.startsWith("standalone-") || name.startsWith("dockerfile.") || name.startsWith("httpd") || name.startsWith("nginx")
                || name.endsWith(".dockerfile") || name.endsWith(".conf")
                || "java".equals(file.language)
                || file.category == Category.SCRIPT;
    }

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        Facts facts = new Facts();
        String text = ctx.textOrEmpty();
        facts.coverage.lines = text.isEmpty() ? 0 : TextUtil.lineCount(text);
        if (text.isEmpty()) {
            return facts;
        }
        String path = file.path;
        String name = fileName(path).toLowerCase(Locale.ROOT);
        Found found = new Found(path, facts, new TextUtil.LineIndex(text));
        if ("java".equals(file.language)) {
            for (Sign s : VENDOR_API) {
                Matcher m = s.rx().matcher(text);
                if (m.find()) {
                    found.add(s.server(), null, APP, m.start());
                    found.finding("server-specific-api", "medium", m.start(),
                            "Uses the " + s.server() + " API " + m.group(1) + ": [[server-api]]");
                }
            }
            return facts;
        }
        if (file.category == Category.SCRIPT && !name.endsWith(".conf")) {
            for (Sign s : SCRIPT_SIGNS) {
                Matcher hit = s.rx().matcher(text);
                if (hit.find()) {
                    found.add(s.server(), null, s.what(), hit.start());
                }
            }
            return facts;
        }
        if (name.equals("dockerfile") || name.equals("containerfile") || name.startsWith("dockerfile.") || name.endsWith(".dockerfile")) {
            Matcher m = DOCKER_FROM.matcher(text);
            while (m.find()) {
                String image = m.group(1).toLowerCase(Locale.ROOT);
                String base = TextUtil.beforeFirst(image, "@");
                String repo = TextUtil.afterLast(base, "/").contains(":") ? TextUtil.beforeLast(base, ":") : base;
                for (Sign s : IMAGES) {
                    if (s.rx().matcher(repo).find()) {
                        found.add(s.server(), versionTag(base), s.what(), m.start());
                        break;
                    }
                }
            }
            return facts;
        }
        for (Sign s : DESCRIPTORS) {
            if (s.rx().matcher(name).lookingAt()) {
                String version = null;
                for (Sign d : DTD_VERSION) {
                    Matcher dm = d.rx().matcher(text);
                    if (dm.find() && d.server().equals(s.server())) {
                        version = dm.group(1);
                    }
                }
                found.add(s.server(), version, APP, 0);
                if (s.server().equals(TOMCAT)) {
                    return facts; // Tomcat's own configuration, not an application descriptor
                }
                boolean old = name.endsWith(".xmi");
                found.finding("server-specific-config", "low", 0,
                        s.what() + " (" + fileName(path) + (old ? ", XMI format of WebSphere 6.x/7" : "")
                        + "): its settings (context root, JNDI names, data sources, security roles, "
                        + "class loading) are specific to " + s.server() + " [[server-move]]");
                return facts;
            }
        }
        if (name.equals("config.xml") && text.contains("<domain") && text.toLowerCase(Locale.ROOT).contains("weblogic")) {
            Matcher dv = DOMAIN_VERSION.matcher(text);
            boolean has = dv.find();
            found.add(WEBLOGIC, has ? dv.group(1) : null, APP, has ? dv.start() : 0);
            found.configFinding(WEBLOGIC, "WebLogic domain configuration (config.xml)");
        } else if (name.equals("standalone.xml") || name.equals("domain.xml") || name.startsWith("standalone-")) {
            Matcher jm = JBOSS_DOMAIN.matcher(text);
            if (jm.find()) {
                found.add(JBOSS, null, APP, jm.start());
                found.configFinding(JBOSS, "JBoss/WildFly server configuration (" + name + ")");
            }
        } else if (name.equals("server.xml")) {
            Matcher lib = LIBERTY_RX.matcher(text);
            Matcher tc = TOMCAT_SERVER.matcher(text);
            if (WAS_TRADITIONAL.matcher(text).find()) {
                found.add(WEBSPHERE, null, APP, 0);
                found.configFinding(WEBSPHERE, "WebSphere server configuration (server.xml)");
            } else if (lib.find()) {
                found.add(LIBERTY, null, APP, lib.start());
                found.configFinding(LIBERTY, "WebSphere Liberty server configuration");
            } else if (tc.find()) {
                found.add(TOMCAT, null, APP, tc.start());
                found.configFinding(TOMCAT, "Tomcat server configuration (server.xml)");
            }
        } else if (name.equals("context.xml") && TOMCAT_CONTEXT.matcher(text).find()) {
            found.add(TOMCAT, null, APP, text.indexOf("<Context"));
            found.configFinding(TOMCAT, "Tomcat context (context.xml)");
        } else if (name.equals("plugin-cfg.xml") && PLUGIN_CFG.matcher(text).find()) {
            found.add(IHS, null, WEB, 0);
            found.add(WEBSPHERE, null, APP, text.indexOf("<ServerCluster"));
        } else if (name.equals("web.config") && IIS_RX.matcher(text).find()) {
            Matcher hit = IIS_RX.matcher(text);
            hit.find();
            found.add(IIS, null, WEB, hit.start());
        } else if (name.equals("workers.properties") && MOD_JK.matcher(text).find()) {
            Matcher hit = MOD_JK.matcher(text);
            hit.find();
            found.add(APACHE, null, WEB, hit.start());
            found.add(TOMCAT, null, APP, hit.start());
        } else if (name.endsWith(".conf") || name.equals(".htaccess") || name.startsWith("httpd") || name.startsWith("nginx")) {
            webServer(name, text, found);
        }
        return facts;
    }

    private static Integer start(Pattern rx, String text) {
        Matcher m = rx.matcher(text);
        return m.find() ? m.start() : null;
    }

    private void webServer(String name, String text, Found found) {
        Integer ngx = start(NGINX_RX, text);
        Integer httpd = start(HTTPD, text);
        if (name.startsWith("nginx") || (ngx != null && httpd == null)) {
            if (ngx != null) {
                found.add(NGINX, null, WEB, ngx);
            }
            return;
        }
        Integer jk = start(MOD_JK, text);
        if (httpd == null && !name.equals(".htaccess") && jk == null) {
            return;
        }
        Integer ihs = start(IHS_RX, text);
        Integer ohs = start(OHS_RX, text);
        if (ihs != null) {
            found.add(IHS, null, WEB, ihs);
            if (WAS_PLUGIN.matcher(text).find()) {
                found.add(WEBSPHERE, null, APP, ihs);
            }
        } else if (ohs != null && text.contains("Oracle HTTP Server")) {
            found.add(OHS, null, WEB, ohs);
        } else {
            found.add(APACHE, null, WEB, httpd != null ? httpd : 0);
        }
        if (ohs != null) { // the WebLogic proxy plug-in sends requests to WebLogic
            found.add(WEBLOGIC, null, APP, ohs);
        }
        if (jk != null) {
            found.add(TOMCAT, null, APP, jk);
        }
    }

    private record Found(String path, Facts facts, TextUtil.LineIndex lines) {

        void add(String server, String version, String category, int offset) {
            Integer line = offset >= 0 ? lines.line(offset) : null;
            facts.add("tech_stack", BuildAnalyzer.tech(server, version, category, path, line));
        }

        void finding(String rule, String severity, int offset, String message) {
            int line = lines.line(offset);
            facts.add("findings", Doc.of(Schemas.FINDING,
                    "id", Fact.makeId("finding", rule, path, line),
                    "rule_id", rule,
                    "severity", severity,
                    "category", "migration",
                    "file", path,
                    "line", line,
                    "message", message,
                    "remediation", "[[server-remediation]]",
                    "evidence", new ArrayList<>(List.of(new Evidence(path, line, null)))));
        }

        void configFinding(String server, String what) {
            finding("server-specific-config", "low", 0, what + ": data sources, JMS resources, security realms and tuning set here for "
                    + server + " [[server-move]]");
        }
    }
}
