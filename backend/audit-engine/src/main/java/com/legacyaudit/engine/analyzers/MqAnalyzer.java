package com.legacyaudit.engine.analyzers;

import com.legacyaudit.engine.inventory.Paths;
import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Category;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IBM MQ MQSC exports ({@code audit-inputs/mq/}, {@code *.mqsc}): {@code DEFINE QLOCAL/QREMOTE/
 * QALIAS/QMODEL} become {@code MQ} integrations (one per queue; transmission queues are skipped),
 * {@code DEFINE CHANNEL} an {@code MQ-CHANNEL} integration whose direction follows the channel
 * type. Each carries the queue manager of the export ({@code qmgr} from a comment, or
 * {@code qmgr_file} from the file name), so one queue manager's objects group together.
 */
public final class MqAnalyzer implements Analyzer {

    private static final Pattern DEFINE = Rx.of("^\\s*(?:DEFINE|DEF)\\s+(QLOCAL|QL|QREMOTE|QR|QALIAS|QA|QMODEL|QM|CHANNEL|CHL|LISTENER|"
            + "NAMELIST|PROCESS|TOPIC|SUB|AUTHINFO|SERVICE)\\s*\\(\\s*'?([^')]+)'?\\s*\\)(.*)$", Rx.I | Pattern.DOTALL);
    private static final Pattern ATTR = Rx.of("\\b([A-Z]+)\\s*\\(\\s*'?((?:[^()']|\\([^()]*\\))*)'?\\s*\\)", Rx.I);
    private static final Pattern QMGR_COMMENT = Rx.of("^\\s*\\*.*?\\bqueue\\s+manager\\s+'?([A-Za-z0-9._%/]{1,48})'?", Rx.I | Rx.M);
    private static final Pattern QMGR_NAME = Rx.of("[A-Z0-9._%]{1,48}");
    private static final Pattern OTHER = Rx.of("^\\s*(ALTER|SET|DISPLAY|DIS|START|STOP|END|DELETE|REFRESH)\\b", Rx.I);
    private static final Map<String, String> QUEUE_TYPES = Map.of("QL", "QLOCAL", "QR", "QREMOTE", "QA", "QALIAS", "QM", "QMODEL");
    private static final Map<String, String> CHANNEL_DIRECTION = Map.of("SDR", "outbound", "SVR", "outbound", "CLUSSDR", "outbound",
            "CLNTCONN", "outbound", "RCVR", "inbound", "RQSTR", "inbound", "CLUSRCVR", "inbound", "SVRCONN", "inbound");
    private static final List<String> QUEUE_KEYS = List.of("RNAME", "RQMNAME", "XMITQ", "TARGET", "TARGQ", "USAGE", "DESCR");

    @Override
    public String name() {
        return "mq";
    }

    @Override
    public String version() {
        return "2";
    }

    @Override
    public boolean supports(FileRecord file) {
        return file.language.equals("mqsc")
                || (file.path.toLowerCase(Locale.ROOT).startsWith("audit-inputs/mq/") && file.category != Category.BINARY);
    }

    record Command(int line, String text) {}

    /** Commands with {@code +}/{@code -} continuations joined, as (first line, text). */
    static List<Command> mqscCommands(String text) {
        List<Command> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int start = 0;
        int no = 0;
        for (String raw : TextUtil.splitLines(text)) {
            no++;
            String line = TextUtil.rstrip(raw);
            if (buf.isEmpty() && (TextUtil.strip(line).isEmpty() || TextUtil.lstrip(line).startsWith("*"))) {
                continue;
            }
            if (buf.isEmpty()) {
                start = no;
            }
            if (line.endsWith("+") || line.endsWith("-")) {
                buf.append(line, 0, line.length() - 1).append(' ');
                continue;
            }
            out.add(new Command(start, buf + line));
            buf.setLength(0);
        }
        if (!buf.isEmpty()) {
            out.add(new Command(start, buf.toString()));
        }
        return out;
    }

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        Facts facts = new Facts();
        String text = ctx.textOrEmpty();
        String path = file.path;
        facts.coverage.lines = TextUtil.lineCount(text);
        boolean first = true;
        Map<String, String> owner = new LinkedHashMap<>();
        Matcher qm = QMGR_COMMENT.matcher(text);
        if (qm.find()) {
            owner.put("qmgr", qm.group(1).toUpperCase(Locale.ROOT));
        }
        String stem = Paths.stem(path).toUpperCase(Locale.ROOT);
        if (owner.isEmpty() && QMGR_NAME.matcher(stem).matches()) {
            owner.put("qmgr_file", stem);
        }
        for (Command c : mqscCommands(text)) {
            Matcher m = DEFINE.matcher(c.text());
            if (!m.lookingAt()) {
                if (!OTHER.matcher(c.text()).lookingAt()) {
                    facts.coverage.gap(c.line(), c.line(), "not an MQSC command");
                }
                continue;
            }
            if (first) {
                facts.add("tech_stack", BuildAnalyzer.tech("IBM MQ", null, "middleware", path, c.line()));
                first = false;
            }
            String otype = m.group(1).toUpperCase(Locale.ROOT);
            otype = QUEUE_TYPES.getOrDefault(otype, otype);
            String name = TextUtil.strip(m.group(2));
            Map<String, String> attrs = new LinkedHashMap<>();
            Matcher a = ATTR.matcher(m.group(3));
            while (a.find()) {
                attrs.put(a.group(1).toUpperCase(Locale.ROOT), TextUtil.strip(a.group(2)));
            }
            if (otype.startsWith("Q")) {
                Map<String, String> details = new LinkedHashMap<>();
                details.put("defined_as", otype);
                details.putAll(owner);
                for (String key : QUEUE_KEYS) {
                    if (TextUtil.truthy(attrs.get(key))) {
                        details.put(key.toLowerCase(Locale.ROOT), attrs.get(key));
                    }
                }
                if (!attrs.getOrDefault("USAGE", "").toUpperCase(Locale.ROOT).equals("XMITQ")) { // transmission queues are plumbing
                    facts.add("integrations", Integrations.integration("MQ", name, "defined", path, c.line(), null, "MQ", details));
                }
                Symbol s = facts.symbol("mq-queue", name, path, c.line());
                details.forEach(s::data);
            } else if (otype.equals("CHANNEL") || otype.equals("CHL")) {
                String ctype = attrs.getOrDefault("CHLTYPE", "").toUpperCase(Locale.ROOT);
                Map<String, String> details = new LinkedHashMap<>();
                details.put("chltype", ctype);
                details.putAll(owner);
                if (TextUtil.truthy(attrs.get("CONNAME"))) {
                    details.put("conname", attrs.get("CONNAME"));
                }
                if (TextUtil.truthy(attrs.get("XMITQ"))) {
                    details.put("xmitq", attrs.get("XMITQ"));
                }
                facts.add("integrations", Integrations.integration("MQ-CHANNEL", name, CHANNEL_DIRECTION.getOrDefault(ctype, "unknown"), path,
                        c.line(), null, "MQ", details));
            }
        }
        return facts;
    }
}
