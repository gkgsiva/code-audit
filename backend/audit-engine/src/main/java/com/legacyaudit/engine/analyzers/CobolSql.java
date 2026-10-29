package com.legacyaudit.engine.analyzers;

import static com.legacyaudit.engine.analyzers.DynSql.concat;
import static com.legacyaudit.engine.analyzers.DynSql.empty;
import static com.legacyaudit.engine.analyzers.DynSql.hole;
import static com.legacyaudit.engine.analyzers.DynSql.lit;

import com.legacyaudit.engine.analyzers.DynSql.Built;
import com.legacyaudit.engine.inventory.Rx;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL text that COBOL builds in working storage for {@code PREPARE ... FROM :var} and
 * {@code EXECUTE IMMEDIATE :var}.
 *
 * <p>Values come from {@code VALUE} clauses, then the procedure division in source order:
 * {@code MOVE x TO a b} (sets; a reference-modified target {@code A(P:N)} is an append),
 * {@code STRING ... INTO a} (sets, or appends when {@code a} is its first operand or
 * {@code WITH POINTER} is used) and {@code INITIALIZE}. Statements inside {@code IF}/
 * {@code EVALUATE}/inline {@code PERFORM} are conditional (a period ends them all). A DB2
 * varying-length host variable (a group with a level-49 length and a level-49 text item) is
 * looked up through its text item. Operands that are not known become {@code ?} holes.
 *
 * <p>One instance per program: not thread-safe.
 */
public final class CobolSql {

    private static final Pattern TOKEN = Rx.of(
            "'(?:[^']|'')*'?|\"(?:[^\"]|\"\")*\"?|[A-Z0-9][\\w-]*(?:\\s*\\([^)]*\\))?|\\.(?=\\s|$)|\\S", Rx.I);
    private static final Pattern ITEM = Rx.of("^\\s*(\\d\\d)\\s+([\\w-]+)\\b(.*)$", Rx.I);
    private static final Pattern VALUE = Rx.of("\\bVALUES?\\s+(?:IS\\s+|ARE\\s+)?('(?:[^']|'')*'|\"(?:[^\"]|\"\")*\")", Rx.I);
    private static final Pattern PIC_X = Rx.of("\\bPIC(?:TURE)?\\s+(?:IS\\s+)?X", Rx.I);
    private static final Pattern PIC = Rx.of("\\bPIC", Rx.I);
    private static final Pattern QUOTED = Rx.of("'(?:[^']|'')*'|\"[^\"]*\"");
    private static final Pattern PROCEDURE = Rx.of("^\\s*PROCEDURE\\s+DIVISION\\b", Rx.I);
    private static final Set<String> VERBS = Set.of("ACCEPT", "ADD", "CALL", "CLOSE", "COMPUTE", "CONTINUE", "DELETE", "DISPLAY", "DIVIDE",
            "ELSE", "END-EVALUATE", "END-IF", "END-PERFORM", "END-STRING", "EVALUATE", "EXEC", "EXIT", "GO", "GOBACK", "IF", "INITIALIZE",
            "INSPECT", "MOVE", "MULTIPLY", "NOT", "ON", "OPEN", "PERFORM", "READ", "RETURN", "REWRITE", "SET", "START", "STOP", "STRING",
            "SUBTRACT", "UNSTRING", "WHEN", "WRITE", "OVERFLOW");
    private static final Map<String, String> FIGURATIVE = Map.ofEntries(Map.entry("SPACE", " "), Map.entry("SPACES", " "),
            Map.entry("QUOTE", "'"), Map.entry("QUOTES", "'"), Map.entry("LOW-VALUE", ""), Map.entry("LOW-VALUES", ""),
            Map.entry("HIGH-VALUE", ""), Map.entry("HIGH-VALUES", ""), Map.entry("ZERO", "0"), Map.entry("ZEROS", "0"),
            Map.entry("ZEROES", "0"));
    private static final Set<String> OPENERS = Set.of("IF", "EVALUATE");
    private static final Set<String> CLOSERS = Set.of("END-IF", "END-EVALUATE", "END-PERFORM");
    private static final Set<String> INLINE_PERFORM = Set.of("UNTIL", "VARYING", "WITH", "TEST");

