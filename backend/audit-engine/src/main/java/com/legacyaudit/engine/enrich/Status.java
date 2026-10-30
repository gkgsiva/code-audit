package com.legacyaudit.engine.enrich;

import com.legacyaudit.model.config.AuditConfig;
import com.legacyaudit.model.json.PyJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Enrichment staleness and the files of a run's {@code enrichment/} folder.
 *
 * <p>Each stage file records in {@code meta.built_from} the hash of every fact file and upstream
 * stage it was built from (stamped by {@code legacy-audit validate}). After a re-scan the new run's
 * hashes are compared with those: a stage whose inputs changed, or whose upstream stage is stale,
 * is stale.
 *
 * <p>Where {@code latest} is a copy rather than a symbolic link (Windows without developer mode),
 * the agent writes into {@code latest/enrichment/}; {@link #syncFromLatest} brings those files into
 * the run folder first so nothing written there is lost.
 */
public final class Status {

    public static final String ENRICHMENT = "enrichment";
    public static final String STATUS_FILE = "status.json";
    static final String MISSING = "missing";
    static final String TARGET_INPUT = "target";
    private static final JsonMapper JSON = JsonMapper.builder().enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS).build();

    private Status() {}

    public static Path enrichmentDir(Path runDir) {
        return runDir.resolve(ENRICHMENT);
    }

    static String sha(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code json.loads} of a file. */
    public static Object read(Path path) throws IOException {
        return JSON.readValue(Files.readString(path, StandardCharsets.UTF_8), Object.class);
    }

    /** Hash of a stage file's content without its {@code meta} (so stamping does not change it). */
    public static String stageHash(Path path) {
        if (!Files.exists(path)) {
            return MISSING;
        }
        Object data;
        try {
            data = read(path);
        } catch (IOException | RuntimeException e) {
            try {
                return sha(Files.readAllBytes(path));
            } catch (IOException io) {
                throw new UncheckedIOException(io);
            }
        }
        if (data instanceof Map<?, ?> m) {
            Map<Object, Object> copy = new LinkedHashMap<>(m);
            copy.remove("meta");
            data = copy;
        }
        return sha(PyJson.compact(data).getBytes(StandardCharsets.UTF_8));
    }

    /** {@code data/target.json} of a run; the default target when it is missing or unreadable. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> target(Path runDir) {
        try {
            Object data = read(runDir.resolve("data").resolve("target.json"));
            if (data instanceof Map<?, ?> m && List.of(AuditConfig.Target.DEFAULT, AuditConfig.Target.WILDFLY, AuditConfig.Target.NONE)
                    .contains(m.get("profile"))) {
                return (Map<String, Object>) m;
            }
        } catch (IOException | RuntimeException e) {
            // the default target
        }
        return AuditConfig.Target.defaults().info();
    }

    /**
     * The stages this run's target needs: all of them, or without the target stages when there is
     * no target (audit only, {@code data/target.json}).
     */
    public static List<Stages.Stage> activeStages(Path runDir) {
        Object skipped = target(runDir).get("skipped_stages");
        List<Stages.Stage> out = new ArrayList<>();
        for (Stages.Stage s : Stages.STAGES) {
            if (!(skipped instanceof List<?> l && l.contains(s.name()))) {
                out.add(s);
            }
        }
        return out;
    }

    public static Map<String, String> currentHashes(Path runDir, Stages.Stage stage) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String kind : stage.inputs()) {
            Path p = runDir.resolve("data").resolve(Stages.factFile(kind));
            try {
                out.put(kind, Files.exists(p) ? sha(Files.readAllBytes(p)) : MISSING);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        for (String up : stage.after()) {
            out.put("stage:" + up, stageHash(enrichmentDir(runDir).resolve(Stages.BY_NAME.get(up).file())));
        }
        return out;
    }

    private static boolean changed(String key, Map<?, ?> was, Map<String, String> now, Path runDir) {
        if (key.equals(TARGET_INPUT) && !was.containsKey(key)) {
            // Built before the target was recorded: it was built for the default target.
            return !AuditConfig.Target.DEFAULT.equals(target(runDir).get("profile"));
        }
        return !now.get(key).equals(was.get(key));
    }

    /** Present / stale per stage, as {@code enrichment/status.json} lists it. */
    public static Map<String, Object> compute(Path runDir) {
        Path edir = enrichmentDir(runDir);
        Map<String, Map<String, Object>> stages = new LinkedHashMap<>();
        for (Stages.Stage st : activeStages(runDir)) {
            Path path = edir.resolve(st.file());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("number", (long) st.number());
            entry.put("file", st.file());
            entry.put("present", Files.exists(path));
            if (Files.exists(path)) {
                Object built = null;
                try {
                    Object data = read(path);
                    if (data instanceof Map<?, ?> m && m.get("meta") instanceof Map<?, ?> meta) {
                        built = meta.get("built_from");
                    } else if (!(data instanceof Map<?, ?>)) {
                        built = null;
                    }
                } catch (IOException | RuntimeException e) {
                    built = null;
                }
                if (!(built instanceof Map<?, ?> b) || b.isEmpty()) {
                    entry.put("stale", false);
                    entry.put("validated", false);
                    entry.put("changed", new ArrayList<>());
                } else {
                    Map<String, String> now = currentHashes(runDir, st);
                    Map<?, ?> was = b.get("facts_hash_by_input") instanceof Map<?, ?> w ? w : Map.of();
                    List<String> changedKeys = new ArrayList<>();
                    for (String k : now.keySet()) {
                        if (changed(k, was, now, runDir)) {
                            changedKeys.add(k);
                        }
                    }
                    changedKeys.sort(PyJson::compareCodePoints);
                    List<String> upstream = new ArrayList<>();
                    for (String u : st.after()) {
                        if (Boolean.TRUE.equals(stages.getOrDefault(u, Map.of()).get("stale"))) {
                            upstream.add(u);
                        }
                    }
                    entry.put("validated", true);
                    entry.put("built_from_run", b.get("run_id"));
                    entry.put("changed", changedKeys);
                    entry.put("stale_upstream", upstream);
                    entry.put("stale", !changedKeys.isEmpty() || !upstream.isEmpty());
                }
            }
            stages.put(st.name(), entry);
        }
        Object run = null;
        try {
            if (Files.exists(runDir.resolve("run.json"))) {
                run = read(runDir.resolve("run.json"));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> stale = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        stages.forEach((n, e) -> {
            if (Boolean.TRUE.equals(e.get("stale"))) {
                stale.add(n);
            }
            if (!Boolean.TRUE.equals(e.get("present"))) {
                missing.add(n);
            }
        });
        Object overrides = run instanceof Map<?, ?> r ? r.get("overrides") : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", runDir.getFileName().toString());
        out.put("stages", stages);
        out.put("stale", stale);
        out.put("missing", missing);
        out.put("overrides", overrides instanceof Map<?, ?> o && !o.isEmpty() ? o : new LinkedHashMap<>());
        return out;
    }

    /** {@code <output>/latest} when it is a plain copy of this run, else null. */
    public static Path latestCopy(Path runDir) {
        Path parent = runDir.toAbsolutePath().normalize().getParent();
        if (parent == null || parent.getFileName() == null || !parent.getFileName().toString().equals("runs")) {
            return null;
        }
        Path latest = parent.getParent().resolve("latest");
        if (Files.isSymbolicLink(latest) || !Files.isDirectory(latest)) {
            return null;
        }
        try {
            Object run = read(latest.resolve("run.json"));
            Object id = run instanceof Map<?, ?> m ? m.get("run_id") : null;
            return runDir.getFileName().toString().equals(id) ? latest : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Bring files the agent wrote into a copied {@code latest/enrichment} into the run folder. */
    public static void syncFromLatest(Path runDir) {
        Path latest = latestCopy(runDir);
        if (latest != null && Files.isDirectory(latest.resolve(ENRICHMENT))) {
            copyTree(latest.resolve(ENRICHMENT), enrichmentDir(runDir), null);
        }
    }

    /** Copy a folder into another, overwriting files ({@code shutil.copytree(dirs_exist_ok=True)}). */
    static void copyTree(Path src, Path dest, String skipName) {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path rel = src.relativize(p);
                if (skipName != null && p.getFileName().toString().equals(skipName)) {
                    continue;
                }
                Path target = dest.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Stage name to parsed file, for every stage file present (validated or not). With no target
     * (audit only), files of the target stages left by an earlier run are not used.
     */
    public static Map<String, Object> loadEnrichment(Path runDir) {
        syncFromLatest(runDir);
        Map<String, Object> out = new LinkedHashMap<>();
        Path edir = enrichmentDir(runDir);
        for (Stages.Stage stage : activeStages(runDir)) {
            Path p = edir.resolve(stage.file());
            if (Files.exists(p)) {
                try {
                    out.put(stage.name(), read(p));
                } catch (IOException | RuntimeException e) {
                    // unreadable stage file: left out
                }
            }
        }
        return out;
    }
}
