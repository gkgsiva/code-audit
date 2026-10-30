package com.legacyaudit.server.enrichment;

import com.legacyaudit.ai.model.AiClient;
import com.legacyaudit.ai.model.Enricher;
import com.legacyaudit.engine.enrich.Stages;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AI enrichment runs in the background, at most one per project, and the stages each run's last
 * validation or enrichment left invalid. Both live in memory (a restart forgets them; the stage
 * files and {@code status.json} stay in the run folder).
 */
@Component
public class EnrichJobs {

    private static final Logger LOG = LoggerFactory.getLogger(EnrichJobs.class);

    /** One enrichment run: the model, the stages, what it says as it goes, and the outcome. */
    public static final class Job {
        public final String runId;
        public final String label;
        public final String user;
        public final Instant startedAt = Instant.now();
        public volatile String state = "running";
        public volatile String stage = "";
        public volatile int done;
        public final int total;
        public final List<String> messages = new ArrayList<>();
        public final List<String> valid = new ArrayList<>();
        public final List<String> invalid = new ArrayList<>();
        public volatile String error;
        public volatile Instant endedAt;

        Job(String runId, String label, String user, int total) {
            this.runId = runId;
            this.label = label;
            this.user = user;
            this.total = total;
        }
    }

    private final Map<String, Job> last = new ConcurrentHashMap<>();
    /** Run id to the stages its last validation or enrichment found invalid. */
    private final Map<String, Set<String>> invalid = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "enrichment");
        t.setDaemon(true);
        return t;
    });

    public Job last(String projectId) {
        return last.get(projectId);
    }

    public Set<String> invalid(String runId) {
        return invalid.getOrDefault(runId, Set.of());
    }

    public void setInvalid(String runId, Set<String> stages) {
        invalid.put(runId, Set.copyOf(stages));
    }

    /** Start a run; null when one is already running for the project. */
    public synchronized Job start(String projectId, Path runDir, List<Stages.Stage> stages, AiClient client, String user) {
        Job running = last.get(projectId);
        if (running != null && running.state.equals("running")) {
            return null;
        }
        Job job = new Job(runDir.getFileName().toString(), client.settings().label(), user, stages.size());
        last.put(projectId, job);
        pool.submit(() -> run(job, runDir, stages, client));
        return job;
    }

    void run(Job job, Path runDir, List<Stages.Stage> stages, AiClient client) {
        try {
            List<Enricher.StageResult> results = Enricher.enrich(runDir, stages, client, text -> {
                job.stage = text;
                synchronized (job.messages) {
                    job.messages.add(text);
                }
                if (!text.endsWith("...")) {
                    job.done++;
                }
            });
            List<String> bad = new ArrayList<>();
            for (Enricher.StageResult r : results) {
                (r.ok ? job.valid : bad).add(r.stage);
            }
            job.invalid.addAll(bad);
            setInvalid(job.runId, Set.copyOf(bad));
            if (!bad.isEmpty() && job.valid.isEmpty()) {
                Enricher.StageResult first = results.stream().filter(r -> !r.ok).findFirst().orElseThrow();
                job.error = first.stage + ": " + (first.errors.isEmpty() ? "failed" : first.errors.get(0));
                job.state = "failed";
            } else {
                job.state = "done";
            }
        } catch (RuntimeException e) {
            LOG.warn("Enrichment failed for run {}", job.runId, e);
            job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            job.state = "failed";
        } finally {
            job.stage = "";
            job.endedAt = Instant.now();
        }
    }
}
