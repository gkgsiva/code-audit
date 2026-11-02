package com.legacyaudit.server.scans;

import com.legacyaudit.engine.scan.Scanner;
import com.legacyaudit.engine.store.ScanLock;
import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.model.config.LoadedConfig;
import com.legacyaudit.engine.store.Runs;
import com.legacyaudit.server.admin.Activity;
import com.legacyaudit.server.admin.AdminStore;
import com.legacyaudit.server.jobs.JobGate;
import com.legacyaudit.server.web.ApiException;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Runs scans in the background, one per project, sharing the site's job slots with report builds
 * and AI runs ({@link JobGate}, {@code limits.max_scans} on the admin page). While a scan runs,
 * its stage and the last log lines are kept in memory for the status screen; when it ends, the
 * log tail is stored with the scan. A scan running longer than {@code limits.scan_timeout_minutes}
 * is stopped, and after a scan the oldest runs beyond {@code limits.keep_runs} are deleted.
 */
@Service
public class ScanJobs {

    static final int LOG_LINES = 500;

    private static final Logger LOG = LoggerFactory.getLogger(ScanJobs.class);

    /** What the status screen shows of a scan that is queued or running. */
    public static final class Live {
        final AtomicBoolean cancel = new AtomicBoolean();
        private final Deque<String> log = new ArrayDeque<>();
        private volatile String stage = "queued";
        private volatile int done;
        private volatile int total;

        synchronized void line(String line) {
            log.addLast(line);
            while (log.size() > LOG_LINES) {
                log.removeFirst();
            }
        }

        public synchronized List<String> lines() {
            return List.copyOf(log);
        }

        public Map<String, Object> progress() {
            return Map.of("stage", stage, "done", done, "total", total);
        }
    }

    private final ScanStore store;
    private final JobGate gate;
    private final AdminStore admin;
    private final Activity activity;
    private final ExecutorService pool = Executors.newCachedThreadPool(Thread.ofPlatform().name("scan-", 1).daemon().factory());
    private final java.util.concurrent.ScheduledExecutorService timers =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("scan-timeout").daemon().factory());
    private final Map<Long, Live> live = new ConcurrentHashMap<>();

    public ScanJobs(ScanStore store, JobGate gate, AdminStore admin, Activity activity) {
        this.store = store;
        this.gate = gate;
        this.admin = admin;
        this.activity = activity;
        gate.slots(() -> admin.get().limits().maxScans());
        gate.onEnd(job -> {
            Map<String, Object> d = new java.util.LinkedHashMap<>();
            d.put("kind", job.kind);
            d.put("title", job.title);
            d.put("state", job.state);
            d.put("seconds", job.worked() == null ? null : Math.round(job.worked() * 10) / 10.0);
            d.put("wait_seconds", job.waited() == null ? null : Math.round(job.waited() * 10) / 10.0);
            d.put("error", job.error);
            activity.log(job.user, "job finished", job.project, d);
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    void failInterrupted() {
        int n = store.failInterrupted();
        if (n > 0) {
            LOG.warn("{} scan(s) were stopped by the last server stop", n);
        }
    }

    /** Queues a scan of the project whose audit.yaml is {@code config}; one at a time per project. */
    public synchronized Scan start(String projectId, Path config, String user, boolean full) {
        if (store.active(projectId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "A scan of this project is already running; wait for it or cancel it");
        }
        long id = store.queue(projectId, user, full);
        Live state = new Live();
        live.put(id, state);
        JobGate.Job slot = gate.queue("scan", projectId, full ? "Full rescan" : "Re-run", user);
        pool.submit(() -> {
            try {
                gate.run(slot, state.cancel::get, () -> {
                    String outcome = run(id, config, full, state);
                    if (!outcome.equals(Scan.DONE)) {
                        gate.outcome(slot, outcome, store.find(id).map(Scan::error).orElse(null));
                    }
                });
            } catch (JobGate.Cancelled e) {
                store.ended(id, Scan.CANCELLED, null, tail(state));
                live.remove(id);
            } catch (Exception e) {
                // run() records its own failure
            }
        });
        return store.find(id).orElseThrow();
    }

    public Live live(long scanId) {
        return live.get(scanId);
    }

    /** Asks a queued or running scan to stop; it ends as cancelled. */
    public boolean cancel(long scanId) {
        Live state = live.get(scanId);
        if (state == null) {
            return false;
        }
        state.cancel.set(true);
        state.line("Cancel asked; the scan stops at the next file");
        return true;
    }

    /** Runs the scan and answers how it ended: done, cancelled or failed. */
    private String run(long id, Path config, boolean full, Live state) {
        java.util.concurrent.ScheduledFuture<?> timeout = null;
        try {
            if (state.cancel.get()) {
                store.ended(id, Scan.CANCELLED, null, tail(state));
                return Scan.CANCELLED;
            }
            store.running(id);
            state.stage = "starting";
            int minutes = admin.get().limits().scanTimeoutMinutes();
            timeout = timers.schedule(() -> {
                state.line("WARNING Stopped: the scan ran over " + minutes + " minutes");
                state.cancel.set(true);
            }, minutes, java.util.concurrent.TimeUnit.MINUTES);
            LoadedConfig loaded = ConfigLoader.load(config);
            var options = new Scanner.Options(full, null, (stage, done, total) -> {
                state.stage = stage;
                state.done = done;
                state.total = total;
            }, state.cancel, state::line);
            Scanner.Result result = Scanner.run(loaded, options);
            timeout.cancel(false);
            List<String> removed = Runs.prune(loaded.outputDir(), admin.get().limits().keepRuns());
            if (!removed.isEmpty()) {
                state.line("Removed " + removed.size() + " old run(s): " + String.join(", ", removed));
            }
            store.done(id, result.run(), result.inventory(), result.components(), tail(state));
            Scan scan = store.find(id).orElseThrow();
            activity.log(null, "scan finished", scan.projectId(), Map.of("run", String.valueOf(scan.runId())));
            return Scan.DONE;
        } catch (Scanner.Cancelled e) {
            store.ended(id, Scan.CANCELLED, null, tail(state));
            return Scan.CANCELLED;
        } catch (ConfigException | ScanLock.ScanBusy e) {
            state.line("ERROR " + e.getMessage());
            store.ended(id, Scan.FAILED, e.getMessage(), tail(state));
            return Scan.FAILED;
        } catch (Exception e) {
            LOG.error("Scan {} failed", id, e);
            String message = e.getClass().getSimpleName() + ": " + e.getMessage();
            state.line("ERROR " + message);
            store.ended(id, Scan.FAILED, message, tail(state));
            return Scan.FAILED;
        } finally {
            if (timeout != null) {
                timeout.cancel(false);
            }
            live.remove(id);
        }
    }

    private static String tail(Live state) {
        return String.join("\n", state.lines());
    }

    @PreDestroy
    void stop() {
        live.values().forEach(s -> s.cancel.set(true));
        pool.shutdownNow();
        timers.shutdownNow();
    }
}
