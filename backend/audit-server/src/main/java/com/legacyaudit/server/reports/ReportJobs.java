package com.legacyaudit.server.reports;

import com.legacyaudit.model.config.LoadedConfig;
import com.legacyaudit.reports.FunctionalReport;
import com.legacyaudit.reports.MgmtReport;
import com.legacyaudit.reports.PdfReports;
import com.legacyaudit.reports.SummaryReport;
import com.legacyaudit.reports.TechReport;
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
 * Report builds running in the background, at most one per project. The state of the last
 * build of each project is kept in memory for the report screen (a restart forgets it; the
 * report files stay in the run folder).
 */
@Component
public class ReportJobs {

    public static final List<String> TYPES = List.of("tech", "functional", "mgmt", "summary", "pdf", "all");
    private static final Logger LOG = LoggerFactory.getLogger(ReportJobs.class);

    /** One build: what is being built, the step now running, and the messages when done. */
    public static final class Job {
        public final String type;
        public final String runId;
        public final String user;
        public final Instant startedAt = Instant.now();
        public volatile String state = "running";
        public volatile String stage = "";
        public volatile int done;
        public volatile int total;
        public final List<String> messages = new ArrayList<>();
        public volatile String error;
        public volatile Instant endedAt;

        Job(String type, String runId, String user) {
            this.type = type;
            this.runId = runId;
            this.user = user;
        }
    }

    private final Map<String, Job> last = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "report-build");
        t.setDaemon(true);
        return t;
    });

    public Job last(String projectId) {
        return last.get(projectId);
    }

    /** Start a build; null when one is already running for the project. */
    public synchronized Job start(String projectId, LoadedConfig loaded, Path runDir, String type, String user) {
        Job running = last.get(projectId);
        if (running != null && running.state.equals("running")) {
            return null;
        }
        Job job = new Job(type, runDir.getFileName().toString(), user);
        last.put(projectId, job);
        pool.submit(() -> run(job, loaded, runDir));
        return job;
    }

    @SuppressWarnings("unchecked")
    void run(Job job, LoadedConfig loaded, Path runDir) {
        try {
            String type = job.type;
            boolean every = type.equals("all") || type.equals("pdf");
            List<String> steps = new ArrayList<>();
            for (String k : List.of("tech", "functional", "mgmt", "summary")) {
                if (every || type.equals(k)) {
                    steps.add(k);
                }
            }
            Map<String, Object> dump = loaded.config().dump();
            PdfReports.PdfConfig pdf = PdfReports.PdfConfig.of(dump.get("pdf"));
            boolean pdfToo = type.equals("pdf") || (type.equals("all") && pdf.enabled());
            job.total = steps.size() + (pdfToo ? 1 : 0);
            Map<String, Object> branding = dump.get("branding") instanceof Map<?, ?> b ? (Map<String, Object>) b : null;
            Path configDir = loaded.path().toAbsolutePath().getParent();
            for (String k : steps) {
                switch (k) {
                    case "tech" -> {
                        job.stage = "technical report";
                        TechReport.build(runDir, loaded.inputsDir());
                    }
                    case "functional" -> {
                        job.stage = "functional document";
                        FunctionalReport.build(runDir, loaded.inputsDir());
                    }
                    case "mgmt" -> {
                        job.stage = "management deck";
                        MgmtReport.build(runDir, branding, configDir);
                    }
                    default -> {
                        job.stage = "summary report";
                        SummaryReport.build(runDir, Set.of());
                    }
                }
                job.done++;
            }
            if (pdfToo) {
                job.stage = "PDF copies";
                PdfReports.PdfResult res = PdfReports.build(runDir, pdf);
                synchronized (job.messages) {
                    if (!res.files.isEmpty()) {
                        job.messages.add("PDF copies written: " + res.files.size() + ".");
                    }
                    job.messages.addAll(res.messages);
                }
                job.done++;
            }
            job.state = "done";
        } catch (RuntimeException e) {
            LOG.warn("Report build failed for run {}", job.runId, e);
            job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            job.state = "failed";
        } finally {
            job.stage = "";
            job.endedAt = Instant.now();
        }
    }
}
