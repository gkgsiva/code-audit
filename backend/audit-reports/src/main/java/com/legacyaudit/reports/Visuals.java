package com.legacyaudit.reports;

import com.legacyaudit.engine.rules.Py;
import com.legacyaudit.model.json.PyNum;
import com.legacyaudit.reports.jinja.Markup;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Graphics shared by the technical report and the functional document.
 *
 * <p>Chart specs are drawn by {@code assets/report.js} with the vendored Chart.js; gauges, heat
 * maps, the risk matrix, the batch week calendar and Gantt bars are plain HTML and CSS (no script),
 * so they print and work without JavaScript. Every value shown comes from the facts or the
 * validated enrichment passed in; nothing here counts anything new.
 *
 * <p>Colours are CSS tokens ({@code series-1} ... {@code series-8} for identity, {@code seq-*} for
 * magnitude, {@code good} / {@code warning} / {@code serious} / {@code critical} for status), so
 * light and dark themes both work.
 */
public final class Visuals {

    private Visuals() {}

    /** Small stroke icons (24x24, currentColor), inline so the report stays offline. */
    private static final Map<String, String> ICON_PATHS = new LinkedHashMap<>();

    static {
        ICON_PATHS.put("files", "M6 2h9l5 5v15H6z M14 2v6h6");
        ICON_PATHS.put("code", "M8 7l-5 5 5 5 M16 7l5 5-5 5 M14 4l-4 16");
        ICON_PATHS.put("components", "M3 3h8v8H3z M13 3h8v8h-8z M3 13h8v8H3z M13 13h8v8h-8z");
        ICON_PATHS.put("services", "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8 M12 2v3 M12 19v3 M2 12h3 M19 12h3 "
                + "M5 5l2 2 M17 17l2 2 M5 19l2-2 M17 7l2-2");
        ICON_PATHS.put("screens", "M3 4h18v12H3z M8 20h8 M12 16v4");
        ICON_PATHS.put("sql", "M4 6c0-2 16-2 16 0v12c0 2-16 2-16 0z M4 6c0 2 16 2 16 0 M4 12c0 2 16 2 16 0");
        ICON_PATHS.put("warning", "M12 3l10 18H2z M12 10v5 M12 18v.5");
        ICON_PATHS.put("procedure", "M4 4h16v16H4z M8 9h8 M8 13h8 M8 17h5");
        ICON_PATHS.put("integrations", "M7 7h10 M14 4l3 3-3 3 M17 17H7 M10 14l-3 3 3 3");
        ICON_PATHS.put("batch", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18 M12 7v5l3 3");
        ICON_PATHS.put("libraries", "M4 4h5v16H4z M10 4h5v16h-5z M16 5l4 1-3 14-4-1z");
        ICON_PATHS.put("shield", "M12 2l8 3v6c0 5-3.5 9-8 11-4.5-2-8-6-8-11V5z");
        ICON_PATHS.put("eol", "M6 2h12 M6 22h12 M7 2c0 6 10 6 10 10S7 16 7 22 M17 2c0 6-10 6-10 10s10 4 10 10");
        ICON_PATHS.put("findings", "M5 21V4 M5 4h12l-2 4 2 4H5");
        ICON_PATHS.put("effort", "M3 5h18v16H3z M3 10h18 M8 3v4 M16 3v4");
        ICON_PATHS.put("users", "M9 11a4 4 0 1 0 0-8a4 4 0 1 0 0 8 M2 21c0-4 3-7 7-7s7 3 7 7 M17 4a4 4 0 0 1 0 7 "
                + "M19 14c2 1 3 3 3 7");
        ICON_PATHS.put("rules", "M9 11l3 3 8-8 M20 12v7H4V5h11");
        ICON_PATHS.put("data", "M4 6c0-2 16-2 16 0v12c0 2-16 2-16 0z M4 6c0 2 16 2 16 0");
        ICON_PATHS.put("flow", "M3 5h6v4H3z M15 15h6v4h-6z M9 7h3v10h3");
        ICON_PATHS.put("calendar", "M3 5h18v16H3z M3 10h18 M8 3v4 M16 3v4 M7 14h2 M11 14h2 M15 14h2");
        ICON_PATHS.put("interfaces", "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20 M2 12h20 M12 2c3 3 3 17 0 20 "
                + "M12 2c-3 3-3 17 0 20");
        ICON_PATHS.put("glossary", "M4 4h12a4 4 0 0 1 4 4v12H8a4 4 0 0 1-4-4z M8 9h8 M8 13h6");
        ICON_PATHS.put("capability", "M3 12l9-9 9 9-9 9z");
    }

    public static Markup icon(String name) {
        String d = ICON_PATHS.getOrDefault(name, ICON_PATHS.get("files"));
        return new Markup("<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" "
                + "stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"" + d + "\"/></svg>");
    }

    /**
     * A chart for the {@code chart} macro: {@code kind} is bar, stacked, doughnut, radar or bubble.
     * {@code values} (one series) or {@code datasets} ([{label, values, color}]) also fill the "Show
     * as table" view, so no chart is colour-only.
     */
    public static final class Chart {
        private final String id;
        private final String title;
        private final String kind;
        private final List<?> labels;
        private List<?> values = List.of();
        private Object colors = "series-1";
        private List<Map<String, Object>> datasets;
        private String label = "Count";
        private String category = "";
        private String unit = "";
        private boolean horizontal;
        private String note = "";
        private boolean tall;
        private final Map<String, Object> extra = new LinkedHashMap<>();

        public Chart(String id, String title, String kind, List<?> labels) {
            this.id = id;
            this.title = title;
            this.kind = kind;
            this.labels = labels;
        }

        public Chart values(List<?> v) {
            values = v == null ? List.of() : v;
            return this;
        }

        /** One colour token for all, or a list, one per label. */
        public Chart colors(Object c) {
            colors = c;
            return this;
        }

        public Chart datasets(List<Map<String, Object>> d) {
            datasets = d;
            return this;
        }

        public Chart label(String v) {
            label = v;
            return this;
        }

        public Chart category(String v) {
            category = v;
            return this;
        }

        public Chart unit(String v) {
            unit = v;
            return this;
        }

        public Chart horizontal(boolean v) {
            horizontal = v;
            return this;
        }

        public Chart note(String v) {
            note = v;
            return this;
        }

        public Chart tall(boolean v) {
            tall = v;
            return this;
        }

        /** Another key of the spec ({@code points}, {@code xLabel}, {@code color}, {@code raw}...). */
        public Chart extra(String key, Object value) {
            extra.put(key, value);
            return this;
        }

        public Map<String, Object> build() {
            List<Object> vals = new ArrayList<>(values);
            List<Object> cols = colors instanceof String s ? new ArrayList<>(Collections.nCopies(labels.size(), s)) : new ArrayList<>((List<?>) colors);
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("type", kind);
            spec.put("labels", labels);
            spec.put("values", vals);
            spec.put("colors", cols);
            spec.put("label", label);
            spec.put("unit", unit);
            spec.put("horizontal", horizontal);
            spec.putAll(extra);
            if (datasets != null) {
                spec.put("datasets", datasets);
            }
            boolean hasData = !vals.isEmpty() || datasets != null && datasets.stream().anyMatch(d -> Py.truthy(d.get("values")))
                    || Py.truthy(extra.get("points"));
            return D.of("id", id, "title", title, "labels", labels, "values", vals, "datasets", datasets == null ? List.of() : datasets,
                    "label", label, "category", category.isEmpty() ? title : category, "spec", spec, "note", note,
                    "has_data", hasData, "tall", tall || labels.size() > 8);
        }
    }

    /** A ring showing {@code part} of {@code whole} as a percentage (both from the facts). */
    public static Map<String, Object> gauge(String label, Number part, Number whole, String color, String sub) {
        double w = whole.doubleValue();
        long pct = w != 0 ? (long) Math.rint(100.0 * part.doubleValue() / w) : 0;
        return D.of("label", label, "pct", pct, "part", part, "whole", whole, "color", color, "sub", sub);
    }

    /** 1..6 shade for a positive count relative to the largest one (0 = empty). */
    public static int heatLevel(long n, long top) {
        if (n <= 0 || top <= 0) {
            return 0;
        }
        return (int) Math.max(1, Math.min(6, (long) Math.rint(6.0 * n / top)));
    }

    public static final List<String> RISK_SEVERITIES = List.of("critical", "high", "medium", "low");
    public static final List<String> RISK_LIKELIHOOD = List.of("low", "medium", "high");

    /** Severity (rows) x likelihood (columns) with the risk ids in each cell. */
    public static Map<String, Object> riskMatrix(List<Map<String, Object>> risks) {
        Map<List<Object>, List<Object>> cells = new LinkedHashMap<>();
        for (Map<String, Object> r : risks) {
            cells.computeIfAbsent(List.of(D.get(r, "severity", ""), D.get(r, "likelihood", "")), k -> new ArrayList<>()).add(r.get("id"));
        }
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < RISK_SEVERITIES.size(); i++) {
            List<Object> row = new ArrayList<>();
            for (int j = 0; j < RISK_LIKELIHOOD.size(); j++) {
                int score = (3 - i) + j; // 0 (low/low) .. 5 (critical/high)
                List<Object> ids = cells.getOrDefault(List.of(RISK_SEVERITIES.get(i), RISK_LIKELIHOOD.get(j)), new ArrayList<>());
                int level = score <= 1 ? 0 : score == 2 ? 1 : score == 3 ? 2 : 3;
                row.add(D.of("ids", ids, "level", (long) level));
            }
            rows.add(D.of("severity", RISK_SEVERITIES.get(i), "cells", row));
        }
        return D.of("rows", rows, "columns", RISK_LIKELIHOOD, "total", (long) risks.size());
    }

