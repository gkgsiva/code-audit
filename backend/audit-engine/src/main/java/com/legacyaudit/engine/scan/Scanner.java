package com.legacyaudit.engine.scan;

import com.legacyaudit.engine.analyzers.Analyzer;
import com.legacyaudit.engine.analyzers.AnalyzerContext;
import com.legacyaudit.engine.analyzers.Analyzers;
import com.legacyaudit.engine.analyzers.Coverage;
import com.legacyaudit.engine.analyzers.Facts;
import com.legacyaudit.engine.components.ComponentDetector;
import com.legacyaudit.engine.components.ComponentOverrides;
import com.legacyaudit.engine.inventory.FileAnalyzer;
import com.legacyaudit.engine.inventory.FileCache;
import com.legacyaudit.engine.inventory.InputCoverages;
import com.legacyaudit.engine.inventory.Walker;
import com.legacyaudit.engine.inventory.Walker.Filters;
import com.legacyaudit.engine.inventory.Walker.Skipped;
import com.legacyaudit.engine.inventory.Walker.Walked;
import com.legacyaudit.engine.store.FactWriter;
import com.legacyaudit.engine.store.FactWriter.Entity;
import com.legacyaudit.engine.store.Runs;
import com.legacyaudit.engine.store.ScanLock;
import com.legacyaudit.model.AppInfo;
import com.legacyaudit.model.config.AuditConfig;
import com.legacyaudit.model.config.AuditConfig.GitSource;
import com.legacyaudit.model.config.LoadedConfig;
import com.legacyaudit.model.config.Overrides;
import com.legacyaudit.model.facts.Component;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Finding;
import com.legacyaudit.model.facts.InputCoverage;
import com.legacyaudit.model.facts.Origin;
import com.legacyaudit.model.facts.RunInfo;
import com.legacyaudit.model.facts.Severity;
import com.legacyaudit.model.json.PyJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

/**
 * The scan pipeline: sources -> inventory -> components -> overrides -> coverage -> export, into a
 * new run folder {@code <output>/runs/<run-id>/}. Later phases add the analyzers, linking, metrics
 * and assessment between components and export.
 */
public final class Scanner {

    public static final String INPUTS_ROOT = "audit-inputs";

    /** Installed by {@code init} in a project folder, never scanned. */
    private static final List<String> COPILOT_PATHS = List.of(
            ".github/agents", ".github/chatmodes", ".github/prompts", ".github/legacy-audit",
            ".github/copilot-instructions.md", ".github/copilot-instructions.md.new");

    private static final List<String> FILE_COLUMNS = List.of("id", "evidence", "path", "root", "origin", "component", "language",
            "category", "size", "sha256", "encoding", "loc_code", "loc_comment", "loc_blank");
    private static final List<String> COMPONENT_COLUMNS = List.of("id", "evidence", "name", "type", "root", "path", "build_files",
            "technology", "roles", "composition", "files", "loc_code", "entry_points", "detected_by", "build_group", "artifact",
            "packaging", "parts");
    private static final List<String> FINDING_COLUMNS =
            List.of("id", "evidence", "rule_id", "severity", "category", "file", "line", "message", "remediation");
    private static final List<String> COVERAGE_COLUMNS =
            List.of("id", "evidence", "kind", "label", "status", "path", "file_count");

    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Where a scan is: stage name, items done, items in the stage (0 when unknown). */
    @FunctionalInterface
    public interface Progress {
        void update(String stage, int done, int total);
    }

    /** The scan was cancelled; its unfinished run folder is removed. */
    public static final class Cancelled extends RuntimeException {
        public Cancelled() {
            super("The scan was cancelled");
        }
    }

    /** What a scan wrote. */
    public record Result(Path runDir, RunInfo run, List<FileRecord> inventory, List<Component> components, List<Finding> findings,
            List<InputCoverage> coverage, List<FileFacts> fileFacts) {}

    /** What one analyzer found in one file. */
    public record FileFacts(String analyzer, String path, Facts facts) {}

    private record Analysed(FileAnalyzer.Result result, String payload) {}

