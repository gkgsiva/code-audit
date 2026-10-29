package com.legacyaudit.engine.inventory;

import static com.legacyaudit.engine.inventory.LanguageTables.BUILD_OUTPUT_DIRS;
import static com.legacyaudit.engine.inventory.LanguageTables.COMPILED_EXTENSIONS;
import static com.legacyaudit.engine.inventory.LanguageTables.DEFAULT_EXCLUDES;
import static com.legacyaudit.engine.inventory.LanguageTables.GENERATED_BY;
import static com.legacyaudit.engine.inventory.LanguageTables.MINIFY_EXTENSIONS;
import static com.legacyaudit.engine.inventory.LanguageTables.SOURCE_EXTENSIONS;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Walks source roots honouring include/exclude globs (gitignore syntax) and default excludes, in a
 * stable (sorted) order: a folder's files, then each sub-folder. Symbolic links are not followed
 * (no loops, nothing outside the tree).
 *
 * <p>With {@code skipBuildOutput}, compiler and IDE output folders, files a precompiler generated
 * from a source next to them, and minified code are left out and reported as {@link Skipped}.
 */
public final class Walker {

    /** Files the .NET build writes into obj/ ({@code MainWindow.g.cs}, assembly attributes). */
    private static final Pattern DOTNET_GENERATED = Rx.of(
            "(\\.g\\.(i\\.)?(cs|vb)|\\.AssemblyAttributes\\.(cs|vb)|\\.AssemblyInfo\\.(cs|vb)|\\.GlobalUsings\\.g\\.cs)$", Rx.I);

    private static final String[] DOTNET_PROJECT = {".csproj", ".vbproj", ".fsproj"};

    /** Minified or bundled JavaScript/CSS, by name (decision 128). */
    private static final Pattern MINIFIED_NAME = Rx.of(
            "(\\.min\\.(js|mjs|cjs|css)|[.-](bundle|chunk)\\.js|\\.(js|css)\\.map|"
                    + "^(main|polyfills|runtime|vendor|styles|scripts)\\.[0-9a-f]{8,}\\.(js|css)|"
                    + "\\.[0-9a-f]{16,}\\.(js|css))$",
            Rx.I);

    private static final int MINIFIED_LINE = 2000;

    private static final Pattern OUTPUT_ENTRY =
            Pattern.compile("<classpathentry\\b[^>]*\\bkind=\"output\"[^>]*\\bpath=\"([^\"]+)\"");

    private static final int SCAN_LIMIT = 5000;

    /** A folder ({@code rel} ends with /) or file left out on purpose (reported, never silent). */
    public record Skipped(String root, String rel, String reason, int files) {}

    /** A selected file: the root it belongs to, where it is, its path relative to the root. */
    public record Walked(String root, Path absPath, String rel) {}

    /** Which files a walk selects. */
    public record Filters(GitIgnore include, GitIgnore exclude, boolean skipBuildOutput) {

        public static Filters of(List<String> include, List<String> exclude, boolean defaultExcludes, List<String> extraExcludes,
                boolean skipBuildOutput) {
            List<String> ex = new ArrayList<>();
            if (defaultExcludes) {
                ex.addAll(DEFAULT_EXCLUDES);
            }
            ex.addAll(exclude);
            ex.addAll(extraExcludes);
            return new Filters(GitIgnore.of(include.isEmpty() ? List.of("**") : include), GitIgnore.of(ex), skipBuildOutput);
        }

        /** Every file, nothing excluded but the defaults. */
        public static Filters all() {
            return of(List.of("**"), List.of(), true, List.of(), true);
        }

        boolean dirExcluded(String relDir) {
            return exclude.matches(relDir + "/");
        }

        boolean fileSelected(String rel) {
            return include.matches(rel) && !exclude.matches(rel);
        }
    }

    private Walker() {}

    /**
     * Patterns excluding tool-owned paths (output folder, config files) inside {@code root}.
     * A folder, or a name without an extension, gets a trailing {@code /}.
     */
    public static List<String> anchoredExcludes(Path root, List<Path> paths) {
        List<String> out = new ArrayList<>();
        Path r = real(root);
        for (Path p : paths) {
            Path rp = real(p);
            if (!rp.startsWith(r) || rp.equals(r)) {
                continue;
            }
            String rel = r.relativize(rp).toString().replace('\\', '/');
            boolean dir = Files.isDirectory(p) || Paths.suffix(rel).isEmpty();
            out.add("/" + rel + (dir ? "/" : ""));
        }
        return out;
    }

    private static Path real(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        try {
            return abs.toRealPath();
        } catch (IOException e) {
            Path parent = abs.getParent();
            return parent == null ? abs : real(parent).resolve(abs.getFileName());
        }
    }

