package com.legacyaudit.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The four HTML reports built from a sample run match, file by file, what the Python tool built
 * from the same run ({@code report-parity/<set>/<sample>/}, see its README).
 */
class ReportParityTest {

    private static final Path FIXTURES = Path.of("src", "test", "resources", "report-parity");
    private static final Path SAMPLES = Path.of("..", "..", "samples");
    private static final Set<String> TEXT = Set.of(".html", ".js", ".csv", ".json", ".md", ".css", ".txt", ".svg");

    static Stream<String> sets() throws IOException {
        List<String> out = new ArrayList<>();
        try (var sets = Files.list(FIXTURES)) {
            for (Path set : sets.filter(Files::isDirectory).sorted().toList()) {
                try (var samples = Files.list(set)) {
                    samples.filter(Files::isDirectory).sorted().forEach(s -> out.add(set.getFileName() + "/" + s.getFileName()));
                }
            }
        }
        return out.stream();
    }

    /** The files of a zip: name to bytes. */
    static Map<String, byte[]> unzip(Path zip) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        try (InputStream in = Files.newInputStream(zip); ZipInputStream z = new ZipInputStream(in)) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                if (!e.isDirectory()) {
                    out.put(e.getName(), z.readAllBytes());
                }
            }
        }
        return out;
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    /** The sample folder written as the fixtures write it. */
    static String normalize(String text, String sample, String samplePath) {
        String out = Pattern.compile("(?:\\.\\./)+(?:[^\"'<>\\s/]+/)*?samples/" + Pattern.quote(sample) + "(?=[/\"'<>\\s]|$)")
                .matcher(text).replaceAll("@REL@");
        return out.replace(samplePath, "@SAMPLE@");
    }

    @ParameterizedTest
    @MethodSource("sets")
    void reportsMatchThePythonTool(String set, @TempDir Path tmp) throws Exception {
        String sample = set.substring(set.indexOf('/') + 1);
        Path sampleDir = SAMPLES.resolve(sample).toAbsolutePath().normalize();
        String samplePath = sampleDir.toString().replace('\\', '/');
        Path runs = tmp.resolve(sample).resolve("runs");
        String runId = null;
        for (Map.Entry<String, byte[]> f : unzip(FIXTURES.resolve(set).resolve("run.zip")).entrySet()) {
            Path target = runs.resolve(f.getKey());
            Files.createDirectories(target.getParent());
            byte[] data = f.getValue();
            if (f.getKey().endsWith("/run.json")) {
                data = new String(data, StandardCharsets.UTF_8).replace("@SAMPLE@", samplePath).getBytes(StandardCharsets.UTF_8);
            }
            Files.write(target, data);
            runId = f.getKey().substring(0, f.getKey().indexOf('/'));
        }
        Path run = runs.resolve(runId);
        RunData rd = RunData.load(run, sampleDir.resolve("audit-inputs"));
        Path reports = run.resolve("reports");
        TechReport.buildInto(rd, reports.resolve("tech"));
        FunctionalReport.buildInto(rd, reports.resolve("functional"));
        MgmtReport.buildInto(rd, reports.resolve("mgmt"), null, null);
        SummaryReport.buildInto(rd, reports.resolve("summary"), Set.of());

        Map<String, byte[]> expected = unzip(FIXTURES.resolve(set).resolve("expected.zip"));
        Map<String, byte[]> actual = new TreeMap<>();
        try (var walk = Files.walk(reports)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = reports.relativize(p).toString().replace('\\', '/');
                if (!List.of(rel.split("/")).contains("assets")) {
                    actual.put(rel, Files.readAllBytes(p));
                }
            }
        }
        assertEquals(expected.keySet(), actual.keySet(), set + ": report files");
        List<String> differ = new ArrayList<>();
        for (String name : expected.keySet()) {
            byte[] want = expected.get(name);
            byte[] got = actual.get(name);
            if (TEXT.contains(extension(name))) {
                String w = new String(want, StandardCharsets.UTF_8);
                String g = normalize(new String(got, StandardCharsets.UTF_8), sample, samplePath);
                if (!w.equals(g)) {
                    differ.add(name);
                    Path keep = Path.of("target", "report-parity-actual", set, name); // for a diff against expected.zip
                    Files.createDirectories(keep.getParent());
                    Files.writeString(keep, g, StandardCharsets.UTF_8);
                }
            } else if (!java.util.Arrays.equals(want, got)) {
                differ.add(name);
            }
        }
        assertTrue(differ.isEmpty(), set + ": differ from the Python reports: " + differ);
    }
}
