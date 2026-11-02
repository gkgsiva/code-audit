package com.legacyaudit.server.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The site's common settings, saved by the admin page to {@code <audits root>/.legacy-audit/admin.yaml}
 * ({@code AdminSettings} of the Python hosted site): which reports and summary sections the site
 * offers, more folders projects may scan, sign-up rules, the AI switch, limits, and the
 * {@code audit.yaml} defaults every project inherits with the keys it may not change.
 */
public record AdminSettings(Reports reports, Paths paths, Users users, Ai ai, Limits limits, Map<String, Object> defaults,
        List<String> locked) {

    /** Report switches: key to label (the PowerPoint is gone, decision 225). */
    public static final Map<String, String> REPORTS = new LinkedHashMap<>();
    public static final Map<String, String> SUMMARY_SECTIONS = new LinkedHashMap<>();
    public static final List<String> ROLES = List.of("admin", "owner", "contributor", "viewer");

    static {
        REPORTS.put("summary", "Summary report (HTML)");
        REPORTS.put("mgmt", "Management deck (HTML)");
        REPORTS.put("functional", "Functional document (HTML)");
        REPORTS.put("tech", "Technical report (HTML)");
        REPORTS.put("pdf", "PDF copies");
        REPORTS.put("datapack", "Data pack (ZIP of the facts)");
        SUMMARY_SECTIONS.put("project", "Project, tech stack and modules");
        SUMMARY_SECTIONS.put("data", "Database and queries");
        SUMMARY_SECTIONS.put("integrations", "Integrations");
        SUMMARY_SECTIONS.put("batch", "Batches");
        SUMMARY_SECTIONS.put("security", "Dependency vulnerabilities");
    }

    public record Reports(Map<String, Boolean> enabled, Map<String, Boolean> summarySections, boolean ownersMayHide) {}

    public record Paths(List<String> extraSourceRoots, String gitCacheDir) {}

    public record Users(boolean openSignup, String signupRole, String whoCanCreate) {}

    public record Ai(boolean allowed) {}

    public record Limits(int maxScans, int keepRuns, int scanTimeoutMinutes, int cloneKeepDays, double minFreeGb) {}

    /** Thrown with every problem found, joined by "; " (as pydantic's messages were). */
    public static final class Invalid extends RuntimeException {
        public Invalid(String message) {
            super(message);
        }
    }

    public static AdminSettings initial() {
        return parse(Map.of());
    }

    /** Checks and reads a mapping (the YAML file or the admin page's JSON); unknown keys are refused. */
    public static AdminSettings parse(Map<?, ?> data) {
        Reader r = new Reader();
        r.only(data, "", List.of("reports", "paths", "users", "ai", "limits", "defaults", "locked"));
        Map<?, ?> rep = r.map(data, "reports");
        r.only(rep, "reports.", List.of("enabled", "summary_sections", "owners_may_hide"));
        Map<String, Boolean> enabled = r.switches(rep, "reports.enabled", REPORTS.keySet());
        Map<String, Boolean> sections = r.switches(rep, "reports.summary_sections", SUMMARY_SECTIONS.keySet());
        Reports reports = new Reports(enabled, sections, r.bool(rep, "reports.owners_may_hide", true));
        Map<?, ?> p = r.map(data, "paths");
        r.only(p, "paths.", List.of("extra_source_roots", "git_cache_dir", "templates_dir"));
        Paths paths = new Paths(r.strings(p, "paths.extra_source_roots"), r.string(p, "paths.git_cache_dir"));
        Map<?, ?> u = r.map(data, "users");
        r.only(u, "users.", List.of("open_signup", "signup_role", "who_can_create"));
        Users users = new Users(r.bool(u, "users.open_signup", true),
                r.choice(u, "users.signup_role", List.of("owner", "contributor", "viewer"), "owner"),
                r.choice(u, "users.who_can_create", List.of("owners", "admins"), "owners"));
        Map<?, ?> a = r.map(data, "ai");
        r.only(a, "ai.", List.of("allowed"));
        Ai ai = new Ai(r.bool(a, "ai.allowed", true));
        Map<?, ?> l = r.map(data, "limits");
        r.only(l, "limits.", List.of("max_scans", "keep_runs", "scan_timeout_minutes", "clone_keep_days", "min_free_gb"));
        Limits limits = new Limits(
                (int) r.number(l, "limits.max_scans", 2, 1, 32, true),
                (int) r.number(l, "limits.keep_runs", 10, 1, 500, true),
                (int) r.number(l, "limits.scan_timeout_minutes", 240, 5, Double.MAX_VALUE, true),
                (int) r.number(l, "limits.clone_keep_days", 14, 1, 3650, true),
                r.number(l, "limits.min_free_gb", 5, 0, 100000, false));
        Map<String, Object> defaults = new LinkedHashMap<>();
        Object d = data.get("defaults");
        if (d instanceof Map<?, ?> dm) {
            dm.forEach((k, v) -> defaults.put(String.valueOf(k), v));
        } else if (d != null) {
            r.problems.add("defaults: Input should be a valid dictionary");
        }
        List<String> locked = r.strings(data, "locked");
        if (!r.problems.isEmpty()) {
            throw new Invalid(String.join("; ", r.problems));
        }
        return new AdminSettings(reports, paths, users, ai, limits, defaults, locked);
    }

    /** The settings as the YAML file and the admin page hold them (snake_case keys). */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("enabled", new LinkedHashMap<>(reports.enabled()));
        rep.put("summary_sections", new LinkedHashMap<>(reports.summarySections()));
        rep.put("owners_may_hide", reports.ownersMayHide());
        out.put("reports", rep);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("extra_source_roots", new ArrayList<>(paths.extraSourceRoots()));
        p.put("git_cache_dir", paths.gitCacheDir());
        out.put("paths", p);
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("open_signup", users.openSignup());
        u.put("signup_role", users.signupRole());
        u.put("who_can_create", users.whoCanCreate());
        out.put("users", u);
        out.put("ai", new LinkedHashMap<>(Map.of("allowed", ai.allowed())));
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("max_scans", limits.maxScans());
        l.put("keep_runs", limits.keepRuns());
        l.put("scan_timeout_minutes", limits.scanTimeoutMinutes());
        l.put("clone_keep_days", limits.cloneKeepDays());
        l.put("min_free_gb", limits.minFreeGb());
        out.put("limits", l);
        out.put("defaults", new LinkedHashMap<>(defaults));
        out.put("locked", new ArrayList<>(locked));
        return out;
    }

    public AdminSettings withLimits(Limits l) {
        return new AdminSettings(reports, paths, users, ai, l, defaults, locked);
    }

    /** Collects problems instead of stopping at the first. */
    private static final class Reader {
        final List<String> problems = new ArrayList<>();

        void only(Map<?, ?> m, String prefix, List<String> keys) {
            for (Object k : m.keySet()) {
                if (!keys.contains(String.valueOf(k))) {
                    problems.add(prefix + k + ": Extra inputs are not permitted");
                }
            }
        }

        Map<?, ?> map(Map<?, ?> m, String key) {
            Object v = m.get(key);
            if (v == null) {
                return Map.of();
            }
            if (v instanceof Map<?, ?> mm) {
                return mm;
            }
            problems.add(key + ": Input should be a valid dictionary");
            return Map.of();
        }

        boolean bool(Map<?, ?> m, String path, boolean dflt) {
            Object v = m.get(last(path));
            if (v == null) {
                return dflt;
            }
            if (v instanceof Boolean b) {
                return b;
            }
            problems.add(path + ": Input should be a valid boolean");
            return dflt;
        }

        Map<String, Boolean> switches(Map<?, ?> m, String path, java.util.Set<String> keys) {
            Map<String, Boolean> out = new LinkedHashMap<>();
            keys.forEach(k -> out.put(k, true));
            Object v = m.get(last(path));
            if (v == null) {
                return out;
            }
            if (!(v instanceof Map<?, ?> mm)) {
                problems.add(path + ": Input should be a valid dictionary");
                return out;
            }
            Map<String, Boolean> given = new LinkedHashMap<>();
            mm.forEach((k, b) -> {
                if (b instanceof Boolean bb) {
                    given.put(String.valueOf(k), bb);
                } else {
                    problems.add(path + "." + k + ": Input should be a valid boolean");
                }
            });
            // as pydantic: the mapping given replaces the default one (a key left out is off);
            // unknown keys are kept and ignored
            return given;
        }

        String string(Map<?, ?> m, String path) {
            Object v = m.get(last(path));
            if (v == null) {
                return null;
            }
            if (v instanceof String s) {
                return s;
            }
            problems.add(path + ": Input should be a valid string");
            return null;
        }

        List<String> strings(Map<?, ?> m, String path) {
            Object v = m.get(last(path));
            if (v == null) {
                return List.of();
            }
            if (!(v instanceof List<?> list)) {
                problems.add(path + ": Input should be a valid list");
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof String s) {
                    out.add(s);
                } else {
                    problems.add(path + "." + i + ": Input should be a valid string");
                }
            }
            return out;
        }

        String choice(Map<?, ?> m, String path, List<String> allowed, String dflt) {
            Object v = m.get(last(path));
            if (v == null) {
                return dflt;
            }
            if (!allowed.contains(String.valueOf(v))) {
                problems.add(path + ": Input should be " + String.join(" or ", allowed.stream().map(a -> "'" + a + "'").toList())
                        .replaceFirst(" or (?=.* or )", ", "));
                return dflt;
            }
            return String.valueOf(v);
        }

        double number(Map<?, ?> m, String path, double dflt, double min, double max, boolean integer) {
            Object v = m.get(last(path));
            if (v == null) {
                return dflt;
            }
            double d;
            if (v instanceof Number n && !(v instanceof Double dd && integer && dd != Math.floor(dd))) {
                d = n.doubleValue();
            } else if (v instanceof String s && s.strip().matches(integer ? "-?\\d+" : "-?\\d+(\\.\\d+)?")) {
                d = Double.parseDouble(s.strip());
            } else {
                problems.add(path + ": Input should be a valid " + (integer ? "integer" : "number"));
                return dflt;
            }
            if (d < min) {
                problems.add(path + ": Input should be greater than or equal to " + fmt(min));
                return dflt;
            }
            if (d > max) {
                problems.add(path + ": Input should be less than or equal to " + fmt(max));
                return dflt;
            }
            return d;
        }

        private static String fmt(double d) {
            return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
        }

        private static String last(String path) {
            return path.substring(path.lastIndexOf('.') + 1);
        }
    }
}
