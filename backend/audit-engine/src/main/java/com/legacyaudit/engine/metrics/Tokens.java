package com.legacyaudit.engine.metrics;

import com.legacyaudit.engine.analyzers.TextUtil;
import com.legacyaudit.engine.inventory.Loc;
import com.legacyaudit.engine.inventory.Rx;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comment stripping and token windows for duplication detection.
 *
 * <p>A window is {@code window} consecutive tokens starting at the first token of a line.
 * Copy-pasted blocks start at a line in both copies, so hashing one window per line finds them
 * while storing at most one hash per line in the file cache.
 */
public final class Tokens {

    private static final Pattern TOKEN = Rx.of("[A-Za-z_$#@][\\w$#@-]*|\\d[\\w.]*|'(?:[^'\\n]|'')*'|\"(?:[^\"\\\\\\n]|\\\\.)*\"|\\S");

    private Tokens() {}

    /** Comments replaced by spaces; line breaks kept so line numbers stay valid. */
    public static String stripComments(String text, String language) {
        Loc.Syntax syn = Loc.syntax(language);
        return syn == null ? text : strip(text, syn);
    }

    private static String strip(String text, Loc.Syntax syn) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        char quote = 0;
        while (i < n) {
            char ch = text.charAt(i);
            if (quote != 0) {
                out.append(ch);
                if (ch == '\\' && i + 1 < n && text.charAt(i + 1) != '\n') {
                    out.append(text.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (ch == quote || ch == '\n') {
                    quote = 0;
                }
                i++;
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
                out.append(ch);
                i++;
                continue;
            }
            boolean matched = false;
            for (String[] b : syn.block()) {
                if (text.startsWith(b[0], i)) {
                    int j = text.indexOf(b[1], i + b[0].length());
                    j = j < 0 ? n : j + b[1].length();
                    blank(out, text, i, j);
                    i = j;
                    matched = true;
                    break;
                }
            }
            if (matched) {
                continue;
            }
            for (String marker : syn.line()) {
                if (text.startsWith(marker, i)) {
                    int j = text.indexOf('\n', i);
                    j = j < 0 ? n : j;
                    blank(out, text, i, j);
                    i = j;
                    matched = true;
                    break;
                }
            }
            if (matched) {
                continue;
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    /** One space per character (code point, as in Python) of {@code text[from:to]}, line breaks kept. */
    private static void blank(StringBuilder out, String text, int from, int to) {
        for (int k = from; k < to; ) {
            int cp = text.codePointAt(k);
            out.append(cp == '\n' ? '\n' : ' ');
            k += Character.charCount(cp);
        }
    }

    static long hash(List<String> tokens) {
        byte[] d = Blake2b.digest(String.join("\u001f", tokens).getBytes(StandardCharsets.UTF_8), 8);
        return ByteBuffer.wrap(d).getLong() >>> 1; // 63 bits: fits SQLite and JSON readers
    }

    /**
     * {@code [hash, first line, last line]} for each window of {@code window} tokens that starts
     * at the first token of a line.
     */
    public static List<List<Long>> windows(String code, int window) {
        List<String> tokens = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        int no = 0;
        for (String line : TextUtil.splitLines(code)) {
            no++;
            Matcher m = TOKEN.matcher(line);
            boolean any = false;
            int before = tokens.size();
            while (m.find()) {
                if (!any) {
                    starts.add(before);
                    any = true;
                }
                tokens.add(m.group());
                lines.add(no);
            }
        }
        List<List<Long>> out = new ArrayList<>();
        for (int s : starts) {
            int e = s + window;
            if (e > tokens.size()) {
                break;
            }
            out.add(List.of(hash(tokens.subList(s, e)), (long) lines.get(s), (long) lines.get(e - 1)));
        }
        return out;
    }
}
