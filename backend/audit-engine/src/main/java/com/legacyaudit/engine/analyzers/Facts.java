package com.legacyaudit.engine.analyzers;

import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Schema;
import com.legacyaudit.model.facts.Schemas;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything one analyzer found in one file. */
public final class Facts {

    /** Entity file stem (services, sql_statements, ...) to the schema of its facts. */
    public static final Map<String, Schema> ENTITIES = entities();

    public final Map<String, List<Doc>> entities = new LinkedHashMap<>();
    public final List<Symbol> symbols = new ArrayList<>();
    public final List<Ref> refs = new ArrayList<>();
    public final Coverage coverage = new Coverage();
    /** Functions, methods, paragraphs and scripts measured for complexity (CodeUnit). */
    public final List<Doc> units = new ArrayList<>();
    /** Duplication windows: [hash, first line, last line] for windows starting at a line. */
    public final List<List<Long>> windows = new ArrayList<>();
    /** Text-rule hits: [rule id, line, matched text (secrets masked)]. */
    public final List<List<Object>> hits = new ArrayList<>();

    private static Map<String, Schema> entities() {
        Map<String, Schema> m = new LinkedHashMap<>();
        m.put("entry_points", Schemas.ENTRY_POINT);
        m.put("services", Schemas.SERVICE);
        m.put("screens", Schemas.SCREEN);
        m.put("sql_statements", Schemas.SQL_STATEMENT);
        m.put("db_objects", Schemas.DB_OBJECT);
        m.put("integrations", Schemas.INTEGRATION);
        m.put("web_services", Schemas.WEB_SERVICE);
        m.put("dependencies", Schemas.CODE_EDGE);
        m.put("batch_jobs", Schemas.BATCH_JOB);
        m.put("libraries", Schemas.LIBRARY);
        m.put("tech_stack", Schemas.TECH_ITEM);
        m.put("findings", Schemas.FINDING);
        m.put("metrics", Schemas.METRIC);
        return m;
    }

    public Doc add(String entity, Doc fact) {
        Schema expected = ENTITIES.get(entity);
        if (expected != fact.schema()) {
            throw new IllegalArgumentException(fact.schema().name() + " is not a fact of " + entity);
        }
        entities.computeIfAbsent(entity, k -> new ArrayList<>()).add(fact);
        return fact;
    }

    public List<Doc> get(String entity) {
        return entities.getOrDefault(entity, List.of());
    }

    public Symbol symbol(String kind, String name, String file, Integer line) {
        Symbol s = new Symbol(kind, name, file, line);
        symbols.add(s);
        return s;
    }

    public Ref ref(String kind, String source, String sourceLabel, String target, List<String> targetKinds, String file, Integer line) {
        Ref r = new Ref(kind, source, sourceLabel, target, targetKinds, file, line);
        refs.add(r);
        return r;
    }

    public void extend(Facts other) {
        other.entities.forEach((e, items) -> entities.computeIfAbsent(e, k -> new ArrayList<>()).addAll(items));
        symbols.addAll(other.symbols);
        refs.addAll(other.refs);
        coverage.unparsed.addAll(other.coverage.unparsed);
        units.addAll(other.units);
        windows.addAll(other.windows);
        hits.addAll(other.hits);
    }

    public boolean isEmpty() {
        return entities.isEmpty() && symbols.isEmpty() && refs.isEmpty() && units.isEmpty() && windows.isEmpty() && hits.isEmpty()
                && coverage.unparsed.isEmpty();
    }

    /** The form kept in the file cache and compared with the Python tool's. */
    public Map<String, Object> dump() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> ents = new LinkedHashMap<>();
        entities.forEach((e, items) -> ents.put(e, items.stream().map(Doc::toMap).toList()));
        out.put("entities", ents);
        out.put("symbols", symbols.stream().map(Symbol::toMap).toList());
        out.put("refs", refs.stream().map(Ref::toMap).toList());
        out.put("coverage", coverage.toMap());
        out.put("units", units.stream().map(Doc::toMap).toList());
        out.put("windows", windows.stream().map(w -> (Object) new ArrayList<>(w)).toList());
        out.put("hits", hits.stream().map(h -> (Object) new ArrayList<>(h)).toList());
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Facts load(Map<String, Object> data) {
        Facts f = new Facts();
        Map<String, Object> ents = (Map<String, Object>) data.getOrDefault("entities", Map.of());
        ents.forEach((e, items) -> {
            List<Doc> docs = new ArrayList<>();
            for (Object o : (List<Object>) items) {
                docs.add(Doc.fromMap(ENTITIES.get(e), (Map<String, Object>) o));
            }
            f.entities.put(e, docs);
        });
        for (Object o : (List<Object>) data.getOrDefault("symbols", List.of())) {
            f.symbols.add(Symbol.fromMap((Map<String, Object>) o));
        }
        for (Object o : (List<Object>) data.getOrDefault("refs", List.of())) {
            f.refs.add(Ref.fromMap((Map<String, Object>) o));
        }
        Coverage c = Coverage.fromMap((Map<String, Object>) data.get("coverage"));
        f.coverage.lines = c.lines;
        f.coverage.unparsed.addAll(c.unparsed);
        for (Object o : (List<Object>) data.getOrDefault("units", List.of())) {
            f.units.add(Doc.fromMap(Schemas.CODE_UNIT, (Map<String, Object>) o));
        }
        for (Object o : (List<Object>) data.getOrDefault("windows", List.of())) {
            f.windows.add(((List<Object>) o).stream().map(x -> ((Number) x).longValue()).toList());
        }
        for (Object o : (List<Object>) data.getOrDefault("hits", List.of())) {
            f.hits.add(new ArrayList<>((List<Object>) o));
        }
        return f;
    }
}
