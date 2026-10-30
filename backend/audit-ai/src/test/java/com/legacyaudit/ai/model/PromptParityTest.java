package com.legacyaudit.ai.model;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.legacyaudit.ai.enrich.ValidateParityTest;
import com.legacyaudit.engine.enrich.Stages;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The prompts {@code enrich} sends are the Python tool's, character for character
 * ({@code prompt-parity/expected.json} holds their SHA-256 and length): every stage of eight
 * fixture runs, with the default budget and with a small one that cuts lists and sources. A prompt
 * that differs is written to {@code target/prompt-parity-actual/}.
 */
class PromptParityTest {

    private static String sha(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void promptsMatchThePythonTool() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, List<Object>> expected = JsonMapper.builder().build()
                .readValue(Files.readString(Path.of("src", "test", "resources", "prompt-parity", "expected.json")), Map.class);
        Set<String> sets = new LinkedHashSet<>();
        expected.keySet().forEach(k -> sets.add(k.substring(0, k.indexOf('|'))));
        List<String> differ = new ArrayList<>();
        for (String set : sets) {
            Path runDir = ValidateParityTest.unpack(set, ValidateParityTest.fresh(Path.of("target", "prompt-parity-runs", set.replace('/', '-'))));
            for (int budget : new int[] {150000, 10000}) {
                for (Stages.Stage st : Stages.STAGES) {
                    String key = set + "|" + budget + "|" + st.name();
                    PromptContext.Prompts p = PromptContext.build(runDir, st, budget, null);
                    List<Object> want = expected.get(key);
                    if (!sha(p.system()).equals(want.get(0)) || !sha(p.user()).equals(want.get(1))) {
                        differ.add(key + " (length " + PromptContext.len(p.user()) + ", Python " + want.get(2) + ")");
                        Path keep = Path.of("target", "prompt-parity-actual", key.replace('/', '_').replace('|', '_'), "user.txt");
                        Files.createDirectories(keep.getParent());
                        Files.writeString(keep, p.user(), StandardCharsets.UTF_8);
                    }
                }
            }
        }
        assertTrue(differ.isEmpty(), differ.size() + " prompt(s) differ: " + differ);
    }
}
