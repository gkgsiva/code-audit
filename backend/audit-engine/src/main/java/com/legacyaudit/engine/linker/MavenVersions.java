package com.legacyaudit.engine.linker;

import com.legacyaudit.engine.analyzers.Symbol;
import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Fact;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maven versions a module inherits from its parent chain ({@code dependencyManagement} and
 * properties), resolved once every {@code pom.xml} has been read.
 */
final class MavenVersions {

    private static final Pattern PROP = Rx.of("\\$\\{([^}]+)\\}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MavenVersions() {}

    static String substitute(String value, Map<String, String> props) {
        for (int i = 0; i < 5; i++) {
            Matcher m = PROP.matcher(value);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(props.getOrDefault(m.group(1), m.group())));
            }
            m.appendTail(sb);
            String next = sb.toString();
            if (next.equals(value)) {
                break;
            }
            value = next;
        }
        return value;
    }

    static List<Doc> resolve(List<Doc> libraries, List<Symbol> symbols) {
        Map<String, Symbol> poms = new HashMap<>();
        Map<String, String> managed = new HashMap<>();
        Map<String, Symbol> unresolved = new HashMap<>();
        for (Symbol s : symbols) {
            switch (s.kind) {
                case "maven-pom" -> poms.putIfAbsent(s.name, s);
                case "maven-managed" -> managed.putIfAbsent(s.name, s.data.getOrDefault("version", ""));
                case "maven-unresolved" -> unresolved.put(s.name, s);
                default -> {
                    // other symbols are not about versions
                }
            }
        }
        if (unresolved.isEmpty()) {
            return libraries;
        }
        List<Doc> out = new ArrayList<>();
        for (Doc lib : libraries) {
            Symbol sym = unresolved.get(lib.id());
            if (sym == null) {
                out.add(lib);
                continue;
            }
            List<String> coords = chain(poms, sym.data.getOrDefault("pom", ""));
            Map<String, String> values = props(poms, coords);
            String raw = sym.data.getOrDefault("raw", "");
            String version = raw.isEmpty() ? null : substitute(raw, values);
            if (version == null || version.isEmpty() || version.contains("${")) {
                for (String c : coords) {
                    String v = managed.get(c + "|" + sym.data.getOrDefault("key", ""));
                    if (v != null && !v.isEmpty()) {
                        version = substitute(v, values);
                        break;
                    }
                }
            }
            if (version != null && !version.isEmpty() && !version.contains("${")) {
                String group = lib.str("group");
                lib = lib.copy()
                        .set("version", version)
                        .set("id", Fact.makeId("library", lib.str("language"), group == null ? "" : group, lib.str("artifact"), version));
            }
            out.add(lib);
        }
        return out;
    }

    private static List<String> chain(Map<String, Symbol> poms, String coord) {
        List<String> out = new ArrayList<>();
        while (coord != null && !coord.isEmpty() && poms.containsKey(coord) && !out.contains(coord)) {
            out.add(coord);
            coord = poms.get(coord).data.getOrDefault("parent", "");
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> props(Map<String, Symbol> poms, List<String> coords) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (int i = coords.size() - 1; i >= 0; i--) { // parent first, child overrides
            Map<String, Object> p = JSON.readValue(poms.get(coords.get(i)).data.getOrDefault("properties", "{}"), Map.class);
            p.forEach((k, v) -> merged.put(k, String.valueOf(v)));
        }
        return merged;
    }
}
