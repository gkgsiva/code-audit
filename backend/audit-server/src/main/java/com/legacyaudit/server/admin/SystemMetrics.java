package com.legacyaudit.server.admin;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * The server's memory, CPU and disk for the admin dashboard, sampled every 10 seconds so the
 * page can draw the last hour ({@code metrics.Sampler} of the Python hosted site). Inside a
 * container the memory limit and CPU use come from the cgroup files (v2, then v1); elsewhere
 * from {@code /proc}; on Windows from what the JVM reports.
 */
@Component
public class SystemMetrics {

    static final int SAMPLE_SECONDS = 10;
    static final int SAMPLES = 360;
    static final long NO_LIMIT = 1L << 60;

    private final Instant started = Instant.now();
    private final Deque<Map<String, Object>> samples = new ArrayDeque<>();
    private ScheduledExecutorService timer;
    private long lastNanos;
    private Double lastCpu;

    @PostConstruct
    void start() {
        sample();
        timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("metrics").daemon().factory());
        timer.scheduleAtFixedRate(() -> {
            try {
                sample();
            } catch (RuntimeException e) {
                // a dashboard number is never worth a crash
            }
        }, SAMPLE_SECONDS, SAMPLE_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    static String read(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8).strip();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static Long toLong(String text) {
        try {
            return text == null ? null : Long.parseLong(text.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Map<String, Long> meminfo() {
        Map<String, Long> out = new LinkedHashMap<>();
        String text = read("/proc/meminfo");
        for (String line : text == null ? new String[0] : text.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String[] parts = line.substring(colon + 1).strip().split("\\s+");
            Long kb = toLong(parts[0]);
            if (kb != null) {
                out.put(line.substring(0, colon), kb * 1024);
            }
        }
        return out;
    }

    /** Memory used and the limit, in bytes: the container's when there is one, else the machine's. */
    public static Map<String, Object> memory() {
        Long used = toLong(read("/sys/fs/cgroup/memory.current"));
        String limitText = read("/sys/fs/cgroup/memory.max");
        String source = "container";
        if (used == null) {
            used = toLong(read("/sys/fs/cgroup/memory/memory.usage_in_bytes"));
            limitText = read("/sys/fs/cgroup/memory/memory.limit_in_bytes");
        }
        Long limit = limitText != null && !limitText.equals("max") ? toLong(limitText) : null;
        Map<String, Long> info = meminfo();
        Long total = info.get("MemTotal");
        if (total == null && ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            total = os.getTotalMemorySize();
            if (used == null) {
                used = total - os.getFreeMemorySize();
                source = "machine";
            }
        }
        if (limit != null && (limit >= NO_LIMIT || (total != null && limit >= total))) {
            limit = null;
        }
        if (used == null) {
            source = "machine";
            if (total != null && info.containsKey("MemAvailable")) {
                used = total - info.get("MemAvailable");
            }
        }
        if (limit == null) {
            limit = total;
        }
        if (source.equals("container") && limit != null && limit.equals(total)) {
            source = "container (no limit set: the machine's memory)";
        }
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("used", used);
        out.put("limit", limit);
        out.put("process", rt.totalMemory() - rt.freeMemory());
        out.put("heap_max", rt.maxMemory());
        out.put("source", source);
        return out;
    }

    static Double cpuQuota() {
        String text = read("/sys/fs/cgroup/cpu.max");
        if (text != null) {
            String[] p = text.split(" ");
            if (!p[0].equals("max") && p.length > 1) {
                return Double.parseDouble(p[0]) / Double.parseDouble(p[1]);
            }
            return null;
        }
        Long q = toLong(read("/sys/fs/cgroup/cpu/cpu.cfs_quota_us"));
        Long period = toLong(read("/sys/fs/cgroup/cpu/cpu.cfs_period_us"));
        return q != null && q > 0 && period != null && period > 0 ? (double) q / period : null;
    }

    /** CPU time used so far by the container (or this process), in seconds. */
    static Double cpuSeconds() {
        String stat = read("/sys/fs/cgroup/cpu.stat");
        for (String line : stat == null ? new String[0] : stat.split("\n")) {
            if (line.startsWith("usage_usec ")) {
                return Long.parseLong(line.split(" ")[1].strip()) / 1_000_000.0;
            }
        }
        Long v1 = toLong(read("/sys/fs/cgroup/cpuacct/cpuacct.usage"));
        if (v1 != null) {
            return v1 / 1_000_000_000.0;
        }
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            long ns = os.getProcessCpuTime();
            return ns < 0 ? null : ns / 1_000_000_000.0;
        }
        return null;
    }

    public static double cpus() {
        Double q = cpuQuota();
        return q != null ? q : Runtime.getRuntime().availableProcessors();
    }

    synchronized Map<String, Object> sample() {
        long now = System.nanoTime();
        Double used = cpuSeconds();
        Double percent = null;
        if (used != null && lastCpu != null && now > lastNanos) {
            double share = (used - lastCpu) / ((now - lastNanos) / 1e9);
            percent = Math.round(Math.max(0, Math.min(100, 100 * share / cpus())) * 10) / 10.0;
        }
        if (used != null) {
            lastNanos = now;
            lastCpu = used;
        }
        Map<String, Object> mem = memory();
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("time", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        point.put("cpu_percent", percent);
        point.put("memory_used", mem.get("used"));
        point.put("memory_limit", mem.get("limit"));
        samples.addLast(point);
        while (samples.size() > SAMPLES) {
            samples.removeFirst();
        }
        return point;
    }

    public synchronized List<Map<String, Object>> history() {
        return new ArrayList<>(samples);
    }

    public static Map<String, Object> disk(Path folder) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Path p = folder;
            while (p != null && !Files.exists(p)) {
                p = p.getParent();
            }
            FileStore store = Files.getFileStore(p == null ? Path.of(".") : p);
            out.put("total", store.getTotalSpace());
            out.put("used", store.getTotalSpace() - store.getUnallocatedSpace());
            out.put("free", store.getUsableSpace());
        } catch (IOException e) {
            out.put("total", null);
            out.put("used", null);
            out.put("free", null);
        }
        return out;
    }

    /** The system panel of the dashboard ({@code metrics.system}). */
    public Map<String, Object> system(Path auditsRoot, Map<String, Integer> jobs) {
        List<Map<String, Object>> history = history();
        double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        String loadavg = read("/proc/loadavg");
        List<Double> loads = null;
        if (loadavg != null) {
            loads = new ArrayList<>();
            for (String s : loadavg.split(" ", 4)) {
                if (loads.size() < 3) {
                    loads.add(Double.parseDouble(s));
                }
            }
        } else if (load >= 0) {
            loads = List.of(Math.round(load * 100) / 100.0);
        }
        Map<String, Object> cpu = new LinkedHashMap<>();
        cpu.put("percent", history.isEmpty() ? null : history.getLast().get("cpu_percent"));
        cpu.put("cpus", cpus());
        cpu.put("machine_cpus", Runtime.getRuntime().availableProcessors());
        cpu.put("load", loads);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("memory", memory());
        out.put("cpu", cpu);
        out.put("disk", disk(auditsRoot));
        out.put("uptime_seconds", java.time.Duration.between(started, Instant.now()).toSeconds());
        out.put("threads", Thread.activeCount());
        out.put("java", Runtime.version().toString());
        out.put("platform", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        out.put("jobs", jobs);
        out.put("history", history);
        out.put("sample_seconds", SAMPLE_SECONDS);
        return out;
    }
}