    /** Plan waves as bars on a month axis (start and duration from the plan). */
    public static Map<String, Object> gantt(List<Map<String, Object>> waves) {
        long end = 0;
        for (Map<String, Object> w : waves) {
            end = Math.max(end, D.i(w, "start_month") + D.i(w, "duration_months"));
        }
        long span = Math.max(end, 1);
        List<Map<String, Object>> sorted = new ArrayList<>(waves);
        sorted.sort((a, b) -> com.legacyaudit.reports.jinja.Values.compare(a.get("order"), b.get("order")));
        List<Object> bars = new ArrayList<>();
        int k = 0;
        for (Map<String, Object> w : sorted) {
            long start = D.i(w, "start_month");
            long dur = D.i(w, "duration_months");
            bars.add(D.of("name", w.get("name"), "goal", D.get(w, "goal", ""), "left", PyNum.round(100.0 * start / span, 2),
                    "width", PyNum.round(100.0 * dur / span, 2), "start", start, "end", start + dur, "color", "series-" + (k % 8 + 1)));
            k++;
        }
        long step = span <= 12 ? 1 : span <= 36 ? 3 : 6;
        List<Object> ticks = new ArrayList<>();
        for (long m = 0; m <= span; m += step) {
            ticks.add(D.of("m", m, "left", PyNum.round(100.0 * m / span, 2)));
        }
        return D.of("bars", bars, "ticks", ticks, "months", end);
    }

    public static final List<String> COMPLEXITY_BANDS = List.of("Simple", "Medium", "Complex", "Very complex");
    public static final Map<String, String> BAND_COLOR = Map.of(
            "Simple", "good", "Medium", "warning", "Complex", "serious", "Very complex", "critical");
}
