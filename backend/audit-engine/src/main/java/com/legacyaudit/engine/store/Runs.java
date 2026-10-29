package com.legacyaudit.engine.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Run folders: {@code <output>/runs/<run-id>/} plus a {@code latest} pointer. {@code latest} is a
 * symbolic link to the newest run where the system allows it, otherwise a copy of the run folder
 * (Windows without developer mode). {@code runs/LATEST} always holds the newest run id, and is what
 * the tool itself reads; {@code runs/HISTORY} lists run ids in completion order.
 */
public final class Runs {

    public static final String LATEST_FILE = "LATEST";
    public static final String HISTORY_FILE = "HISTORY";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** No run matches what was asked for. */
    public static final class RunNotFound extends RuntimeException {
        public RunNotFound(String message) {
            super(message);
        }
    }

    private Runs() {}

    public static Path runsDir(Path outputDir) {
        return outputDir.resolve("runs");
    }

    /** {@code <yyyyMMdd-HHmmss>-<6 hex>}, unique in {@code runsDir}. */
    public static String newRunId(String seed, OffsetDateTime now, Path runsDir) {
        String stamp = now.format(STAMP);
        for (int n = 0;; n++) {
            String shortHash = sha256(seed + "|" + now + "|" + n).substring(0, 6);
            String runId = stamp + "-" + shortHash;
            if (!Files.exists(runsDir.resolve(runId))) {
                return runId;
            }
        }
    }

    /** Completed runs (those with a run.json), oldest first. */
    public static List<String> list(Path outputDir) {
        Path d = runsDir(outputDir);
        if (!Files.isDirectory(d)) {
            return List.of();
        }
        Set<String> present = new TreeSet<>();
        try (Stream<Path> kids = Files.list(d)) {
            kids.filter(p -> Files.isDirectory(p) && Files.exists(p.resolve("run.json")))
                    .forEach(p -> present.add(p.getFileName().toString()));
        } catch (IOException e) {
            return List.of();
        }
        List<String> history = new ArrayList<>();
        Path h = d.resolve(HISTORY_FILE);
        if (Files.exists(h)) {
            try {
                for (String id : Files.readString(h, StandardCharsets.UTF_8).split("\\s+")) {
                    if (!id.isEmpty()) {
                        history.add(id);
                    }
                }
            } catch (IOException e) {
                // an unreadable history only loses the order
            }
        }
        List<String> ordered = new ArrayList<>();
        for (String id : new LinkedHashSet<>(history)) {
            if (present.contains(id)) {
                ordered.add(id);
            }
        }
        List<String> out = new ArrayList<>(present);
        out.removeAll(ordered);
        out.addAll(ordered);
        return out;
    }

    /** Records {@code runId} as the newest run and points {@code latest} at it. */
    public static void setLatest(Path outputDir, String runId) throws IOException {
        Path rd = runsDir(outputDir);
        Files.writeString(rd.resolve(LATEST_FILE), runId + "\n", StandardCharsets.UTF_8);
        Files.writeString(rd.resolve(HISTORY_FILE), runId + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Path link = outputDir.resolve("latest");
        if (Files.isSymbolicLink(link) || Files.isRegularFile(link)) {
            Files.delete(link);
        } else if (Files.isDirectory(link)) {
            deleteTree(link);
        }
        try {
            Files.createSymbolicLink(link, Path.of("runs", runId));
        } catch (IOException | UnsupportedOperationException e) {
            copyTree(rd.resolve(runId), link);
        }
    }

    /** A run folder from an id (or its start), {@code latest}, {@code previous} or a path. */
    public static Path resolve(Path outputDir, String ref) {
        Path p = Path.of(ref);
        if (Files.isDirectory(p) && Files.exists(p.resolve("run.json"))) {
            return p;
        }
        List<String> runs = list(outputDir);
        Path rd = runsDir(outputDir);
        if (ref.equals("latest")) {
            String runId = "";
            try {
                Path latest = rd.resolve(LATEST_FILE);
                runId = Files.exists(latest) ? Files.readString(latest, StandardCharsets.UTF_8).strip() : "";
            } catch (IOException e) {
                runId = "";
            }
            if (runId.isEmpty() && !runs.isEmpty()) {
                runId = runs.getLast();
            }
            if (!runId.isEmpty()) {
                return rd.resolve(runId);
            }
        } else if (ref.equals("previous")) {
            if (runs.size() >= 2) {
                return rd.resolve(runs.get(runs.size() - 2));
            }
        } else {
            List<String> matches = runs.stream().filter(r -> r.equals(ref) || r.startsWith(ref)).toList();
            if (matches.size() == 1) {
                return rd.resolve(matches.getFirst());
            }
            if (matches.size() > 1) {
                throw new RunNotFound("Run id '" + ref + "' is ambiguous: " + String.join(", ", matches));
            }
        }
        throw new RunNotFound("No run '" + ref + "' in " + rd);
    }

    public static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> all = Files.walk(dir)) {
            for (Path p : all.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            // ignore_errors=True: what cannot be removed stays
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> all = Files.walk(from)) {
            for (Path p : all.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
