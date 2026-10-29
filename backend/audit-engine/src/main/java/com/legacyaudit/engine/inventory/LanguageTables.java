package com.legacyaudit.engine.inventory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The tables behind language, category and build-output detection, as the Python tool had them
 * (inventory/language.py, walker.py, components.py on office-main). Keep the two in step.
 */
public final class LanguageTables {

    private LanguageTables() {}

    /** Language by extension (lower case, with the dot). */
    static final Map<String, String> EXTENSIONS = ordered(
            ".java", "java",
            ".jws", "java",
            ".jsp", "jsp",
            ".jspf", "jsp",
            ".jspx", "jsp",
            ".tag", "jsp",
            ".tld", "xml",
            ".xml", "xml",
            ".xsd", "xml",
            ".wsdl", "xml",
            ".xsl", "xml",
            ".xslt", "xml",
            ".dtd", "xml",
            ".xmi", "xml",
            ".xjb", "xml",
            ".wsdd", "xml",
            ".jrxml", "xml",
            ".bpmn", "xml",
            ".tagx", "jsp",
            ".properties", "properties",
            ".policy", "properties",
            ".yml", "yaml",
            ".yaml", "yaml",
            ".ini", "ini",
            ".cfg", "ini",
            ".conf", "ini",
            ".toml", "ini",
            ".json", "json",
            ".c", "c",
            ".h", "c",
            ".cpp", "cpp",
            ".cc", "cpp",
            ".cxx", "cpp",
            ".hpp", "cpp",
            ".hh", "cpp",
            ".hxx", "cpp",
            ".pc", "proc",
            ".sqc", "proc",
            ".ec", "proc",
            ".sqb", "procobol",
            ".pco", "procobol",
            ".cbl", "cobol",
            ".cob", "cobol",
            ".cobol", "cobol",
            ".cpy", "cobol",
            ".copy", "cobol",
            ".cbk", "cobol",
            ".jcl", "jcl",
            ".proc", "jcl",
            ".bms", "bms",
            ".sh", "shell",
            ".ksh", "shell",
            ".bash", "shell",
            ".csh", "shell",
            ".zsh", "shell",
            ".env", "shell",
            ".profile", "shell",
            ".awk", "awk",
            ".sed", "sed",
            ".tcl", "tcl",
            ".ps1", "powershell",
            ".vbs", "vbscript",
            ".pl", "perl",
            ".pm", "perl",
            ".py", "python",
            ".bat", "batch",
            ".cmd", "batch",
            ".sql", "sql",
            ".ddl", "sql",
            ".pks", "plsql",
            ".pkb", "plsql",
            ".pls", "plsql",
            ".plb", "plsql",
            ".prc", "plsql",
            ".fnc", "plsql",
            ".trg", "plsql",
            ".pkg", "plsql",
            ".pck", "plsql",
            ".spc", "plsql",
            ".bdy", "plsql",
            ".tps", "plsql",
            ".tpb", "plsql",
            ".typ", "plsql",
            ".vw", "sql",
            ".ctl", "sqlloader",
            ".jil", "jil",
            ".mqsc", "mqsc",
            ".mqs", "mqsc",
            ".js", "javascript",
            ".mjs", "javascript",
            ".cjs", "javascript",
            ".jsx", "javascript",
            ".ts", "typescript",
            ".tsx", "typescript",
            ".mts", "typescript",
            ".cts", "typescript",
            ".prisma", "prisma",
            ".kt", "kotlin",
            ".scala", "scala",
            ".sqlj", "sqlj",
            ".cs", "csharp",
            ".csx", "csharp",
            ".vb", "vbnet",
            ".bas", "vb6",
            ".frm", "vb6",
            ".cls", "vb6",
            ".dob", "vb6",
            ".pag", "vb6",
            ".dsr", "vb6",
            ".vbp", "vb6-project",
            ".vbg", "vb6-project",
            ".vbw", "ini",
            ".csproj", "msbuild",
            ".vbproj", "msbuild",
            ".sln", "msbuild",
            ".props", "msbuild",
            ".targets", "msbuild",
            ".nuspec", "xml",
            ".resx", "xml",
            ".settings", "xml",
            ".config", "xml",
            ".xaml", "xaml",
            ".fxml", "fxml",
            ".aspx", "aspx",
            ".ascx", "aspx",
            ".master", "aspx",
            ".cshtml", "razor",
            ".vbhtml", "razor",
            ".razor", "razor",
            ".asmx", "aspnet",
            ".svc", "aspnet",
            ".ashx", "aspnet",
            ".asax", "aspnet",
            ".php", "php",
            ".rb", "ruby",
            ".go", "go",
            ".html", "html",
            ".htm", "html",
            ".xhtml", "html",
            ".vm", "velocity",
            ".ftl", "freemarker",
            ".css", "css",
            ".scss", "css",
            ".sass", "css",
            ".less", "css",
            ".gradle", "groovy",
            ".groovy", "groovy",
            ".mk", "make",
            ".mak", "make",
            ".md", "markdown",
            ".txt", "text",
            ".rst", "text",
            ".log", "text",
            ".lst", "text",
            ".lis", "text",
            ".out", "text",
            ".csv", "data",
            ".tsv", "data",
            ".psv", "data",
            ".dat", "data",
            ".del", "data",
            ".mf", "manifest");

