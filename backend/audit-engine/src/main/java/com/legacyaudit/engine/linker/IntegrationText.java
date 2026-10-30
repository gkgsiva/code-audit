package com.legacyaudit.engine.linker;

import com.legacyaudit.engine.analyzers.PyError;
import com.legacyaudit.engine.analyzers.PyUrl;
import com.legacyaudit.engine.analyzers.TextUtil;
import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Doc;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One row per interface, and what each interface is for: the consolidation key that makes
 * usages of the same target one integration, the merged direction, the identity providers, and
 * the purpose and one-sentence description every integration row gets.
 */
public final class IntegrationText {

    private IntegrationText() {}

    /** Types whose target is a host (and port): ftp://h, h:21 and FTP h all name one server. */
    static final Set<String> HOST_TYPES = Set.of("FTP", "SFTP", "SCP", "SMTP", "LDAP", "SOCKET");
    static final Map<String, String> DEFAULT_PORTS = Map.of("http", "80", "https", "443", "ftp", "21", "ftps", "990", "sftp", "22",
            "scp", "22", "smtp", "25", "ldap", "389", "ldaps", "636");
    private static final Map<String, String> TYPE_SCHEME = Map.of("FTP", "ftp", "SFTP", "sftp", "SCP", "scp", "SMTP", "smtp", "LDAP", "ldap");
    private static final Pattern QUEUE_URI = Rx.of("^queue://[^/]*/", Rx.I);
    private static final Pattern HOST_NAME = Rx.of("^[\\w.*?-]+$");
    private static final Pattern DB_HOST = Rx.of("^(\\w+://)([^:/]+)");

    /** Python's {@code str(parts.port) if parts.port else ""}; null when the port is not valid. */
    static String portText(PyUrl parts) {
        try {
            Integer p = parts.port();
            return p != null && p != 0 ? String.valueOf(p) : "";
        } catch (PyError e) {
            return null;
        }
    }

    static String host(PyUrl parts) {
        String h = parts.hostname();
        return h == null ? "" : h.toLowerCase(Locale.ROOT);
    }

    /** (display, key) for a host target, the key without a default port; null when not a host. */
    private static String[] hostPort(String type, String target) {
        String scheme = "";
        String rest = TextUtil.strip(target);
        String host;
        String port;
        if (rest.contains("://")) {
            PyUrl parts = PyUrl.split(rest);
            scheme = parts.scheme().toLowerCase(Locale.ROOT);
            host = host(parts);
            port = portText(parts);
            if (port == null) {
                return null;
            }
        } else {
            rest = TextUtil.afterLast(rest, "@"); // user@host
            int c = rest.indexOf(':');
            host = (c < 0 ? rest : rest.substring(0, c)).toLowerCase(Locale.ROOT);
            port = c < 0 ? "" : rest.substring(c + 1);
            if (!port.isEmpty() && !TextUtil.isDigits(port)) {
                port = type.equals("SOCKET") ? port : ""; // host:/path (scp) keeps the host only
            }
        }
        if (host.isEmpty() || !HOST_NAME.matcher(host).find()) {
            return null;
        }
        String shown = port.isEmpty() ? host : host + ":" + port;
        String def = DEFAULT_PORTS.getOrDefault(scheme.isEmpty() ? TYPE_SCHEME.getOrDefault(type, "") : scheme, "");
        if (!port.isEmpty() && port.equals(def)) {
            port = "";
        }
        return new String[] {scheme.isEmpty() ? shown : scheme + "://" + shown, port.isEmpty() ? host : host + ":" + port};
    }

