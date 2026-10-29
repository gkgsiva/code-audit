package com.legacyaudit.engine.components;

import com.legacyaudit.model.facts.ComponentType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A folder that may become a component, with what the rules found about it. */
final class Candidate {

    String root;
    /** '' = source root. */
    String path;
    String detectedBy;
    String name;
    ComponentType type;
    List<String> buildFiles = new ArrayList<>();
    String artifact;
    String packaging;
    /** Found by the folder rules inside a Maven/Gradle group folder. */
    boolean inGroup;
    /** A folder holding an application's code folders (src, WebContent, ...). */
    boolean app;
    /** One batch of a batch folder holding several (rule 4). */
    boolean batchKid;
    /** The Maven/Gradle group folder it sits in ('' = none). */
    String level = "";
    /** Folders grouped into it (decision 176). */
    List<String> parts = new ArrayList<>();
    /** A Maven or Gradle module. */
    boolean jvmModule;
    /** Modules it uses (artifactIds, Gradle paths). */
    List<String> deps = new ArrayList<>();
    boolean runnable;

    Candidate(String root, String path, String detectedBy) {
        this.root = root;
        this.path = path;
        this.detectedBy = detectedBy;
    }

    /** Field-by-field equality, as Python's dataclass comparison ({@code c in list}). */
    boolean sameAs(Candidate o) {
        return this == o
                || (Objects.equals(root, o.root) && Objects.equals(path, o.path) && Objects.equals(detectedBy, o.detectedBy)
                        && Objects.equals(name, o.name) && type == o.type && buildFiles.equals(o.buildFiles)
                        && Objects.equals(artifact, o.artifact) && Objects.equals(packaging, o.packaging) && inGroup == o.inGroup
                        && app == o.app && batchKid == o.batchKid && Objects.equals(level, o.level) && parts.equals(o.parts)
                        && jvmModule == o.jvmModule && deps.equals(o.deps) && runnable == o.runnable);
    }

    static boolean contains(List<Candidate> list, Candidate c) {
        return list.stream().anyMatch(x -> x.sameAs(c));
    }

    static void removeFirst(List<Candidate> list, Candidate c) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).sameAs(c)) {
                list.remove(i);
                return;
            }
        }
    }
}