    /** Archives: always inventoried (library detection reads them). */
    static final Set<String> ARCHIVE_EXTENSIONS = Set.of(
            ".ear", ".gz", ".jar", ".rar", ".tar", ".tgz", ".war", ".zip");

    /** Binary files, archives included. */
    static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".7z", ".a", ".avi", ".baml", ".bmp", ".bz2", ".class", ".ctx", ".db", ".dcx", ".der", ".dll",
            ".dmp", ".doc", ".docx", ".dox", ".dsx", ".dylib", ".ear", ".eot", ".exe", ".frx", ".gif", ".gnt",
            ".gz", ".ico", ".idy", ".ilk", ".int", ".jar", ".jasper", ".jks", ".jpeg", ".jpg", ".keystore",
            ".lbr", ".lib", ".mdb", ".mp3", ".mp4", ".nupkg", ".o", ".obj", ".ocx", ".olb", ".p12", ".pdb",
            ".pdf", ".pfx", ".pgx", ".png", ".ppt", ".pptx", ".pyc", ".pyo", ".rar", ".res", ".resources",
            ".ser", ".snk", ".so", ".sqlite", ".suo", ".tar", ".tgz", ".tif", ".tiff", ".tlb", ".ttf", ".war",
            ".woff", ".woff2", ".xls", ".xlsx", ".xz", ".z", ".zip");

    /** Language by file name (lower case). */
    static final Map<String, String> FILENAMES = ordered(
            "makefile", "make",
            "gnumakefile", "make",
            "crontab", "crontab",
            "manifest.mf", "manifest",
            "dockerfile", "dockerfile",
            "containerfile", "dockerfile",
            ".project", "xml",
            ".classpath", "xml",
            "cmakelists.txt", "make",
            ".profile", "shell",
            ".kshrc", "shell",
            ".bashrc", "shell",
            ".bash_profile", "shell",
            ".env", "shell",
            ".nvmrc", "text",
            ".htaccess", "ini",
            "web.config", "xml",
            "package-lock.json", "lockfile",
            "npm-shrinkwrap.json", "lockfile",
            "yarn.lock", "lockfile",
            "pnpm-lock.yaml", "lockfile",
            "packages.lock.json", "lockfile",
            "packages.config", "xml",
            "nuget.config", "xml");

    /** Copies kept beside the real file: listed, their lines not counted. */
    static final Set<String> BACKUP_EXTENSIONS = Set.of(
            ".backup", ".bak", ".bkp", ".copy_of", ".old", ".orig", ".prev", ".rej", ".sav", ".save", ".swp",
            ".tmp");

    /** Languages listed in the inventory whose lines are not code. */
    static final Set<String> NO_LOC_LANGUAGES = Set.of(
            "backup", "data", "lockfile");

    /** Build files (lower-case names). */
    static final Set<String> BUILD_FILES = Set.of(
            "angular.json", "build.gradle", "build.gradle.kts", "build.xml", "gnumakefile", "ivy.xml",
            "makefile", "package.json", "pom.xml", "settings.gradle");

    /** npm lock files. */
    public static final Set<String> NPM_LOCK_FILES = Set.of(
            "npm-shrinkwrap.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock");

    /** NuGet package lists. */
    public static final Set<String> NUGET_FILES = Set.of(
            "directory.packages.props", "packages.config", "packages.lock.json");

    /** Project files whose language is a build language. */
    static final Set<String> BUILD_LANGUAGES = Set.of(
            "make", "msbuild", "vb6-project");

    /** Screen languages. */
    static final Set<String> SCREEN_LANGUAGES = Set.of(
            "aspx", "bms", "freemarker", "fxml", "html", "jsp", "razor", "velocity", "xaml");

    /** Script languages. */
    static final Set<String> SCRIPT_LANGUAGES = Set.of(
            "awk", "batch", "perl", "powershell", "python", "sed", "shell", "tcl", "vbscript");

    /** Job languages. */
    static final Set<String> JOB_LANGUAGES = Set.of(
            "crontab", "jcl", "jil");

    /** Sql languages. */
    static final Set<String> SQL_LANGUAGES = Set.of(
            "plsql", "sql");

    /** Config languages. */
    static final Set<String> CONFIG_LANGUAGES = Set.of(
            "dockerfile", "ini", "json", "manifest", "mqsc", "prisma", "properties", "sqlloader", "xml",
            "yaml");

    /** Doc languages. */
    static final Set<String> DOC_LANGUAGES = Set.of(
            "markdown", "text");

    /** Language of a script by the program its #! line names. */
    static final Map<String, String> SHEBANG_LANGUAGES = ordered(
            "sh", "shell",
            "ksh", "shell",
            "bash", "shell",
            "csh", "shell",
            "zsh", "shell",
            "perl", "perl",
            "python", "python",
            "python3", "python",
            "python2", "python");

    /** Folders holding tests. */
    static final Set<String> TEST_DIRS = Set.of(
            "__tests__", "cypress", "e2e", "it", "test", "testing", "tests", "unittest");

    /** Folders holding generated code. */
    static final Set<String> GENERATED_DIRS = Set.of(
            "gen", "gen-src", "generated", "generated-sources");

    /** .NET test project folders end with one of these. */
    static final String[] TEST_DIR_SUFFIXES = {".tests", ".test", ".unittests", ".integrationtests", ".specs"};

    /** Default excludes (gitignore syntax), in order. */
    static final java.util.List<String> DEFAULT_EXCLUDES = java.util.List.of(
            ".git/",
            ".svn/",
            ".hg/",
            "CVS/",
            ".idea/",
            ".vscode/",
            ".settings/",
            ".gradle/",
            "node_modules/",
            "bower_components/",
            "jspm_packages/",
            ".angular/",
            ".nx/",
            ".next/",
            ".nuxt/",
            ".turbo/",
            ".svelte-kit/",
            ".parcel-cache/",
            "target/",
            "build/",
            "dist/",
            "out/",
            "__pycache__/",
            ".venv/",
            ".mypy_cache/",
            ".pytest_cache/",
            ".DS_Store",
            "Thumbs.db",
            ".gitkeep");

    /** Folders compilers and IDEs write into (lower case). */
    static final Set<String> BUILD_OUTPUT_DIRS = Set.of(
            "bin", "build-output", "classes", "debug", "lib64", "obj", "objs", "output", "release",
            "test-classes", "win32", "x64", "x86");

    /** Compiled output. */
    static final Set<String> COMPILED_EXTENSIONS = Set.of(
            ".a", ".baml", ".cache", ".class", ".dll", ".dylib", ".exe", ".exp", ".gnt", ".idy", ".ilk",
            ".int", ".lbr", ".lib", ".nupkg", ".o", ".obj", ".ocx", ".pdb", ".pyc", ".pyo", ".resources",
            ".sl", ".so", ".tlb");

    /** Source and scripts: a folder holding one is not build output. */
    static final Set<String> SOURCE_EXTENSIONS = Set.of(
            ".aspx", ".bas", ".bash", ".bat", ".c", ".cbl", ".cc", ".cjs", ".cls", ".cmd", ".cob", ".cpp",
            ".cpy", ".cs", ".csh", ".cshtml", ".ctl", ".cxx", ".frm", ".groovy", ".h", ".hpp", ".htm",
            ".html", ".java", ".jcl", ".jil", ".js", ".jsp", ".jspf", ".jsx", ".ksh", ".mjs", ".pc", ".pco",
            ".pkb", ".pks", ".pl", ".pm", ".py", ".razor", ".sh", ".sql", ".ts", ".tsx", ".vb", ".xaml",
            ".zsh");

    /** Precompilers write a generated file next to its source: extension -> (source extension, tool). */
    static final Map<String, String[]> GENERATED_BY = Map.of(
            ".c", new String[] {".pc", "the Pro*C precompiler"},
            ".cpp", new String[] {".pc", "the Pro*C precompiler"},
            ".cob", new String[] {".pco", "the Pro*COBOL precompiler"},
            ".cbl", new String[] {".pco", "the Pro*COBOL precompiler"},
            ".java", new String[] {".sqlj", "the SQLJ translator"});

    /** Extensions of minifiable code. */
    static final Set<String> MINIFY_EXTENSIONS = Set.of(
            ".cjs", ".css", ".js", ".mjs");

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return Collections.unmodifiableMap(out);
    }
}
