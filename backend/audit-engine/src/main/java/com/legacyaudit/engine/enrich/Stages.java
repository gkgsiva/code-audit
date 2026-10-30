package com.legacyaudit.engine.enrich;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enrichment stages (thirteen; stage 8 writes two files) and what each is built from.
 *
 * <p>{@code inputs} are fact files in {@code data/} (an entity name, {@code effort},
 * {@code target-mapping} or {@code target}; the stages that suggest something read {@code target},
 * so changing the target makes them stale); {@code after} are earlier stages whose output the stage
 * reads. A stage is stale when the hash of one of them changed since it was built (see
 * {@link Status}).
 */
public final class Stages {

    private Stages() {}

    /** One stage: its number, file name stem, title, fact inputs and upstream stages. */
    public record Stage(int number, String name, String title, List<String> inputs, List<String> after) {
        public String file() {
            return name + ".json";
        }
    }

    public static final List<Stage> STAGES = List.of(
            new Stage(1, "overview", "Orient", List.of("components", "services", "screens", "sql_statements", "integrations",
                    "batch_jobs", "libraries", "tech_stack"), List.of()),
            new Stage(2, "classification", "Classify", List.of("components", "entry_points"), List.of()),
            new Stage(3, "architecture", "Architecture", List.of("components", "entry_points", "dependencies", "integrations",
                    "web_services", "tech_stack"), List.of("classification")),
            new Stage(4, "flows", "Flows", List.of("services", "screens", "entry_points", "batch_jobs", "integrations",
                    "web_services", "dependencies"), List.of("classification")),
            new Stage(5, "service_mapping", "Services", List.of("services", "components", "target-mapping", "target"),
                    List.of("classification")),
            new Stage(6, "data_migration", "Data", List.of("sql_statements", "db_objects", "target"), List.of()),
            new Stage(7, "dependency_plan", "Dependencies", List.of("libraries", "tech_stack", "target"), List.of()),
            new Stage(8, "risks", "Risks", List.of("findings", "libraries", "tech_stack", "integrations", "batch_jobs",
                    "input_coverage", "target"), List.of()),
            new Stage(8, "assumptions", "Assumptions", List.of("input_coverage", "findings"), List.of()),
            new Stage(9, "target_architecture", "Target architecture", List.of("components", "integrations", "web_services",
                    "tech_stack", "target-mapping", "target"), List.of("architecture", "service_mapping")),
            new Stage(10, "plan", "Plan", List.of("components", "effort", "target"),
                    List.of("risks", "dependency_plan", "target_architecture", "service_mapping", "data_migration")),
            new Stage(11, "narratives", "Narratives", List.of(), List.of("overview", "classification", "architecture", "flows",
                    "service_mapping", "data_migration", "dependency_plan", "risks", "assumptions", "target_architecture", "plan")),
            new Stage(12, "functional", "Functional", List.of("components", "services", "screens", "batch_jobs", "integrations",
                    "web_services", "db_objects", "sql_statements", "dependencies"), List.of("classification", "flows")),
            new Stage(13, "component_docs", "Component documentation", List.of("components", "entry_points", "services",
                    "screens", "batch_jobs", "sql_statements", "db_objects", "integrations", "web_services", "dependencies",
                    "metrics", "findings", "libraries"), List.of("classification")));

    public static final Map<String, Stage> BY_NAME = new LinkedHashMap<>();

    static {
        STAGES.forEach(s -> BY_NAME.put(s.name(), s));
    }

    private static final Map<String, String> FACT_FILES = Map.of(
            "effort", "effort.json", "target-mapping", "target-mapping.json", "target", "target.json");

    /** The file in {@code data/} of a stage input. */
    public static String factFile(String kind) {
        return FACT_FILES.getOrDefault(kind, kind + ".json");
    }
}
