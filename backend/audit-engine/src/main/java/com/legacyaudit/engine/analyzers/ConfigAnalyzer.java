package com.legacyaudit.engine.analyzers;

import static com.legacyaudit.engine.analyzers.Integrations.integration;

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
 * Configuration files (properties, XML, YAML, INI, JSON): JDBC URLs, hosts and URLs, queue names
 * and CFT partners as integrations; credentials are reported masked, never copied.
 */
public final class ConfigAnalyzer implements Analyzer {

    public static final Pattern SECRET_KEY = Rx.of("(passw(or)?d|pwd|passphrase|secret|api[_-]?key|token|credential)", Rx.I);
    // Message/label bundles: their "password" keys are UI labels, not credentials.
    private static final Pattern MESSAGE_BUNDLE = Rx.of(
            "(?i)(^|[_.-])(messages?|\\w*resources?|labels?|i18n|strings?|text)(_[a-z]{2}(_[a-z]{2})?)?\\.properties$");
    private static final Pattern KV = Rx.of("^\\s*([\\w.\\-\\[\\]/]+)\\s*[=:]\\s*(.*?)\\s*$");
    private static final Pattern XML_PROP = Rx.of(
            "<(?:property|entry|add|param|init-param|context-param|env-entry)\\b[^>]*?"
            + "(?:name|key)\\s*=\\s*\"([^\"]+)\"[^>]*?value\\s*=\\s*\"([^\"]*)\"", Rx.I);
    private static final Pattern XML_PARAM = Rx.of(
            "<param-name>\\s*([^<]+?)\\s*</param-name>\\s*<param-value>\\s*([^<]*?)\\s*</param-value>", Rx.I);
    private static final Pattern HOST_KEY = Rx.of("(^|[._-])(host|hostname|server)$", Rx.I);
    private static final Pattern PORT_KEY = Rx.of("(^|[._-])port$", Rx.I);
    private static final Pattern QUEUE_KEY = Rx.of("queue", Rx.I);
    public static final List<String> OWNED_INPUTS = List.of("audit-inputs/autosys/", "audit-inputs/mq/", "audit-inputs/cft/",
            "audit-inputs/crontab/", "audit-inputs/ddl/");
    private static final Pattern NOT_QUEUE_KEY = Rx.of("manager|mgr|factory|depth|size|timeout|count|max|min", Rx.I);
    private static final Pattern QUEUE_VALUE = Rx.of("^[A-Z0-9][A-Z0-9._]{2,47}$");
    private static final Pattern CFT_KEY = Rx.of("cft", Rx.I);
    private static final Pattern PLACEHOLDER = Rx.of("^\\s*(\\$\\{.*\\}|@\\w+@|%\\w+%|<[^>]+>)?\\s*$");
    private static final Pattern YAML_KV = Rx.of("^(\\s*)([\\w.\\-]+)\\s*:\\s*(.*?)\\s*$");
    private static final Pattern JSON_KV = Rx.of("^\\s*\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern BEANS = Rx.of("<(\\w+:)?beans\\b");
    private static final Pattern SPACE = Rx.of("\\s");
    private static final Pattern HOST_SUFFIX = Rx.of("[._-]?(host|hostname|server|port)$", Rx.I);
    private static final Pattern PARTNER = Rx.of("part(ner)?", Rx.I);
    private static final Pattern HOST_VALUE = Rx.of("^[\\w.-]+$");
    private static final Pattern SEPARATORS = Rx.of("[._-]");

    private static final Pattern PRODUCER_HINT = Rx.of("(request|req|out|send|put|publish|producer|dest|target|to)\\b", Rx.I);
    private static final Pattern CONSUMER_HINT = Rx.of("(reply|resp|in|receive|recv|get|listen|consumer|source|from)\\b", Rx.I);

    private static final Set<String> LANGUAGES = Set.of("properties", "yaml", "ini", "xml", "json");
    private static final Set<Category> CATEGORIES = Set.of(Category.CONFIG, Category.SOURCE, Category.OTHER);

    @Override
    public String name() {
        return "config";
    }

    @Override
    public String version() {
        return "2";
    }

    /** "producer", "consumer" or null from the words of a configuration key. */
    public static String queueRole(String key) {
        String words = SEPARATORS.matcher(key).replaceAll(" ");
        if (PRODUCER_HINT.matcher(words).find()) {
            return "producer";
        }
        if (CONSUMER_HINT.matcher(words).find()) {
            return "consumer";
        }
        return null;
    }

    public static Doc credentialFinding(String file, int line, String key) {
        return Doc.of(Schemas.FINDING,
                "id", Fact.makeId("finding", "config-credential", file, line),
                "rule_id", "config-credential",
                "severity", "high",
                "category", "security",
                "file", file,
                "line", (long) line,
                "message", "Credential '" + key + "' is set in configuration (value masked)",
                "evidence", new ArrayList<>(List.of(new Evidence(file, line, key + "=****"))));
    }

    public static void scanValueForEndpoints(String value, String file, int line, Facts facts, String key) {
        Matcher m = Integrations.JDBC_RE.matcher(value);
        while (m.find()) {
            String[] pt = Integrations.parseJdbc(m.group());
            facts.add("integrations", integration("DB", pt[1], "outbound", file, line, null, "jdbc", null));
            facts.add("tech_stack", BuildAnalyzer.tech(pt[0], null, "database", file, line));
        }
        Matcher u = Integrations.URL_RE.matcher(value);
        while (u.find()) {
            if (u.group().toLowerCase(Locale.ROOT).startsWith("jdbc")) {
                continue;
            }
            String[] c = Integrations.classifyUrl(u.group());
            if (c != null) {
                // The key lets a client written as ${payment.url} find its address (decision 133).
                Map<String, String> details = key != null && !key.isEmpty() && (c[0].equals("HTTP") || c[0].equals("SOAP"))
                        ? Map.of("key", key) : null;
                facts.add("integrations", integration(c[0], c[1], "outbound", file, line, null, null, details));
            }
        }
    }

    @Override
    public boolean supports(FileRecord file) {
        String lower = file.path.toLowerCase(Locale.ROOT);
        for (String owned : OWNED_INPUTS) {
            if (lower.startsWith(owned)) {
                return false;
            }
        }
        return LANGUAGES.contains(file.language) && CATEGORIES.contains(file.category);
    }

    private record Pair(int line, String key, String value) {}

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        Facts facts = new Facts();
        String text = ctx.textOrEmpty();
        facts.coverage.lines = TextUtil.lineCount(text);
        List<Pair> pairs = new ArrayList<>();
        if ("xml".equals(file.language)) {
            for (Pattern rx : List.of(XML_PROP, XML_PARAM)) {
                Matcher m = rx.matcher(text);
                while (m.find()) {
                    pairs.add(new Pair(TextUtil.lineAt(text, m.start()), m.group(1), m.group(2)));
                }
            }
            // Endpoints in any attribute or text (namespaces and DTD hosts are ignored).
            int no = 0;
            for (String line : TextUtil.splitLines(text)) {
                no++;
                if (line.contains("<!DOCTYPE") || line.contains("xmlns") || line.contains("schemaLocation") || line.contains("amespace")) {
                    continue;
                }
                if (line.contains("jdbc:") || line.contains("://")) {
                    scanValueForEndpoints(line, file.path, no, facts, null);
                }
            }
        } else {
            String section = "";
            int no = 0;
            for (String line : TextUtil.splitLines(text)) {
                no++;
                String s = TextUtil.strip(line);
                if (s.isEmpty() || s.startsWith("#") || s.startsWith("!") || s.startsWith(";") || s.startsWith("//")) {
                    continue;
                }
                if ("ini".equals(file.language) && s.startsWith("[") && s.endsWith("]")) {
                    section = s.substring(1, s.length() - 1) + ".";
                    continue;
                }
                if ("yaml".equals(file.language)) {
                    Matcher ym = YAML_KV.matcher(line);
                    if (ym.lookingAt()) {
                        pairs.add(new Pair(no, ym.group(2), TextUtil.strip(ym.group(3), "'\"")));
                    }
                    continue;
                }
                if ("json".equals(file.language)) {
                    Matcher jm = JSON_KV.matcher(line);
                    if (jm.lookingAt()) {
                        pairs.add(new Pair(no, jm.group(1), jm.group(2)));
                    }
                    continue;
                }
                Matcher kv = KV.matcher(line);
                if (kv.lookingAt()) {
                    pairs.add(new Pair(no, section + kv.group(1), kv.group(2)));
                } else {
                    facts.coverage.gap(no, no, "not a key/value line");
                }
            }
            for (Pair p : pairs) {
                scanValueForEndpoints(p.value(), file.path, p.line(), facts, p.key());
            }
        }

        Map<String, Pair> hosts = new LinkedHashMap<>();
        Map<String, String> ports = new LinkedHashMap<>();
        boolean bundle = MESSAGE_BUNDLE.matcher(TextUtil.afterLast(file.path, "/")).find();
        // Spring bean files are read by the spring analyzer, which knows what each bean is.
        boolean springXml = "xml".equals(file.language) && BEANS.matcher(text).find();
        for (Pair p : pairs) {
            String key = p.key();
            String value = p.value();
            int no = p.line();
            if (SECRET_KEY.matcher(key).find() && !value.isEmpty() && !PLACEHOLDER.matcher(value).lookingAt() && !bundle
                    && !SPACE.matcher(value).find()) {
                facts.add("findings", credentialFinding(file.path, no, key));
                continue;
            }
            String prefix = HOST_SUFFIX.matcher(key).replaceAll("");
            if (springXml) {
                continue;
            }
            if (HOST_KEY.matcher(key).find() && !value.isEmpty() && !PLACEHOLDER.matcher(value).lookingAt()) {
                hosts.put(prefix, new Pair(no, prefix, value));
            } else if (PORT_KEY.matcher(key).find() && TextUtil.isDigits(value)) {
                ports.put(prefix, value);
            } else if (QUEUE_KEY.matcher(key).find() && !NOT_QUEUE_KEY.matcher(key).find() && QUEUE_VALUE.matcher(value).lookingAt()) {
                facts.add("integrations", integration("MQ", value, "unknown", file.path, no, queueRole(key), null, Map.of("key", key)));
            } else if (CFT_KEY.matcher(key).find() && PARTNER.matcher(key).find() && !value.isEmpty()) {
                facts.add("integrations", integration("CFT", value, "outbound", file.path, no, null, null, Map.of("partner", value)));
            }
        }
        for (Map.Entry<String, Pair> e : hosts.entrySet()) {
            String prefix = e.getKey();
            String host = e.getValue().value();
            if (host.contains("://") || !HOST_VALUE.matcher(host).lookingAt()) {
                continue;
            }
            String target = ports.containsKey(prefix) ? host + ":" + ports.get(prefix) : host;
            facts.add("integrations", integration(hostKind(prefix), target, "outbound", file.path, e.getValue().line(), null, null,
                    Map.of("key", prefix)));
        }
        return facts;
    }

    private static final String[][] HOST_KINDS = {
        {"smtp", "SMTP"}, {"mail", "SMTP"}, {"sftp", "SFTP"}, {"ftp", "FTP"}, {"ldap", "LDAP"}, {"mq", "MQ-QMGR"},
        {"cft", "CFT"}, {"db", "DB"}, {"jdbc", "DB"}, {"http", "HTTP"}, {"ws", "HTTP"}, {"api", "HTTP"},
    };

    static String hostKind(String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        for (String[] wk : HOST_KINDS) {
            if (p.contains(wk[0])) {
                return wk[1];
            }
        }
        return "SOCKET";
    }
}
