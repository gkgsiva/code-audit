package com.legacyaudit.engine.analyzers;

import static com.legacyaudit.engine.analyzers.BuildAnalyzer.tech;
import static com.legacyaudit.engine.analyzers.Integrations.integration;

import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Schemas;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * COBOL and Pro*COBOL (fixed or free format), copybooks and CICS BMS maps.
 *
 * <p>Programs: {@code PROGRAM-ID} (a {@code cobol-program} service, or a
 * {@code cics-transaction} service when the program runs under CICS), paragraphs, {@code CALL}
 * (literal or a variable with a {@code VALUE}), {@code COPY}, {@code EXEC SQL} (dialect
 * {@code cobol.sql_dialect}; dynamic SQL rebuilt by {@link CobolSql}), {@code EXEC CICS}
 * ({@code SEND/RECEIVE MAP} to BMS maps, {@code LINK/XCTL}, {@code START/RETURN TRANSID}, file
 * commands, web services), file {@code SELECT ... ASSIGN} and IBM MQ (MQI) calls. A program
 * that is only ever CALLed is not counted as a service; the linker removes it once every file
 * is known.
 *
 * <p>BMS mapsets: every {@code DFHMDI} map is a {@code Screen}; its fields are the named
 * {@code DFHMDF} macros.
 */
public final class CobolAnalyzer implements Analyzer {

    private static final Pattern PROGRAM_ID = Rx.of("\\bPROGRAM-ID\\.?\\s+['\"]?([\\w-]+)", Rx.I);
    private static final Pattern PARAGRAPH = Rx.of("^([\\w-]+)(\\s+SECTION)?\\s*\\.\\s*$", Rx.I);
    private static final Pattern CALL = Rx.of("\\bCALL\\s+(?:'([\\w-]+)'|\"([\\w-]+)\"|([A-Z][\\w-]*))", Rx.I);
    private static final Pattern COPY = Rx.of("\\bCOPY\\s+['\"]?([\\w-]+)['\"]?(?:\\s+(?:OF|IN)\\s+([\\w-]+))?", Rx.I);
    private static final Pattern VALUE = Rx.of("^\\s*\\d\\d\\s+([\\w-]+)\\s+.*?\\bVALUE\\s+(?:IS\\s+)?['\"]([^'\"]+)['\"]", Rx.I);
    private static final Pattern MOVE = Rx.of("\\bMOVE\\s+['\"]([^'\"]+)['\"]\\s+TO\\s+([\\w-]+)", Rx.I);
    private static final Pattern SELECT = Rx.of(
            "\\bSELECT\\s+(?:OPTIONAL\\s+)?([\\w-]+)\\s+ASSIGN\\s+(?:TO\\s+)?(?:'([^']+)'|\"([^\"]+)\"|([\\w-]+))", Rx.I);
    // Possessive loops: nothing follows them, so they match what the greedy Python ones do.
    private static final Pattern OPEN = Rx.of("\\bOPEN\\s+((?:(?:INPUT|OUTPUT|I-O|EXTEND)\\s+(?:[\\w-]+\\s*)++)++)", Rx.I);
    private static final Pattern CICS = Rx.of("\\bEXEC\\s+CICS\\s+([\\s\\S]*?)\\bEND-EXEC\\b", Rx.I);
    private static final Pattern CICS_ARG = Rx.of("\\b([A-Z]+)\\s*\\(\\s*(?:'([^']*)'|\"([^\"]*)\"|([\\w-]+))\\s*\\)", Rx.I);
    private static final Pattern EXEC_BLOCK = Rx.of("\\bEXEC\\s+(SQL|CICS|DLI)\\b[\\s\\S]*?\\bEND-EXEC\\b", Rx.I);
    private static final Pattern NOT_NEWLINE = Rx.of("[^\\n]");
    private static final Pattern FREE_FORMAT = Rx.of(">>\\s*SOURCE\\s+(FORMAT\\s+)?(IS\\s+)?FREE", Rx.I);
    private static final Pattern STARTS_LEFT = Rx.of("^\\s{0,6}[A-Z]", Rx.I);
    private static final Pattern SEQUENCE_AREA = Rx.of("^[\\d ]{6}[ *\\-/D]");
    private static final Pattern PROCEDURE = Rx.of("^\\s*PROCEDURE\\s+DIVISION\\b", Rx.I);
    private static final Pattern DIVISION = Rx.of("^\\s*(IDENTIFICATION|ID|ENVIRONMENT|DATA|PROCEDURE)\\s+DIVISION\\b", Rx.I);
    private static final Pattern ASSIGN_PREFIX = Rx.of("^(UT-S-|UT-|S-|DA-S-)");
    private static final Pattern BMS_MACRO = Rx.of("^(\\S+)?\\s+(DFHMSD|DFHMDI|DFHMDF|PRINT|END|TITLE)\\b\\s*(.*)$", Rx.I);
    private static final Set<String> MQ_CALLS = Set.of("MQCONN", "MQCONNX", "MQOPEN", "MQPUT", "MQPUT1", "MQGET", "MQCLOSE", "MQDISC");
    private static final Set<String> NOT_PARAGRAPHS = Set.of("END-IF", "END-PERFORM", "END-EVALUATE", "EXIT", "GOBACK", "END-EXEC", "CONTINUE",
            "STOP RUN");
    private static final List<String> RUNTIME_PREFIXES = List.of("CEE", "DSN", "ILBO", "IGZ");