    /**
     * (family, key, display target): integrations with the same family and key are one
     * interface, whatever direction, spelling or default port each usage was written with.
     */
    public static String[] consolidationKey(String type, String target) {
        if (type.equals("HTTP") || type.equals("SOAP")) {
            PyUrl parts = PyUrl.split(target);
            String scheme = parts.scheme().toLowerCase(Locale.ROOT);
            String host = host(parts);
            String port = portText(parts);
            if (port == null) {
                port = "";
            }
            if (host.isEmpty()) {
                return new String[] {"HTTP", target, target};
            }
            String path = TextUtil.rstrip(parts.path(), "/");
            String shown = scheme + "://" + host + (port.isEmpty() ? "" : ":" + port) + path;
            if (port.equals(DEFAULT_PORTS.get(scheme))) {
                port = "";
            }
            return new String[] {"HTTP", scheme + "://" + host + (port.isEmpty() ? "" : ":" + port) + path, shown};
        }
        if (HOST_TYPES.contains(type)) {
            String[] hp = hostPort(type, target);
            if (hp != null) {
                return new String[] {type, hp[1], hp[0]};
            }
            return new String[] {type, TextUtil.strip(target).toLowerCase(Locale.ROOT), TextUtil.strip(target)};
        }
        if (type.equals("DB")) {
            Matcher m = DB_HOST.matcher(target);
            String shown = m.find() ? m.group(1) + m.group(2).toLowerCase(Locale.ROOT) + target.substring(m.end()) : target;
            return new String[] {type, target.toLowerCase(Locale.ROOT), shown};
        }
        if (type.equals("MQ-QMGR")) {
            return new String[] {type, TextUtil.strip(TextUtil.beforeFirst(target, "@")), target};
        }
        if (type.equals("MQ") || type.equals("JMS") || type.equals("MQ-CHANNEL")) {
            String name = TextUtil.beforeFirst(QUEUE_URI.matcher(TextUtil.strip(target)).replaceFirst(""), "?");
            return new String[] {type, name, name};
        }
        if (type.equals("FILE")) {
            String path = target.replace("\\", "/");
            return new String[] {type, path, path};
        }
        return new String[] {type, target, target};
    }

    /** inbound + outbound is both; a concrete direction wins over defined and unknown. */
    public static String mergeDirections(Set<String> directions) {
        Set<String> concrete = new HashSet<>(directions);
        concrete.remove("defined");
        concrete.remove("unknown");
        if (concrete.contains("both") || (concrete.contains("inbound") && concrete.contains("outbound"))) {
            return "both";
        }
        if (!concrete.isEmpty()) {
            return concrete.iterator().next();
        }
        return directions.contains("defined") ? "defined" : "unknown";
    }

    // ---- what each interface is for -------------------------------------------------------

    private record Idp(Pattern rx, String name) {}

    // Identity providers: a call to one is a login / authentication interface, whatever its type.
    private static final List<Idp> IDENTITY = List.of(
            new Idp(Rx.of("(^|\\.)okta(preview|-emea)?\\.com$|(^|\\.)okta\\.", Rx.I), "Okta"),
            new Idp(Rx.of("(^|\\.)auth0\\.com$", Rx.I), "Auth0"),
            new Idp(Rx.of("^login\\.(microsoftonline|windows)\\.(com|net)$|^sts\\.windows\\.net$", Rx.I), "Microsoft Entra ID"),
            new Idp(Rx.of("(^|\\.)(pingidentity|pingone)\\.(com|eu|asia)$", Rx.I), "PingIdentity"),
            new Idp(Rx.of("(^|\\.)(onelogin|forgerock)\\.(com|io)$", Rx.I), "Identity provider"),
            new Idp(Rx.of("(^|[.-])(keycloak|sso|idp|adfs|login|auth|oauth|cas|iam)[.-]", Rx.I), "Identity provider"));
    private static final Pattern IDENTITY_PATH = Rx.of("/(oauth2?|openid-connect|\\.well-known/openid-configuration|saml2?|sso|adfs|"
            + "auth/realms|realms/[^/]+/protocol|cas/login|idp|authorize|token)(/|$)", Rx.I);

    public static final Map<String, String> PURPOSES = Map.ofEntries(
            Map.entry("HTTP", "HTTP API"),
            Map.entry("SOAP", "web service"),
            Map.entry("FTP", "file transfer"),
            Map.entry("SFTP", "file transfer"),
            Map.entry("SCP", "file transfer"),
            Map.entry("CFT", "file transfer"),
            Map.entry("MQ", "messaging"),
            Map.entry("JMS", "messaging"),
            Map.entry("MQ-CHANNEL", "messaging"),
            Map.entry("MQ-QMGR", "messaging"),
            Map.entry("DB", "database"),
            Map.entry("SMTP", "email"),
            Map.entry("LDAP", "directory"),
            Map.entry("SOCKET", "socket"),
            Map.entry("FILE", "file"));
    public static final String LOGIN = "login / authentication";

    private static final Map<String, String> VERB = Map.ofEntries(
            Map.entry("outbound|HTTP", "Calls"),
            Map.entry("inbound|HTTP", "Is called on"),
            Map.entry("both|HTTP", "Calls and is called on"),
            Map.entry("outbound|file transfer", "Sends files to"),
            Map.entry("inbound|file transfer", "Receives files from"),
            Map.entry("both|file transfer", "Sends and receives files with"),
            Map.entry("outbound|messaging", "Puts messages on"),
            Map.entry("inbound|messaging", "Reads messages from"),
            Map.entry("both|messaging", "Puts and reads messages on"),
            Map.entry("outbound|file", "Writes"),
            Map.entry("inbound|file", "Reads"),
            Map.entry("both|file", "Reads and writes"));

