package com.legacyaudit.reports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Python's {@code collections.Counter}: counts in first-seen order, {@code most_common} stable. */
public final class Counter extends LinkedHashMap<String, Long> {

    public Counter add(String key) {
        return add(key, 1);
    }

    public Counter add(String key, long n) {
        merge(key, n, Long::sum);
        return this;
    }

    public long count(String key) {
        return getOrDefault(key, 0L);
    }

    /** By count, highest first; equal counts keep their first-seen order. */
    public List<Map.Entry<String, Long>> mostCommon() {
        List<Map.Entry<String, Long>> l = new ArrayList<>(entrySet());
        l.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return l;
    }

    public List<Map.Entry<String, Long>> mostCommon(int n) {
        List<Map.Entry<String, Long>> l = mostCommon();
        return l.subList(0, Math.min(n, l.size()));
    }
}
