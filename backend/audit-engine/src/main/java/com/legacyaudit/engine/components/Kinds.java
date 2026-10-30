package com.legacyaudit.engine.components;

import com.legacyaudit.engine.inventory.Paths;
import com.legacyaudit.model.facts.Category;
import com.legacyaudit.model.facts.ComponentType;
import com.legacyaudit.model.facts.FileRecord;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Component types from the inventory alone, and the folder names the component rules know. */
public final class Kinds {

    /** Categories that make a component's technology (and make a folder count as code). */
    static final Set<Category> TECH_CATEGORIES = Set.of(
            Category.SOURCE, Category.SCREEN, Category.SCRIPT, Category.SQL, Category.JOB, Category.TEST, Category.CONFIG);

    /** Files that make their folder a component root, besides the build files. */
    static final Set<String> PROJECT_FILES = Set.of(
            ".project", "cmakelists.txt", "configure.ac", "configure.in", "imakefile", "bower.json", "settings.gradle.kts");

    static final String[] PROJECT_SUFFIXES = {".vcxproj", ".vcproj", ".dsp", ".vbp", ".csproj", ".vbproj"};

    /** Folder names that hold an application's code: their parent is the application. */
    static final Set<String> CODE_FOLDERS = Set.of(
            "src", "source", "sources", "java", "main", "webcontent", "webroot", "webapp", "web-inf", "javasource", "javasrc");

    /**
     * Folder names that are a part of whatever application they sit in (never a component on their
     * own next to code). Java package roots are here too, for sources kept without a src/ folder.
     */
    static final Set<String> PART_FOLDERS = union(CODE_FOLDERS, Set.of(
            "web", "jsp", "jsps", "pages", "html", "resources", "res", "conf", "config", "configs",
            "cfg", "configuration", "properties", "props", "prop", "param", "params", "parm", "etc",
            "env", "bin", "scripts", "script", "shell", "ksh", "sh", "lib", "libs", "include", "inc",
            "includes", "sql", "ddl", "plsql", "db", "database", "jcl", "proclib", "proc", "procs",
            "cobol", "cbl", "cpy", "copy", "copybook", "copybooks", "bms", "maps", "ctl", "control",
            "data", "logs", "log", "test", "tests", "doc", "docs", "meta-inf", "images", "img", "css",
            "js", "static", "templates", "tmp", "temp", "jobs", "job", "cron", "batch-scripts",
            "com", "org", "net", "fr", "de", "it", "uk", "edu", "gov", "io",
            "forms", "modules", "classes", "usercontrols", "controls", "designers", "views",
            "controllers", "models", "viewmodels", "app_code", "app_data", "app_start"));

    /** Folder names that say "batch" (a script under one of them is batch work, not a utility). */
    static final Set<String> BATCH_FOLDERS = Set.of(
            "batch", "batchs", "batches", "jobs", "job", "cron", "sched", "scheduler", "nightly", "eod", "daily", "monthly", "weekly");

    /** Folders of a batch folder that hold what its batches share, not a batch of their own. */
    static final Set<String> SHARED_FOLDERS = Set.of(
            "common", "commun", "commons", "shared", "share", "util", "utils", "utilities", "tools",
            "functions", "fonctions", "lib", "libs", "include", "includes", "inc", "env", "conf",
            "config", "configs", "cfg", "properties", "props", "param", "params", "parm", "etc");

    /** Component.detectedBy of the shared part of a split batch folder. */
    public static final String BATCH_SHARED = "batch-shared";

    /** Component.detectedBy of a component made of several part folders. */
    static final String GROUPED = "grouped";

