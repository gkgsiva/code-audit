package com.legacyaudit.server.git;

import com.legacyaudit.engine.scan.GitClones;
import com.legacyaudit.model.config.AuditConfig.GitSource;
import com.legacyaudit.model.config.ConfigException;
import com.legacyaudit.model.config.ConfigLoader;
import com.legacyaudit.server.admin.Activity;
import com.legacyaudit.server.admin.AdminSettings;
import com.legacyaudit.server.admin.AdminStore;
import com.legacyaudit.server.admin.SystemMetrics;
import com.legacyaudit.server.jobs.JobGate;
import com.legacyaudit.server.projects.HostedLayer;
import com.legacyaudit.server.projects.Project;
import com.legacyaudit.server.projects.ProjectService;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The Git clones of the hosted site, as the Python site kept them: one cache folder
 * ({@link HostedLayer#gitCache()}), cleaned at start, every hour and after each scan. A clone not
 * used for {@code clone_keep_days} is deleted, then the least recently used ones while the disk
 * has less than {@code min_free_gb} free. Clones a running or waiting job may use are kept.
 */
@Component
public class Clones {

    private static final Logger LOG = LoggerFactory.getLogger(Clones.class);
    static final long CHECK_SECONDS = 3600;

    private final HostedLayer layer;
    private final com.legacyaudit.server.ServerSettings settings;
    private final AdminStore admin;
    private final ProjectService projects;
    private final JobGate gate;
    private final Activity activity;
    private final Object lock = new Object();
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("git-clones").daemon().factory());

    public Clones(HostedLayer layer, com.legacyaudit.server.ServerSettings settings, AdminStore admin, ProjectService projects, JobGate gate, Activity activity) {
        this.layer = layer;
        this.settings = settings;
        this.admin = admin;
        this.projects = projects;
        this.gate = gate;
        this.activity = activity;
        gate.onEnd(job -> {
            if (job.kind.equals("scan")) {
                prune();
            }
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        timer.scheduleWithFixedDelay(() -> {
            try {
                prune();
            } catch (RuntimeException e) {
                LOG.error("Cleaning up the Git clones failed", e);
            }
        }, 0, CHECK_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }

    public Path cache() {
        return layer.gitCache();
    }

    /** The Git addresses each project scans (from its own audit.yaml, with the admin defaults). */
    public Map<String, Set<String>> projectRepoUrls() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Project p : projects.store().all()) {
            try {
                if (ConfigLoader.load(projects.configPath(p.id())).config().source() instanceof GitSource g) {
                    Set<String> urls = new HashSet<>();
                    g.repos().forEach(r -> urls.add(GitClones.stripCredentials(r.url())));
                    out.put(p.id(), urls);
                }
            } catch (ConfigException | java.io.UncheckedIOException e) {
                // a broken audit.yaml scans nothing
            }
        }
        return out;
    }

    /** Start (epoch seconds) of the oldest job running or waiting, less a minute; null when idle. */
    public Double busySince() {
        Map<String, List<JobGate.Job>> snap = gate.snapshot();
        Double min = null;
        for (String k : List.of("running", "waiting")) {
            for (JobGate.Job j : snap.get(k)) {
                Instant at = j.startedAt != null && j.startedAt.isBefore(j.queuedAt) ? j.startedAt : j.queuedAt;
                double s = at.toEpochMilli() / 1000.0;
                min = min == null ? s : Math.min(min, s);
            }
        }
        return min == null ? null : min - 60;
    }

    private void remove(GitClones.Clone clone, String reason, String user) {
        GitClones.remove(cache(), clone.key());
        activity.log(user, "delete clone", null, Map.of("clone", clone.key(), "reason", reason));
    }

    /** Deletes the clones not used for a while, then the oldest while the disk is short of space. */
    public List<String> prune() {
        AdminSettings.Limits limits = admin.get().limits();
        Path cache = cache();
        List<String> removed = new ArrayList<>();
        if (!Files.isDirectory(cache)) {
            return removed;
        }
        synchronized (lock) {
            Double busy = busySince();
            List<GitClones.Clone> clones = new ArrayList<>(GitClones.list(cache, false).stream()
                    .filter(c -> busy == null || c.lastUsed() < busy).toList());
            double cutoff = System.currentTimeMillis() / 1000.0 - limits.cloneKeepDays() * 86400.0;
            for (GitClones.Clone c : List.copyOf(clones)) {
                if (c.lastUsed() < cutoff) {
                    remove(c, "not used for " + limits.cloneKeepDays() + " days", null);
                    removed.add(c.key());
                    clones.remove(c);
                }
            }
            double need = limits.minFreeGb() * Math.pow(1024, 3);
            for (GitClones.Clone c : clones) { // least recently used first
                Long free = (Long) SystemMetrics.disk(cache).get("free");
                if (free == null || free >= need) {
                    break;
                }
                remove(c, "less than " + gb(limits.minFreeGb()) + " GB free", null);
                removed.add(c.key());
            }
        }
        return removed;
    }

    /** Python's {@code :g} for the free-space figure: 5 not 5.0. */
    static String gb(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** Clones only this project uses (other projects scanning the same address keep them). */
    public List<GitClones.Clone> clonesOf(String projectId) {
        Map<String, Set<String>> urls = projectRepoUrls();
        Set<String> mine = urls.remove(projectId);
        if (mine == null) {
            return List.of();
        }
        Set<String> others = new HashSet<>();
        urls.values().forEach(others::addAll);
        return GitClones.list(cache(), false).stream().filter(c -> mine.contains(c.url()) && !others.contains(c.url())).toList();
    }

    /** Deletes the clones only a deleted project used. */
    public void removeFor(List<GitClones.Clone> clones, String projectId, String user) {
        for (GitClones.Clone c : clones) {
            try {
                synchronized (lock) {
                    remove(c, "project " + projectId + " deleted", user);
                }
            } catch (RuntimeException e) {
                LOG.warn("Could not delete the clone {}: {}", c.key(), e.getMessage());
            }
        }
    }

    /** The admin page's list: every clone with the projects that scan its address, newest use first. */
    public Map<String, Object> view() {
        Path cache = cache();
        Map<String, List<String>> byUrl = new LinkedHashMap<>();
        projectRepoUrls().forEach((pid, urls) -> urls.forEach(u -> byUrl.computeIfAbsent(u, k -> new ArrayList<>()).add(pid)));
        List<Map<String, Object>> rows = new ArrayList<>();
        long total = 0;
        List<GitClones.Clone> all = new ArrayList<>(GitClones.list(cache, true));
        java.util.Collections.reverse(all);
        for (GitClones.Clone c : all) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("key", c.key());
            r.put("url", c.url());
            r.put("ref", c.ref());
            String commit = c.commit() == null ? "" : c.commit();
            r.put("commit", commit.substring(0, Math.min(12, commit.length())));
            r.put("last_used", Instant.ofEpochSecond((long) c.lastUsed()).atOffset(java.time.ZoneOffset.UTC)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")));
            r.put("size", c.size());
            List<String> users = new ArrayList<>(byUrl.getOrDefault(c.url() == null ? "" : c.url(), List.of()));
            java.util.Collections.sort(users);
            r.put("projects", users);
            rows.add(r);
            total += c.size();
        }
        AdminSettings.Limits limits = admin.get().limits();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cache", cache.toString());
        out.put("clones", rows);
        out.put("total", total);
        out.put("free", SystemMetrics.disk(Files.isDirectory(cache) ? cache : settings.auditsRoot()).get("free"));
        out.put("keep_days", limits.cloneKeepDays());
        out.put("min_free_gb", limits.minFreeGb());
        return out;
    }

    /** Deletes one clone from the admin page; refused while a job may use it. */
    public void delete(String key, String user) {
        GitClones.Clone match = GitClones.list(cache(), false).stream().filter(c -> c.key().equals(key)).findFirst().orElse(null);
        if (match == null) {
            throw com.legacyaudit.server.web.ApiException.notFound("No clone '" + key + "'");
        }
        Double busy = busySince();
        if (busy != null && match.lastUsed() >= busy) {
            throw new com.legacyaudit.server.web.ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "A running or waiting job may use this clone; try later");
        }
        synchronized (lock) {
            remove(match, "deleted on the admin page", user);
        }
    }
}
