package com.legacyaudit.engine.analyzers;

import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Schemas;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scripts (sh/ksh/bash, Windows batch, Perl, Python) and crontab files. Finds invoked scripts
 * and programs (refs the linker resolves), database client calls with their inline SQL, file
 * transfers and mail ({@code ftp/sftp/scp/curl/mailx/CFTUTIL}) as integrations, and cron entries
 * as batch jobs. {@link #commandFacts} reads one scheduler command line (Autosys) the same way.
 */
public final class ShellAnalyzer implements Analyzer {

    static final List<String> SCRIPT_EXT = List.of(".sh", ".ksh", ".bash", ".csh", ".pl", ".py", ".bat", ".cmd", ".awk", ".sql", ".ctl");
    static final Map<String, String> DB_CLIENTS = WebAnalyzer.ordered(
            "sqlplus", "SQL*Plus",
            "sqlldr", "SQL*Loader",
            "db2", "DB2 CLP",
            "isql", "isql",
            "osql", "osql",
            "sqlcmd", "sqlcmd",
            "bteq", "Teradata BTEQ",
            "psql", "psql",
            "mysql", "mysql client",
            "dbaccess", "Informix dbaccess");
    static final Set<String> PREFIXES = Set.of("nohup", "exec", "time", "sudo", "nice", "command", "env", "eval", "then", "do", "else",
            "{", "(", "!", "call", "start", "su", "timeout");
    static final Set<String> INTERPRETERS = Set.of("sh", "ksh", "bash", "csh", "perl", "python", "python3", "awk", "nawk", "cmd",
            "cmd.exe", ".", "source");
    static final Set<String> SHELL_WORDS = Set.of("if", "fi", "for", "done", "while", "until", "case", "esac", "echo", "print", "printf",
            "export", "set", "unset", "read", "return", "exit", "test", "[", "[[", "cd", "ls", "cp", "mv", "rm", "mkdir", "rmdir", "chmod",
            "chown", "cat", "grep", "egrep", "sed", "awk", "cut", "sort", "uniq", "wc", "head", "tail", "tr", "date", "sleep", "find",
            "xargs", "touch", "tee", "basename", "dirname", "expr", "let", "typeset", "local", "declare", "trap", "wait", "kill", "true",
            "false", "function", "shift", "getopts", "rem", "goto", "setlocal", "endlocal", "pause", "copy", "del", "move", "type", "dir",
            "md", "rd", "if exist", "my", "use", "sub", "gzip", "gunzip", "compress", "uncompress", "tar", "zip", "unzip", "ps", "logger",
            "mkfifo");
    private static final Set<String> DML = Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "CALL");

    private static final Pattern ASSIGN = Rx.of("^\\s*(?:export\\s+|typeset\\s+(?:-x\\s+)?|set\\s+)?([A-Za-z_]\\w*)=(.*)$");
    private static final Pattern HEREDOC = Rx.of("<<-?\\s*['\"]?(\\w+)['\"]?");
    private static final Pattern VAR = Rx.of("\\$\\{?(\\w+)\\}?");
    private static final Pattern REMOTE = Rx.of("^(?:[\\w.-]+@)?([\\w.-]+):(.*)$");
    private static final Pattern PERL_SYSTEM = Rx.of("(?:system|exec)\\s*\\(?\\s*([\"'])(.+?)\\1|`([^`]+)`|qx\\s*[({/](.+?)[)}/]");
    private static final Pattern PY_SYSTEM = Rx.of("os\\.system\\(\\s*([\"'])(.+?)\\1|subprocess\\.\\w+\\(\\s*\\[\\s*([\"'])([^\"']+)\\3");
    private static final Pattern CRON = Rx.of("^\\s*((?:[\\d*/,\\-]+\\s+){4}[\\d*/,\\-A-Za-z]+|@\\w+)\\s+(.+)$");
    private static final Pattern WIN_VAR = Rx.of("%(\\w+)%");
    private static final Pattern REM = Rx.of("^\\s*(@?rem\\b|::)", Rx.I);
    private static final Pattern ENV_PREFIX = Rx.of("^\\w+=");
    private static final Pattern CONTROL = Rx.of("control=(.+)", Rx.I);
    private static final Pattern EXEC_PROC = Rx.of("EXEC(?:UTE)?\\s+([\\w.$#]+)", Rx.I);
    private static final Pattern FTP_OPEN = Rx.of("\\s*open\\s+(\\S+)", Rx.I);
    private static final Pattern PUT = Rx.of("\\b(m?put)\\b");
    private static final Pattern GET = Rx.of("\\b(m?get)\\b");
    private static final Pattern DRIVE = Rx.of("^[A-Za-z]:\\\\");

    @Override
    public String name() {
        return "shell";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public boolean supports(FileRecord file) {
        return Set.of("shell", "perl", "python", "batch", "crontab").contains(file.language) || file.path.startsWith("audit-inputs/crontab/");
    }

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        Facts facts = new Facts();
        String text = ctx.textOrEmpty();
        facts.coverage.lines = TextUtil.lineCount(text);
        Run run = new Run(file.path, ctx.config().sqlDialect(), facts);
        if (file.language.equals("crontab") || file.path.startsWith("audit-inputs/crontab/")) {
            run.crontab(text);
            return facts;
        }
        if (file.language.equals("perl")) {
            text = embedded(text, PERL_SYSTEM, 2, 3, 4);
        } else if (file.language.equals("python")) {
            text = embedded(text, PY_SYSTEM, 2, 4);
        }
        run.script(text, file.language.equals("batch"));
        return facts;
    }

    /**
     * Facts for one command line run by a scheduler (Autosys {@code command}), reported at
     * {@code path:line} and owned by {@code source} (a batch job).
     */
    public static Facts commandFacts(String command, String path, int line, String dialect, String source, String sourceLabel) {
        Facts tmp = new Facts();
        new Run(path, dialect, tmp).script(command, false);
        Facts out = new Facts();
        tmp.entities.forEach((entity, items) -> {
            for (Doc item : items) {
                Doc copy = item.copy();
                List<Evidence> ev = new ArrayList<>();
                for (Evidence e : item.<Evidence>list("evidence")) {
                    ev.add(new Evidence(e.file(), line, e.snippet()));
                }
                copy.set("evidence", ev);
                out.add(entity, copy);
            }
        });
        for (Ref r : tmp.refs) {
            Ref copy = new Ref(r.kind, source, sourceLabel, r.target, r.targetKinds, r.file, line).reportUnresolved(r.reportUnresolved);
            copy.data.putAll(r.data);
            out.refs.add(copy);
        }
        return out;
    }

    /** Keeps only the shell commands a Perl/Python script runs, on their original lines. */
    private static String embedded(String text, Pattern rx, int... groups) {
        List<String> out = new ArrayList<>();
        for (String line : TextUtil.splitLines(text)) {
            List<String> cmds = new ArrayList<>();
            Matcher m = rx.matcher(line);
            while (m.find()) {
                for (int g : groups) {
                    String v = m.group(g);
                    if (v != null && !v.isEmpty()) {
                        cmds.add(v);
                    }
                }
            }
            out.add(String.join("; ", cmds));
        }
        return String.join("\n", out);
    }

    /** Splits a command line on ; && || | outside quotes. */
    static List<String> splitSegments(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        char quote = 0;
        int i = 0;
        while (i < line.length()) {
            char ch = line.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                buf.append(ch);
            } else if (ch == '\'' || ch == '"') {
                quote = ch;
                buf.append(ch);
            } else if (ch == '&' && ((!buf.isEmpty() && buf.charAt(buf.length() - 1) == '>') || (i + 1 < line.length() && line.charAt(i + 1) == '>'))) {
                buf.append(ch); // redirection: 2>&1, &>file
            } else if (ch == ';' || ch == '|' || ch == '&') {
                out.add(buf.toString());
                buf.setLength(0);
                while (i + 1 < line.length() && (line.charAt(i + 1) == '|' || line.charAt(i + 1) == '&')) {
                    i++;
                }
            } else if (ch == '#' && (buf.isEmpty() || TextUtil.isSpace(buf.charAt(buf.length() - 1)))) {
                break;
            } else {
                buf.append(ch);
            }
            i++;
        }
        out.add(buf.toString());
        List<String> result = new ArrayList<>();
        for (String s : out) {
            String t = TextUtil.strip(s);
            if (!t.isEmpty()) {
                result.add(t);
            }
        }
        return result;
    }

    static List<String> tokens(String segment) {
        try {
            return PyShlex.split(segment);
        } catch (PyError e) {
            return TextUtil.words(segment);
        }
    }

    private static boolean endsWithAny(String s, List<String> suffixes) {
        for (String x : suffixes) {
            if (s.endsWith(x)) {
                return true;
            }
        }
        return false;
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** One file (or one scheduler command): the dialect for inline SQL and where facts go. */
    private static final class Run {
        private final String path;
        private final String dialect;
        private final Facts facts;
        private final String fileSource;

        Run(String path, String dialect, Facts facts) {
            this.path = path;
            this.dialect = dialect;
            this.facts = facts;
            this.fileSource = "file:" + path;
        }

        void script(String text, boolean windows) {
            Map<String, String> variables = new HashMap<>();
            List<String> lines = TextUtil.splitLines(text);
            int i = 0;
            while (i < lines.size()) {
                int no = i + 1;
                String line = lines.get(i);
                while (TextUtil.rstrip(line).endsWith("\\") && i + 1 < lines.size()) {
                    i++;
                    String r = TextUtil.rstrip(line);
                    line = r.substring(0, r.length() - 1) + " " + TextUtil.strip(lines.get(i));
                }
                if (windows) {
                    line = WIN_VAR.matcher(line).replaceAll("\\${$1}");
                    if (REM.matcher(line).lookingAt()) {
                        i++;
                        continue;
                    }
                }
                List<String> heredoc = new ArrayList<>();
                Matcher h = HEREDOC.matcher(line);
                boolean hasHeredoc = h.find();
                if (hasHeredoc) {
                    String end = h.group(1);
                    int j = i + 1;
                    while (j < lines.size() && !TextUtil.strip(lines.get(j)).equals(end)) {
                        heredoc.add(lines.get(j));
                        j++;
                    }
                    i = j;
                }
                Matcher a = ASSIGN.matcher(line);
                if (a.lookingAt()) {
                    String value = TextUtil.strip(TextUtil.strip(a.group(2)), "'\"");
                    if (!value.contains(" ")) {
                        variables.put(a.group(1), value);
                    } else if (windows) {
                        variables.put(a.group(1), TextUtil.strip(a.group(2)));
                    }
                }
                String cmdLine = hasHeredoc ? TextUtil.beforeFirst(line, "<<") : line;
                for (String seg : splitSegments(cmdLine)) {
                    command(no, seg, variables, heredoc);
                }
                i++;
            }
        }

        private String expand(String value, Map<String, String> variables) {
            for (int k = 0; k < 3; k++) {
                String nw = TextUtil.sub(VAR, value, m -> variables.getOrDefault(m.group(1), m.group(0)));
                if (nw.equals(value)) {
                    break;
                }
                value = nw;
            }
            return value;
        }

        private void command(int no, String seg, Map<String, String> variables, List<String> heredoc) {
            List<String> toks = tokens(seg);
            int start = 0;
            while (start < toks.size() && (PREFIXES.contains(toks.get(start)) || ENV_PREFIX.matcher(toks.get(start)).lookingAt())) {
                start++;
            }
            toks = toks.subList(start, toks.size());
            if (toks.isEmpty()) {
                return;
            }
            String cmdRaw = toks.get(0);
            String cmd = lower(TextUtil.basename(cmdRaw));
            List<String> args = toks.subList(1, toks.size());
            if (INTERPRETERS.contains(cmd) && !args.isEmpty()) {
                String target = null;
                for (String x : args) {
                    if (!x.startsWith("-")) {
                        target = x;
                        break;
                    }
                }
                if (target != null) {
                    // Sourcing an environment file (". ./app.env") is not running a program.
                    boolean explicit = !(cmd.equals(".") || cmd.equals("source")) || endsWithAny(lower(target), SCRIPT_EXT);
                    invoke(no, expand(target, variables), explicit);
                }
                return;
            }
            if (cmd.equals("java") || cmd.equals("javaw") || cmd.equals("java.exe")) {
                List<String> expanded = new ArrayList<>();
                for (String x : args) {
                    expanded.add(expand(x, variables));
                }
                java(no, expanded);
                return;
            }
            if (DB_CLIENTS.containsKey(cmd)) {
                dbClient(no, cmd, args, heredoc, variables);
                return;
            }
            if (Set.of("ftp", "sftp", "lftp", "ncftp", "ncftpput", "ncftpget").contains(cmd)) {
                ftp(no, cmd, args, heredoc, variables);
                return;
            }
            if (cmd.equals("scp") || cmd.equals("rsync") || cmd.equals("pscp")) {
                scp(no, cmd, args, variables);
                return;
            }
            if (cmd.equals("curl") || cmd.equals("wget")) {
                for (String x : args) {
                    String[] c = Integrations.classifyUrl(expand(x, variables));
                    if (c != null) {
                        facts.add("integrations", Integrations.integration(c[0], c[1], "outbound", path, no, null, cmd, null));
                    }
                }
                return;
            }
            if (Set.of("mail", "mailx", "sendmail", "mutt", "uuencode", "blat").contains(cmd)) {
                facts.add("integrations", Integrations.integration("SMTP", "mail (" + cmd + ")", "outbound", path, no));
                return;
            }
            if (cmd.equals("cftutil")) {
                Doc integ = CftAnalyzer.cftutilIntegration(expand(String.join(" ", args), variables), path, no);
                if (integ != null) {
                    facts.add("integrations", integ);
                }
                return;
            }
            if (SHELL_WORDS.contains(cmd) || cmd.startsWith("$(") || cmd.startsWith("`")) {
                return;
            }
            if (cmdRaw.contains("/") || cmdRaw.contains("\\") || endsWithAny(cmd, SCRIPT_EXT) || cmdRaw.startsWith("$")) {
                invoke(no, expand(cmdRaw, variables), false);
            }
        }

        private void invoke(int no, String target, boolean explicit) {
            String name = TextUtil.basename(target);
            if (name.isEmpty() || name.startsWith("$") || name.contains("*")) {
                return;
            }
            boolean report = explicit || endsWithAny(lower(name), SCRIPT_EXT);
            facts.ref("invokes", fileSource, path, name, List.of("file-name", "c-executable"), path, no).reportUnresolved(report);
        }

        private void java(int no, List<String> args) {
            int i = 0;
            List<String> positional = new ArrayList<>();
            while (i < args.size()) {
                String a = args.get(i);
                if (a.equals("-cp") || a.equals("-classpath") || a.equals("--class-path")) {
                    i += 2;
                    continue;
                }
                if (a.equals("-jar") && i + 1 < args.size()) {
                    facts.ref("invokes", fileSource, path, TextUtil.basename(args.get(i + 1)), List.of("file-name"), path, no);
                    return;
                }
                if (a.startsWith("-")) {
                    i++;
                    continue;
                }
                positional.add(a);
                i++;
            }
            if (positional.isEmpty()) {
                return;
            }
            String main = positional.get(0);
            facts.ref("invokes", fileSource, path, main, List.of("java-main", "java-class"), path, no)
                    .reportUnresolved(!main.startsWith("org.springframework"));
            if (main.endsWith("CommandLineJobRunner") && positional.size() >= 3) {
                facts.ref("triggers", fileSource, path, positional.get(2), List.of("spring-batch-job"), path, no);
            }
        }

        private void dbClient(int no, String cmd, List<String> args, List<String> heredoc, Map<String, String> variables) {
            facts.add("tech_stack", BuildAnalyzer.tech(DB_CLIENTS.get(cmd), null, "database", path, no));
            for (String a : args) {
                if (a.startsWith("@")) {
                    facts.ref("invokes", fileSource, path, TextUtil.basename(expand(a.substring(1), variables)), List.of("file-name"), path, no);
                }
                Matcher m = CONTROL.matcher(a);
                if (m.lookingAt()) {
                    facts.ref("invokes", fileSource, path, TextUtil.basename(expand(m.group(1), variables)), List.of("file-name"), path, no);
                }
            }
            for (String a : args) {
                if (DML.contains(SqlTools.statementType(a))) {
                    facts.add("sql_statements", SqlTools.sqlFact(path, no, a, "script", cmd.equals("db2") ? "db2" : dialect));
                }
            }
            if (!heredoc.isEmpty()) {
                String body = String.join("\n", heredoc);
                for (SqlTools.Statement s : SqlTools.splitStatements(body)) {
                    String st = SqlTools.statementType(s.text());
                    if (DML.contains(st)) {
                        facts.add("sql_statements", SqlTools.sqlFact(path, no + s.line(), s.text(), "script", dialect));
                    } else if (st.equals("EXEC") || st.equals("EXECUTE")) {
                        Matcher proc = EXEC_PROC.matcher(s.text());
                        if (proc.lookingAt()) {
                            facts.add("sql_statements", SqlTools.sqlFact(path, no + s.line(), "CALL " + proc.group(1), "script", dialect));
                        }
                    }
                }
                for (String hl : heredoc) {
                    String t = TextUtil.strip(hl);
                    if (t.startsWith("@")) {
                        List<String> words = TextUtil.words(t.substring(1));
                        if (words.isEmpty()) {
                            throw new PyError("IndexError", "list index out of range");
                        }
                        facts.ref("invokes", fileSource, path, TextUtil.basename(words.get(0)), List.of("file-name"), path, no);
                    }
                }
            }
        }

        private void ftp(int no, String cmd, List<String> args, List<String> heredoc, Map<String, String> variables) {
            String kind = cmd.equals("sftp") ? "SFTP" : "FTP";
            String host = "";
            for (String a : args) {
                if (!a.startsWith("-")) {
                    host = expand(a, variables);
                    break;
                }
            }
            for (String hl : heredoc) {
                Matcher m = FTP_OPEN.matcher(hl);
                if (m.lookingAt()) {
                    host = expand(m.group(1), variables);
                }
            }
            host = TextUtil.beforeFirst(TextUtil.afterLast(host, "@"), ":");
            if (host.isEmpty()) {
                host = "?";
            }
            String body = lower(String.join(" ", heredoc));
            boolean put = PUT.matcher(body).find();
            boolean get = GET.matcher(body).find();
            String direction = put && get ? "both" : get ? "inbound" : "outbound";
            facts.add("integrations", Integrations.integration(kind, host, direction, path, no, null, cmd, null));
        }

        private void scp(int no, String cmd, List<String> args, Map<String, String> variables) {
            List<String> files = new ArrayList<>();
            for (String a : args) {
                if (!a.startsWith("-")) {
                    files.add(expand(a, variables));
                }
            }
            for (int idx = 0; idx < files.size(); idx++) {
                String f = files.get(idx);
                Matcher m = REMOTE.matcher(f);
                if (m.lookingAt() && !DRIVE.matcher(f).lookingAt()) {
                    String direction = idx == files.size() - 1 ? "outbound" : "inbound";
                    facts.add("integrations", Integrations.integration(cmd.equals("rsync") ? "RSYNC" : "SCP", m.group(1), direction, path, no,
                            null, cmd, null));
                }
            }
        }

        void crontab(String text) {
            facts.add("tech_stack", BuildAnalyzer.tech("cron", null, "scheduler", path, 1));
            int no = 0;
            for (String line : TextUtil.splitLines(text)) {
                no++;
                String s = TextUtil.strip(line);
                if (s.isEmpty() || s.startsWith("#") || ENV_PREFIX.matcher(s).lookingAt()) {
                    continue;
                }
                Matcher m = CRON.matcher(s);
                if (!m.lookingAt()) {
                    facts.coverage.gap(no, no, "not a cron entry");
                    continue;
                }
                String schedule = m.group(1);
                String command = m.group(2);
                List<String> segments = splitSegments(command);
                List<String> toks = segments.isEmpty() ? List.of() : tokens(segments.get(0));
                int k = 0;
                while (k < toks.size() && (PREFIXES.contains(toks.get(k)) || INTERPRETERS.contains(toks.get(k)))) {
                    k++;
                }
                String prog = k < toks.size() ? TextUtil.basename(toks.get(k)) : command;
                String jid = Fact.makeId("batch_job", "cron", path, no);
                facts.add("batch_jobs", Doc.of(Schemas.BATCH_JOB,
                        "id", jid,
                        "name", prog,
                        "scheduler", "cron",
                        "schedule", schedule,
                        "command", command,
                        "evidence", new ArrayList<>(List.of(new Evidence(path, no, null)))));
                facts.symbol("cron-job", prog, path, no).owner(jid);
                for (String seg : segments) {
                    List<String> ct = tokens(seg);
                    int c = 0;
                    while (c < ct.size() && (PREFIXES.contains(ct.get(c)) || INTERPRETERS.contains(ct.get(c))
                            || ENV_PREFIX.matcher(ct.get(c)).lookingAt())) {
                        c++;
                    }
                    if (c < ct.size() && !SHELL_WORDS.contains(ct.get(c))) {
                        facts.ref("invokes", "batch_job:" + jid, prog, TextUtil.basename(ct.get(c)), List.of("file-name", "c-executable"), path, no);
                    }
                }
            }
        }
    }
}
