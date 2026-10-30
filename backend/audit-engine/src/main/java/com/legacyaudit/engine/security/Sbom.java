package com.legacyaudit.engine.security;

import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.json.PyJson;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/** CycloneDX 1.5 SBOM ({@code data/sbom.cdx.json}) from the library inventory and vulnerabilities. */
public final class Sbom {

    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");
    private static final UUID NS = UUID.fromString("8c3a6f0e-6f1e-4c1b-9a43-1b7f2d0a5e11");

    private Sbom() {}

    /** A name-based (SHA-1, version 5) UUID, as Python's {@code uuid.uuid5}. */
    static UUID uuid5(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            ByteBuffer ns = ByteBuffer.allocate(16).putLong(namespace.getMostSignificantBits()).putLong(namespace.getLeastSignificantBits());
            sha1.update(ns.array());
            sha1.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] h = sha1.digest();
            h[6] = (byte) ((h[6] & 0x0f) | 0x50);
            h[8] = (byte) ((h[8] & 0x3f) | 0x80);
            ByteBuffer b = ByteBuffer.wrap(h, 0, 16);
            return new UUID(b.getLong(), b.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String scope(Doc lib) {
        String s = lib.str("scope") == null ? "" : lib.str("scope").toLowerCase(Locale.ROOT);
        if (s.equals("test")) {
            return "excluded";
        }
        if (s.equals("provided") || s.equals("optional")) {
            return "optional";
        }
        return "required";
    }

    private static Map<String, Object> prop(String name, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value);
        return m;
    }

    private static Map<String, Object> component(Doc lib) {
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("type", "library");
        comp.put("bom-ref", lib.str("id"));
        comp.put("name", lib.str("artifact"));
        if (Eol.truthy(lib.str("group"))) {
            comp.put("group", lib.str("group"));
        }
        if (Eol.truthy(lib.str("version"))) {
            comp.put("version", lib.str("version"));
        }
        comp.put("scope", scope(lib));
        String p = Vulns.purl(lib);
        if (p != null) {
            comp.put("purl", p);
        }
        String sha1 = lib.str("sha1");
        if (Eol.truthy(sha1) && SHA1.matcher(sha1.toLowerCase(Locale.ROOT)).matches()) {
            Map<String, Object> hash = new LinkedHashMap<>();
            hash.put("alg", "SHA-1");
            hash.put("content", sha1.toLowerCase(Locale.ROOT));
            comp.put("hashes", List.of(hash));
        }
        if (Eol.truthy(lib.str("license"))) {
            comp.put("licenses", List.of(Map.of("license", Map.of("name", lib.str("license")))));
        }
        List<Object> props = new ArrayList<>();
        props.add(prop("legacy-audit:source", lib.str("source")));
        props.add(prop("legacy-audit:declared", String.valueOf(lib.bool("declared"))));
        if (lib.bool("eol")) {
            props.add(prop("legacy-audit:eol", Eol.truthy(lib.str("eol_date")) ? lib.str("eol_date") : "true"));
        }
        Object rec = lib.get("recommendation");
        if (rec instanceof Doc r) {
            props.add(prop("legacy-audit:recommendation", r.str("action")));
        }
        comp.put("properties", props);
        TreeSet<String> locations = new TreeSet<>(PyJson::compareCodePoints);
        for (Object e : lib.list("evidence")) {
            locations.add(((Evidence) e).file());
        }
        if (!locations.isEmpty()) {
            List<Object> occ = new ArrayList<>();
            for (String loc : locations.stream().limit(20).toList()) {
                occ.add(Map.of("location", loc));
            }
            comp.put("evidence", Map.of("occurrences", occ));
        }
        return comp;
    }

    public static Map<String, Object> build(String application, String runId, String timestamp, String toolVersion, List<Doc> libraries) {
        String appRef = "application:" + application;
        Map<String, Map<String, Object>> vulns = new TreeMap<>(PyJson::compareCodePoints);
        for (Doc lib : libraries) {
            for (Doc v : lib.<Doc>list("vulnerabilities")) {
                String id = v.str("id");
                Map<String, Object> entry = vulns.get(id);
                if (entry == null) {
                    Map<String, Object> rating = new LinkedHashMap<>();
                    rating.put("severity", v.str("severity"));
                    rating.put("method", v.get("cvss") != null ? "CVSSv31" : "other");
                    if (v.get("cvss") != null) {
                        rating.put("score", v.get("cvss"));
                    }
                    entry = new LinkedHashMap<>();
                    entry.put("bom-ref", "vulnerability:" + id);
                    entry.put("id", id);
                    entry.put("source", Map.of("name", id.startsWith("CVE-") ? "NVD" : "OSV"));
                    entry.put("ratings", List.of(rating));
                    entry.put("affects", new ArrayList<>());
                    List<String> sources = new ArrayList<>();
                    v.list("sources").forEach(s -> sources.add(String.valueOf(s)));
                    entry.put("properties", List.of(prop("legacy-audit:reported-by", String.join(",", sources))));
                    if (Eol.truthy(v.str("description"))) {
                        entry.put("description", v.str("description"));
                    }
                    List<Object> aliases = v.list("aliases");
                    if (!aliases.isEmpty()) {
                        List<Object> refs = new ArrayList<>();
                        for (Object a : aliases) {
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("id", a);
                            r.put("source", Map.of("name", "OSV"));
                            refs.add(r);
                        }
                        entry.put("references", refs);
                    }
                    List<String> fixed = new ArrayList<>();
                    v.list("fixed_versions").forEach(f -> fixed.add(String.valueOf(f)));
                    if (!fixed.isEmpty()) {
                        entry.put("recommendation", "Fixed in " + String.join(", ", fixed));
                    }
                    vulns.put(id, entry);
                }
                @SuppressWarnings("unchecked")
                List<Object> affects = (List<Object>) entry.get("affects");
                affects.add(Map.of("ref", lib.str("id")));
            }
        }
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "application");
        tool.put("name", "legacy-audit");
        tool.put("version", toolVersion);
        Map<String, Object> appComp = new LinkedHashMap<>();
        appComp.put("type", "application");
        appComp.put("bom-ref", appRef);
        appComp.put("name", application);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("timestamp", timestamp);
        metadata.put("tools", Map.of("components", List.of(tool)));
        metadata.put("component", appComp);
        List<Object> components = new ArrayList<>();
        List<Object> ids = new ArrayList<>();
        for (Doc lib : libraries) {
            components.add(component(lib));
            ids.add(lib.str("id"));
        }
        Map<String, Object> dep = new LinkedHashMap<>();
        dep.put("ref", appRef);
        dep.put("dependsOn", ids);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bomFormat", "CycloneDX");
        out.put("specVersion", "1.5");
        out.put("serialNumber", "urn:uuid:" + uuid5(NS, application + "/" + runId));
        out.put("version", 1L);
        out.put("metadata", metadata);
        out.put("components", components);
        out.put("dependencies", List.of(dep));
        out.put("vulnerabilities", new ArrayList<>(vulns.values()));
        return out;
    }
}
