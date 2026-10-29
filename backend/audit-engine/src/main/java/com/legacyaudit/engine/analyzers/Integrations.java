package com.legacyaudit.engine.analyzers;

import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
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
 * Building integration facts: one usage per (type, target, direction); MQ/JMS queues per queue,
 * their direction derived later from producers and consumers.
 */
public final class Integrations {

    private Integrations() {}

    /** Queues: direction is derived after merging. */
    public static final Set<String> DIRECTIONLESS = Set.of("MQ", "JMS");

    /** Hosts of specification documents, XML namespaces and schema locations: never integrations. */
    public static final Pattern IGNORED_HOSTS = Rx.of(
            "(^|\\.)(java\\.sun\\.com|jakarta\\.apache\\.org|struts\\.apache\\.org|www\\.w3\\.org|w3\\.org|"
            + "xmlns\\.jcp\\.org|xmlns\\.oracle\\.com|www\\.springframework\\.org|springframework\\.org|"
            + "maven\\.apache\\.org|logging\\.apache\\.org|mybatis\\.org|ibatis\\.apache\\.org|"
            + "hibernate\\.org|hibernate\\.sourceforge\\.net|www\\.hibernate\\.org|jboss\\.org|"
            + "www\\.oracle\\.com|java\\.oracle\\.com|schemas\\.xmlsoap\\.org|schemas\\.microsoft\\.com|"
            + "www\\.apache\\.org|apache\\.org|tiles\\.apache\\.org|jcp\\.org|example\\.com|example\\.org|"
            + "localhost|127\\.0\\.0\\.1|purl\\.org|www\\.opensymphony\\.com|dtd\\.\\w+|"
            + "json\\.schemastore\\.org|(registry\\.)?npmjs\\.(org|com)|registry\\.yarnpkg\\.com|"
            + "angular\\.io|angular\\.dev|angularjs\\.org|docs\\.angularjs\\.org|nodejs\\.org|"
            + "typescriptlang\\.org|nestjs\\.com|docs\\.nestjs\\.com|expressjs\\.com|"
            + "tempuri\\.org|go\\.microsoft\\.com|(docs|learn|msdn)\\.microsoft\\.com|aka\\.ms|"
            + "(api\\.)?nuget\\.org|"
            + "goo\\.gl|bit\\.ly|xmlns\\.jcp\\.org|java\\.sun\\.com|xmlns\\.jboss\\.org|www\\.jboss\\.org|"
            + "www\\.bea\\.com|www\\.ibm\\.com|websphere\\.ibm\\.com|tomcat\\.apache\\.org|"
            + "www\\.eclipse\\.org|glassfish\\.org|payara\\.fish)$",
            Rx.I);

    public static final Pattern URL_RE = Rx.of(
            "\\b(https?|ftps?|sftp|ldaps?|smtp|jdbc:[a-z0-9]+(?::[a-z0-9]+)?)://?[^\\s\"'<>()\\\\]+", Rx.I);
    public static final Pattern JDBC_RE = Rx.of("\\bjdbc:([a-z0-9]+)(?::([a-z0-9]+))?:[^\\s\"'<>\\\\]+", Rx.I);

    private static final Map<String, String> DB_NAMES = Map.ofEntries(
            Map.entry("oracle", "Oracle"),
            Map.entry("db2", "DB2"),
            Map.entry("as400", "DB2"),
            Map.entry("sqlserver", "SQL Server"),
            Map.entry("jtds", "SQL Server"),
            Map.entry("microsoft", "SQL Server"),
            Map.entry("mysql", "MySQL"),
            Map.entry("mariadb", "MariaDB"),
            Map.entry("postgresql", "PostgreSQL"),
            Map.entry("sybase", "Sybase"),
            Map.entry("informix-sqli", "Informix"),
            Map.entry("informix", "Informix"),
            Map.entry("h2", "H2"),
            Map.entry("hsqldb", "HSQLDB"),
            Map.entry("derby", "Derby"),
            Map.entry("teradata", "Teradata"));