    /** Options of one scan; every field may be left null. */
    public record Options(boolean full, Path outputDir, Progress progress, AtomicBoolean cancel, Consumer<String> logListener) {

        public static Options defaults() {
            return new Options(false, null, null, null, null);
        }
    }

    private Scanner() {}

    /**
     * Scans into a new run folder. Throws {@link ScanLock.ScanBusy} when another scan holds the
     * output folder and {@link Cancelled} when {@code cancel} is set.
     */
    public static Result run(LoadedConfig loaded, Options options) throws IOException {
        Path outDir = (options.outputDir() != null ? options.outputDir() : loaded.outputDir()).toAbsolutePath().normalize();
        Path rd = Runs.runsDir(outDir);
        try (ScanLock lock = ScanLock.acquire(rd)) {
            cleanInterrupted(rd);
            OffsetDateTime started = OffsetDateTime.now(ZoneOffset.UTC);
            String runId = Runs.newRunId(loaded.hash(), started, rd);
            Path runDir = rd.resolve(runId);
            Files.createDirectories(runDir.resolve("logs"));
            boolean cancelled = false;
            try (ScanLog log = new ScanLog(runDir.resolve("logs").resolve("scan.log"), secrets(loaded), options.logListener())) {
                try {
                    return new Run(loaded, outDir, runId, runDir, started, options, log).run();
                } catch (Cancelled e) {
                    cancelled = true;
                    throw e;
                } catch (Exception e) {
                    // The folder has no run.json, so it is not a run; its log stays until the next scan.
                    log.error("Run " + runId + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    throw e;
                }
            } finally {
                if (cancelled) {
                    Runs.deleteTree(runDir);
                }
            }
        }
    }

    private static List<String> secrets(LoadedConfig loaded) {
        List<String> out = new ArrayList<>();
        if (loaded.config().source() instanceof GitSource g) {
            g.repos().forEach(r -> {
                if (r.tokenEnv() != null) {
                    out.add(System.getenv(r.tokenEnv()));
                }
            });
        }
        return out;
    }

    /** Removes run folders an earlier scan left unfinished (no run.json); its cache is kept. */
    private static void cleanInterrupted(Path rd) throws IOException {
        try (Stream<Path> kids = Files.list(rd)) {
            for (Path d : kids.sorted().toList()) {
                if (Files.isDirectory(d) && !Files.exists(d.resolve("run.json"))) {
                    Runs.deleteTree(d);
                }
            }
        }
    }

    /** Files each analyzer read, for the log and the command line: {@code java 11 files, sql 2 files}. */
    public static String analyzerLine(Map<String, Object> analyzers) {
        List<String> parts = new ArrayList<>();
        analyzers.forEach((name, cov) -> {
            Object files = cov instanceof Map<?, ?> m ? m.get("files") : null;
            parts.add(name + " " + files + (Long.valueOf(1).equals(files) ? " file" : " files"));
        });
        return String.join(", ", parts);
    }

    private static final class Run {
        private final LoadedConfig loaded;
        private final AuditConfig cfg;
        private final Path outDir;
        private final String runId;
        private final Path runDir;
        private final OffsetDateTime started;
        private final Options options;
        private final ScanLog log;
        private final Map<String, Double> timings = new LinkedHashMap<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<FileFacts> fileFacts = new ArrayList<>();
        private final List<Analyzer> analyzers = Analyzers.registered();
        private final List<String> analyzerWarnings = new ArrayList<>();

        Run(LoadedConfig loaded, Path outDir, String runId, Path runDir, OffsetDateTime started, Options options, ScanLog log) {
            this.loaded = loaded;
            this.cfg = loaded.config();
            this.outDir = outDir;
            this.runId = runId;
            this.runDir = runDir;
            this.started = started;
            this.options = options;
            this.log = log;
        }

        private void check() {
            if (options.cancel() != null && options.cancel().get()) {
                throw new Cancelled();
            }
        }

        private void progress(String stage, int done, int total) {
            if (options.progress() != null) {
                options.progress().update(stage, done, total);
            }
        }

        private void stage(String name) {
            check();
            progress(name, 0, 0);
        }

        private static double seconds(long t0) {
            return Math.round((System.nanoTime() - t0) / 1e6) / 1000.0;
        }

        Result run() throws IOException {
            log.info("Run " + runId + " for '" + cfg.application().name() + "' (legacy-audit " + AppInfo.version() + ")");
            long t = System.nanoTime();
            Overrides overrides = Overrides.load(loaded.overridesPath());
            List<Sources.Root> roots = Sources.resolve(loaded);
            timings.put("sources", seconds(t));
            for (Sources.Root r : roots) {
                log.info("Source " + r.name() + ": " + r.path() + (r.commit() != null ? " @ " + r.commit() : ""));
            }

            // ---- Inventory ----------------------------------------------------------------
            t = System.nanoTime();
            List<Path> toolOwned = new ArrayList<>(List.of(outDir, loaded.inputsDir(), loaded.path(), loaded.overridesPath()));
            for (String p : COPILOT_PATHS) {
                toolOwned.add(loaded.baseDir().resolve(p));
            }
            List<String> exclude = new ArrayList<>(cfg.exclude());
            exclude.addAll(overrides.filesExclude());
            overrides.filesInclude().forEach(p -> exclude.add("!" + p));
            boolean multi = roots.size() > 1;
            record Item(Walked file, String path, Origin origin) {}
            List<Item> walked = new ArrayList<>();
            List<Skipped> leftOut = new ArrayList<>();
            AuditConfig.Inventory inv = cfg.inventory();
            for (Sources.Root root : roots) {
                Filters filters = Filters.of(cfg.include(), exclude, inv.useDefaultExcludes(),
                        Walker.anchoredExcludes(root.path(), toolOwned), inv.skipBuildOutput());
                for (Walked wf : Walker.walk(root.name(), root.path(), filters, root.subpaths(), leftOut)) {
                    walked.add(new Item(wf, multi ? root.name() + "/" + wf.rel() : wf.rel(), Origin.SOURCE));
                }
            }
            Path inputsDir = loaded.inputsDir();
            if (Files.isDirectory(inputsDir)) {
                for (Walked wf : Walker.walk(INPUTS_ROOT, inputsDir, Filters.all(), null, null)) {
                    walked.add(new Item(wf, INPUTS_ROOT + "/" + wf.rel(), Origin.INPUT));
                }
            }

            // Settings that change per-file results are part of the cache key.
            String cacheVersion = Analyzers.versionKey(FileAnalyzer.VERSION) + ";binaries=" + inv.includeBinaries() + ";max_mb=" + inv.maxFileSizeMb();
            long maxBytes = (long) (inv.maxFileSizeMb() * 1024 * 1024);
            List<String> unreadable = new ArrayList<>(); // "path\u001fwhy"
            String[] slots = new String[walked.size()]; // cache payload per walked file, in walk order
            int analyzed = 0;
            int cached = 0;
            int skippedBinaries = 0;
            int removed;
            List<FileRecord> records = new ArrayList<>();
            try (FileCache cache = new FileCache(outDir.resolve("cache").resolve("file-cache.sqlite"), cacheVersion)) {
                // Pass 1: what the cache already holds. Pass 2 (worker threads): the rest.
                record Job(int slot, Item item, long size, long mtimeNs, FileCache.Entry entry) {}
                List<Job> todo = new ArrayList<>();
                for (int n = 0; n < walked.size(); n++) {
                    check();
                    progress("inventory", n + 1, walked.size());
                    Item it = walked.get(n);
                    if (FileAnalyzer.skipByExtension(it.file().rel(), inv.includeBinaries())) {
                        skippedBinaries++;
                        continue;
                    }
                    BasicFileAttributes st;
                    try {
                        st = Files.readAttributes(it.file().absPath(), BasicFileAttributes.class);
                    } catch (IOException e) {
                        unreadable.add(it.path() + "\u001f" + why(e));
                        continue;
                    }
                    long mtime = st.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS);
                    FileCache.Entry entry = options.full() ? null : cache.get(it.file().root(), it.path());
                    if (entry != null && entry.size() == st.size() && entry.mtimeNs() == mtime) {
                        cached++;
                        if (entry.payload().contains("\"record\": null")) {
                            skippedBinaries++;
                        }
                        slots[n] = entry.payload();
                        continue;
                    }
                    todo.add(new Job(n, it, st.size(), mtime, entry));
                }

                int workers = inv.workers() > 0 ? inv.workers() : Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() - 1, 8));
                log.info("Analysing " + todo.size() + " file(s) with " + workers + " worker(s)");
                ExecutorService pool = Executors.newFixedThreadPool(workers);
                try {
                    List<Future<Object>> futures = new ArrayList<>();
                    for (Job job : todo) {
                        futures.add(pool.submit(() -> {
                            try {
                                FileAnalyzer.Result res = FileAnalyzer.analyze(job.item().file().absPath(), job.item().path(),
                                        job.item().file().root(), job.item().origin(), inv.includeBinaries(), maxBytes);
                                return new Analysed(res, payload(res, job.item().file().absPath()));
                            } catch (IOException e) {
                                return e;
                            }
                        }));
                    }
                    for (int k = 0; k < todo.size(); k++) {
                        check();
                        progress("analysis", k + 1, todo.size());
                        Job job = todo.get(k);
                        Object done;
                        try {
                            done = futures.get(k).get();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new Cancelled();
                        } catch (ExecutionException e) {
                            done = e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
                        }
                        if (done instanceof IOException e) {
                            unreadable.add(job.item().path() + "\u001f" + why(e));
                            continue;
                        }
                        FileAnalyzer.Result res = ((Analysed) done).result();
                        String sha = res.record() != null ? res.record().sha256 : "";
                        if (job.entry() != null && !sha.isEmpty() && job.entry().sha256().equals(sha)) {
                            // Touched but unchanged: refresh the stat fields, keep the cached analysis.
                            cached++;
                            cache.put(job.item().file().root(), job.item().path(),
                                    new FileCache.Entry(job.size(), job.mtimeNs(), sha, job.entry().payload()));
                            slots[job.slot()] = job.entry().payload();
                            continue;
                        }
                        analyzed++;
                        String text = ((Analysed) done).payload();
                        cache.put(job.item().file().root(), job.item().path(), new FileCache.Entry(job.size(), job.mtimeNs(), sha, text));
                        slots[job.slot()] = text;
                        if (res.record() == null) {
                            skippedBinaries++;
                        }
                        if ((k + 1) % 500 == 0) {
                            cache.commit(); // an interrupted run keeps what it analysed (the next run resumes)
                        }
                    }
                } finally {
                    pool.shutdownNow();
                }
                for (String payload : slots) {
                    if (payload == null) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> p = JSON.readValue(payload, Map.class);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rec = (Map<String, Object>) p.get("record");
                    if (rec != null) { // null: binary skipped by content
                        FileRecord fr = FileRecord.fromMap(rec);
                        records.add(fr);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> facts = (Map<String, Object>) p.getOrDefault("facts", Map.of());
                        facts.forEach((name, data) -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> d = (Map<String, Object>) data;
                            fileFacts.add(new FileFacts(name, fr.path, Facts.load(d)));
                        });
                    }
                }
                removed = cache.prune();
            }
            for (String w : analyzerWarnings) {
                log.warning(w);
            }
            List<Finding> findings = new ArrayList<>(limitFindings(records, unreadable, maxBytes));
            findings.addAll(skippedFindings(leftOut, multi));
            timings.put("inventory", seconds(t));
            log.info("Inventory: " + records.size() + " files, " + analyzed + " analyzed, " + cached + " from cache, "
                    + skippedBinaries + " skipped binaries, " + removed + " removed");

            // ---- Components and overrides -------------------------------------------------------
            stage("components");
            t = System.nanoTime();
            List<FileRecord> sourceRecords = records.stream().filter(r -> r.origin == Origin.SOURCE).toList();
            Map<String, Path> rootPaths = new LinkedHashMap<>();
            roots.forEach(r -> rootPaths.put(r.name(), r.path()));
            ComponentDetector.Result detected = ComponentDetector.detect(sourceRecords, roots.stream().map(Sources.Root::name).toList(),
                    cfg.components(), (root, rel) -> readBuildFile(rootPaths, root, rel), inv.componentGrouping());
            for (FileRecord rec : sourceRecords) {
                rec.component = detected.fileToComponent().get(rec.id);
            }
            Map<String, Object> overrideReport = new LinkedHashMap<>();
            List<Component> components = ComponentOverrides.apply(overrides, sourceRecords, detected.components(), overrideReport, warnings);
            timings.put("components", seconds(t));

            // ---- Coverage, export ------------------------------------------------------------------
            List<InputCoverage> coverage = InputCoverages.of(inputsDir, INPUTS_ROOT);
            for (InputCoverage c : coverage) {
                log.info("Input coverage: " + c.line());
            }
            records.sort(Comparator.comparing((FileRecord r) -> r.origin != Origin.SOURCE).thenComparing(r -> r.path, PyJson::compareCodePoints));

            RunInfo run = new RunInfo();
            run.runId = runId;
            run.toolVersion = AppInfo.version();
            run.startedAt = started.truncatedTo(ChronoUnit.SECONDS).format(ISO_SECONDS);
            run.configPath = loaded.path().toString().replace('\\', '/');
            run.configHash = loaded.hash();
            run.application = cfg.application().name();
            run.sources = roots.stream().map(Sources.Root::info).toList();
            run.fullScan = options.full();
            run.filesTotal = records.size();
            run.filesAnalyzed = analyzed;
            run.filesCached = cached;
            run.filesRemoved = removed;
            run.coverage = coverage;
            run.overrides = overrideReport;
            run.countingRules = cfg.counting();
            run.target = cfg.target().info();
            run.analyzers = analyzerCoverage(fileFacts);
            if (!run.analyzers.isEmpty()) {
                log.info("Analyzers: " + analyzerLine(run.analyzers));
            }

            stage("export");
            t = System.nanoTime();
            Path dataDir = runDir.resolve("data");
            List<Entity> entities = FactWriter.dropDuplicateIds(List.of(
                    new Entity("inventory", FILE_COLUMNS, records),
                    new Entity("components", COMPONENT_COLUMNS, components),
                    new Entity("findings", FINDING_COLUMNS, findings),
                    new Entity("input_coverage", COVERAGE_COLUMNS, coverage)), warnings);
            run.warnings = warnings;
            FactWriter.write(dataDir, entities);
            FactWriter.writeJson(dataDir.resolve("target.json"), run.target);
            timings.put("export", seconds(t));
            run.timings = timings;
            run.finishedAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).format(ISO_SECONDS);
            FactWriter.writeJson(runDir.resolve("run.json"), run.toMap());
            Runs.setLatest(outDir, runId);
            for (String w : warnings) {
                log.warning(w);
            }
            log.info("Run " + runId + " written to " + runDir);
            @SuppressWarnings("unchecked")
            List<Component> comps = (List<Component>) entities.get(1).items();
            @SuppressWarnings("unchecked")
            List<FileRecord> files = (List<FileRecord>) entities.get(0).items();
            @SuppressWarnings("unchecked")
            List<Finding> found = (List<Finding>) entities.get(2).items();
            return new Result(runDir, run, files, comps, found, coverage, fileFacts);
        }

        /** The file-cache payload of one file: its record and what each analyzer found. */
        private String payload(FileAnalyzer.Result res, Path absPath) {
            Map<String, Object> facts = new LinkedHashMap<>();
            FileRecord rec = res.record();
            if (rec != null && (res.text() != null || rec.language.equals("archive"))) {
                AnalyzerContext ctx = new AnalyzerContext(cfg, absPath, res.text(), null);
                Analyzers.run(analyzers, rec, ctx, analyzerWarnings).forEach((name, f) -> facts.put(name, f.dump()));
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("facts", facts);
            payload.put("record", rec != null ? rec.toMap() : null);
            return PyJson.compact(payload);
        }

        /** Files, lines and unparsed lines per analyzer, with the first 50 unparsed regions. */
        private static Map<String, Object> analyzerCoverage(List<FileFacts> fileFacts) {
            record Cov(int[] counts, List<Map<String, Object>> regions) {}
            Map<String, Cov> out = new java.util.TreeMap<>();
            for (FileFacts ff : fileFacts) {
                Cov cov = out.computeIfAbsent(ff.analyzer(), k -> new Cov(new int[3], new ArrayList<>()));
                cov.counts()[0]++;
                int lines = Math.max(ff.facts().coverage.lines, 0);
                cov.counts()[1] += lines;
                java.util.Set<Integer> covered = new java.util.HashSet<>();
                for (Coverage.Unparsed u : ff.facts().coverage.unparsed) {
                    for (int i = Math.max(u.start(), 1); i <= Math.min(u.end(), lines); i++) {
                        covered.add(i);
                    }
                    if (cov.regions().size() < 50) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("file", ff.path());
                        r.put("start", (long) u.start());
                        r.put("end", (long) u.end());
                        r.put("reason", u.reason());
                        cov.regions().add(r);
                    }
                }
                cov.counts()[2] += covered.size();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            out.forEach((name, cov) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("files", (long) cov.counts()[0]);
                m.put("lines", (long) cov.counts()[1]);
                m.put("unparsed_lines", (long) cov.counts()[2]);
                double pct = 100.0;
                if (cov.counts()[1] != 0) {
                    pct = new java.math.BigDecimal(100.0 * (cov.counts()[1] - cov.counts()[2]) / cov.counts()[1])
                            .setScale(1, java.math.RoundingMode.HALF_EVEN).doubleValue();
                }
                m.put("coverage_pct", pct);
                m.put("regions", cov.regions());
                result.put(name, m);
            });
            return result;
        }

        /** Build files only, never written to. */
        private static String readBuildFile(Map<String, Path> roots, String root, String rel) {
            Path base = roots.get(root);
            if (base == null) {
                return null;
            }
            Path p = base.resolve(rel);
            try {
                if (Files.size(p) > 2_000_000) {
                    return null;
                }
                return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return null;
            }
        }

        private String why(IOException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        /**
         * Files the scan could not read in full: too large (inventoried, not analysed) or unreadable
         * (permissions, path too long, vanished). Both are coverage gaps, never a failed scan.
         */
        private List<Finding> limitFindings(List<FileRecord> records, List<String> unreadable, long maxBytes) {
            List<Finding> out = new ArrayList<>();
            for (FileRecord rec : records) {
                if (rec.size > maxBytes && rec.category != com.legacyaudit.model.facts.Category.BINARY && !rec.language.equals("archive")) {
                    out.add(Finding.about("file-too-large", Severity.LOW, "coverage", rec.path,
                            String.format(Locale.ROOT, "%.1f", rec.size / 1048576.0)
                                    + " MB is above inventory.max_file_size_mb: hashed and counted as a file, but not read for LOC or analysed.",
                            rec.path));
                }
            }
            for (String u : unreadable) {
                String path = u.substring(0, u.indexOf('\u001f'));
                String why = u.substring(u.indexOf('\u001f') + 1);
                log.warning("Cannot read " + path + ": " + why);
                out.add(Finding.about("file-unreadable", Severity.LOW, "coverage", path,
                        "The file could not be read (" + why + "); it is not in the inventory.", path));
            }
            return out;
        }

        /** Build output and precompiler-generated files the walker left out (decision 113). */
        private List<Finding> skippedFindings(List<Skipped> skipped, boolean multi) {
            List<Finding> out = new ArrayList<>();
            for (Skipped sk : skipped) {
                String path = multi ? sk.root() + "/" + sk.rel() : sk.rel();
                String what = sk.rel().endsWith("/") ? sk.files() + " file(s)" : "the file";
                log.info("Left out " + path + ": " + sk.reason() + " (" + what + ")");
                String file = path.replaceAll("/+$", "");
                out.add(new Finding(Fact.makeId("finding", "build-output-skipped", path), Evidence.list(file), "build-output-skipped",
                        Severity.INFO, "coverage", file, null,
                        "Left out of the scan: " + sk.reason() + "; " + what
                                + ". Set inventory.skip_build_output: false in audit.yaml to scan it.",
                        null));
            }
            return out;
        }
    }
}