    private static String[] hostAndPath(String target) {
        if (target.contains("://")) {
            PyUrl parts = PyUrl.split(target);
            return new String[] {host(parts), parts.path()};
        }
        return new String[] {TextUtil.beforeFirst(target, ":").toLowerCase(Locale.ROOT), ""};
    }

    /** The identity provider an integration calls (Okta, Entra ID, ...), or null. */
    public static String identityProvider(Doc it) {
        String type = it.str("type");
        if (!type.equals("HTTP") && !type.equals("SOAP") && !type.equals("LDAP")) {
            return null;
        }
        String[] hp = hostAndPath(it.str("target"));
        for (Idp idp : IDENTITY) {
            if (!hp[0].isEmpty() && idp.rx().matcher(hp[0]).find()) {
                return idp.name();
            }
        }
        Map<String, Object> details = it.map("details");
        List<String> values = new ArrayList<>();
        details.values().forEach(v -> values.add(String.valueOf(v)));
        if (String.join(" ", values).toLowerCase(Locale.ROOT).contains("okta")) {
            return "Okta";
        }
        List<String> paths = new ArrayList<>();
        paths.add(hp[1]);
        for (Object e : it.list("endpoints")) {
            paths.add(TextUtil.beforeFirst(String.valueOf(e), " "));
        }
        if (!type.equals("LDAP")) {
            for (String p : paths) {
                String up = PyUrl.split(p).path();
                if (IDENTITY_PATH.matcher(up.isEmpty() ? p : up).find()) {
                    return "Identity provider";
                }
            }
        }
        return null;
    }

    private static String listed(List<String> items) {
        int limit = 5;
        String shown = String.join(", ", items.subList(0, Math.min(limit, items.size())));
        return shown + (items.size() > limit ? " and " + (items.size() - limit) + " more" : "");
    }

    /** What a row of one external service holds (decision 177), for its description. */
    private static String grouped(Doc it) {
        List<String> endpoints = new ArrayList<>();
        it.list("endpoints").forEach(e -> endpoints.add(String.valueOf(e)));
        if (it.str("type").equals("MQ-QMGR")) {
            List<String> queues = new ArrayList<>();
            List<String> channels = new ArrayList<>();
            for (String e : endpoints) {
                String[] parts = e.split(" ", 3);
                if (e.startsWith("queue ")) {
                    queues.add(parts.length > 1 ? parts[1] : "");
                } else if (e.startsWith("channel ")) {
                    channels.add(parts.length > 1 ? parts[1] : "");
                }
            }
            List<String> bits = new ArrayList<>();
            if (!queues.isEmpty()) {
                bits.add("queue" + (queues.size() != 1 ? "s" : "") + " " + listed(queues));
            }
            if (!channels.isEmpty()) {
                bits.add("channel" + (channels.size() != 1 ? "s" : "") + " " + listed(channels));
            }
            return String.join("; ", bits);
        }
        int n = endpoints.size();
        String noun = it.str("type").equals("FILE") ? "file" : "endpoint";
        return n + " " + noun + (n != 1 ? "s" : "") + ": " + listed(endpoints);
    }

    /**
     * (purpose, one-sentence description) of a consolidated integration, from its facts only; a
     * row of one external service also says what it groups (endpoints, queues, files).
     */
    public static String[] describe(Doc it) {
        String[] ps = describeOne(it);
        if (it.list("endpoints").isEmpty()) {
            return ps;
        }
        String sentence = ps[1];
        int k = sentence.lastIndexOf("; ");
        if (k < 0) {
            return new String[] {ps[0], TextUtil.rstrip(sentence, ".") + " (" + grouped(it) + ")."};
        }
        return new String[] {ps[0], sentence.substring(0, k) + " (" + grouped(it) + "); " + sentence.substring(k + 2)};
    }

    /** Sets {@code purpose} and {@code description} from {@link #describe}. */
    public static void describeInto(Doc it) {
        String[] d = describe(it);
        it.set("purpose", d[0]);
        it.set("description", d[1]);
    }

