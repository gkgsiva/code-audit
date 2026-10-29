package com.legacyaudit.server.scans;

import com.legacyaudit.engine.scan.Scanner;
import com.legacyaudit.engine.store.ScanLock;
import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.model.config.LoadedConfig;
import com.legacyaudit.server.ServerSettings;
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
 * Runs scans in the background, at most {@code legacy-audit.max-scans} at a time and one per
 * project. While a scan runs, its stage and the last log lines are kept in memory for the
 * status screen; when it ends, the log tail is stored with the scan.
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
    private final ExecutorService pool;
    private final Map<Long, Live> live = new ConcurrentHashMap<>();

    public ScanJobs(ScanStore store, ServerSettings settings) {
        this.store = store;
        this.pool = Executors.newFixedThreadPool(settings.maxScans(), Thread.ofPlatform().name("scan-", 1).daemon().factory());
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
        pool.submit(() -> run(id, config, full, state));
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

    private void run(long id, Path config, boolean full, Live state) {
        try {
            if (state.cancel.get()) {
                store.ended(id, Scan.CANCELLED, null, tail(state));
                return;
            }
            store.running(id);
            state.stage = "starting";
            LoadedConfig loaded = ConfigLoader.load(config);
            var options = new Scanner.Options(full, null, (stage, done, total) -> {
                state.stage = stage;
                state.done = done;
                state.total = total;
            }, state.cancel, state::line);
            Scanner.Result result = Scanner.run(loaded, options);
            store.done(id, result.run(), result.inventory(), result.components(), tail(state));
        } catch (Scanner.Cancelled e) {
            store.ended(id, Scan.CANCELLED, null, tail(state));
        } catch (ConfigException | ScanLock.ScanBusy e) {
            state.line("ERROR " + e.getMessage());
            store.ended(id, Scan.FAILED, e.getMessage(), tail(state));
        } catch (Exception e) {
            LOG.error("Scan {} failed", id, e);
            String message = e.getClass().getSimpleName() + ": " + e.getMessage();
            state.line("ERROR " + message);
            store.ended(id, Scan.FAILED, message, tail(state));
        } finally {
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
    }
}