    @Override
    public String name() {
        return "cobol";
    }

    @Override
    public String version() {
        return "4"; // 4: CICS web service calls
    }

    @Override
    public boolean supports(FileRecord file) {
        return file.language.equals("cobol") || file.language.equals("procobol") || file.language.equals("bms");
    }

    private static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    private static String or(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }

    /** {@code s[a:b]} by code point (Python slicing, clamped). */
    private static String slice(String s, int a, int b) {
        int n = s.codePointCount(0, s.length());
        a = Math.min(a, n);
        b = Math.min(b, n);
        if (a >= b) {
            return "";
        }
        return s.substring(s.offsetByCodePoints(0, a), s.offsetByCodePoints(0, b));
    }

    /**
     * Code part of each line (sequence area, indicator and identification area removed, comments
     * blanked). Fixed format unless the source says {@code >>SOURCE FORMAT FREE}.
     */
    public static List<String> sourceLines(String text) {
        boolean free = FREE_FORMAT.matcher(Rx.head(text, 2000)).find();
        List<String> raw = TextUtil.splitLines(text);
        if (!free) {
            // Sources without a sequence area (code starting in column 1-7) are treated as free.
            int coded = 0;
            int startsLeft = 0;
            for (String ln : raw) {
                if (TextUtil.strip(ln).isEmpty()) {
                    continue;
                }
                coded++;
                if (STARTS_LEFT.matcher(ln).lookingAt() && !SEQUENCE_AREA.matcher(ln).lookingAt()) {
                    startsLeft++;
                }
            }
            free = coded > 0 && startsLeft > coded * 0.6;
        }
        List<String> out = new ArrayList<>(raw.size());
        for (String line : raw) {
            if (free) {
                String code = TextUtil.beforeFirst(line, "*>");
                out.add(TextUtil.lstrip(code).startsWith("*") ? "" : code);
                continue;
            }
            int n = line.codePointCount(0, line.length());
            if (n < 7) {
                out.add("");
                continue;
            }
            String indicator = slice(line, 6, 7);
            if (indicator.equals("*") || indicator.equals("/")) {
                out.add("");
            } else if (indicator.equals("-")) {
                out.add(" " + TextUtil.lstrip(TextUtil.lstrip(slice(line, 7, 72)), "'\"")); // continuation
            } else {
                out.add(TextUtil.beforeFirst(slice(line, 7, 72), "*>"));
            }
        }
        return out;
    }

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        Facts facts = new Facts();
        String text = ctx.textOrEmpty();
        facts.coverage.lines = TextUtil.count(text, '\n') + 1;
        if (file.language.equals("bms")) {
            bms(file.path, text, facts);
        } else {
            cobol(file, text, ctx, facts);
        }
        return facts;
    }

    // ---- COBOL ------------------------------------------------------------------------------

    private void cobol(FileRecord file, String text, AnalyzerContext ctx, Facts facts) {
        String path = file.path;
        List<String> lines = sourceLines(text);
        String code = String.join("\n", lines);
        TextUtil.LineIndex index = new TextUtil.LineIndex(code);
        Matcher programM = PROGRAM_ID.matcher(code);
        boolean found = programM.find();
        boolean copybook = !found;
        String src = "file:" + path;
        if (copybook) {
            facts.symbol("cobol-copybook", upper(TextUtil.stem(path)), path, 1);
        } else {
            facts.add("tech_stack", tech("COBOL", null, "language", path, 1));
        }
        String program = found ? upper(programM.group(1)) : upper(TextUtil.stem(path));
        int progLine = found ? index.line(programM.start()) : 1;

        // Blank EXEC blocks in the code view so their words are not read as COBOL.
        String codeWoExec = TextUtil.sub(EXEC_BLOCK, code, m -> NOT_NEWLINE.matcher(m.group()).replaceAll(" "));
        // Embedded SQL, from the code area only (one line per source line, so lines match);
        // PREPARE ... FROM :var is rebuilt from the MOVE/STRING statements that fill the var.
        String dialect = ctx.config().cobolSqlDialect();
        CobolSql built = new CobolSql();
        built.run(Arrays.asList(codeWoExec.split("\n", -1)));
        Embedded.Result res = Embedded.extract(code, path, dialect, true, built::valueAt);
        if (!res.statements.isEmpty()) {
            facts.add("tech_stack", tech(dialect.equals("db2") ? "DB2" : upper(dialect), null, "database", path,
                    ((Number) res.statements.getFirst().get("line")).intValue()));
        }
        for (Doc stmt : res.statements) {
            facts.add("sql_statements", stmt);
        }
        for (Embedded.Include inc : res.includes) {
            facts.ref("includes", src, program, upper(inc.member()), List.of("cobol-copybook", "file-name"), path, inc.line());
        }
        for (Embedded.Span s : res.unparsed) {
            facts.coverage.gap(s.start(), s.end(), "embedded SQL not parsed");
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (String textLine : lines) {
            Matcher v = VALUE.matcher(textLine);
            if (v.lookingAt()) {
                values.put(upper(v.group(1)), TextUtil.strip(v.group(2)));
            }
        }
        record Move(String var, String val, int line) {}
        List<Move> moves = new ArrayList<>();
        Matcher mv = MOVE.matcher(codeWoExec);
        while (mv.find()) {
            moves.add(new Move(upper(mv.group(2)), TextUtil.strip(mv.group(1)), index.line(mv.start())));
        }
        for (Move mo : moves) {
            values.putIfAbsent(mo.var(), mo.val());
        }

        Matcher cp = COPY.matcher(codeWoExec);
        while (cp.find()) {
            facts.ref("includes", src, program, upper(cp.group(1)), List.of("cobol-copybook", "file-name"), path, index.line(cp.start()));
        }

        Set<String> mqUsed = new HashSet<>();
        Matcher call = CALL.matcher(codeWoExec);
        while (call.find()) {
            int line = index.line(call.start());
            String literal = call.group(1) != null ? call.group(1) : call.group(2);
            String target = literal != null ? literal : values.get(upper(call.group(3) == null ? "" : call.group(3)));
            if (target == null || target.isEmpty()) {
                facts.coverage.gap(line, line, "dynamic CALL " + call.group(3) + " not resolved");
                continue;
            }
            target = upper(target);
            if (MQ_CALLS.contains(target)) {
                mqUsed.add(target);
                continue;
            }
            String t = target;
            if (RUNTIME_PREFIXES.stream().anyMatch(t::startsWith) || target.equals("CBLTDLI") || target.equals("AIBTDLI")) {
                continue; // runtime / DB2 / IMS services, not application programs
            }
            facts.ref("calls", src, program, target, List.of("cobol-program", "c-function", "c-executable"), path, line)
                    .data("dynamic", literal != null ? "false" : "true");
        }

        if (!mqUsed.isEmpty()) {
            facts.add("tech_stack", tech("IBM MQ (MQI)", null, "middleware", path, progLine));
            List<Map.Entry<String, Integer>> queues = new ArrayList<>();
            for (Move mo : moves) {
                if (mo.var().endsWith("OBJECTNAME")) {
                    queues.add(Map.entry(mo.val(), mo.line()));
                }
            }
            List<Map.Entry<String, Integer>> fromValues = new ArrayList<>();
            for (var e : values.entrySet()) {
                if (e.getKey().endsWith("OBJECTNAME") && queues.stream().noneMatch(q -> q.getKey().equals(e.getValue()))) {
                    fromValues.add(Map.entry(e.getValue(), 1));
                }
            }
            queues.addAll(fromValues);
            boolean puts = mqUsed.contains("MQPUT") || mqUsed.contains("MQPUT1");
            boolean gets = mqUsed.contains("MQGET");
            String role = puts && !gets ? "producer" : gets && !puts ? "consumer" : null;
            for (var q : queues) {
                facts.add("integrations", integration("MQ", q.getKey(), "unknown", path, q.getValue(), role, "MQI", null));
            }
        }

        files(path, codeWoExec, index, facts);
        Map<String, String> cics = cics(path, program, code, index, facts);

        if (copybook) {
            return;
        }
        List<String> paragraphs = paragraphs(lines);
        String transid = cics.get("transid");
        boolean cicsUsed = cics.containsKey("used");
        String kind = cicsUsed ? "cics-transaction" : "cobol-program";
        String sid = Fact.makeId("service", "cobol", path, program);
        facts.add("services", Doc.of(Schemas.SERVICE,
                "id", sid,
                "name", or(transid, program),
                "kind", kind,
                "file", path,
                "handler", program,
                "evidence", new ArrayList<>(List.of(new Evidence(path, progLine, "PROGRAM-ID. " + program)))));
        facts.symbol("cobol-program", program, path, progLine).owner(sid)
                .data("paragraphs", String.valueOf(paragraphs.size()))
                .data("cics", cicsUsed ? "true" : "false");
        if (cicsUsed) {
            facts.add("tech_stack", tech("CICS", null, "middleware", path, progLine));
        }
        if (!DIVISION.matcher(code).find()) {
            facts.coverage.gap(1, lines.size(), "no COBOL division found");
        }
    }

    private static List<String> paragraphs(List<String> lines) {
        List<String> out = new ArrayList<>();
        boolean inProc = false;
        for (String line : lines) {
            if (PROCEDURE.matcher(line).lookingAt()) {
                inProc = true;
                continue;
            }
            String stripped = TextUtil.strip(line);
            if (!inProc || stripped.isEmpty() || line.length() - TextUtil.lstrip(line).length() > 3) {
                continue; // paragraph names start in area A
            }
            Matcher m = PARAGRAPH.matcher(stripped);
            if (m.lookingAt() && !NOT_PARAGRAPHS.contains(upper(m.group(1)))) {
                out.add(upper(m.group(1)));
            }
        }
        return out;
    }

    private static void files(String path, String code, TextUtil.LineIndex index, Facts facts) {
        Map<String, Map.Entry<String, Integer>> assigns = new LinkedHashMap<>();
        Matcher m = SELECT.matcher(code);
        while (m.find()) {
            String target = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
            assigns.put(upper(m.group(1)), Map.entry(target, index.line(m.start())));
        }
        Map<String, Set<String>> modes = new LinkedHashMap<>();
        Matcher o = OPEN.matcher(code);
        while (o.find()) {
            String mode = "INPUT";
            for (String tok : TextUtil.words(o.group(1))) {
                String t = upper(tok);
                if (t.equals("INPUT") || t.equals("OUTPUT") || t.equals("I-O") || t.equals("EXTEND")) {
                    mode = t;
                } else {
                    modes.computeIfAbsent(t, k -> new LinkedHashSet<>()).add(mode);
                }
            }
        }
        for (var e : assigns.entrySet()) {
            Set<String> used = modes.getOrDefault(e.getKey(), Set.of());
            String direction;
            if (used.isEmpty()) {
                direction = "unknown";
            } else if (Set.of("INPUT").containsAll(used)) {
                direction = "inbound";
            } else if (used.contains("I-O") || (used.contains("INPUT") && (used.contains("OUTPUT") || used.contains("EXTEND")))) {
                direction = "both";
            } else {
                direction = "outbound";
            }
            String name = ASSIGN_PREFIX.matcher(upper(e.getValue().getKey())).replaceFirst("");
            facts.add("integrations", integration("FILE", "DD:" + name, direction, path, e.getValue().getValue(), null, null,
                    Map.of("select", e.getKey())));
        }
    }

    private static Map<String, String> cics(String path, String program, String code, TextUtil.LineIndex index, Facts facts) {
        Map<String, String> info = new LinkedHashMap<>();
        String src = "file:" + path;
        Matcher m = CICS.matcher(code);
        while (m.find()) {
            info.put("used", "true");
            int line = index.line(m.start());
            String body = String.join(" ", TextUtil.words(m.group(1)));
            List<String> bodyWords = TextUtil.words(body);
            String verb = upper(String.join(" ", bodyWords.subList(0, Math.min(2, bodyWords.size()))));
            Map<String, String> args = new LinkedHashMap<>();
            Set<String> literal = new HashSet<>();
            Matcher a = CICS_ARG.matcher(body);
            while (a.find()) {
                String key = upper(a.group(1));
                String value = or(a.group(2), or(a.group(3), or(a.group(4), "")));
                args.put(key, value);
                if (or(a.group(2), a.group(3)) != null && !or(a.group(2), or(a.group(3), "")).isEmpty()) {
                    literal.add(key);
                }
            }
            List<String> verbWords = TextUtil.words(verb);
            if ((verb.startsWith("SEND MAP") || verb.startsWith("RECEIVE MAP")) && args.containsKey("MAP")) {
                facts.ref(verb.startsWith("SEND") ? "forwards" : "reads", src, program, upper(args.get("MAP")), List.of("bms-map"), path, line)
                        .reportUnresolved(literal.contains("MAP"))
                        .data("mapset", upper(args.getOrDefault("MAPSET", "")));
                continue;
            }
            if (verbWords.isEmpty()) {
                throw new PyError("IndexError", "list index out of range");
            }
            String first = verbWords.getFirst();
            if ((first.equals("LINK") || first.equals("XCTL")) && args.containsKey("PROGRAM")) {
                facts.ref("calls", src, program, upper(args.get("PROGRAM")), List.of("cobol-program"), path, line)
                        .reportUnresolved(literal.contains("PROGRAM"));
            } else if (first.equals("RETURN") && args.containsKey("TRANSID") && literal.contains("TRANSID")) {
                info.putIfAbsent("transid", upper(args.get("TRANSID")));
            } else if (first.equals("START") && args.containsKey("TRANSID")) {
                facts.ref("triggers", src, program, upper(args.get("TRANSID")), List.of("cics-transaction"), path, line)
                        .reportUnresolved(false);
            } else if (Set.of("READ", "WRITE", "REWRITE", "DELETE", "STARTBR").contains(first)
                    && (args.containsKey("FILE") || args.containsKey("DATASET"))) {
                String ds = upper(or(args.get("FILE"), or(args.get("DATASET"), "")));
                String direction = first.equals("READ") || first.equals("STARTBR") ? "inbound" : "outbound";
                facts.add("integrations", integration("FILE", "CICS:" + ds, direction, path, line, null, "CICS", null));
            } else if (verb.startsWith("WEB ") || verb.startsWith("INVOKE ")) {
                String endpoint = or(args.get("URIMAP"), or(args.get("WEBSERVICE"), or(args.get("HOST"), "?")));
                boolean invoke = verb.startsWith("INVOKE ");
                String second = verbWords.get(1);
                if (invoke || second.equals("OPEN") || second.equals("CONVERSE") || second.equals("SEND")) {
                    String svc = or(args.get("WEBSERVICE"), or(args.get("SERVICE"), endpoint));
                    String host = args.getOrDefault("HOST", "");
                    String url = !host.isEmpty() && literal.contains("HOST")
                            ? (upper(body).contains("HTTPS") ? "https" : "http") + "://" + host.toLowerCase(Locale.ROOT)
                                    + args.getOrDefault("PATH", "")
                            : null;
                    facts.add("web_services", WebSvc.called(invoke ? "SOAP" : "REST",
                            invoke ? upper(svc) : or(host.toLowerCase(Locale.ROOT), upper(svc)),
                            invoke ? "CICS web services" : "CICS WEB", path, line, url,
                            invoke ? args.get("OPERATION") : null,
                            invoke ? null : args.get("METHOD"), null, null, null));
                }
                facts.add("integrations", integration("HTTP", "CICS " + first + " " + endpoint, "outbound", path, line, null, "CICS", null));
            }
        }
        return info;
    }

    // ---- BMS --------------------------------------------------------------------------------

    private record Macro(int line, String label, String macro, String operands) {}

    private static final class BmsMap {
        final String name;
        final int line;
        int fields;

        BmsMap(String name, int line) {
            this.name = name;
            this.line = line;
        }
    }

    private static void bms(String path, String text, Facts facts) {
        List<Macro> statements = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int start = 0;
        int no = 0;
        for (String raw : TextUtil.splitLines(text)) {
            no++;
            if (raw.startsWith("*") || TextUtil.strip(raw).isEmpty()) {
                continue;
            }
            int n = raw.codePointCount(0, raw.length());
            String body = slice(raw, 0, 71);
            boolean cont = (n > 71 && !slice(raw, 71, 72).equals(" ")) || TextUtil.rstrip(body).endsWith(",");
            if (buf.isEmpty()) {
                start = no;
            }
            buf.append(buf.isEmpty() ? TextUtil.rstrip(body) : TextUtil.strip(body));
            if (cont) {
                continue;
            }
            Matcher m = BMS_MACRO.matcher(buf);
            if (m.lookingAt()) {
                statements.add(new Macro(start, m.group(1) == null ? "" : upper(m.group(1)), upper(m.group(2)), m.group(3)));
            } else if (!TextUtil.strip(buf.toString()).isEmpty()) {
                facts.coverage.gap(start, no, "not a BMS macro");
            }
            buf.setLength(0);
        }
        String mapset = upper(TextUtil.stem(path));
        BmsMap current = null;
        List<BmsMap> maps = new ArrayList<>();
        for (Macro st : statements) {
            if (st.macro().equals("DFHMSD") && !st.label().isEmpty() && !upper(st.operands()).contains("TYPE=FINAL")) {
                mapset = st.label();
            } else if (st.macro().equals("DFHMDI")) {
                current = new BmsMap(st.label(), st.line());
                maps.add(current);
            } else if (st.macro().equals("DFHMDF") && current != null && !st.label().isEmpty()) {
                current.fields++;
            }
        }
        if (!maps.isEmpty()) {
            facts.add("tech_stack", tech("CICS BMS", null, "framework", path, maps.getFirst().line));
        }
        for (BmsMap mp : maps) {
            String name = or(mp.name, mapset);
            String sid = Fact.makeId("screen", path, name);
            facts.add("screens", Doc.of(Schemas.SCREEN,
                    "id", sid,
                    "name", name,
                    "technology", "BMS",
                    "file", path,
                    "fields_count", (long) mp.fields,
                    "evidence", new ArrayList<>(List.of(new Evidence(path, mp.line, null)))));
            facts.symbol("bms-map", name, path, mp.line).owner(sid).data("mapset", mapset);
        }
    }
}
