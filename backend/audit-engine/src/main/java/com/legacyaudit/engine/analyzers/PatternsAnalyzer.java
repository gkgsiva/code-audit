package com.legacyaudit.engine.analyzers;

import com.legacyaudit.engine.rules.Packs;
import com.legacyaudit.engine.rules.PyRegex;
import com.legacyaudit.model.facts.Category;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Origin;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * The {@code patterns} analyzer: runs the text rules of the enabled rule packs over one file.
 *
 * <p>Hits are cached with the file; which hits become findings (and with what severity) is
 * decided every run by the rule engine. Named groups {@code secret...} in a pattern are masked
 * before the hit is stored, so credentials never reach the cache, the outputs or the logs.
 */
public final class PatternsAnalyzer implements Analyzer {

    static final int MAX_TEXT = 120;

    @Override
    public String name() {
        return "patterns";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public boolean supports(FileRecord file) {
        return file.origin == Origin.SOURCE && file.category != Category.BINARY && file.category != Category.GENERATED
                && file.category != Category.DOC;
    }

    static String mask(Matcher m, PyRegex rx) {
        String text = m.group();
        List<int[]> spans = new ArrayList<>();
        for (var g : rx.groups.entrySet()) {
            if (g.getValue().startsWith("secret") && m.group(g.getKey()) != null) {
                spans.add(new int[] {m.start(g.getKey()) - m.start(), m.end(g.getKey()) - m.start()});
            }
        }
        spans.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        for (int k = spans.size() - 1; k >= 0; k--) {
            int[] s = spans.get(k);
            text = text.substring(0, s[0]) + "****" + text.substring(s[1]);
        }
        text = String.join(" ", TextUtil.words(text));
        int n = text.codePointCount(0, text.length());
        return n <= MAX_TEXT ? text : text.substring(0, text.offsetByCodePoints(0, MAX_TEXT - 3)) + "...";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        Facts facts = new Facts();
        String text = ctx.text();
        if (text == null || text.isEmpty()) {
            return facts;
        }
        List<Packs.Rule> rules = ctx.textRules() != null
                ? (List<Packs.Rule>) (List<?>) ctx.textRules()
                : Packs.textRules(List.of("risk", "postgresql", "dependency"), null);
        int[] starts = null;
        for (Packs.Rule rule : rules) {
            Packs.TextPattern pat = rule.pattern();
            if (pat == null || (!pat.languages.isEmpty() && !pat.languages.contains(file.language))) {
                continue;
            }
            if (pat.unlessRegex() != null && pat.unlessRegex().pattern.matcher(text).find()) {
                continue;
            }
            PyRegex ignore = pat.ignoreRegex();
            PyRegex rx = pat.compiled();
            int count = 0;
            Matcher m = rx.pattern.matcher(text);
            while (m.find()) {
                if (ignore != null && ignore.pattern.matcher(m.group()).find()) {
                    continue;
                }
                if (starts == null) {
                    starts = lineStarts(text);
                }
                List<Object> hit = new ArrayList<>();
                hit.add(rule.id());
                hit.add((long) line(starts, m.start()));
                hit.add(mask(m, rx));
                facts.hits.add(hit);
                count++;
                if (count >= pat.maxPerFile) {
                    break;
                }
            }
        }
        return facts;
    }

    private static int[] lineStarts(String text) {
        List<Integer> s = new ArrayList<>();
        s.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                s.add(i + 1);
            }
        }
        return s.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int line(int[] starts, int offset) {
        int lo = 0;
        int hi = starts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (starts[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo + 1;
    }
}