    /** One usage of an integration; {@code role} is "producer" or "consumer" (queues). */
    public static Doc integration(String type, String target, String direction, String file, Integer line, String role,
            String protocol, Map<String, String> details) {
        String id = DIRECTIONLESS.contains(type)
                ? Fact.makeId("integration", type, target)
                : Fact.makeId("integration", type, target, direction);
        return Doc.of(Schemas.INTEGRATION,
                "id", id,
                "type", type,
                "target", target,
                "direction", direction,
                "protocol", protocol,
                "usages", 1L,
                "source_files", new ArrayList<>(List.of(file)),
                "producers", "producer".equals(role) ? new ArrayList<>(List.of(file)) : new ArrayList<>(),
                "consumers", "consumer".equals(role) ? new ArrayList<>(List.of(file)) : new ArrayList<>(),
                "details", details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details),
                "evidence", new ArrayList<>(List.of(new Evidence(file, line, null))));
    }

    public static Doc integration(String type, String target, String direction, String file, Integer line) {
        return integration(type, target, direction, file, line, null, null, null);
    }

    private static final Pattern USERINFO = Rx.of("(://)[^/@\\s]+@");
    private static final Pattern ORACLE_USER = Rx.of("(jdbc:oracle:[a-z]+:)[^@/\\s]+/[^@\\s]+@", Rx.I);

    /** Drops user:password@ from a URL and user/password@ from an Oracle thin URL. */
    public static String maskUserinfo(String url) {
        url = USERINFO.matcher(url).replaceAll("$1");
        return ORACLE_USER.matcher(url).replaceAll("$1@");
    }

    private static final Pattern JDBC_PREFIX = Rx.of("^jdbc:", Rx.I);
    private static final Pattern PARAMS = Rx.of("[;?].*$");
    private static final Pattern AT_HOST = Rx.of("@(?://)?([\\w.-]+)(?::(\\d+))?[:/]?([\\w.$-]+)?");
    private static final Pattern SLASH_HOST = Rx.of("//([\\w.-]+)(?::(\\d+))?(?:/([\\w.$-]+))?");

    /** (database product, target) for a JDBC URL; the target has no credentials. */
    public static String[] parseJdbc(String url) {
        Matcher m = JDBC_RE.matcher(url);
        String vendor = (m.lookingAt() ? m.group(1) : "unknown").toLowerCase(Locale.ROOT);
        String product = DB_NAMES.getOrDefault(vendor, vendor);
        String clean = maskUserinfo(url);
        String target = JDBC_PREFIX.matcher(clean).replaceFirst("");
        target = PARAMS.matcher(target).replaceFirst("");
        Matcher host = AT_HOST.matcher(target);
        if (host.find()) {
            target = vendor + "://" + host.group(1) + (host.group(2) != null ? ":" + host.group(2) : "");
            if (host.group(3) != null) {
                target += "/" + host.group(3);
            }
        } else {
            Matcher hp = SLASH_HOST.matcher(target);
            if (hp.find()) {
                target = vendor + "://" + hp.group(1) + (hp.group(2) != null ? ":" + hp.group(2) : "");
                if (hp.group(3) != null) {
                    target += "/" + hp.group(3);
                }
            }
        }
        return new String[] {product, target};
    }

    public static String[] classifyUrl(String url) {
        return classifyUrl(url, false);
    }

    /** (type, target) for a URL found in code or config; null for spec and namespace URLs. */
    public static String[] classifyUrl(String url, boolean soapHint) {
        url = maskUserinfo(TextUtil.rstrip(url, ".,;"));
        if (url.toLowerCase(Locale.ROOT).startsWith("jdbc:")) {
            return new String[] {"DB", parseJdbc(url)[1]};
        }
        PyUrl parts = PyUrl.split(url);
        String host = parts.hostname() == null ? "" : parts.hostname();
        if (host.isEmpty() || IGNORED_HOSTS.matcher(host).find() || host.contains("${") || host.contains("$")) {
            return null;
        }
        String scheme = parts.scheme().toLowerCase(Locale.ROOT);
        Integer p = parts.port();
        String port = p != null && p != 0 ? ":" + p : "";
        String path = TextUtil.rstrip(parts.path(), "/");
        if (scheme.equals("http") || scheme.equals("https")) {
            String lower = path.toLowerCase(Locale.ROOT);
            boolean soap = soapHint
                    || lower.contains("/services/")
                    || parts.query().toLowerCase(Locale.ROOT).contains("wsdl")
                    || lower.endsWith(".asmx") || lower.endsWith(".svc");
            return new String[] {soap ? "SOAP" : "HTTP", scheme + "://" + host + port + path};
        }
        String kind = switch (scheme) {
            case "ftp", "ftps" -> "FTP";
            case "sftp" -> "SFTP";
            case "ldap", "ldaps" -> "LDAP";
            case "smtp" -> "SMTP";
            default -> null;
        };
        if (kind == null) {
            return null;
        }
        return new String[] {kind, scheme + "://" + host + port};
    }
}