    /** Selected files in walk order; {@code skipped} receives what was left out on purpose. */
    public static List<Walked> walk(String rootName, Path root, Filters filters, List<String> subpaths, List<Skipped> skipped)
            throws IOException {
        List<Walked> out = new ArrayList<>();
        List<Path> starts = new ArrayList<>();
        if (subpaths == null || subpaths.isEmpty()) {
            starts.add(root);
        } else {
            for (String s : subpaths) {
                starts.add(root.resolve(Paths.posix(s)));
            }
        }
        Set<String> declared = new HashSet<>(); // Eclipse output folders, relative to the root
        for (Path start : starts) {
            if (Files.exists(start)) {
                walkDir(rootName, root, start, filters, skipped, declared, out);
            }
        }
        return out;
    }

    private static void walkDir(String rootName, Path root, Path here, Filters filters, List<Skipped> skipped, Set<String> declared,
            List<Walked> out) throws IOException {
        String relDir = here.equals(root) ? "" : Paths.posix(root.relativize(here).toString());
        List<String> dirNames = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();
        try (Stream<Path> kids = Files.list(here)) {
            for (Path k : (Iterable<Path>) kids::iterator) {
                // Like os.walk: a link to a folder is listed as a folder (and then left out).
                if (Files.isDirectory(k)) {
                    dirNames.add(k.getFileName().toString());
                } else {
                    fileNames.add(k.getFileName().toString());
                }
            }
        } catch (IOException e) {
            return; // os.walk skips folders it cannot list
        }
        if (filters.skipBuildOutput() && fileNames.contains(".classpath")) {
            for (String o : eclipseOutputDirs(here)) {
                declared.add(relDir.isEmpty() ? o : relDir + "/" + o);
            }
        }
        dirNames.sort(String::compareTo);
        fileNames.sort(String::compareTo);
        List<String> keep = new ArrayList<>();
        for (String d : dirNames) {
            String rel = relDir.isEmpty() ? d : relDir + "/" + d;
            Path dir = here.resolve(d);
            if (Files.isSymbolicLink(dir) || filters.dirExcluded(rel)) {
                continue;
            }
            if (filters.skipBuildOutput()) {
                String reason = "";
                int n = 0;
                String low = d.toLowerCase(Locale.ROOT);
                if (declared.contains(rel)) {
                    n = buildOutputFiles(dir);
                    if (n == 0) {
                        n = countFiles(dir);
                    }
                    reason = "Eclipse output folder (.classpath)";
                } else if (low.equals("obj") && fileNames.stream().anyMatch(f -> endsWithAny(f.toLowerCase(Locale.ROOT), DOTNET_PROJECT))) {
                    // .NET intermediate output next to its project file: never source, whatever it holds.
                    n = countFiles(dir);
                    reason = ".NET intermediate output (obj next to the project file)";
                } else if (low.equals("packages") && nugetPackages(dir)) {
                    n = countFiles(dir);
                    reason = "NuGet packages folder (downloaded libraries, not code)";
                } else if (BUILD_OUTPUT_DIRS.contains(low)) {
                    n = buildOutputFiles(dir);
                    reason = n > 0 ? "compiled output (no source or script inside)" : "";
                }
                if (!reason.isEmpty()) {
                    if (skipped != null) {
                        skipped.add(new Skipped(rootName, rel + "/", reason, n));
                    }
                    continue;
                }
            }
            keep.add(d);
        }
        Set<String> names = new HashSet<>();
        for (String f : fileNames) {
            names.add(f.toLowerCase(Locale.ROOT));
        }
        for (String f : fileNames) {
            String rel = relDir.isEmpty() ? f : relDir + "/" + f;
            Path abs = here.resolve(f);
            if (Files.isSymbolicLink(abs) || !Files.isRegularFile(abs)) {
                continue;
            }
            if (!filters.fileSelected(rel)) {
                continue;
            }
            String[] se = Paths.splitext(f);
            String[] gen = GENERATED_BY.get(se[1].toLowerCase(Locale.ROOT));
            if (filters.skipBuildOutput() && gen != null && names.contains((se[0] + gen[0]).toLowerCase(Locale.ROOT))) {
                if (skipped != null) {
                    skipped.add(new Skipped(rootName, rel, "generated by " + gen[1], 1));
                }
                continue;
            }
            if (filters.skipBuildOutput() && isMinified(abs, f)) {
                if (skipped != null) {
                    skipped.add(new Skipped(rootName, rel, "minified or bundled code", 1));
                }
                continue;
            }
            out.add(new Walked(rootName, abs, rel));
        }
        for (String d : keep) {
            walkDir(rootName, root, here.resolve(d), filters, skipped, declared, out);
        }
    }

    private static boolean endsWithAny(String s, String[] ends) {
        for (String e : ends) {
            if (s.endsWith(e)) {
                return true;
            }
        }
        return false;
    }

