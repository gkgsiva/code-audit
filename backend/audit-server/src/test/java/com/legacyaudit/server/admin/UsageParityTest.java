package com.legacyaudit.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The usage dashboard figures equal the Python hosted site's {@code metrics.usage} on 400
 * recorded activity entries for three periods ({@code admin/usage.json}).
 */
class UsageParityTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @SuppressWarnings("unchecked")
    void sameFiguresAsPython() throws Exception {
        JsonNode data;
        try (InputStream in = getClass().getResourceAsStream("/admin/usage.json")) {
            data = JSON.readTree(in);
        }
        List<Map<String, Object>> entries = JSON.convertValue(data.get("entries"), List.class);
        for (JsonNode c : data.get("cases")) {
            Map<String, Object> actual = Usage.compute(entries, c.get("days").asInt(), 2, 2, 1, LocalDate.parse(c.get("today").asString()));
            JsonNode got = JSON.readTree(JSON.writeValueAsString(actual));
            assertThat(normalize(got)).as("%s days to %s", c.get("days"), c.get("today")).isEqualTo(normalize(c.get("expected")));
        }
    }

    /** Numbers compared as doubles (Python writes 12 and 12.0 where Java writes one of them). */
    static Object normalize(JsonNode n) {
        if (n.isNumber()) {
            return n.asDouble();
        }
        if (n.isArray()) {
            return n.valueStream().map(UsageParityTest::normalize).toList();
        }
        if (n.isObject()) {
            Map<String, Object> m = new java.util.TreeMap<>();
            n.propertyStream().forEach(e -> m.put(e.getKey(), normalize(e.getValue())));
            return m;
        }
        return n.isNull() ? "null" : n.asString();
    }
}
