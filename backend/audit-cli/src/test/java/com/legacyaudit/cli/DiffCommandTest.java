package com.legacyaudit.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiffCommandTest {

    private static final Path SAMPLE = Path.of("..", "..", "samples", "maven-multimodule-app", "audit.yaml");

    @Test
    void twoScansOfTheSameCodeHaveNoChanges(@TempDir Path tmp) throws Exception {
        String out = tmp.resolve("out").toString();
        assertEquals(0, Main.run("scan", "-c", SAMPLE.toString(), "-o", out));
        assertEquals(0, Main.run("scan", "-c", SAMPLE.toString(), "-o", out));
        Path json = tmp.resolve("diff.json");
        assertEquals(0, Main.run("diff", "previous", "latest", "-o", out, "--json", json.toString()));
        String text = Files.readString(json);
        assertTrue(text.contains("\"changed\": []"));
        assertTrue(!text.contains("\"changed\": [\n"));
        assertEquals(1, Main.run("diff", "no-such-run", "-o", out));
    }
}
