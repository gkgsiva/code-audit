package com.legacyaudit.engine.security;

import com.legacyaudit.model.facts.Fact;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional Java 25 compatibility checks with the JDK's {@code jdeps} and {@code jdeprscan}, on
 * the jars found in the source tree when a JDK is available ({@code security.jdk_tools}).
 * Results are {@code jdk_findings} items {@code {tool, name, class, api, file}} that the
 * dependency rules turn into findings.
 */
public final class Jdk {

    static final int MAX_JARS = 200;
    static final int TIMEOUT_S = 120;
    private static final Logger LOG = Logger.getLogger("legacy_audit");
    // jdeps -jdkinternals:  "   com.foo.Bar     -> sun.misc.BASE64Encoder    JDK internal API (...)"
    private static final Pattern JDEPS = Pattern.compile("^\\s+(\\S+)\\s+->\\s+(\\S+)\\s+(JDK internal API|JDK removed internal API)");
    // jdeprscan --for-removal: "class com/foo/Bar uses deprecated method java/lang/Thread::stop()V (forRemoval=true)"
    private static final Pattern JDEPRSCAN = Pattern.compile("^class (\\S+) uses deprecated \\w+ (\\S+).*forRemoval=true");

    private Jdk() {}

    /** Items found and the status: disabled, missing or ran. */
    public record Result(List<Map<String, Object>> items, String status) {}

    static String findTool(String javaHome, String name) {
        if (javaHome != null && !javaHome.isEmpty()) {
            for (String candidate : List.of(name + ".exe", name)) {
                Path p = Path.of(javaHome, "bin", candidate);
                if (Files.isRegularFile(p)) {
                    return p.toString();
                }
            }
            return null;
        }
        return Vulns.which(name);
    }

    private static String run(List<String> cmd) {
        try {
            Path outFile = Files.createTempFile("legacy-audit-", ".out");
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).redirectOutput(outFile.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                LOG.warning(Path.of(cmd.get(0)).getFileName() + " failed: TimeoutExpired");
                return null;
            }
            String out = Files.readString(outFile, StandardCharsets.UTF_8);
            Files.deleteIfExists(outFile);
            return out;
        } catch (IOException e) {
            LOG.warning(Path.of(cmd.get(0)).getFileName() + " failed: OSError");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static List<Map<String, Object>> parseJdeps(String output, String jar) {
        List<String[]> found = new ArrayList<>();
        for (String line : output.split("\\R")) {
            Matcher m = JDEPS.matcher(line);
            if (m.find()) {
                found.add(new String[] {"jdeps", m.group(1), m.group(2)});
            }
        }
        return items(found, jar);
    }

    static List<Map<String, Object>> parseJdeprscan(String output, String jar) {
        List<String[]> found = new ArrayList<>();
        for (String line : output.split("\\R")) {
            Matcher m = JDEPRSCAN.matcher(line);
            if (m.find()) {
                found.add(new String[] {"jdeprscan", m.group(1).replace('/', '.'), m.group(2)});
            }
        }
        return items(found, jar);
    }

    private static List<Map<String, Object>> items(List<String[]> found, String jar) {
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] f : found) {
            if (!seen.add(f[0] + "\u0000" + f[2])) { // one finding per API per jar
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tool", f[0]);
            item.put("class", f[1]);
            item.put("api", f[2]);
            item.put("id", Fact.makeId("jdk_finding", f[0], jar, f[2]));
            item.put("name", Path.of(jar).getFileName().toString());
            item.put("file", jar);
            item.put("evidence", List.of(Map.of("file", jar)));
            out.add(item);
        }
        return out;
    }

    /** Checks for jars given as (display path, absolute path). */
    public static Result run(boolean enabled, String javaHome, List<Map.Entry<String, Path>> jars) {
        if (!enabled) {
            return new Result(List.of(), "disabled");
        }
        String jdeps = findTool(javaHome, "jdeps");
        String jdeprscan = findTool(javaHome, "jdeprscan");
        if (jdeps == null && jdeprscan == null) {
            return new Result(List.of(), "missing");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, Path> jar : jars.subList(0, Math.min(jars.size(), MAX_JARS))) {
            if (jdeps != null) {
                String text = run(List.of(jdeps, "--multi-release", "base", "-jdkinternals", jar.getValue().toString()));
                items.addAll(parseJdeps(text == null ? "" : text, jar.getKey()));
            }
            if (jdeprscan != null) {
                String text = run(List.of(jdeprscan, "--for-removal", jar.getValue().toString()));
                items.addAll(parseJdeprscan(text == null ? "" : text, jar.getKey()));
            }
        }
        return new Result(items, "ran");
    }
}
