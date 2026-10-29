package com.legacyaudit.engine.analyzers;

import com.legacyaudit.engine.inventory.Rx;
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
 * z/OS JCL: jobs, steps, {@code EXEC PGM=} / procedure calls, DD datasets, in-stream procedures
 * and DB2 {@code RUN PROGRAM(...)} under {@code IKJEFT01}. A job is a batch job (scheduler
 * {@code jcl}); a catalogued procedure ({@code //NAME PROC}) is a {@code jcl-proc} symbol its
 * callers link to.
 */
public final class JclAnalyzer implements Analyzer {

    static final Set<String> UTILITIES = Set.of("IEFBR14", "IDCAMS", "IEBGENER", "IEBCOPY", "IEBUPDTE", "IEBPTPCH", "SORT", "DFSORT",
            "ICEMAN", "ICETOOL", "SYNCSORT", "ADRDSSU", "IKJEFT01", "IKJEFT1A", "IKJEFT1B", "IRXJCL", "DSNUTILB", "DSNTIAUL", "DSNTEP2",
            "DSNTEP4", "DFSRRC00", "IEHLIST", "IEHMOVE", "IEHPROGM", "FTP", "BPXBATCH", "IGYCRCTL", "IEWL", "HEWL", "ASMA90", "DSNHPC",
            "FILEAID", "IOEAGFMT", "EZACFSM1");
    private static final Set<String> TSO = Set.of("IKJEFT01", "IKJEFT1A", "IKJEFT1B");
    private static final Pattern STMT = Rx.of("^//([A-Z@#$][\\w@#$.]*)?\\s+(JOB|EXEC|DD|PROC|PEND|SET|INCLUDE|JCLLIB|OUTPUT|IF|ELSE|ENDIF|CNTL|"
            + "ENDCNTL)\\b\\s*(.*)$", Rx.I);
    private static final Pattern PARAM = Rx.of("([A-Z]+)=(\\((?:[^()]|\\([^()]*\\))*\\)|'[^']*'|[^,\\s]+)", Rx.I);
    private static final Pattern RUN = Rx.of("\\bRUN\\s+PROGRAM\\s*\\(\\s*([\\w@#$]+)\\s*\\)(?:\\s+PLAN\\s*\\(\\s*([\\w@#$]+)\\s*\\))?", Rx.I);
    private static final Pattern INSTREAM = Rx.of("^(\\*|DATA\\b)", Rx.I);
    private static final Pattern DLM = Rx.of("DLM=(?:'([^']+)'|(\\S\\S))", Rx.I);

    @Override
    public String name() {
        return "jcl";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public boolean supports(FileRecord file) {
        return file.language.equals("jcl");
    }

    static Map<String, String> params(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = PARAM.matcher(text);
        while (m.find()) {
            out.put(m.group(1).toUpperCase(Locale.ROOT), m.group(2));
        }
        return out;
    }

    /** A statement with continuations joined, and its in-stream data lines. */
    record Statement(int line, String name, String op, String operands, List<String> data) {}

    private static String head72(String s) {
        return s.length() > 72 ? s.substring(0, 72) : s;
    }

    static List<Statement> statements(String text, Facts facts) {
        List<Statement> out = new ArrayList<>();
        List<String> lines = TextUtil.splitLines(text);
        int i = 0;
        while (i < lines.size()) {
            String raw = head72(lines.get(i));
            int no = i + 1;
            i++;
            if (raw.startsWith("//*") || TextUtil.strip(raw).isEmpty()) {
                continue;
            }
            if (!raw.startsWith("//")) {
                if (raw.startsWith("/*")) {
                    continue;
                }
                facts.coverage.gap(no, no, "not a JCL statement");
                continue;
            }
            String operands = raw;
            // Continuation: operand field ends with a comma; the next line is "//   more".
            while (TextUtil.rstrip(operands).endsWith(",") && i < lines.size() && lines.get(i).startsWith("//")
                    && !lines.get(i).startsWith("//*")) {
                String next = lines.get(i);
                operands = TextUtil.rstrip(operands) + TextUtil.strip(next.substring(2, Math.min(72, Math.max(2, next.length()))));
                i++;
            }
            Matcher m = STMT.matcher(operands);
            if (!m.lookingAt()) {
                if (!TextUtil.strip(operands).equals("//")) {
                    facts.coverage.gap(no, no, "unrecognised JCL statement");
                }
                continue;
            }
            List<String> data = new ArrayList<>();
            String ops = m.group(3);
            if (m.group(2).toUpperCase(Locale.ROOT).equals("DD") && INSTREAM.matcher(TextUtil.strip(ops)).lookingAt()) {
                String delim = "/*";
                Matcher dm = DLM.matcher(ops);
                if (dm.find()) {
                    delim = dm.group(1) != null && !dm.group(1).isEmpty() ? dm.group(1) : dm.group(2);
                }
                while (i < lines.size() && !lines.get(i).startsWith(delim) && !(delim.equals("/*") && lines.get(i).startsWith("//"))) {
                    data.add(head72(lines.get(i)));
                    i++;
                }
                if (i < lines.size() && lines.get(i).startsWith(delim)) {
                    i++;
                }
            }
            String name = m.group(1) == null ? "" : m.group(1).toUpperCase(Locale.ROOT);
            out.add(new Statement(no, name, m.group(2).toUpperCase(Locale.ROOT), ops, data));
        }
        return out;
    }

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        Facts facts = new Facts();
        String text = ctx.textOrEmpty();
        String path = file.path;
        facts.coverage.lines = TextUtil.lineCount(text);
        List<Statement> statements = statements(text, facts);
        Doc job = null;
        String owner = "file:" + path;
        String ownerLabel = path;
        String currentStep = "";
        String currentPgm = "";
        String inProc = "";
        for (Statement st : statements) {
            int line = st.line();
            String name = st.name();
            String ops = st.operands();
            Map<String, String> p = params(ops);
            switch (st.op()) {
                case "JOB" -> {
                    String jid = Fact.makeId("batch_job", "jcl", name);
                    job = Doc.of(Schemas.BATCH_JOB,
                            "id", jid,
                            "name", name,
                            "scheduler", "jcl",
                            "component", null,
                            "evidence", new ArrayList<>(List.of(new Evidence(path, line, null))));
                    facts.add("batch_jobs", job);
                    facts.symbol("jcl-job", name, path, line).owner(jid);
                    owner = "batch_job:" + jid;
                    ownerLabel = name;
                    if (!facts.entities.containsKey("tech_stack")) {
                        facts.add("tech_stack", BuildAnalyzer.tech("JCL", null, "scheduler", path, line));
                    }
                }
                case "PROC" -> {
                    if (!name.isEmpty()) {
                        inProc = name;
                        facts.symbol("jcl-proc", name, path, line);
                        if (job == null) {
                            owner = "file:" + path;
                            ownerLabel = name;
                        }
                    }
                }
                case "PEND" -> inProc = "";
                case "EXEC" -> {
                    currentStep = !name.isEmpty() ? name : "STEP@" + line;
                    String first = TextUtil.strip(TextUtil.beforeFirst(ops, ","));
                    String pgm = TextUtil.strip(p.getOrDefault("PGM", ""), "'").toUpperCase(Locale.ROOT);
                    String proc = p.getOrDefault("PROC", "").toUpperCase(Locale.ROOT);
                    if (proc.isEmpty()) {
                        proc = first.contains("=") ? "" : first.toUpperCase(Locale.ROOT);
                    }
                    currentPgm = pgm;
                    if (job != null) {
                        job.<String>list("steps").add(currentStep);
                    }
                    if (!pgm.isEmpty()) {
                        if (job != null) {
                            job.<String>list("programs").add(pgm);
                        }
                        if (!UTILITIES.contains(pgm) && !pgm.startsWith("&")) {
                            facts.ref("runs", owner, ownerLabel, pgm, List.of("cobol-program", "c-executable", "file-name"), path, line)
                                    .data("step", currentStep).data("proc", inProc);
                        }
                    } else if (!proc.isEmpty()) {
                        facts.ref("runs", owner, ownerLabel, proc, List.of("jcl-proc"), path, line).data("step", currentStep);
                    }
                }
                case "DD" -> {
                    String dsn = TextUtil.or(p.get("DSN"), p.get("DSNAME"));
                    if (TextUtil.truthy(dsn) && job != null && !dsn.startsWith("&&")) {
                        String ds = TextUtil.beforeFirst(TextUtil.strip(dsn, "'"), "(").toUpperCase(Locale.ROOT);
                        List<String> datasets = job.list("datasets");
                        if (!datasets.contains(ds)) {
                            datasets.add(ds);
                        }
                    }
                    if (!st.data().isEmpty() && TSO.contains(currentPgm)) {
                        int k = 0;
                        for (String dl : st.data()) {
                            k++;
                            Matcher r = RUN.matcher(dl);
                            if (r.find()) {
                                String prog = r.group(1).toUpperCase(Locale.ROOT);
                                if (job != null) {
                                    job.<String>list("programs").add(prog);
                                }
                                facts.add("tech_stack", BuildAnalyzer.tech("DB2", null, "database", path, line + k));
                                facts.ref("runs", owner, ownerLabel, prog, List.of("cobol-program", "c-executable"), path, line + k)
                                        .data("step", currentStep).data("plan", r.group(2) == null ? "" : r.group(2));
                            }
                        }
                    }
                }
                default -> {
                    // SET, INCLUDE, JCLLIB, OUTPUT, IF/ELSE/ENDIF, CNTL: nothing to record
                }
            }
        }
        return facts;
    }
}
