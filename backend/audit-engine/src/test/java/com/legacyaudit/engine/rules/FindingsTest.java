package com.legacyaudit.engine.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Finding;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The rules engine: conditions, messages, fact and text rules, config switches (the Python tool's tests). */
class FindingsTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void conditions() {
        Map<String, Object> item = map("a", 3L, "b", "Hello", "c", List.of(1L, 2L), "d", null, "n", map("x", "y"));
        assertTrue(Findings.matches(item, map("a", 3L, "b", map("regex", "^H"), "c", map("contains", 2L))));
        assertTrue(Findings.matches(item, map("a", map("gte", 3L, "lt", 4L), "n.x", "y", "d", map("exists", false))));
        assertTrue(Findings.matches(item, map("zz", map("empty", true), "c", map("empty", false), "b", map("in", List.of("Hello")))));
        assertFalse(Findings.matches(item, map("a", map("gt", 3L))));
        assertFalse(Findings.matches(item, map("b", map("not_in", List.of("Hello")))));
        assertTrue(Findings.matches(item, map("b", map("ne", "x"), "zz", map("ne", 1L))));
        assertThrows(IllegalArgumentException.class, () -> Findings.matches(item, map("a", map("between", 1L))));
    }

    @Test
    void renderUnknownFieldsAndLists() {
        assertEquals("1 1, 2 ? y", Findings.render("{a} {b} {missing} {n.x}", map("a", 1L, "b", List.of(1L, 2L), "n", map("x", "y"))));
    }

    private static Packs.Rule rule(String id, String severityField) {
        return new Packs.Rule(id, "medium", "c", "{name}", null, severityField, new Packs.FactMatch("things", map("bad", true)), null);
    }

    @Test
    void evaluateAndApplyConfig() {
        List<Map<String, Object>> items = List.of(
                map("id", "t1", "name", "one", "bad", true, "evidence", List.of(map("file", "a.java", "line", 3L))),
                map("id", "t2", "name", "two", "bad", false),
                map("id", "t3", "name", "three", "bad", true, "level", "critical", "file", "b.c"));
        List<Finding> found = Findings.evaluate(List.of(rule("r1", null), rule("r2", "level")),
                name -> name.equals("things") ? items : List.of(), List.of());
        TreeSet<String> pairs = new TreeSet<>();
        found.forEach(f -> pairs.add(f.ruleId() + "/" + f.message()));
        assertEquals(List.of("r1/one", "r1/three", "r2/one", "r2/three"), new ArrayList<>(pairs));
        Set<String> severities = new TreeSet<>();
        found.stream().filter(f -> f.ruleId().equals("r2")).forEach(f -> severities.add(f.severity().value()));
        assertEquals(Set.of("medium", "critical"), severities);
        Finding first = found.stream().filter(f -> f.message().equals("one")).findFirst().orElseThrow();
        assertEquals("a.java", first.file());
        assertEquals(3, first.line());
        assertEquals(List.of(new Evidence("a.java", 3, null)), first.evidence());

        List<Finding> twice = new ArrayList<>(found);
        twice.addAll(found);
        Map<String, String> severity = new HashMap<>(Map.of("r2", "low"));
        List<Finding> out = Findings.applyConfig(twice, Set.of("r1"), severity);
        assertEquals(List.of("r2/low", "r2/low"), out.stream().map(f -> f.ruleId() + "/" + f.severity().value()).toList());
    }

    @Test
    void textHitsBecomeFindings() {
        Packs.Rule r = new Packs.Rule("p", "low", "c", "found {match}", null, null, null,
                new Packs.TextPattern("x", false, false, List.of(), null, null, 20));
        List<Finding> found = Findings.evaluate(List.of(r), name -> List.of(), List.of(new Findings.Hit("a.sh", "p", 4, "x=1")));
        assertEquals(1, found.size());
        assertEquals(List.of("a.sh", 4, "found x=1"), List.of(found.get(0).file(), found.get(0).line(), found.get(0).message()));
    }

    @Test
    void pythonStrings() {
        assertEquals("('a.java', 3, None)", Py.repr(java.util.Arrays.asList("a.java", 3L, null), true));
        assertEquals("[\"it's\", True, 1.5]", Py.repr(List.of("it's", true, 1.5), false));
        assertEquals("2.0", Py.str(2.0));
    }
}
