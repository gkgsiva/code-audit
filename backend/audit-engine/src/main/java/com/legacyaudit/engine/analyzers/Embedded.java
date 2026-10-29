package com.legacyaudit.engine.analyzers;

import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedded SQL ({@code EXEC SQL ... ;} in Pro*C, {@code EXEC SQL ... END-EXEC} in COBOL and
 * Pro*COBOL).
 *
 * <p>Each block becomes a {@code SqlStatement} with source {@code embedded}; cursor declarations
 * count as the SELECT they declare; {@code OPEN/FETCH/CLOSE}, {@code WHENEVER}, {@code CONNECT}
 * and transaction control are not statements. {@code PREPARE}/{@code EXECUTE IMMEDIATE} from a
 * host variable are rebuilt from the code that fills the variable when the caller can (see
 * {@code resolve}), else recorded as {@code DYNAMIC}. Callers get the block spans back so they
 * can blank them before parsing the host language.
 */
public final class Embedded {

    private Embedded() {}

    private static final Pattern C_BLOCK = Rx.of(
            "\\bEXEC\\s+SQL\\b(\\s+EXECUTE\\s+(?:BEGIN|DECLARE)\\b[\\s\\S]*?END-EXEC|(?:'[^']*'|\"[^\"]*\"|[^;'\"])*+)\\s*;", Rx.I);
    private static final Pattern COBOL_BLOCK = Rx.of("\\bEXEC\\s+SQL\\b([\\s\\S]*?)\\bEND-EXEC\\b", Rx.I);
    private static final Pattern NOT_STATEMENTS = Rx.of(
            "^\\s*(OPEN|FETCH|CLOSE|WHENEVER|CONNECT|COMMIT|ROLLBACK|SAVEPOINT|BEGIN\\s+DECLARE|"
                    + "END\\s+DECLARE|DECLARE\\s+[\\w-]+\\s+(STATEMENT|TABLE)|VAR\\b|TYPE\\b|ENABLE|CONTEXT|ALLOCATE|"
                    + "FREE|DESCRIBE|SET\\b|GET\\s+DIAGNOSTICS|EXECUTE\\s+[\\w-]+\\s*(USING|$))",
            Rx.I);
    private static final Pattern INCLUDE = Rx.of("^\\s*INCLUDE\\s+['\"]?([\\w.$#-]+)['\"]?", Rx.I);
    private static final Pattern DYNAMIC = Rx.of("^\\s*(PREPARE\\s+[\\w-]+\\s+FROM|EXECUTE\\s+IMMEDIATE)\\s+(.*)$", Rx.I | Pattern.DOTALL);
    private static final Pattern PLSQL_BLOCK = Rx.of(
            "^\\s*EXECUTE\\s+(DECLARE|BEGIN)\\b([\\s\\S]*?)(END\\s*;?\\s*END-EXEC|END\\s*;?)?\\s*$", Rx.I);
    private static final Pattern PLSQL_CALL = Rx.of("(?:\\bBEGIN|;)\\s*([A-Za-z][\\w$#]*(?:\\.[A-Za-z][\\w$#]*)?)\\s*\\(", Rx.I);
    private static final Pattern CURSOR = Rx.of("^\\s*DECLARE\\s+[\\w-]+\\s+CURSOR\\s+(?:WITH\\s+HOLD\\s+)?FOR\\s+([\\s\\S]+)$", Rx.I);
    private static final Pattern NAME = Rx.of("^[\\w-]+$");
    private static final Set<String> PLSQL_WORDS = Set.of("IF", "WHILE", "FOR", "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "RETURN",
            "RAISE", "OPEN", "FETCH", "CLOSE", "EXIT", "NULL", "DBMS_OUTPUT.PUT_LINE");
    private static final Set<String> DML_TYPES = Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "CALL", "WITH");
    private static final Set<String> SYSTEM_INCLUDES = Set.of("SQLCA", "SQLDA", "ORACA", "SQLCA.H", "SQLDA.H", "ORACA.H");

    /** An {@code INCLUDE} member and its line. */
    public record Include(int line, String member) {}

    /** A character span {@code [start, end)} or a line range. */
    public record Span(int start, int end) {}

    /** What {@link #extract} found. */
    public static final class Result {
        public final List<Doc> statements = new ArrayList<>();
        public final List<Include> includes = new ArrayList<>();
        public final List<Span> spans = new ArrayList<>();
        public final List<Span> unparsed = new ArrayList<>();
    }

    /**
     * EXEC SQL blocks of C ({@code ;}) or COBOL ({@code END-EXEC}) text. COBOL callers pass the
     * code area only, so sequence and identification columns are gone. {@code resolve(":host-var",
     * line)} rebuilds the text a dynamic statement prepares (may be null).
     */
    public static Result extract(String text, String file, String dialect, boolean cobol, BiFunction<String, Integer, DynSql.Built> resolve) {
        Result res = new Result();
        TextUtil.LineIndex index = new TextUtil.LineIndex(text);
        Matcher m = (cobol ? COBOL_BLOCK : C_BLOCK).matcher(text);
        while (m.find()) {
            res.spans.add(new Span(m.start(), m.end()));
            int line = index.line(m.start());
            String body = TextUtil.strip(m.group(1));
            Matcher inc = INCLUDE.matcher(body);
            if (inc.lookingAt()) {
                String member = inc.group(1);
                if (!SYSTEM_INCLUDES.contains(member.toUpperCase(Locale.ROOT))) {
                    res.includes.add(new Include(line, member));
                }
                continue;
            }
            if (body.isEmpty() || NOT_STATEMENTS.matcher(body).lookingAt()) {
                continue;
            }
            if (PLSQL_BLOCK.matcher(body).lookingAt()) {
                Matcher call = PLSQL_CALL.matcher(body);
                while (call.find()) {
                    if (!PLSQL_WORDS.contains(call.group(1).toUpperCase(Locale.ROOT))) {
                        res.statements.add(SqlTools.sqlFact(file, line + TextUtil.count(body.substring(0, call.start(1)), '\n'),
                                "CALL " + call.group(1), "embedded", dialect));
                    }
                }
                for (SqlAnalyzer.Embedded dml : SqlAnalyzer.embeddedDml(body)) {
                    res.statements.add(SqlTools.sqlFact(file, line + TextUtil.count(body.substring(0, dml.offset()), '\n'), dml.text(),
                            "embedded", dialect));
                }
                continue;
            }
            Matcher dyn = DYNAMIC.matcher(body);
            if (dyn.lookingAt()) {
                String arg = TextUtil.strip(dyn.group(2));
                DynSql.Built built = resolve != null && arg.startsWith(":") ? resolve.apply(TextUtil.words(arg).getFirst(), line) : null;
                if (built != null && SqlTools.looksLikeSql(built.text)) {
                    Doc fact = SqlTools.sqlFact(file, line, built.text, "embedded", dialect, built.dynamic(), false);
                    fact.set("built_with", new ArrayList<>(List.of("prepare-from-variable")));
                    res.statements.add(DynSql.fill(fact, built));
                } else if (arg.startsWith(":")) {
                    Doc fact = SqlTools.sqlFact(file, line, body, "embedded", dialect, true, false);
                    fact.set("type", "DYNAMIC");
                    fact.set("structural_holes", 1L);
                    fact.set("built_with", new ArrayList<>(List.of("prepare-from-variable")));
                    res.statements.add(fact);
                } else if (arg.startsWith("'") || arg.startsWith("\"")) {
                    res.statements.add(SqlTools.sqlFact(file, line, TextUtil.strip(arg, "'\""), "embedded", dialect));
                }
                continue;
            }
            Matcher cursor = CURSOR.matcher(body);
            if (cursor.lookingAt()) {
                String target = TextUtil.strip(cursor.group(1));
                if (NAME.matcher(target).lookingAt()) { // cursor over a prepared statement
                    continue;
                }
            }
            int endLine = line + TextUtil.count(body, '\n');
            if (!DML_TYPES.contains(SqlTools.statementType(body))) {
                res.unparsed.add(new Span(line, endLine));
                continue;
            }
            Doc fact = SqlTools.sqlFact(file, line, body, "embedded", dialect);
            if (!Boolean.TRUE.equals(fact.get("parsed"))) {
                res.unparsed.add(new Span(line, endLine));
            }
            res.statements.add(fact);
        }
        return res;
    }

    /** Character spans of the {@code EXEC SQL} blocks (the same spans {@link #extract} returns). */
    public static List<Span> blockSpans(String text, boolean cobol) {
        List<Span> out = new ArrayList<>();
        Matcher m = (cobol ? COBOL_BLOCK : C_BLOCK).matcher(text);
        while (m.find()) {
            out.add(new Span(m.start(), m.end()));
        }
        return out;
    }

    /** Replaces each span with spaces, keeping newlines so line numbers stay right. */
    public static String blankSpans(String text, List<Span> spans) {
        if (spans.isEmpty()) {
            return text;
        }
        char[] chars = text.toCharArray();
        for (Span s : spans) {
            for (int i = s.start(); i < s.end(); i++) {
                if (chars[i] != '\n') {
                    chars[i] = ' ';
                }
            }
        }
        return new String(chars);
    }
}
