package com.legacyaudit.engine.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.legacyaudit.engine.inventory.Encodings;
import com.legacyaudit.engine.inventory.GitIgnore;
import com.legacyaudit.engine.inventory.Languages;
import com.legacyaudit.engine.inventory.Loc;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ignore rules, languages, categories, line counts and encodings give what the Python tool gave
 * for the same input ({@code parity/rules.json}, made from office-main).
 */
class RulesParityTest {

    private static JsonNode cases() throws Exception {
        try (InputStream in = RulesParityTest.class.getResourceAsStream("/parity/rules.json")) {
            return JsonMapper.builder().build().readTree(in);
        }
    }

    @Test
    void ignorePatternsSelectTheSameFiles() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (JsonNode c : cases().get("gitignore")) {
            List<String> patterns = new ArrayList<>();
            c.get("patterns").forEach(p -> patterns.add(p.asString()));
            GitIgnore spec = GitIgnore.of(patterns);
            for (Map.Entry<String, JsonNode> e : c.get("matches").properties()) {
                if (spec.matches(e.getKey()) != e.getValue().asBoolean()) {
                    wrong.add(patterns + " " + e.getKey() + " expected " + e.getValue().asBoolean());
                }
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void languagesAndCategoriesMatch() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (JsonNode c : cases().get("languages")) {
            String path = c.get("path").asString();
            String head = c.get("head").asString();
            String lang = Languages.detect(path, head);
            String cat = Languages.category(path, lang, head).value();
            String bin = Languages.detect(path, null);
            if (!lang.equals(c.get("language").asString()) || !cat.equals(c.get("category").asString())
                    || !bin.equals(c.get("binary_language").asString())) {
                wrong.add(path + ": " + lang + "/" + cat + "/" + bin + " expected " + c.get("language").asString() + "/"
                        + c.get("category").asString() + "/" + c.get("binary_language").asString());
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void linesAreCountedTheSameWay() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (JsonNode c : cases().get("loc")) {
            Loc loc = Loc.count(c.get("text").asString(), c.get("language").asString());
            String got = loc.code() + "," + loc.comment() + "," + loc.blank();
            JsonNode e = c.get("loc");
            String want = e.get(0).asInt() + "," + e.get(1).asInt() + "," + e.get(2).asInt();
            if (!got.equals(want)) {
                wrong.add(c.get("language").asString() + ": " + got + " expected " + want);
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void encodingsAreNamedTheSameWay() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (JsonNode c : cases().get("encodings")) {
            byte[] data = new byte[c.get("bytes").size()];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) c.get("bytes").get(i).asInt();
            }
            String got = Encodings.detect(data);
            if (!got.equals(c.get("encoding").asString())) {
                wrong.add(c.get("name").asString() + ": " + got + " expected " + c.get("encoding").asString());
            }
        }
        assertEquals(List.of(), wrong);
    }
}