    private static String[] describeOne(Doc it) {
        String type = it.str("type");
        String target = it.str("target");
        String idp = identityProvider(it);
        String[] hp = hostAndPath(target);
        String host = hp[0];
        String path = hp[1];
        long usages = ((Number) it.get("usages")).longValue();
        String where = usages + " place" + (usages != 1 ? "s" : "") + " in the code";
        if (usages == 0) {
            where = "defined in the inputs, not used by the code";
        }
        if (idp != null) {
            String what = path.toLowerCase(Locale.ROOT).contains("/oauth") ? "OAuth 2.0 / OpenID Connect" : "single sign-on";
            if (type.equals("LDAP")) {
                what = "LDAP";
            }
            return new String[] {LOGIN, "Login / authentication with " + idp + " (" + what + ") at " + (host.isEmpty() ? target : host) + "; "
                    + where + "."};
        }
        String purpose = PURPOSES.getOrDefault(type, type.toLowerCase(Locale.ROOT));
        String dir = it.str("direction");
        String d = Set.of("outbound", "inbound", "both").contains(dir) ? dir : "";
        Map<String, Object> details = it.map("details");
        if (type.equals("HTTP") || type.equals("SOAP")) {
            String scheme = target.toLowerCase(Locale.ROOT).startsWith("https:") ? "HTTPS" : "HTTP";
            String name = TextUtil.strip(path, "/").isEmpty() ? "" : TextUtil.afterLast(TextUtil.rstrip(path, "/"), "/");
            String thing;
            if (type.equals("SOAP") && !name.isEmpty()) {
                thing = "the " + name + " web service";
            } else if (type.equals("SOAP")) {
                thing = "a SOAP web service";
            } else if (!TextUtil.strip(path, "/").isEmpty()) {
                thing = "the " + scheme + " endpoint " + path;
            } else {
                thing = "an " + scheme + " service";
            }
            String verb = VERB.getOrDefault((d.isEmpty() ? "outbound" : d) + "|HTTP", "Calls");
            String tail = thing.contains(scheme) ? "" : " (" + scheme + ")";
            String on = verb.startsWith("Is") ? " at" : " on";
            return new String[] {purpose, verb + " " + thing + on + " " + host + tail + "; " + where + "."};
        }
        if (purpose.equals("file transfer")) {
            String verb = details.containsKey("key") ? "Transfers files with" : VERB.getOrDefault(d + "|" + purpose, "");
            if (verb.isEmpty()) {
                verb = "Transfers files with";
            }
            String extra = "";
            if (type.equals("CFT")) {
                if (truthy(details.get("idf"))) {
                    extra = ", flow " + details.get("idf");
                }
                if (truthy(details.get("host"))) {
                    extra += ", partner host " + details.get("host");
                }
            }
            return new String[] {purpose, verb + " " + target + " by " + type + extra + "; " + where + "."};
        }
        if (purpose.equals("messaging")) {
            if (type.equals("MQ-QMGR")) {
                return new String[] {purpose, "Connects to the MQ queue manager " + target + "; " + where + "."};
            }
            if (type.equals("MQ-CHANNEL")) {
                return new String[] {purpose, "Uses the MQ channel " + target + "; " + where + "."};
            }
            String verb = VERB.getOrDefault(d + "|" + purpose, "Uses");
            return new String[] {purpose, verb + " the queue " + target + "; " + where + "."};
        }
        switch (purpose) {
            case "database" -> {
                return new String[] {purpose, "Connects to the database " + target + "; " + where + "."};
            }
            case "email" -> {
                return new String[] {purpose, "Sends email through " + target + "; " + where + "."};
            }
            case "directory" -> {
                return new String[] {purpose, "Looks up users or groups in the directory " + target + "; " + where + "."};
            }
            case "socket" -> {
                String verb = d.equals("inbound") ? "Listens on" : "Opens a socket to";
                return new String[] {purpose, verb + " " + target + "; " + where + "."};
            }
            case "file" -> {
                String verb = VERB.getOrDefault(d + "|" + purpose, "Uses");
                if (!it.list("endpoints").isEmpty()) {
                    String on = target.startsWith("//") ? "on" : "in";
                    return new String[] {purpose, verb + " files " + on + " " + target + "; " + where + "."};
                }
                return new String[] {purpose, verb + " the file " + target + "; " + where + "."};
            }
            default -> {
                return new String[] {purpose, type + " " + target + "; " + where + "."};
            }
        }
    }

    static boolean truthy(Object v) {
        return v != null && !(v instanceof String s && s.isEmpty());
    }
}
