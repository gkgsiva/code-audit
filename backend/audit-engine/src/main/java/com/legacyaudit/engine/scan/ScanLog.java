package com.legacyaudit.engine.scan;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/**
 * The log of one scan, written to {@code <run>/logs/scan.log} ({@code 2026-10-29 10:30:00,123
 * INFO legacy_audit: ...}). Secret values (Git tokens) are masked in every line; a listener can
 * follow the lines as they are written (the web app's live log).
 */
public final class ScanLog implements AutoCloseable {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");

    private final BufferedWriter out;
    private final List<String> secrets;
    private final Consumer<String> listener;

    public ScanLog(Path file, List<String> secrets, Consumer<String> listener) throws IOException {
        Files.createDirectories(file.getParent());
        this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        this.secrets = secrets.stream().filter(s -> s != null && !s.isEmpty()).toList();
        this.listener = listener;
    }

    public void info(String message) {
        write("INFO", message);
    }

    public void warning(String message) {
        write("WARNING", message);
    }

    public void error(String message) {
        write("ERROR", message);
    }

    private synchronized void write(String level, String message) {
        String msg = message;
        for (String s : secrets) {
            msg = msg.replace(s, "****");
        }
        String line = LocalDateTime.now().format(TIME) + " " + level + " legacy_audit: " + msg;
        try {
            out.write(line);
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            // a full disk must not stop the scan; the run's own files report what happened
        }
        if (listener != null) {
            listener.accept(line);
        }
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
