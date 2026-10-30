package com.legacyaudit.engine.security;

import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.engine.rules.PyRegex;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Schemas;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Upgrade recommendations per library (data in {@code rules/dependency-upgrades.yaml}). */
public final class Upgrades {

    private Upgrades() {}

    private static boolean fullMatch(String regex, String value) {
        return PyRegex.compile(regex, Rx.I).pattern.matcher(value).matches();
    }

    /** True when {@code regex} is unset or {@code value} is unknown or fully matches it. */
    private static boolean full(Object regex, String value) {
        return !Eol.truthy(regex) || !Eol.truthy(value) || fullMatch(String.valueOf(regex), value);
    }

    /** First entry matching the library; {@code needGroup}: the library's group must be known. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> find(Object entries, Doc lib, boolean needGroup) {
        if (needGroup && !Eol.truthy(lib.str("group"))) {
            return null;
        }
        if (entries instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> e = (Map<String, Object>) o;
                if (fullMatch(String.valueOf(e.get("artifact")), lib.str("artifact")) && full(e.get("group"), lib.str("group"))) {
                    return e;
                }
            }
        }
        return null;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String or(Object v, String dflt) {
        return Eol.truthy(v) ? String.valueOf(v) : dflt;
    }

    /** Lowest version above the current one that every vulnerability lists as fixed, and whether to verify it. */
    private static Object[] fixedVersion(Doc lib) {
        List<List<String>> perVuln = new ArrayList<>();
        boolean unknown = false;
        String current = lib.str("version");
        for (Doc v : lib.<Doc>list("vulnerabilities")) {
            List<String> fixes = new ArrayList<>();
            for (Object f : v.list("fixed_versions")) {
                if (!Eol.truthy(current) || Versions.compare(String.valueOf(f), current) > 0) {
                    fixes.add(String.valueOf(f));
                }
            }
            if (!fixes.isEmpty()) {
                perVuln.add(Versions.sort(fixes));
            } else {
                unknown = true;
            }
        }
        if (perVuln.isEmpty()) {
            return new Object[] {null, true};
        }
        // The lowest fix of each vulnerability; the highest of those fixes all of them on one line.
        List<String> lowest = new ArrayList<>();
        perVuln.forEach(f -> lowest.add(f.get(0)));
        List<String> sorted = Versions.sort(lowest);
        return new Object[] {sorted.get(sorted.size() - 1), unknown};
    }

    private static Doc rec(String action, String target, String reason, String effort, String recipe, boolean verify, String rule) {
        return Doc.of(Schemas.RECOMMENDATION, "action", action, "target", target, "reason", reason, "effort", effort, "recipe", recipe,
                "verify", verify, "rule", rule);
    }

    /** {@code data} is the dependency pack resolved for the target ({@code Targets.resolve}). */
    @SuppressWarnings("unchecked")
    public static Doc recommend(Doc lib, Map<String, Object> data) {
        Map<String, Object> recipes = (Map<String, Object>) (data.get("recipes") instanceof Map<?, ?> m ? m : Map.of());
        Map<String, Object> targets = (Map<String, Object>) (data.get("targets") instanceof Map<?, ?> m ? m : Map.of());
        Map<String, Object> bom = (Map<String, Object>) (data.get("bom") instanceof Map<?, ?> m ? m : Map.of());
        String bomName = or(bom.get("name"), "Spring Boot " + (targets.containsKey("spring_boot") ? str(targets.get("spring_boot")) : "4"));
        String bomShort = or(bom.get("short"), "Spring Boot");
        String manages = or(bom.get("manages"), "manages");
        String how = or(bom.get("how"), "managed by");
        String bomRecipe = Eol.truthy(bom.get("recipe")) ? str(recipes.get(str(bom.get("recipe")))) : null;
        String eolDate = lib.str("eol_date");
        if (lib.bool("eol")) {
            Map<String, Object> front = find(data.get("frontend"), lib, false);
            if (front != null) {
                return rec(str(front.get("action")), str(front.get("target")), str(front.get("reason")),
                        front.containsKey("effort") ? str(front.get("effort")) : "high", null, false, "frontend");
            }
            Map<String, Object> boot = find(data.get("boot_managed"), lib, true);
            if (boot != null) {
                return rec("use-bom", or(lib.str("replacement"), "current version") + ", " + how + " " + bomName,
                        Eol.truthy(eolDate) ? "end of life since " + eolDate + "; " + bomShort + " " + manages + " the new version"
                                : "end of life; " + bomShort + " " + manages + " the new version",
                        "medium", bomRecipe, false, "eol-boot-managed");
            }
            Map<String, Object> eolRecipe = find(data.get("eol_recipes"), lib, false);
            String recipeKey = eolRecipe != null ? str(eolRecipe.get("recipe")) : "";
            return rec("replace", lib.str("replacement"), Eol.truthy(eolDate) ? "end of life since " + eolDate : "end of life", "medium",
                    str(recipes.get(recipeKey)), false, "eol");
        }
        Map<String, Object> front = find(data.get("frontend"), lib, false);
        if (front != null && !"java".equals(lib.str("language"))) {
            return rec(str(front.get("action")), str(front.get("target")), str(front.get("reason")),
                    front.containsKey("effort") ? str(front.get("effort")) : "medium", null, false, "frontend");
        }
        Map<String, Object> jak = find(data.get("jakarta"), lib, false);
        if (jak != null) {
            return rec("migrate-jakarta", str(jak.get("target")), or(data.get("jakarta_reason"),
                    "javax.* API: Spring Boot 4 runs on Jakarta EE " + (targets.containsKey("jakarta_ee") ? str(targets.get("jakarta_ee")) : "11")),
                    "low", str(recipes.get("jakarta")), false, "jakarta");
        }
        Map<String, Object> rep = find(data.get("replace"), lib, false);
        if (rep != null) {
            return rec("replace", str(rep.get("target")), str(rep.get("reason")), rep.containsKey("effort") ? str(rep.get("effort")) : "medium",
                    null, false, "replace");
        }
        Map<String, Object> boot = find(data.get("boot_managed"), lib, true);
        if (boot != null) {
            return rec("use-bom", or(boot.get("target"), "version " + how + " " + bomName),
                    or(bom.get("reason"), bomShort + " " + manages + " this dependency; drop the explicit version"), "low", bomRecipe,
                    Eol.truthy(boot.get("verify")), "boot-managed");
        }
        List<Doc> vulns = lib.list("vulnerabilities");
        if (!vulns.isEmpty()) {
            Object[] fixed = fixedVersion(lib);
            String version = (String) fixed[0];
            String group = lib.str("group");
            return rec("upgrade", version != null && Eol.truthy(group) ? group + ":" + lib.str("artifact") + ":" + version : version,
                    version != null ? "fixes " + vulns.size() + " known vulnerabilities" : "known vulnerabilities; no fixed version in the reports",
                    "low", null, (Boolean) fixed[1], "vulnerable");
        }
        return rec("keep", null, "no rule applies", "low", null, false, "keep");
    }

    public static void apply(List<Doc> libraries, Map<String, Object> data) {
        for (Doc lib : libraries) {
            Doc rec = recommend(lib, data);
            lib.set("recommendation", rec);
            String target = rec.str("target");
            if ("upgrade".equals(rec.str("action")) && Eol.truthy(target)) {
                lib.set("recommended_version", target.substring(target.lastIndexOf(':') + 1));
            }
        }
    }
}
