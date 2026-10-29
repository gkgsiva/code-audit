package com.legacyaudit.engine.inventory;

import com.legacyaudit.model.config.AuditConfig;
import com.legacyaudit.model.facts.CoverageStatus;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.InputCoverage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Supplementary inputs coverage ({@code audit-inputs/<kind>/}). Missing is a gap, not an error. */
public final class InputCoverages {

    private InputCoverages() {}

    public static List<InputCoverage> of(Path inputsDir, String displayRoot) {
        List<InputCoverage> out = new ArrayList<>();
        AuditConfig.INPUT_KINDS.forEach((kind, label) -> {
            Path folder = inputsDir.resolve(kind);
            List<String> files = files(folder);
            String rel = displayRoot + "/" + kind;
            List<Evidence> evidence = files.stream().limit(20).map(f -> Evidence.of(rel + "/" + f)).toList();
            out.add(new InputCoverage(Fact.makeId("input_coverage", kind), evidence, kind, label,
                    files.isEmpty() ? CoverageStatus.MISSING : CoverageStatus.PRESENT, rel, files.size()));
        });
        return out;
    }

    /** Python sorts paths folder by folder ({@code a/b} before {@code a.txt}). */
    static int byParts(String a, String b) {
        String[] pa = a.split("/");
        String[] pb = b.split("/");
        for (int i = 0; i < Math.min(pa.length, pb.length); i++) {
            int c = pa[i].compareTo(pb[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(pa.length, pb.length);
    }

    /** Files under {@code folder} (relative, POSIX, sorted), leaving out hidden ones and hidden folders. */
    private static List<String> files(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> all = Files.walk(folder)) {
            return all.filter(Files::isRegularFile)
                    .map(p -> folder.relativize(p).toString().replace('\\', '/'))
                    .filter(rel -> Stream.of(rel.split("/")).noneMatch(part -> part.startsWith(".")))
                    .sorted(InputCoverages::byParts)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