    private record Token(int line, String text) {}

    private final DynSql.Tracker tracker = new DynSql.Tracker();
    private final Map<String, String> textItem = new HashMap<>(); // varying-length group -> its text item

    private static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    private static String literal(String token) {
        if (token.startsWith("'") || token.startsWith("\"")) {
            String q = token.substring(0, 1);
            String body = token.length() > 1 && token.endsWith(q) ? token.substring(1, token.length() - 1) : token.substring(1);
            return body.replace(q + q, q);
        }
        return null;
    }

    private static String name(String token) {
        return upper(TextUtil.strip(TextUtil.beforeFirst(token, "(")));
    }

    /** What {@code name} (a {@code :host-var}) held just before {@code line}. */
    public Built valueAt(String name, int line) {
        String key = upper(TextUtil.strip(TextUtil.beforeFirst(TextUtil.lstrip(name, ":"), ":")));
        Built got = tracker.at(key, line);
        if (got == null && textItem.containsKey(key)) {
            got = tracker.at(textItem.get(key), line);
        }
        return got;
    }

    /** {@code lines}: the code area, one entry per source line, {@code EXEC} blocks blanked. */
    public void run(List<String> lines) {
        int proc = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            if (PROCEDURE.matcher(lines.get(i)).lookingAt()) {
                proc = i;
                break;
            }
        }
        data(lines.subList(0, proc));
        List<Token> tokens = new ArrayList<>();
        for (int i = proc; i < lines.size(); i++) {
            Matcher m = TOKEN.matcher(lines.get(i));
            while (m.find()) {
                tokens.add(new Token(i + 1, m.group()));
            }
        }
        procedure(tokens);
    }

    // ---- data division ----------------------------------------------------------------------

    private void data(List<String> lines) {
        String group = null; // name of the last group item
        int i = 0;
        while (i < lines.size()) {
            Matcher m = ITEM.matcher(lines.get(i));
            if (!m.lookingAt()) {
                i++;
                continue;
            }
            int start = i;
            String entry = lines.get(i);
            while (!QUOTED.matcher(entry).replaceAll("").contains(".") && i + 1 < lines.size()) {
                i++;
                String nxt = lines.get(i);
                entry = TextUtil.rstrip(entry) + (TextUtil.count(entry, '\'') % 2 != 0 ? pySlice1(nxt) : " " + TextUtil.strip(nxt));
            }
            i++;
            int level = Integer.parseInt(m.group(1));
            String name = upper(m.group(2));
            if (level == 49 && group != null && PIC_X.matcher(entry).find()) {
                textItem.put(group, name);
            } else if (level != 49) {
                group = !PIC.matcher(entry).find() ? name : null;
            }
            Matcher v = VALUE.matcher(entry);
            if (v.find()) {
                String text = literal(v.group(1));
                tracker.assign(name, lit(text == null ? "" : text, start + 1), start + 1, false, false);
            }
        }
    }

    /** {@code s[1:]} by code point. */
    private static String pySlice1(String s) {
        return s.isEmpty() ? "" : s.substring(s.offsetByCodePoints(0, 1));
    }

    // ---- procedure division -----------------------------------------------------------------

    private void procedure(List<Token> tokens) {
        int depth = 0;
        int i = 0;
        while (i < tokens.size()) {
            int line = tokens.get(i).line();
            String tok = tokens.get(i).text();
            String word = upper(tok);
            if (tok.equals(".")) {
                depth = 0;
            } else if (OPENERS.contains(word)) {
                depth++;
            } else if (CLOSERS.contains(word)) {
                depth = Math.max(depth - 1, 0);
            } else if (word.equals("PERFORM") && i + 1 < tokens.size() && INLINE_PERFORM.contains(upper(tokens.get(i + 1).text()))) {
                depth++; // inline PERFORM ... END-PERFORM
            } else if (word.equals("MOVE")) {
                i = move(tokens, i + 1, depth > 0);
                continue;
            } else if (word.equals("STRING")) {
                i = string(tokens, i + 1, depth > 0, line);
                continue;
            } else if (word.equals("INITIALIZE")) {
                int j = i + 1;
                while (operandAt(tokens, j)) {
                    if (depth == 0) {
                        tracker.assign(name(tokens.get(j).text()), empty(line), line, false, false);
                    }
                    j++;
                }
                i = j;
                continue;
            }
            i++;
        }
    }

    /** True while {@code tokens[j]} still belongs to the current statement. */
    private static boolean operandAt(List<Token> tokens, int j) {
        return j < tokens.size() && !tokens.get(j).text().equals(".") && !VERBS.contains(upper(tokens.get(j).text()));
    }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        return s.codePoints().allMatch(Character::isDigit);
    }

    private Built operand(String tok, String before, int line) {
        String text = literal(tok);
        if (text != null) {
            return lit(text, line);
        }
        String word = name(tok);
        if (FIGURATIVE.containsKey(word)) {
            Built b = new Built(FIGURATIVE.get(word), line);
            b.fragments = 0;
            return b;
        }
        if (isDigits(word)) {
            return lit(word, line);
        }
        Built got = tracker.get(word);
        if (got != null) {
            return got;
        }
        return hole(before, line);
    }

    private int move(List<Token> tokens, int i, boolean cond) {
        if (i + 2 > tokens.size()) {
            return i;
        }
        int line = tokens.get(i).line();
        String src = tokens.get(i).text();
        if (upper(src).equals("CORRESPONDING") || upper(src).equals("CORR")) {
            return i + 1;
        }
        int j = i + 1;
        if (j >= tokens.size() || !upper(tokens.get(j).text()).equals("TO")) {
            return j;
        }
        j++;
        while (operandAt(tokens, j)) {
            String target = tokens.get(j).text();
            String name = name(target);
            boolean append = target.contains("("); // reference modification: writes into the middle/end
            Built prev = tracker.get(name);
            Built value = operand(src, prev != null && append ? prev.text : "", line).replaced();
            value.lines.add(line);
            if (value.valueHoles + value.structuralHoles == 0 || prev != null) {
                tracker.assign(name, value, line, cond, append && prev != null);
            }
            j++;
        }
        return j;
    }

    private int string(List<Token> tokens, int i, boolean cond, int line) {
        List<String> parts = new ArrayList<>();
        int j = i;
        while (j < tokens.size() && !upper(tokens.get(j).text()).equals("INTO") && !tokens.get(j).text().equals(".")) {
            String word = upper(tokens.get(j).text());
            if (word.equals("DELIMITED")) {
                j += j + 1 < tokens.size() && upper(tokens.get(j + 1).text()).equals("BY") ? 2 : 1;
                j++; // the delimiter itself
                continue;
            }
            parts.add(tokens.get(j).text());
            j++;
        }
        if (j >= tokens.size() || !upper(tokens.get(j).text()).equals("INTO") || j + 1 >= tokens.size()) {
            return j;
        }
        String target = name(tokens.get(j + 1).text());
        int k = j + 2;
        boolean pointer = false;
        while (operandAt(tokens, k)) {
            pointer = pointer || upper(tokens.get(k).text()).equals("POINTER");
            k++;
        }
        if (k < tokens.size() && Set.of("ON", "NOT", "OVERFLOW").contains(upper(tokens.get(k).text()))) {
            while (k < tokens.size() && !upper(tokens.get(k).text()).equals("END-STRING") && !tokens.get(k).text().equals(".")) {
                k++;
            }
        }
        Built prev = tracker.get(target);
        boolean selfFirst = !parts.isEmpty() && name(parts.getFirst()).equals(target);
        boolean append = pointer || selfFirst;
        if (selfFirst) {
            parts = parts.subList(1, parts.size());
        }
        Built out = empty(line);
        String before = prev != null && append ? prev.text : "";
        for (String p : parts) {
            out = concat(out, operand(p, before + out.text, line));
        }
        out.fragments = Math.max(out.fragments, 1);
        out.how.add("string-statement");
        tracker.assign(target, out, line, cond, append && prev != null);
        return k;
    }
}
