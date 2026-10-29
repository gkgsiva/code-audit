package com.legacyaudit.engine.inventory;

/** POSIX path pieces as Python's {@code PurePosixPath} gives them. */
public final class Paths {

    private Paths() {}

    /** Last component ({@code "a/b.txt"} -> {@code "b.txt"}). */
    public static String name(String path) {
        String p = path;
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        int i = p.lastIndexOf('/');
        String n = i < 0 ? p : p.substring(i + 1);
        return n.equals(".") ? "" : n;
    }

    /** Extension with its dot ({@code ".java"}); empty for {@code ".profile"}, {@code "Makefile"}, {@code "x."}. */
    public static String suffix(String path) {
        String name = name(path);
        int i = name.lastIndexOf('.');
        return i > 0 && i < name.length() - 1 ? name.substring(i) : "";
    }

    /** Name without its extension. */
    public static String stem(String path) {
        String name = name(path);
        int i = name.lastIndexOf('.');
        return i > 0 && i < name.length() - 1 ? name.substring(0, i) : name;
    }

    /** Parent folder; {@code ""} at the top. */
    public static String parent(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    /** {@code path} is {@code folder} or inside it; every path is under {@code ""}. */
    public static boolean under(String path, String folder) {
        return folder.isEmpty() || path.equals(folder) || path.startsWith(folder + "/");
    }

    /** The folders of a relative path, outermost first, without the file name. */
    public static String[] dirs(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? new String[0] : path.substring(0, i).split("/");
    }

    /** Relative path in POSIX form, whatever separators the input used, without outer slashes. */
    public static String posix(String rel) {
        String p = rel.replace('\\', '/');
        int a = 0;
        int b = p.length();
        while (a < b && p.charAt(a) == '/') {
            a++;
        }
        while (b > a && p.charAt(b - 1) == '/') {
            b--;
        }
        return p.substring(a, b);
    }

    /** Python's {@code os.path.splitext}: extension of the name, a leading dot does not count. */
    public static String[] splitext(String name) {
        int i = name.lastIndexOf('.');
        int start = 0;
        while (start < name.length() && name.charAt(start) == '.') {
            start++;
        }
        if (i <= start - 1 || i < start) {
            return new String[] {name, ""};
        }
        return new String[] {name.substring(0, i), name.substring(i)};
    }
}
