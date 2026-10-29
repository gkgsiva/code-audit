package com.legacyaudit.engine.analyzers;

import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.FileRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Axway CFT: configuration exports ({@code CFTPART}, {@code CFTTCP}, {@code CFTSEND},
 * {@code CFTRECV}) and {@code CFTUTIL SEND/RECV} calls (the shell analyzer calls
 * {@link #cftutilIntegration}). A CFT integration is one (partner/flow, direction); flows defined
 * in an export but never sent or received by a scanned script are kept with partner {@code *}.
 */
public final class CftAnalyzer implements Analyzer {

    private static final Pattern CMD = Rx.of("^\\s*(CFT[A-Z]+)\\b(.*)$", Rx.I);
    private static final Pattern PARAM = Rx.of("(\\w+)\\s*=\\s*(\\([^)]*\\)|'[^']*'|\"[^\"]*\"|[^,\\s]+)");
    private static final Pattern SEND_RECV = Rx.of("\\s*(SEND|RECV)\\b(.*)$", Rx.I | Pattern.DOTALL);

    @Override
    public String name() {
        return "cft";
    }

    @Override
    public String version() {
        return "2";
    }

    static Map<String, String> parseParams(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = PARAM.matcher(text);
        while (m.find()) {
            out.put(m.group(1).toUpperCase(Locale.ROOT), TextUtil.strip(m.group(2), "'\"()"));
        }
        return out;
    }

    /** {@code SEND PART=X, IDF=Y, FNAME=Z} (the text after {@code CFTUTIL}) as an integration, or null. */
    public static Doc cftutilIntegration(String args, String file, int line) {
        Matcher m = SEND_RECV.matcher(args);
        if (!m.lookingAt()) {
            return null;
        }
        Map<String, String> params = parseParams(m.group(2));
        String partner = params.getOrDefault("PART", "?");
        String idf = params.getOrDefault("IDF", "?");
        String direction = m.group(1).toUpperCase(Locale.ROOT).equals("SEND") ? "outbound" : "inbound";
        Map<String, String> details = new LinkedHashMap<>();
        details.put("partner", partner);
        details.put("idf", idf);
        if (TextUtil.truthy(params.get("FNAME"))) {
            details.put("file", params.get("FNAME"));
        }
        return Integrations.integration("CFT", partner + "/" + idf, direction, file, line, null, "CFT", details);
    }

    @Override
    public boolean supports(FileRecord file) {
        String p = file.path.toLowerCase(Locale.ROOT);
        return p.startsWith("audit-inputs/cft/") || p.endsWith(".cft") || p.endsWith(".cfg.cft")
                || ((file.language.equals("text") || file.language.equals("ini") || file.language.equals("data")
                        || file.language.startsWith("other (")) && p.contains("/cft"));
    }

    private record Command(int line, String cmd, String params) {}

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        Facts facts = new Facts();
        List<String> lines = TextUtil.splitLines(ctx.textOrEmpty());
        facts.coverage.lines = lines.size();
        List<Command> commands = new ArrayList<>();
        List<String> current = new ArrayList<>();
        String curCmd = "";
        int curLine = 0;
        int no = 0;
        for (String raw : lines) {
            no++;
            String s = TextUtil.strip(raw);
            if (s.isEmpty() || s.startsWith("/*") || s.startsWith("*") || s.startsWith("#")) {
                continue;
            }
            Matcher m = CMD.matcher(s);
            if (m.lookingAt()) {
                if (!curCmd.isEmpty()) {
                    commands.add(new Command(curLine, curCmd, String.join(" ", current)));
                }
                curCmd = m.group(1).toUpperCase(Locale.ROOT);
                curLine = no;
                current = new ArrayList<>(List.of(m.group(2)));
            } else if (!curCmd.isEmpty()) {
                current.add(TextUtil.lstrip(s, "+"));
            } else {
                facts.coverage.gap(no, no, "not a CFT command");
            }
        }
        if (!curCmd.isEmpty()) {
            commands.add(new Command(curLine, curCmd, String.join(" ", current)));
        }
        if (!commands.isEmpty()) {
            facts.add("tech_stack", BuildAnalyzer.tech("Axway CFT", null, "middleware", file.path, commands.get(0).line()));
        }
        Map<String, String> hosts = new HashMap<>();
        for (Command c : commands) {
            Map<String, String> p = parseParams(c.params());
            if (c.cmd().equals("CFTTCP") && TextUtil.truthy(p.get("ID"))) {
                hosts.put(p.get("ID"), p.getOrDefault("HOST", ""));
            }
        }
        for (Command c : commands) {
            Map<String, String> p = parseParams(c.params());
            String ident = p.getOrDefault("ID", "");
            if (c.cmd().equals("CFTPART") && !ident.isEmpty()) {
                facts.symbol("cft-partner", ident, file.path, c.line())
                        .data("host", hosts.getOrDefault(ident, ""))
                        .data("nspart", p.getOrDefault("NSPART", ""));
            } else if ((c.cmd().equals("CFTSEND") || c.cmd().equals("CFTRECV")) && !ident.isEmpty()) {
                String direction = c.cmd().equals("CFTSEND") ? "outbound" : "inbound";
                Map<String, String> details = new LinkedHashMap<>();
                details.put("partner", "*");
                details.put("idf", ident);
                details.put("defined_by_export", "true");
                if (TextUtil.truthy(p.get("FNAME"))) {
                    details.put("file", p.get("FNAME"));
                }
                facts.add("integrations", Integrations.integration("CFT", "*/" + ident, direction, file.path, c.line(), null, "CFT", details));
            }
        }
        return facts;
    }
}
