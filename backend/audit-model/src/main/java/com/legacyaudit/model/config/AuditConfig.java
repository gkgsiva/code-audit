package com.legacyaudit.model.config;

import com.legacyaudit.model.facts.ComponentType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A validated {@code audit.yaml}. Only what the scan uses so far is typed; every other section is
 * still checked (unknown keys, value types) and kept in {@link #dump()}.
 *
 * @param dump every setting with its defaults, as read; the config hash is computed from it
 */
public record AuditConfig(
        Application application,
        Source source,
        List<String> include,
        List<String> exclude,
        List<ComponentSpec> components,
        String inputsDir,
        String outputDir,
        String overridesFile,
        Target target,
        Map<String, String> counting,
        Inventory inventory,
        List<String> copybookPaths,
        int duplicationTokens,
        List<String> rulePacks,
        Map<String, Object> dump) {

    public record Application(String name, String description, String owner) {}

    /** Where the code is: folders on disk or Git repositories. */
    public sealed interface Source permits LocalSource, GitSource {}

    public record LocalSource(List<String> paths) implements Source {}

    public record GitSource(List<GitRepo> repos, String cacheDir) implements Source {}

    public record GitRepo(String url, String ref, List<String> paths, String name, String tokenEnv) {}

    /**
     * A component named in audit.yaml; it takes precedence over the detected ones for the files
     * under its folder.
     */
    public record ComponentSpec(String name, String path, ComponentType type, String root) {}

    /** The migration target (decision 161). */
    public record Target(
            String profile,
            String java,
            String springBoot,
            String angular,
            String wildfly,
            String jakartaEe,
            String database,
            String cloud) {

        public static final String DEFAULT = "spring-boot-angular";
        public static final String WILDFLY = "wildfly-gke";
        public static final String NONE = "none";
        static final List<String> PROFILES = List.of(DEFAULT, WILDFLY, NONE);
        static final List<String> STAGES =
                List.of("service_mapping", "data_migration", "dependency_plan", "target_architecture", "plan");

        public String label() {
            if (NONE.equals(profile)) {
                return "Audit only (no target)";
            }
            String db = database.equalsIgnoreCase("postgresql") ? "PostgreSQL" : database;
            if (WILDFLY.equals(profile)) {
                return "WildFly " + wildfly + " (Jakarta EE " + jakartaEe + ", Java " + java + ") on GKE, database on " + db;
            }
            return "Java " + java + ", Spring Boot " + springBoot + ", Angular " + angular + ", " + db + " on GCP";
        }

        /** What {@code data/target.json} and {@code run.json > target} hold. */
        public Map<String, Object> info() {
            Map<String, Object> versions = new LinkedHashMap<>();
            if (!NONE.equals(profile)) {
                versions.put("java", java);
                versions.put("database", database);
                versions.put("cloud", cloud);
            }
            if (DEFAULT.equals(profile)) {
                versions.put("spring_boot", springBoot);
                versions.put("angular", angular);
            } else if (WILDFLY.equals(profile)) {
                versions.put("wildfly", wildfly);
                versions.put("jakarta_ee", jakartaEe);
                versions.put("platform", "GKE");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("profile", profile);
            out.put("label", label());
            out.put("audit_only", NONE.equals(profile));
            out.put("versions", versions);
            out.put("skipped_stages", NONE.equals(profile) ? STAGES : List.of());
            return out;
        }
    }

    /**
     * @param maxFileSizeMb larger files are hashed and counted but not read
     * @param componentGrouping {@code auto} (decision 176) or {@code module}
     * @param workers threads that read files; 0 = one per CPU less one, at most 8
     */
    public record Inventory(
            boolean includeBinaries,
            double maxFileSizeMb,
            boolean useDefaultExcludes,
            boolean skipBuildOutput,
            String componentGrouping,
            int workers) {}

    /** A branch, tag or commit: no spaces or shell/option characters, never starting with "-". */
    public static final Pattern GIT_REF = Pattern.compile("^[A-Za-z0-9_.][A-Za-z0-9_./@+-]{0,199}$");

    /** Supplementary input kinds ({@code audit-inputs/<kind>/}) and their labels. */
    public static final Map<String, String> INPUT_KINDS = ordered(
            "autosys", "Autosys JIL",
            "mq", "IBM MQ definitions (MQSC)",
            "cft", "CFT configuration export",
            "ddl", "Database DDL",
            "crontab", "Crontab entries",
            "server-config", "Server configuration",
            "security", "Vulnerability reports (Dependency-Check / OSV-Scanner JSON)");

    /** The counting rules every report prints; {@code counting:} in audit.yaml replaces single ones. */
    public static final Map<String, String> COUNTING_RULES = ordered(
            "service",
            "One independently invokable business operation: Struts action, Spring MVC handler, servlet endpoint, SOAP/REST operation, remotely exposed EJB method, CICS transaction, COBOL main program, batch job, C/C++ daemon or executable. Utility libraries and copybooks are shared components, not services.",
            "screen",
            "One rendered UI page or map: JSP/JSF/HTML page that is a forward/view target, BMS map, terminal form. JSP fragments/tiles are counted separately as partials.",
            "db_query",
            "One distinct SQL statement after normalizing whitespace and bind variables. Reported as total, distinct, by type and by PostgreSQL grade; dynamic SQL is flagged.",
            "stored_procedure",
            "One package/procedure/function, counted separately and graded manual or review.",
            "integration",
            "One interface per (type, target), whatever direction or spelling each usage has: the same FTP host used by 10 scripts is 1 integration with 10 usages; a target both read and written is one row with direction both; host case, a default port, a trailing slash or a queue:/// prefix do not make a second row, and HTTP and SOAP calls to the same URL are one SOAP integration. Calls to an identity provider (Okta, Entra ID, ...) have purpose 'login / authentication'.",
            "batch_job",
            "One schedulable job (Autosys job, JCL job, cron entry, shell wrapper, Spring Batch job). Steps are listed under it; Autosys boxes are containers, not jobs. A component doing batch work that no scheduler definition covers counts as one batch, however many ksh scripts and programs it holds (they are its steps). The All batches list shows each once, with when it runs from the scheduler or, without one, from what the code states (cron expressions, @Scheduled, comments).",
            "library",
            "One distinct (group, artifact, version). Undeclared jars identified by manifest or hash.",
            "loc",
            "Lines of code per file split into code, comment and blank using the language's comment syntax. A line holding both code and a comment counts as code. Every text file is counted under the language its extension names; a text extension no rule knows is listed as 'other (.ext)'. Binary files (by extension, a NUL byte or mostly control bytes, e.g. .class, .o, .exe) are left out, data files (.dat, .csv) and backup copies (.bak, .old, ~, .java_old, .sql.20190312) are listed with no lines.");

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(out);
    }
}
