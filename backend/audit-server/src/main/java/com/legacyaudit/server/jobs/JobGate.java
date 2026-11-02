package com.legacyaudit.server.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import org.springframework.stereotype.Component;

/**
 * The slots shared by every background job of the site (scans, report builds, AI enrichment):
 * at most {@code limits.max_scans} run at once, the others wait in order. The number of slots
 * is read again whenever a slot frees, so an admin change counts at once. The last 200 jobs are
 * kept for the status page ({@code Gate} and {@code Site.jobs} of the Python hosted site).
 */
@Component
public class JobGate {

    static final int RECENT = 200;

    /** One background job as the status page lists it. */
    public static final class Job {
        public final String kind;
        public final String project;
        public final String title;
        public final String user;
        public final Instant queuedAt = Instant.now();
        public volatile Instant startedAt;
        public volatile Instant endedAt;
        public volatile String state = "waiting";
        public volatile String error;

        public Job(String kind, String project, String title, String user) {
            this.kind = kind;
            this.project = project;
            this.title = title;
            this.user = user;
        }

        public boolean active() {
            return state.equals("waiting") || state.equals("running");
        }

        /** Seconds waited for a slot and seconds worked (null while not known). */
        public Double waited() {
            return startedAt == null ? null : seconds(queuedAt, startedAt);
        }

        public Double worked() {
            return startedAt == null || endedAt == null ? null : seconds(startedAt, endedAt);
        }

        public Map<String, Object> info() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("project", project);
            m.put("title", title);
            m.put("user", user);
            m.put("state", state);
            m.put("queued_at", queuedAt.toString());
            m.put("started_at", startedAt == null ? null : startedAt.toString());
            m.put("ended_at", endedAt == null ? null : endedAt.toString());
            m.put("wait_seconds", waited() == null ? null : Math.round(waited() * 10) / 10.0);
            m.put("seconds", worked() == null ? null : Math.round(worked() * 10) / 10.0);
            m.put("error", error);
            return m;
        }

        private static double seconds(Instant a, Instant b) {
            return Duration.between(a, b).toMillis() / 1000.0;
        }
    }

    /** Thrown inside {@link #run} when the job was cancelled while it waited. */
    public static final class Cancelled extends RuntimeException {
        public Cancelled() {
            super("cancelled", null, false, false);
        }
    }

    /** What a job's body does; it may throw anything, which ends the job as failed. */
    @FunctionalInterface
    public interface Work {
        void run() throws Exception;
    }

    private final Deque<Job> waiting = new ArrayDeque<>();
    private final List<Job> running = new ArrayList<>();
    private final Deque<Job> recent = new ArrayDeque<>();
    private volatile IntSupplier slots = () -> 2;
    private volatile Consumer<Job> onEnd = j -> { };

    /** Where the number of slots comes from (the admin settings). */
    public void slots(IntSupplier slots) {
        this.slots = slots;
    }

    /** Called after every job ends (the activity log). */
    public void onEnd(Consumer<Job> onEnd) {
        this.onEnd = onEnd;
    }

    public int slots() {
        return Math.max(1, slots.getAsInt());
    }

    /** Registers a job as waiting; call {@link #run} from its worker thread. */
    public synchronized Job queue(String kind, String project, String title, String user) {
        Job job = new Job(kind, project, title, user);
        waiting.addLast(job);
        recent.addFirst(job);
        while (recent.size() > RECENT) {
            recent.removeLast();
        }
        return job;
    }

    /**
     * Waits for a slot (first come, first served), runs the work and records how it ended.
     * {@code cancelled} is asked while waiting; a cancelled job never takes a slot.
     */
    public void run(Job job, java.util.function.BooleanSupplier cancelled, Work work) throws Exception {
        synchronized (this) {
            while (waiting.peekFirst() != job || running.size() >= slots()) {
                if (cancelled.getAsBoolean()) {
                    waiting.remove(job);
                    end(job, "cancelled", null);
                    notifyAll();
                    throw new Cancelled();
                }
                wait(500);
            }
            waiting.removeFirst();
            running.add(job);
            job.startedAt = Instant.now();
            job.state = "running";
            notifyAll();
        }
        String state = "done";
        String error = null;
        try {
            work.run();
        } catch (Cancelled e) {
            state = "cancelled";
            throw e;
        } catch (Exception e) {
            state = "failed";
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw e;
        } finally {
            synchronized (this) {
                running.remove(job);
                end(job, job.state.equals("running") ? state : job.state, error);
                notifyAll();
            }
        }
    }

    /** Marks how the job ended when the work reports it itself (cancelled, failed). */
    public void outcome(Job job, String state, String error) {
        job.state = state;
        job.error = error;
    }

    private void end(Job job, String state, String error) {
        job.endedAt = Instant.now();
        job.state = state;
        if (job.error == null) {
            job.error = error;
        }
        try {
            onEnd.accept(job);
        } catch (RuntimeException e) {
            // the activity log is never worth failing a job
        }
    }

    /** Running and waiting jobs. */
    public synchronized Map<String, List<Job>> snapshot() {
        return Map.of("running", List.copyOf(running), "waiting", List.copyOf(waiting));
    }

    /** The last 200 jobs, newest first. */
    public synchronized List<Job> recent() {
        return List.copyOf(recent);
    }
}
