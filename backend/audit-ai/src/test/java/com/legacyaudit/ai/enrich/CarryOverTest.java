package com.legacyaudit.ai.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.legacyaudit.engine.enrich.Status;
import com.legacyaudit.engine.store.Runs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A new scan takes over the previous run's enrichment and marks what its new facts made stale;
 * enrichment written into a copied {@code latest} (Windows without symbolic links) is kept.
 */
class CarryOverTest {

    @Test
    void enrichmentIsCarriedAndMarkedStale() throws Exception {
        Path out = ValidateParityTest.fresh(Path.of("target", "carry-over"));
        Path previous = ValidateParityTest.unpack("enriched/struts-oracle-app", out);
        Validator.validate(previous, null, false); // stamps every stage
        Files.writeString(Runs.runsDir(out).resolve(Runs.LATEST_FILE), previous.getFileName() + "\n");
        assertEquals(previous, Runs.previousRun(out));

        // the new run: the same facts, one more library
        Path next = Runs.runsDir(out).resolve("20991231-000000-000000");
        Files.createDirectories(next.resolve("data"));
        try (var files = Files.list(previous.resolve("data"))) {
            for (Path f : files.toList()) {
                Files.copy(f, next.resolve("data").resolve(f.getFileName()));
            }
        }
        Files.copy(previous.resolve("run.json"), next.resolve("run.json"));
        String libs = Files.readString(next.resolve("data/libraries.json"));
        Files.writeString(next.resolve("data/libraries.json"), libs.replaceFirst("\\[", "[{\"id\": \"lib:new\"}, "));

        Map<String, Object> status = Status.carryOver(previous, next);
        assertTrue(Files.isRegularFile(next.resolve("enrichment/plan.json")));
        assertEquals(List.of("overview", "dependency_plan", "risks", "plan", "narratives", "component_docs"), status.get("stale"));
        assertEquals(List.of(), status.get("missing"));
        assertTrue(Files.readString(next.resolve("enrichment/status.json")).contains("\"stale\""));

        assertNull(Status.carryOver(null, next));
        assertNull(Status.carryOver(out.resolve("nowhere"), next));
    }

    @Test
    void filesWrittenIntoACopiedLatestAreKept() throws Exception {
        Path out = ValidateParityTest.fresh(Path.of("target", "carry-latest"));
        Path run = ValidateParityTest.unpack("enriched/struts-oracle-app", out);
        Path latest = out.resolve("latest");
        Files.createDirectories(latest.resolve("enrichment"));
        Files.copy(run.resolve("run.json"), latest.resolve("run.json"));
        String overview = Files.readString(run.resolve("enrichment/overview.json"));
        Files.writeString(latest.resolve("enrichment/overview.json"), overview.replace("\"purpose\": \"", "\"purpose\": \"Edited. "));
        Validator.Report rep = Validator.validate(run, List.of("overview"), false);
        assertTrue(rep.ok(), rep.messages.toString());
        assertTrue(Files.readString(run.resolve("enrichment/overview.json")).contains("Edited. "));
        assertTrue(Files.readString(latest.resolve("enrichment/overview.json")).contains("built_from"));
    }
}