    /** True for compiled output, false for source, null for anything else (resources). */
    private static Boolean isCompiled(Path path, String ext) {
        if (COMPILED_EXTENSIONS.contains(ext) || DOTNET_GENERATED.matcher(path.getFileName().toString()).find()) {
            return true;
        }
        if (SOURCE_EXTENSIONS.contains(ext)) {
            return false;
        }
        if (!ext.isEmpty()) {
            return null;
        }
        byte[] head;
        try (InputStream in = Files.newInputStream(path)) { // no extension: an executable or a script
            head = in.readNBytes(4);
        } catch (IOException e) {
            return null;
        }
        if (head.length >= 2 && head[0] == '#' && head[1] == '!') {
            return false;
        }
        int[][] magic = {{0x7f, 'E', 'L', 'F'}, {'M', 'Z'}, {0x01, 0xdf}, {0x01, 0xf7}, {0xcf, 0xfa}, {0xfe, 0xed}};
        for (int[] m : magic) {
            if (head.length >= m.length) {
                boolean hit = true;
                for (int i = 0; i < m.length; i++) {
                    hit &= (head[i] & 0xFF) == m[i];
                }
                if (hit) {
                    return true;
                }
            }
        }
        return null;
    }

    /**
     * A minified or bundled JavaScript/CSS file, by its name or its content (a line over 2,000
     * characters in the first 64 KB, and few lines).
     */
    static boolean isMinified(Path path, String name) {
        if (MINIFIED_NAME.matcher(name).find()) {
            return true;
        }
        if (!MINIFY_EXTENSIONS.contains(Paths.splitext(name)[1].toLowerCase(Locale.ROOT))) {
            return false;
        }
        byte[] head;
        try (InputStream in = Files.newInputStream(path)) {
            head = in.readNBytes(64 * 1024);
        } catch (IOException e) {
            return false;
        }
        int lines = 1;
        int longest = 0;
        int run = 0;
        for (byte b : head) {
            if (b == '\n') {
                lines++;
                longest = Math.max(longest, run);
                run = 0;
            } else {
                run++;
            }
        }
        longest = Math.max(longest, run);
        return longest > MINIFIED_LINE && (double) head.length / Math.max(lines, 1) > 300;
    }

    /** A NuGet {@code packages/} folder: package folders holding a {@code .nupkg} file. */
    private static boolean nugetPackages(Path folder) {
        try (Stream<Path> kids = Files.list(folder)) {
            for (Path kid : (Iterable<Path>) kids::iterator) {
                if (Files.isDirectory(kid)) {
                    try (Stream<Path> files = Files.list(kid)) {
                        if (files.anyMatch(f -> Paths.suffix(f.getFileName().toString()).toLowerCase(Locale.ROOT).equals(".nupkg"))) {
                            return true;
                        }
                    }
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    /**
     * Number of files in {@code folder} when it looks like compiler or IDE output (compiled files
     * and no source or script), else 0.
     */
    static int buildOutputFiles(Path folder) {
        int[] counts = new int[2]; // files, compiled
        Boolean verdict = scanOutput(folder, counts);
        if (verdict != null) {
            return verdict ? counts[0] : 0;
        }
        return counts[1] > 0 ? counts[0] : 0;
    }

    /** False as soon as a source file is seen; true or false at the scan limit; null when done. */
    private static Boolean scanOutput(Path dir, int[] counts) {
        List<Path> files = new ArrayList<>();
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> kids = Files.list(dir)) {
            for (Path k : (Iterable<Path>) kids::iterator) {
                if (Files.isDirectory(k)) {
                    dirs.add(k);
                } else {
                    files.add(k);
                }
            }
        } catch (IOException e) {
            return null;
        }
        for (Path f : files) {
            counts[0]++;
            Boolean kind = isCompiled(f, Paths.splitext(f.getFileName().toString())[1].toLowerCase(Locale.ROOT));
            if (Boolean.FALSE.equals(kind)) {
                return false;
            }
            if (Boolean.TRUE.equals(kind)) {
                counts[1]++;
            }
            if (counts[0] >= SCAN_LIMIT) {
                return counts[1] > 0;
            }
        }
        for (Path d : dirs) {
            if (Files.isSymbolicLink(d)) {
                continue;
            }
            Boolean v = scanOutput(d, counts);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** Files in a folder tree (os.walk counts, links to folders not followed). */
    private static int countFiles(Path folder) {
        int n = 0;
        try (Stream<Path> kids = Files.list(folder)) {
            for (Path k : (Iterable<Path>) kids::iterator) {
                if (Files.isDirectory(k)) {
                    if (!Files.isSymbolicLink(k)) {
                        n += countFiles(k);
                    }
                } else {
                    n++;
                }
            }
        } catch (IOException e) {
            return n;
        }
        return n;
    }

    /** Output folders declared in an Eclipse {@code .classpath} ({@code kind="output"}), relative to {@code folder}. */
    private static List<String> eclipseOutputDirs(Path folder) {
        String text;
        try {
            text = new String(Files.readAllBytes(folder.resolve(".classpath")), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Matcher m = OUTPUT_ENTRY.matcher(text);
        while (m.find()) {
            String p = m.group(1);
            if (!Path.of(p).isAbsolute() && !p.startsWith("/")) {
                out.add(Paths.posix(p));
            }
        }
        return out;
    }
}
