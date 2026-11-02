package com.legacyaudit.server.admin;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Usage figures for the admin dashboard from the activity log: per day logins, active users,
 * scans (done, failed, cancelled), reports and AI runs, with totals, the busiest projects and
 * users and the latest failures ({@code metrics.usage} of the Python hosted site, same keys).
 */
public final class Usage {

    private Usage() {}

    /**
     * @param entries activity entries (any order)
     * @param users number of users that are not disabled
     * @param projects number of projects
     * @param archived number of archived projects
     */
    public static Map<String, Object> compute(List<Map<String, Object>> entries, int days, int users, int projects, int archived,
            LocalDate today) {
        LocalDate first = today.minusDays(days - 1);
        List<String> dayList = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            dayList.add(first.plusDays(i).toString());
        }
        String start = dayList.getFirst();
        Map<String, Map<String, Object>> perDay = new LinkedHashMap<>();
        Map<String, Set<String>> dayUsers = new LinkedHashMap<>();
        for (String d : dayList) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", d);
            for (String k : List.of("logins", "scans_done", "scans_failed", "scans_cancelled", "reports", "enrich")) {
                row.put(k, 0);
            }
            perDay.put(d, row);
            dayUsers.put(d, new HashSet<>());
        }
        List<Double> scanSeconds = new ArrayList<>();
        List<Double> waitSeconds = new ArrayList<>();
        Map<String, Integer> byProject = new LinkedHashMap<>();
        Map<String, Integer> byUser = new LinkedHashMap<>();
        Map<String, Double> projectSeconds = new LinkedHashMap<>();
        Map<String, Integer> reportTitles = new LinkedHashMap<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        Set<String> active = new HashSet<>();
        for (Map<String, Object> e : entries) {
            String t = e.get("time") == null ? "" : String.valueOf(e.get("time"));
            String d = t.length() >= 10 ? t.substring(0, 10) : t;
            if (d.compareTo(start) < 0 || !perDay.containsKey(d)) {
                continue;
            }
            Map<String, Object> row = perDay.get(d);
            String user = e.get("user") instanceof String s && !s.isEmpty() ? s : null;
            Object action = e.get("action");
            if (user != null) {
                dayUsers.get(d).add(user);
                active.add(user);
                byUser.merge(user, 1, Integer::sum);
            }
            if ("log in".equals(action)) {
                inc(row, "logins");
            }
            if (!"job finished".equals(action)) {
                continue;
            }
            Object kind = e.get("kind");
            Object state = e.get("state");
            String project = e.get("project") instanceof String s && !s.isEmpty() ? s : "-";
            if (e.get("wait_seconds") instanceof Number w) {
                waitSeconds.add(w.doubleValue());
            }
            if ("scan".equals(kind)) {
                byProject.merge(project, 1, Integer::sum);
                if ("done".equals(state)) {
                    inc(row, "scans_done");
                    if (e.get("seconds") instanceof Number s) {
                        scanSeconds.add(s.doubleValue());
                        projectSeconds.merge(project, s.doubleValue(), Double::sum);
                    }
                } else if ("failed".equals(state)) {
                    inc(row, "scans_failed");
                } else {
                    inc(row, "scans_cancelled");
                }
            } else if ("report".equals(kind) && "done".equals(state)) {
                inc(row, "reports");
            } else if ("enrich".equals(kind) && "done".equals(state)) {
                inc(row, "enrich");
            }
            if ("failed".equals(state)) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("time", t);
                f.put("project", project);
                f.put("kind", kind);
                f.put("title", e.get("title"));
                f.put("error", e.get("error"));
                failures.add(f);
            }
        }
        // as Python: report titles count every entry from the first day on (later ones too)
        for (Map<String, Object> e : entries) {
            String t = e.get("time") == null ? "" : String.valueOf(e.get("time"));
            if ("job finished".equals(e.get("action")) && "report".equals(e.get("kind")) && "done".equals(e.get("state"))
                    && (t.length() >= 10 ? t.substring(0, 10) : t).compareTo(start) >= 0) {
                reportTitles.merge(e.get("title") == null ? "reports" : String.valueOf(e.get("title")), 1, Integer::sum);
            }
        }
        List<Map<String, Object>> series = new ArrayList<>();
        int scans = 0;
        int done = 0;
        int failed = 0;
        int logins = 0;
        int reports = 0;
        int enrich = 0;
        for (String d : dayList) {
            Map<String, Object> row = new LinkedHashMap<>(perDay.get(d));
            row.put("active_users", dayUsers.get(d).size());
            series.add(row);
            scans += (int) row.get("scans_done") + (int) row.get("scans_failed") + (int) row.get("scans_cancelled");
            done += (int) row.get("scans_done");
            failed += (int) row.get("scans_failed");
            logins += (int) row.get("logins");
            reports += (int) row.get("reports");
            enrich += (int) row.get("enrich");
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("users", users);
        totals.put("active_users", active.size());
        totals.put("projects", projects);
        totals.put("archived_projects", archived);
        totals.put("logins", logins);
        totals.put("scans", scans);
        totals.put("scans_done", done);
        totals.put("scans_failed", failed);
        totals.put("success_percent", scans == 0 ? null : round(100.0 * done / scans, 1));
        totals.put("scan_seconds_avg", scanSeconds.isEmpty() ? null : (long) round(avg(scanSeconds), 0));
        totals.put("scan_seconds_max", scanSeconds.isEmpty() ? null : (long) round(scanSeconds.stream().mapToDouble(x -> x).max().orElse(0), 0));
        totals.put("wait_seconds_avg", waitSeconds.isEmpty() ? null : round(avg(waitSeconds), 1));
        totals.put("reports", reports);
        totals.put("enrich", enrich);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("from", start);
        out.put("to", dayList.getLast());
        out.put("series", series);
        out.put("totals", totals);
        out.put("reports_by_type", top(reportTitles, 8).stream().map(en -> named(en.getKey(), "count", en.getValue())).toList());
        out.put("top_projects", top(byProject, 8).stream().map(en -> {
            Map<String, Object> m = named(en.getKey(), "scans", en.getValue());
            m.put("scan_seconds", (long) round(projectSeconds.getOrDefault(en.getKey(), 0.0), 0));
            return m;
        }).toList());
        out.put("top_users", top(byUser, 8).stream().map(en -> named(en.getKey(), "actions", en.getValue())).toList());
        failures.sort(Comparator.comparing((Map<String, Object> f) -> String.valueOf(f.get("time"))).reversed());
        out.put("failures", failures.subList(0, Math.min(20, failures.size())));
        return out;
    }

    private static void inc(Map<String, Object> row, String key) {
        row.put(key, (int) row.get(key) + 1);
    }

    /** Python's {@code round(x, n)}: the exact value rounded half to even. */
    static double round(double x, int n) {
        return new java.math.BigDecimal(x).setScale(n, java.math.RoundingMode.HALF_EVEN).doubleValue();
    }

    private static double avg(List<Double> values) {
        return values.stream().mapToDouble(x -> x).sum() / values.size();
    }

    /** {@code Counter.most_common(n)}: highest first, ties in first-seen order. */
    static List<Map.Entry<String, Integer>> top(Map<String, Integer> counts, int n) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return entries.subList(0, Math.min(n, entries.size()));
    }

    private static Map<String, Object> named(String name, String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put(key, value);
        return m;
    }
}
