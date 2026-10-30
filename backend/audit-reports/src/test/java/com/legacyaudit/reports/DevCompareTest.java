package com.legacyaudit.reports;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Builds the Java reports next to Python reference reports (REPORT_REF folder) for a manual diff. */
@EnabledIfEnvironmentVariable(named = "REPORT_REF", matches = ".+")
class DevCompareTest {

    @Test
    void build() throws Exception {
        Path ref = Path.of(System.getenv("REPORT_REF"));
        Path samples = Path.of(System.getenv("REPORT_SAMPLES"));
        String only = System.getenv().getOrDefault("REPORT_ONLY", "");
        try (Stream<Path> dirs = Files.list(ref)) {
            for (Path sample : dirs.sorted().toList()) {
                if (!only.isEmpty() && !sample.getFileName().toString().equals(only)) {
                    continue;
                }
                Path run;
                try (Stream<Path> runs = Files.list(sample.resolve("runs"))) {
                    run = runs.sorted().toList().getLast();
                }
                RunData rd = RunData.load(run, samples.resolve(sample.getFileName()).resolve("audit-inputs").toAbsolutePath());
                TechReport.buildInto(rd, run.resolve("reports-java").resolve("tech"));
                FunctionalReport.buildInto(rd, run.resolve("reports-java").resolve("functional"));
                SummaryReport.buildInto(rd, run.resolve("reports-java").resolve("summary"), java.util.Set.of());
                MgmtReport.buildInto(rd, run.resolve("reports-java").resolve("mgmt"), null, null);
                System.out.println(sample.getFileName() + " built");
            }
        }
    }
}
