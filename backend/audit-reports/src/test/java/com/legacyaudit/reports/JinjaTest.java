package com.legacyaudit.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.legacyaudit.reports.jinja.Environment;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The template engine renders like Jinja2 on cases recorded from Python's Jinja2 ({@code jinja/cases.json}). */
class JinjaTest {

    @Test
    @SuppressWarnings("unchecked")
    void rendersLikeJinja2() throws Exception {
        List<Map<String, Object>> cases;
        try (InputStream in = JinjaTest.class.getResourceAsStream("/jinja/cases.json")) {
            cases = JsonMapper.builder().build().readValue(in, List.class);
        }
        List<String> differ = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            String got;
            try {
                got = new Environment("none").fromString((String) c.get("template")).render((Map<String, Object>) c.get("context"));
            } catch (RuntimeException e) {
                got = "ERROR " + e;
            }
            if (!got.equals(c.get("expected"))) {
                differ.add(c.get("template") + "\n  expected: " + c.get("expected") + "\n  got:      " + got);
            }
        }
        assertEquals("", String.join("\n", differ));
    }
}