    private Kinds() {}

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.addAll(b);
        return Set.copyOf(out);
    }

    static boolean isProjectFile(String name) {
        String low = name.toLowerCase(Locale.ROOT);
        if (com.legacyaudit.engine.inventory.Languages.isBuildFile(low) || PROJECT_FILES.contains(low) || low.startsWith("makefile.")) {
            return true;
        }
        for (String s : PROJECT_SUFFIXES) {
            if (low.endsWith(s)) {
                return true;
            }
        }
        return false;
    }

    public static boolean inBatchFolder(String path) {
        for (String p : Paths.dirs(path)) {
            String low = p.toLowerCase(Locale.ROOT);
            if (BATCH_FOLDERS.contains(low) || low.endsWith("-batch") || low.endsWith("_batch")) {
                return true;
            }
        }
        return false;
    }

    /** A file of the shared part of a batch folder ({@code batch/common/functions.ksh}). */
    public static boolean inSharedFolder(String path) {
        String[] parts = Paths.dirs(path);
        return parts.length >= 2 && SHARED_FOLDERS.contains(parts[parts.length - 1].toLowerCase(Locale.ROOT))
                && BATCH_FOLDERS.contains(parts[parts.length - 2].toLowerCase(Locale.ROOT));
    }

    /**
     * The type from the inventory alone; the linker refines it from what was found (services,
     * screens, jobs). Mixed components take the type of their most specific part: Java + JSP is web;
     * Java or C + ksh + properties is batch when a script or folder says so.
     */
    public static ComponentType inferType(List<FileRecord> files, String name) {
        Set<String> langs = new HashSet<>();
        Set<Category> cats = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (FileRecord f : files) {
            langs.add(f.language);
            cats.add(f.category);
            int i = f.path.lastIndexOf('/');
            names.add((i < 0 ? f.path : f.path.substring(i + 1)).toLowerCase(Locale.ROOT));
        }
        boolean javaWeb = files.stream().anyMatch(f -> f.path.toLowerCase(Locale.ROOT).endsWith("web-inf/web.xml"))
                || (cats.contains(Category.SCREEN)
                        && langs.stream().anyMatch(Set.of("jsp", "bms", "velocity", "freemarker", "aspx", "razor")::contains));
        if (!javaWeb && desktop(files)) {
            return ComponentType.DESKTOP; // VB6, Windows Forms, WPF
        }
        boolean scriptLangs = langs.contains("typescript") || langs.contains("javascript");
        if (!javaWeb && (names.contains("angular.json") || (scriptLangs && cats.contains(Category.SCREEN) && langs.contains("html")))) {
            return ComponentType.FRONTEND; // Angular, AngularJS or another single-page app
        }
        if (javaWeb || (cats.contains(Category.SCREEN) && langs.contains("html"))) {
            return ComponentType.WEB;
        }
        boolean batchy = inBatchFolder(name + "/x")
                || files.stream().anyMatch(f -> f.category == Category.SCRIPT && inBatchFolder(f.path));
        if (cats.contains(Category.JOB) || (batchy && (cats.contains(Category.SCRIPT) || cats.contains(Category.SOURCE)))) {
            return ComponentType.BATCH;
        }
        if (cats.contains(Category.SOURCE)) {
            return ComponentType.LIBRARY;
        }
        if (cats.contains(Category.SCRIPT)) {
            return ComponentType.SCRIPT;
        }
        if (cats.contains(Category.SQL)) {
            return ComponentType.DB;
        }
        if (cats.contains(Category.CONFIG)) {
            return ComponentType.CONFIG;
        }
        return ComponentType.UNKNOWN;
    }

    /**
     * A desktop application by its files: VB6 forms, Windows Forms designer files, WPF windows. Java
     * Swing is only seen in the code, so the linker decides it.
     */
    private static boolean desktop(List<FileRecord> files) {
        for (FileRecord f : files) {
            String low = f.path.toLowerCase(Locale.ROOT);
            if (f.category == Category.TEST) {
                continue;
            }
            if (low.endsWith(".frm") && f.language.equals("vb6")) {
                return true;
            }
            if ((low.endsWith(".designer.cs") || low.endsWith(".designer.vb"))
                    && !(low.endsWith("resources.designer.cs") || low.endsWith("settings.designer.cs")
                            || low.endsWith("resources.designer.vb") || low.endsWith("settings.designer.vb"))) {
                return true;
            }
            if (f.language.equals("xaml") && !(low.endsWith("app.xaml") || low.endsWith("application.xaml"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The shared part of a batch folder: configuration, helper scripts or code, never a batch of
     * its own (its batches are the folders beside it).
     */
    static ComponentType sharedType(List<FileRecord> files) {
        Set<Category> cats = new HashSet<>();
        files.forEach(f -> cats.add(f.category));
        if (cats.contains(Category.SOURCE)) {
            return ComponentType.LIBRARY;
        }
        if (cats.contains(Category.SCRIPT)) {
            return ComponentType.SCRIPT;
        }
        if (cats.contains(Category.SQL)) {
            return ComponentType.DB;
        }
        if (cats.contains(Category.CONFIG)) {
            return ComponentType.CONFIG;
        }
        return ComponentType.UNKNOWN;
    }

    /** Files by category, keys sorted. */
    static Map<String, Integer> composition(List<FileRecord> files) {
        Map<String, Integer> out = new TreeMap<>();
        for (FileRecord f : files) {
            out.merge(f.category.value(), 1, Integer::sum);
        }
        return out;
    }
}
