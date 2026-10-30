package com.legacyaudit.reports;

import com.legacyaudit.engine.enrich.Status;
import com.legacyaudit.model.json.PyNum;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PageMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;

/**
 * {@code legacy-audit report --type pdf}: PDF copies of the HTML reports.
 *
 * <p>The HTML reports are printed by a browser in headless mode (Microsoft Edge, Chrome or
 * Chromium), so charts and diagrams look as they do on screen. Nothing is downloaded and the
 * browser runs with host names unresolvable, so the pages cannot reach the network. Without a
 * browser the PDFs are a coverage gap: skipped with a message, never an error.
 *
 * <p>Each HTML page is printed from a temporary copy ({@code _pdf-*.html} next to it, removed
 * afterwards) that adds the paper size and {@code window.LEGACY_AUDIT_PDF}, which tells the page
 * scripts to print up to {@code pdf.max_table_rows} rows per table. The multi-page reports
 * (technical, functional) are printed page by page and joined with PDFBox behind a cover with
 * contents, with bookmarks and "page i of n" stamped on every page.
 */
public final class PdfReports {

    private PdfReports() {}

    /** Chromium's PDFs trip harmless structure warnings in PDFBox when joined. */
    private static final java.util.logging.Logger PDFBOX_LOG = java.util.logging.Logger.getLogger("org.apache.pdfbox");

    static {
        PDFBOX_LOG.setLevel(java.util.logging.Level.SEVERE);
    }

    /** A report: its folder under reports/, the PDF file name and the title on the cover. */
    public record Report(String folder, String file, String kind) {}

    public static final Map<String, Report> REPORTS = new LinkedHashMap<>();

    static {
        REPORTS.put("summary", new Report("summary", "summary.pdf", "Summary report"));
        REPORTS.put("mgmt", new Report("mgmt", "management.pdf", "Management deck"));
        REPORTS.put("functional", new Report("functional", "functional.pdf", "Functional document"));
        REPORTS.put("tech", new Report("tech", "technical.pdf", "Technical report"));
    }

    /** Detail pages that follow a main page in the joined PDFs: main page id to sub-folder. */
    static final Map<String, Map<String, String>> DETAIL_FOLDERS = Map.of(
            "tech", Map.of("components", "components", "flows", "flows"),
            "functional", Map.of("business-flows", "flows"));
    static final Map<String, double[]> PAPER_MM = Map.of("A4", new double[] {210.0, 297.0}, "Letter", new double[] {215.9, 279.4});
    static final List<String> LINUX_NAMES = List.of(
            "microsoft-edge", "microsoft-edge-stable", "google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "chrome");
    static final List<String> WINDOWS_PROGRAMS = List.of("Microsoft\\Edge\\Application\\msedge.exe", "Google\\Chrome\\Application\\chrome.exe");
    static final List<String> MAC_APPS = List.of(
            "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium");
    private static final Pattern H1 = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern HEAD = Pattern.compile("<head[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECTION = Pattern.compile(
            "<section[^>]*\\bid=\"([\\w-]+)\"[^>]*>\\s*<h2[^>]*>(.*?)</h2>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    /** Contents entries link here; the link is turned into a jump to that page of the PDF. */
    static final String GOTO = "https://legacy-audit.invalid/goto/";

    /** The {@code pdf} section of audit.yaml. */
    public record PdfConfig(boolean enabled, String browserPath, String paper, int maxTableRows, boolean detailPages,
            int timeoutSeconds) {

        public static PdfConfig defaults() {
            return new PdfConfig(true, null, "A4", 500, true, 180);
        }

        /** From the {@code pdf} section of a config dump (defaults for anything missing). */
        public static PdfConfig of(Object section) {
            PdfConfig d = defaults();
            if (!(section instanceof Map<?, ?>)) {
                return d;
            }
            Object path = D.get(section, "browser_path");
            return new PdfConfig(
                    D.truthy(D.get(section, "enabled", true)),
                    path == null ? null : path.toString(),
                    D.get(section, "paper") instanceof String p ? p : d.paper(),
                    D.get(section, "max_table_rows") instanceof Number n ? n.intValue() : d.maxTableRows(),
                    D.truthy(D.get(section, "detail_pages", true)),
                    D.get(section, "timeout_seconds") instanceof Number n ? n.intValue() : d.timeoutSeconds());
        }
    }

    /** A page could not be printed (browser failed, timed out or wrote nothing). */
    public static final class PdfError extends Exception {
        public PdfError(String message) {
            super(message);
        }
    }

    /** A browser program; {@code windowsFromWsl}: Windows Edge/Chrome started from WSL. */
    public record Browser(String path, String label, boolean windowsFromWsl) {}

    /** The PDFs written (report key to file), messages for the user and the browser used. */
    public static final class PdfResult {
        public final Map<String, Path> files = new LinkedHashMap<>();
        public final List<String> messages = new ArrayList<>();
        public Browser browser;
    }

    // ---- finding a browser ---------------------------------------------------------------------

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
    }

    static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");
    }

    public static boolean isWsl() {
        if (!isLinux()) {
            return false;
        }
        try {
            return Files.readString(Path.of("/proc/version")).toLowerCase(Locale.ROOT).contains("microsoft");
        } catch (IOException e) {
            return false;
        }
    }

    static String label(String path) {
        String low = path.toLowerCase(Locale.ROOT);
        if (low.contains("edge")) {
            return "Microsoft Edge";
        }
        if (low.contains("chromium") || low.contains("pw-browsers")) {
            return "Chromium";
        }
        return "Google Chrome";
    }

    /** {@code shutil.which}: a program on the PATH, or null. */
    static String which(String name) {
        Path direct = Path.of(name);
        if (direct.getParent() != null) {
            return Files.isRegularFile(direct) && Files.isExecutable(direct) ? direct.toString() : null;
        }
        List<String> exts = new ArrayList<>(List.of(""));
        if (isWindows()) {
            String pathext = System.getenv().getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD");
            exts = new ArrayList<>(List.of(pathext.toLowerCase(Locale.ROOT).split(";")));
            if (name.contains(".")) {
                exts.add(0, "");
            }
        }
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            for (String ext : exts) {
                Path p = Path.of(dir, name + ext);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                    return p.toString();
                }
            }
        }
        return null;
    }

    /** Places a browser is looked for, best first, for this machine. */
    public static List<String> candidates() {
        List<String> found = new ArrayList<>();
        if (isWindows()) {
            List<String> roots = new ArrayList<>();
            for (String v : List.of("ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA")) {
                String r = System.getenv(v);
                if (r != null && !r.isEmpty()) {
                    roots.add(r);
                }
            }
            for (String rel : WINDOWS_PROGRAMS) {
                for (String r : roots) {
                    found.add(Path.of(r, rel).toString());
                }
            }
            for (String n : List.of("msedge", "chrome")) {
                String w = which(n);
                if (w != null) {
                    found.add(w);
                }
            }
            return found;
        }
        if (isMac()) {
            found.addAll(MAC_APPS);
        }
        for (String n : LINUX_NAMES) {
            String w = which(n);
            if (w != null) {
                found.add(w);
            }
        }
        String pw = System.getenv("PLAYWRIGHT_BROWSERS_PATH"); // a Playwright install (CI, dev containers)
        if (pw != null && !pw.isEmpty() && Files.isDirectory(Path.of(pw))) {
            try (var dirs = Files.list(Path.of(pw))) {
                for (Path d : dirs.filter(p -> p.getFileName().toString().startsWith("chromium-")).sorted().toList()) {
                    for (String sub : List.of("chrome-linux/chrome", "chrome-linux64/chrome")) {
                        if (Files.isRegularFile(d.resolve(sub))) {
                            found.add(d.resolve(sub).toString());
                        }
                    }
                }
            } catch (IOException e) {
                // unreadable folder: no Playwright browser
            }
        }
        if (isWsl()) {
            for (String base : List.of("/mnt/c/Program Files (x86)", "/mnt/c/Program Files")) {
                for (String rel : WINDOWS_PROGRAMS) {
                    found.add(base + "/" + rel.replace('\\', '/'));
                }
            }
        }
        return found;
    }

    /** {@code os.path.expandvars(os.path.expanduser(text))}. */
    static String expand(String text) {
        String s = text;
        if (s.equals("~") || s.startsWith("~/") || s.startsWith("~\\")) {
            s = System.getProperty("user.home") + s.substring(1);
        }
        Matcher m = Pattern.compile("\\$(\\w+)|\\$\\{([^}]*)}|%(\\w+)%").matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            String value = m.group(3) != null && !isWindows() ? null : System.getenv(name);
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** {@code pdf.browser_path} when set (it must exist), else the first browser found. */
    public static Browser findBrowser(String configured) {
        if (configured != null && !configured.isEmpty()) {
            Path p = Path.of(expand(configured));
            String hit = Files.isRegularFile(p) ? p.toString() : which(configured);
            return hit == null ? null : new Browser(hit, label(hit), fromWsl(hit));
        }
        for (String c : candidates()) {
            if (Files.isRegularFile(Path.of(c))) {
                return new Browser(c, label(c), fromWsl(c));
            }
        }
        return null;
    }

    static boolean fromWsl(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".exe") && path.startsWith("/mnt/") && isWsl();
    }

    public static String missingMessage(String configured) {
        if (configured != null && !configured.isEmpty()) {
            return "PDF skipped: the browser in pdf.browser_path was not found at " + configured + ". "
                    + "Fix the path, or set it to null to find Edge or Chrome automatically.";
        }
        return "PDF skipped: no Microsoft Edge, Google Chrome or Chromium found. Install one, or set "
                + "pdf.browser_path in audit.yaml to the browser program.";
    }

    // ---- printing one page ---------------------------------------------------------------------

    /** Prints HTML files to PDF with one browser. */
    public static final class Printer {
        final Browser browser;
        final PdfConfig cfg;
        /** Turned off when the browser cannot start its sandbox (Linux). */
        volatile boolean sandbox = true;
        private String winTemp;

        public Printer(Browser browser, PdfConfig cfg) {
            this.browser = browser;
            this.cfg = cfg;
        }

        /** A WSL path as Windows sees it ({@code \\wsl.localhost\<distro>\...}). */
        String win(Path p) throws PdfError {
            String out = runText(List.of("wslpath", "-w", p.toString()));
            if (out == null) {
                throw new PdfError(p.getFileName() + ": wslpath failed");
            }
            return out.strip();
        }

        String arg(Path p) throws PdfError {
            return browser.windowsFromWsl() ? win(p) : p.toString();
        }

        String url(Path p) throws PdfError {
            if (browser.windowsFromWsl()) {
                String w = win(p);
                if (w.startsWith("\\\\")) {
                    return "file:" + RunData.quote(w.replace('\\', '/'));
                }
                return "file:///" + w.substring(0, 2) + RunData.quote(w.substring(2).replace('\\', '/'));
            }
            return "file://" + RunData.quote(RunData.resolve(p).toString().replace('\\', '/'));
        }

        /** A throw-away browser profile, so an open Edge/Chrome window is never touched. */
        String profile(Path scratch) {
            if (!browser.windowsFromWsl()) {
                return scratch.toString();
            }
            synchronized (this) {
                if (winTemp == null) {
                    String out = runText(List.of("cmd.exe", "/c", "echo %TEMP%"));
                    List<String> lines = out == null ? List.of() : out.strip().lines().toList();
                    winTemp = lines.isEmpty() ? "" : lines.get(lines.size() - 1).strip();
                }
            }
            if (winTemp.isEmpty()) {
                return null;
            }
            return winTemp + "\\legacy-audit-pdf-" + ProcessHandle.current().pid() + "-" + System.identityHashCode(scratch);
        }

        /** Width of the printed area (paper less 2 x 11 mm margins) in CSS pixels. */
        int pagePx() {
            return mmPx(PAPER_MM.get(cfg.paper())[0] - 22);
        }

        List<String> command(String url, String pdfArg, String profile) {
            List<String> cmd = new ArrayList<>(List.of(
                    browser.path(),
                    "--headless",
                    "--disable-gpu",
                    "--no-first-run",
                    "--no-default-browser-check",
                    "--disable-extensions",
                    "--disable-sync",
                    "--disable-background-networking",
                    "--disable-component-update",
                    "--host-resolver-rules=MAP * ~NOTFOUND", // offline: no host name resolves
                    "--hide-scrollbars",
                    "--window-size=" + pagePx() + ",1200", // lay out at the printed width
                    "--no-pdf-header-footer",
                    "--run-all-compositor-stages-before-draw",
                    "--virtual-time-budget=20000", // lets charts and diagrams finish drawing
                    "--print-to-pdf=" + pdfArg));
            if (profile != null) {
                cmd.add("--user-data-dir=" + profile);
            }
            if (isRoot() || !sandbox) {
                cmd.add("--no-sandbox"); // Chromium refuses to run as root otherwise
            }
            cmd.add(url);
            return cmd;
        }

        /** Print an HTML file as it is (it must carry its own @page rules). */
        public void printFile(Path page, Path pdf) throws PdfError {
            try {
                Files.createDirectories(pdf.getParent());
            } catch (IOException e) {
                throw new PdfError(page.getFileName() + ": " + e.getMessage());
            }
            Run out = run(page, pdf);
            if (out == null) {
                return;
            }
            if (isLinux() && sandbox && !browser.windowsFromWsl()) {
                // Linux without user namespaces (AppArmor on Ubuntu 24, containers): the sandbox
                // aborts the browser. The pages are local report files and the network is off.
                sandbox = false;
                Run retry = run(page, pdf);
                if (retry == null) {
                    return;
                }
                sandbox = true;
                out = retry;
            }
            List<String> lines = out.output().strip().lines().toList();
            String tail = "";
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (lines.get(i).contains("ERROR") || lines.get(i).contains("FATAL")) {
                    tail = lines.get(i);
                    break;
                }
            }
            if (tail.isEmpty() && !lines.isEmpty()) {
                tail = lines.get(lines.size() - 1);
            }
            throw new PdfError(page.getFileName() + ": no PDF written (exit " + out.code() + ") "
                    + tail.substring(Math.max(0, tail.length() - 300)));
        }

        record Run(int code, String output) {}

        /** One browser run; null when the PDF was written, else the finished process. */
        Run run(Path page, Path pdf) throws PdfError {
            Path scratch = null;
            int code;
            String output;
            try {
                Files.deleteIfExists(pdf);
                scratch = Files.createTempDirectory("legacy-audit-pdf-");
                Path log = scratch.resolve("browser.log");
                List<String> cmd = command(url(page), arg(pdf), profile(scratch.resolve("profile")));
                Process p = new ProcessBuilder(cmd)
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .redirectInput(ProcessBuilder.Redirect.from(new File(isWindows() ? "NUL" : "/dev/null")))
                        .start();
                if (!p.waitFor(cfg.timeoutSeconds(), TimeUnit.SECONDS)) {
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                    throw new PdfError(page.getFileName() + ": the browser did not finish in " + cfg.timeoutSeconds()
                            + " s (raise pdf.timeout_seconds)");
                }
                code = p.exitValue();
                output = Files.exists(log) ? Fmt.readText(log) : "";
            } catch (IOException e) {
                throw new PdfError(page.getFileName() + ": could not start " + browser.path() + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PdfError(page.getFileName() + ": interrupted");
            } finally {
                if (scratch != null) {
                    try {
                        Fmt.rmtree(scratch);
                    } catch (UncheckedIOException e) {
                        // the browser may still hold a file of its profile
                    }
                }
            }
            int waits = code == 0 ? 20 : 2; // Windows browsers can return before closing it
            for (int i = 0; i < waits; i++) {
                try {
                    if (Files.isRegularFile(pdf) && Files.size(pdf) > 0) {
                        return null;
                    }
                    Thread.sleep(250);
                } catch (IOException e) {
                    // not there yet
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return new Run(code, output);
        }

        /** Print a report page through a temporary copy that adds the PDF settings. */
        public void printPage(Path page, Path pdf, boolean deck) throws PdfError {
            String stem = page.getFileName().toString().replaceFirst("\\.[^.]*$", "");
            Path copy = page.resolveSibling("_pdf-" + stem + ".html");
            try {
                Files.writeString(copy, inject(Files.readString(page, StandardCharsets.UTF_8), cfg, deck), StandardCharsets.UTF_8);
                printFile(copy, pdf);
            } catch (IOException e) {
                throw new PdfError(page.getFileName() + ": " + e.getMessage());
            } finally {
                try {
                    Files.deleteIfExists(copy);
                } catch (IOException e) {
                    // left behind; the next print overwrites it
                }
            }
        }
    }

    static boolean isRoot() {
        if (!isLinux()) {
            return false;
        }
        try {
            return Integer.valueOf(0).equals(Files.getAttribute(Path.of("/proc/self"), "unix:uid"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** A command's output, or null when it could not run or failed. */
    static String runText(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static int mmPx(double mm) {
        return (int) Math.rint(mm * 96 / 25.4);
    }

    static String pageCss(PdfConfig cfg) {
        return "@page { size: " + cfg.paper() + "; margin: 12mm 11mm 16mm; }"
                + " @page wide { size: " + cfg.paper() + " landscape; margin: 12mm 11mm 16mm; }";
    }

    /** The page with the PDF settings added at the top of {@code <head>}. */
    public static String inject(String text, PdfConfig cfg, boolean deck) {
        StringBuilder settings = new StringBuilder("<meta name=\"color-scheme\" content=\"light\">"
                + "<script>window.LEGACY_AUDIT_PDF = {\"maxRows\": " + cfg.maxTableRows() + ", \"fit\": " + (!deck)
                + ", \"landscapePx\": " + mmPx(PAPER_MM.get(cfg.paper())[1] - 22) + "};</script>");
        if (!deck) { // the deck keeps its own 16:9 slide size
            settings.append("<style>").append(pageCss(cfg)).append("html, body { background: #fff !important; }"
                    + " .diagram svg, pre.mermaid svg { max-width: 100% !important; height: auto; }"
                    + " .chart, .chart-box, .panel, .card, figure, .tile, .kpi, tr { break-inside: avoid; }"
                    // tables print whole: no scroll boxes (they clip on paper), text wraps in its cell
                    + " .wrap, .table-wrap, .catalogue, [data-catalogue] { overflow: visible !important;"
                    + " max-height: none !important; }"
                    + " table { width: 100% !important; max-width: 100% !important; }"
                    + " th { position: static !important; }"
                    + " td { overflow-wrap: break-word; white-space: normal !important; }"
                    + " th { overflow-wrap: normal; white-space: normal !important; }"
                    + "</style>");
        }
        settings.append("<script>").append(PDF_JS).append("</script>");
        Matcher m = HEAD.matcher(text);
        int at = m.find() ? m.end() : 0;
        return text.substring(0, at) + settings + text.substring(at);
    }

    /**
     * Opens folded sections and shortens long server-written tables (the catalogue tables of the
     * technical report read LEGACY_AUDIT_PDF themselves).
     */
    static final String PDF_JS = """

            // A table wider than the page: smaller font down to 9px, then long unspaced values break; if
            // still too wide, it goes on landscape pages ("wide", A4 across) and shrinks again down to
            // 6.5px; last resort, any value breaks.
            window.addEventListener("load", function () {
              var cfg = window.LEGACY_AUDIT_PDF;
              if (!cfg.fit) return;
              function wide(t, box) { return t.scrollWidth > box.clientWidth + 1; }
              function shrink(t, box, floor) {
                var size = parseFloat(getComputedStyle(t).fontSize) || 13;
                while (wide(t, box) && size > floor) {
                  size -= 0.5;
                  t.style.fontSize = size + "px";
                  t.querySelectorAll("th, td").forEach(function (c) {
                    c.style.fontSize = size + "px"; c.style.padding = "3px 4px";
                  });
                }
              }
              document.querySelectorAll("table").forEach(function (t) {
                var box = t.closest("[data-catalogue], .wrap, .table-wrap") || t.parentElement;
                if (!box || !wide(t, box)) return;
                shrink(t, box, 9);
                if (wide(t, box)) {  // break long unspaced values (addresses, paths) before turning the page
                  t.querySelectorAll("td").forEach(function (c) {
                    if (/\\S{24,}/.test(c.textContent)) c.style.overflowWrap = "anywhere";
                  });
                }
                if (wide(t, box) && cfg.landscapePx) {
                  var aside = window.innerWidth - box.getBoundingClientRect().width;  // ancestors' padding
                  box.style.page = "wide";
                  box.style.width = (cfg.landscapePx - aside) + "px";
                  box.style.maxWidth = "none";
                  t.style.fontSize = "";
                  t.querySelectorAll("th, td").forEach(function (c) {
                    c.style.fontSize = ""; c.style.padding = "";
                  });
                  shrink(t, box, 6.5);
                }
                if (wide(t, box)) {
                  t.querySelectorAll("td").forEach(function (c) { c.style.overflowWrap = "anywhere"; });
                }
              });
            });
            document.addEventListener("DOMContentLoaded", function () {
              var max = window.LEGACY_AUDIT_PDF.maxRows;
              document.querySelectorAll("details").forEach(function (d) {
                d.open = !d.querySelector("pre.block");  // tables yes, raw diagram source no
              });
              document.querySelectorAll("table").forEach(function (t) {
                if (t.closest("[data-catalogue]") || !t.tBodies.length) return;
                var rows = t.tBodies[0].rows, n = rows.length;
                if (n <= max) return;
                for (var i = n - 1; i >= max; i--) rows[i].remove();
                var p = document.createElement("p");
                p.className = "note pdf-cut";
                p.textContent = "First " + max + " of " + n + " rows; the full list is in the HTML report " +
                  "and its CSV download.";
                var box = t.closest(".wrap") || t;
                box.parentNode.insertBefore(p, box.nextSibling);
              });
            });
            """;

    // ---- joining pages -------------------------------------------------------------------------

    /** One printed page of a multi-page report; level 0 = main page, 1 = detail page under it. */
    static final class Part {
        final String title;
        final Path page;
        final int level;
        Path pdf;
        int pages;
        String error;

        Part(String title, Path page, int level) {
            this.title = title;
            this.page = page;
            this.level = level;
        }
    }

    record Entry(String title, int level, Integer page) {}

    /** {@code html.escape}. */
    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#x27;");
    }

    /** {@code html.unescape} for the entities the report pages use. */
    static String unescape(String s) {
        Matcher m = Pattern.compile("&(#x[0-9A-Fa-f]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp|middot|rarr|hellip|ndash|mdash);").matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String e = m.group(1);
            String r = switch (e) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "nbsp" -> "\u00a0";
                case "middot" -> "\u00b7";
                case "rarr" -> "\u2192";
                case "hellip" -> "\u2026";
                case "ndash" -> "\u2013";
                case "mdash" -> "\u2014";
                default -> new String(Character.toChars(e.startsWith("#x")
                        ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1))));
            };
            m.appendReplacement(out, Matcher.quoteReplacement(r));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String stem(Path p) {
        return p.getFileName().toString().replaceFirst("\\.[^.]*$", "");
    }

    static String pageTitle(Path page) {
        String text;
        try {
            text = Fmt.readText(page);
        } catch (IOException e) {
            return stem(page);
        }
        Matcher m = H1.matcher(text);
        boolean found = m.find();
        if (!found) {
            m = TITLE.matcher(text);
            found = m.find();
        }
        String title = found ? unescape(TAG.matcher(m.group(1)).replaceAll("")).strip() : "";
        String first = title.split(" · ", -1)[0];
        String out = first.replaceAll("\\s+", " ");
        return out.isEmpty() ? stem(page) : out;
    }

    /** Pages of a multi-page report in menu order, detail pages after their main page. */
    static List<Part> partsFor(String key, Path folder, PdfConfig cfg) {
        List<List<String>> nav = key.equals("tech") ? TechReport.NAV : FunctionalReport.FN_NAV;
        List<Part> out = new ArrayList<>();
        for (List<String> n : nav) {
            Path page = folder.resolve(n.get(1));
            if (!Files.isRegularFile(page)) {
                continue;
            }
            out.add(new Part(n.get(2), page, 0));
            String sub = DETAIL_FOLDERS.getOrDefault(key, Map.of()).get(n.get(0));
            if (cfg.detailPages() && sub != null && Files.isDirectory(folder.resolve(sub))) {
                try (var files = Files.list(folder.resolve(sub))) {
                    for (Path p : files.filter(p -> p.getFileName().toString().endsWith(".html")).sorted().toList()) {
                        if (!p.getFileName().toString().startsWith("_pdf-")) {
                            out.add(new Part(pageTitle(p), p, 1));
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return out;
    }

    /** Cover with the contents. */
    static String coverHtml(String app, String kind, Map<String, Object> run, List<Entry> entries, PdfConfig cfg) {
        StringBuilder rows = new StringBuilder();
        for (Entry e : entries) {
            String cls = e.level() != 0 ? "sub" : "main";
            String text = esc(e.title());
            if (e.page() == null) {
                rows.append("<li class=\"").append(cls).append("\"><span>").append(text).append("</span><i></i><b>not printed</b></li>");
            } else {
                rows.append("<li class=\"").append(cls).append("\"><a href=\"").append(GOTO).append(e.page() - 1).append("\"><span>")
                        .append(text).append("</span><i></i><b>").append(e.page()).append("</b></a></li>");
            }
        }
        String started = D.s(run, "started_at");
        String date = esc(started.substring(0, Math.min(16, started.length())).replace("T", " "));
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + esc(kind) + "</title>\n"
                + "<style>" + pageCss(cfg) + "\n"
                + "body { font-family: \"Segoe UI\", Arial, sans-serif; color: #1d2733; margin: 0; }\n"
                + ".kind { color: #1f4e79; text-transform: uppercase; letter-spacing: 2px; font-size: 12px;\n"
                + "  margin-top: 40mm; }\n"
                + "h1 { font-size: 30px; margin: 6px 0 4px; }\n"
                + ".meta { color: #5b6876; font-size: 12px; margin-bottom: 18mm; border-top: 3px solid #1f4e79;\n"
                + "  padding-top: 8px; }\n"
                + "h2 { font-size: 16px; margin: 0 0 6px; }\n"
                + "ol { list-style: none; padding: 0; margin: 0; font-size: 12.5px; }\n"
                + "li { display: flex; align-items: baseline; gap: 6px; padding: 2px 0; break-inside: avoid; }\n"
                + "li.main { font-weight: 600; margin-top: 4px; }\n"
                + "li.sub { padding-left: 18px; color: #3b4652; font-size: 11.5px; }\n"
                + "li a { display: flex; flex: 1; align-items: baseline; gap: 6px; color: inherit;\n"
                + "  text-decoration: none; }\n"
                + "li i { flex: 1; border-bottom: 1px dotted #9aa5b1; }\n"
                + "</style></head><body>\n"
                + "<div class=\"kind\">" + esc(kind) + "</div>\n"
                + "<h1>" + esc(app) + "</h1>\n"
                + "<div class=\"meta\">Run " + esc(com.legacyaudit.engine.rules.Py.str(D.get(run, "run_id", ""))) + " · " + date
                + " · legacy-audit\n" + esc(com.legacyaudit.engine.rules.Py.str(D.get(run, "tool_version", "")))
                + ". Every number comes from the scan; text marked\n"
                + "AI was written by the Copilot analysis and checked against it.</div>\n"
                + "<h2>Contents</h2><ol>" + rows + "</ol></body></html>";
    }

    static List<Entry> entries(List<Part> parts, int offset) {
        List<Entry> out = new ArrayList<>();
        int n = offset + 1;
        for (Part p : parts) {
            out.add(new Entry(p.title, p.level, p.pages != 0 ? n : null));
            n += p.pages;
        }
        return out;
    }

    record Cover(Path pdf, int pages) {}

    /** Print the cover; the contents can run over a page, so reprint until the numbers hold. */
    static Cover cover(Printer printer, String app, String kind, Map<String, Object> run, IntFunction<List<Entry>> entriesAt,
            Path work, String name) throws PdfError, IOException {
        int pages = 1;
        Path cover = null;
        for (int i = 0; i < 3; i++) {
            cover = renderHtml(printer, coverHtml(app, kind, run, entriesAt.apply(pages), printer.cfg), work, name);
            int n = pageCount(cover);
            if (n == pages) {
                break;
            }
            pages = n;
        }
        return new Cover(cover, pages);
    }

    /**
     * Turn links to a contents entry (GOTO) or to a page printed in this PDF ({@code targets}: file
     * URL to first page) into jumps inside the PDF.
     */
    static void link(PDDocument doc, Map<String, Integer> targets) throws IOException {
        int count = doc.getNumberOfPages();
        for (PDPage page : doc.getPages()) {
            for (PDAnnotation a : page.getAnnotations()) {
                if (!(a instanceof PDAnnotationLink l) || !(l.getAction() instanceof PDActionURI u) || u.getURI() == null) {
                    continue;
                }
                String uri = u.getURI();
                Integer index;
                if (uri.startsWith(GOTO)) {
                    index = Integer.valueOf(uri.substring(GOTO.length()));
                } else {
                    int hash = uri.indexOf('#');
                    index = targets.get(hash < 0 ? uri : uri.substring(0, hash));
                }
                if (index == null || index >= count) {
                    continue;
                }
                PDPageFitDestination dest = new PDPageFitDestination();
                dest.setPage(doc.getPage(index));
                PDActionGoTo go = new PDActionGoTo();
                go.setDestination(dest);
                l.setAction(go);
            }
        }
    }

    record Size(double w, double h) {}

    /** One page per entry of {@code sizes} (mm) with its "page i of n" footer, to stamp onto the printed pages. */
    static String numbersHtml(List<Size> sizes, String label, int first) {
        Map<Size, Integer> kinds = new LinkedHashMap<>();
        for (Size s : sizes) {
            kinds.putIfAbsent(s, kinds.size());
        }
        StringBuilder css = new StringBuilder();
        for (Map.Entry<Size, Integer> k : kinds.entrySet()) {
            String w = Fmt.fixed(k.getKey().w(), 1, false);
            String h = Fmt.fixed(k.getKey().h(), 1, false);
            int i = k.getValue();
            css.append("@page s").append(i).append(" { size: ").append(w).append("mm ").append(h).append("mm; margin: 0; }")
                    .append(" .s").append(i).append(" { page: s").append(i).append("; width: ").append(w).append("mm; height: ")
                    .append(h).append("mm; }\n");
        }
        int total = sizes.size();
        String e = esc(label);
        StringBuilder pages = new StringBuilder();
        for (int i = 1; i <= total; i++) {
            pages.append("<div class=\"p s").append(kinds.get(sizes.get(i - 1))).append("\">");
            if (i >= first) {
                pages.append("<span>").append(e).append("</span><span>Page ").append(i).append(" of ").append(total).append("</span>");
            }
            pages.append("</div>");
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\"><style>\n"
                + css + "body { margin: 0; font-family: \"Segoe UI\", Arial, sans-serif; }\n"
                + ".p { position: relative; break-after: page; overflow: hidden; }\n"
                + ".p span { position: absolute; bottom: 6mm; font-size: 8.5px; color: #6b7785; }\n"
                + ".p span:first-child { left: 11mm; } .p span:last-child { right: 11mm; }\n"
                + "</style></head><body>" + pages + "</body></html>";
    }

    /** Each page's size in mm, rounded so equal paper sizes match. */
    static List<Size> sizes(PDDocument doc) {
        List<Size> out = new ArrayList<>();
        for (PDPage p : doc.getPages()) {
            PDRectangle box = p.getMediaBox();
            out.add(new Size(PyNum.round(box.getWidth() / 72.0 * 25.4, 1), PyNum.round(box.getHeight() / 72.0 * 25.4, 1)));
        }
        return out;
    }

    static Path renderHtml(Printer printer, String text, Path work, String name) throws PdfError, IOException {
        Path src = work.resolve(name + ".html");
        Path pdf = work.resolve(name + ".pdf");
        Files.writeString(src, text, StandardCharsets.UTF_8);
        printer.printFile(src, pdf);
        return pdf;
    }

    static int pageCount(Path pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            return d.getNumberOfPages();
        }
    }

    /** Stamp the pages of {@code overlay} onto the pages of {@code doc} from index {@code from}. */
    static void stamp(PDDocument doc, PDDocument overlay, int from) throws IOException {
        LayerUtility layers = new LayerUtility(doc);
        for (int i = from; i < doc.getNumberOfPages() && i < overlay.getNumberOfPages(); i++) {
            PDFormXObject form = layers.importPageAsForm(overlay, i);
            try (PDPageContentStream cs = new PDPageContentStream(doc, doc.getPage(i), PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.drawForm(form);
            }
        }
    }

    static void finish(PDDocument doc, String title, Path dest) throws IOException {
        PDDocumentInformation info = doc.getDocumentInformation();
        info.setTitle(title);
        info.setCreator("legacy-audit");
        Path tmp = dest.resolveSibling(stem(dest) + ".tmp");
        doc.save(tmp.toFile());
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Print every page of a multi-page report and join them into {@code dest}; returns problems. */
    static List<String> joinReport(Printer printer, String key, Path folder, Path dest, Path work, Map<String, Object> run, String app)
            throws IOException, PdfError {
        PdfConfig cfg = printer.cfg;
        String kind = REPORTS.get(key).kind();
        List<Part> parts = partsFor(key, folder, cfg);
        if (parts.isEmpty()) {
            return new ArrayList<>(List.of(kind + ": no pages found in " + folder));
        }
        int cpus = Runtime.getRuntime().availableProcessors();
        int workers = Math.max(1, Math.min(Math.min(4, Math.max(1, cpus / 2)), parts.size()));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                Part part = parts.get(i);
                part.pdf = work.resolve(String.format(Locale.ROOT, "%s-%04d.pdf", key, i));
                jobs.add(pool.submit(() -> {
                    try {
                        printer.printPage(part.page, part.pdf, false);
                        part.pages = pageCount(part.pdf);
                    } catch (PdfError | IOException | RuntimeException err) {
                        part.error = err.getMessage();
                    }
                }));
            }
            for (Future<?> j : jobs) {
                j.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PdfError(kind + ": interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new PdfError(kind + ": " + e.getCause());
        } finally {
            pool.shutdownNow();
        }
        List<String> problems = new ArrayList<>();
        parts.stream().filter(p -> p.error != null).forEach(p -> problems.add(kind + ": " + p.error));
        if (parts.stream().noneMatch(p -> p.pages != 0)) {
            return problems.isEmpty() ? new ArrayList<>(List.of(kind + ": nothing printed")) : problems;
        }

        Cover cover = cover(printer, app, kind, run, n -> entries(parts, n), work, key + "-cover");
        List<PDDocument> sources = new ArrayList<>();
        try (PDDocument out = new PDDocument()) {
            PDFMergerUtility merger = new PDFMergerUtility();
            PDDocument coverDoc = Loader.loadPDF(cover.pdf().toFile());
            sources.add(coverDoc);
            merger.appendDocument(out, coverDoc);
            PDDocumentOutline outline = new PDDocumentOutline();
            PDOutlineItem parent = null;
            Map<String, Integer> targets = new LinkedHashMap<>();
            for (Part p : parts) {
                if (p.pages == 0 || p.pdf == null) {
                    continue;
                }
                int start = out.getNumberOfPages();
                PDDocument src = Loader.loadPDF(p.pdf.toFile());
                sources.add(src);
                merger.appendDocument(out, src);
                targets.put(printer.url(p.page), start);
                PDOutlineItem item = outlineItem(out, p.title, start);
                if (p.level == 0 || parent == null) {
                    outline.addLast(item);
                    parent = p.level == 0 ? item : parent;
                } else {
                    parent.addLast(item);
                }
            }
            PDDocumentCatalog catalog = out.getDocumentCatalog();
            catalog.setDocumentOutline(outline);
            link(out, targets);
            Path numbers = renderHtml(printer, numbersHtml(sizes(out), app + " · " + kind, cover.pages() + 1), work, key + "-n");
            try (PDDocument overlay = Loader.loadPDF(numbers.toFile())) {
                stamp(out, overlay, cover.pages());
                catalog.setPageMode(PageMode.USE_OUTLINES);
                finish(out, app + ": " + kind, dest);
            }
        } finally {
            for (PDDocument d : sources) {
                d.close();
            }
        }
        return problems;
    }

    static PDOutlineItem outlineItem(PDDocument doc, String title, int page) {
        PDOutlineItem item = new PDOutlineItem();
        item.setTitle(title);
        PDPageFitDestination d = new PDPageFitDestination();
        d.setPage(doc.getPage(page));
        item.setDestination(d);
        return item;
    }

    record SectionAt(String title, int page) {}

    /** (title, page index) of each numbered part of the summary report, from its anchors. */
    static List<SectionAt> sections(Path page, PDDocument doc) throws IOException {
        List<SectionAt> out = new ArrayList<>();
        PDDocumentCatalog catalog = doc.getDocumentCatalog();
        Matcher m = SECTION.matcher(Files.readString(page, StandardCharsets.UTF_8));
        while (m.find()) {
            PDPageDestination dest = namedDest(catalog, m.group(1));
            if (dest == null) {
                continue;
            }
            int at = dest.retrievePageNumber();
            if (at < 0) {
                continue;
            }
            String title = unescape(TAG.matcher(m.group(2)).replaceAll(" ")).replaceAll("\\s+", " ").strip();
            out.add(new SectionAt(title, at));
        }
        return out;
    }

    static PDPageDestination namedDest(PDDocumentCatalog catalog, String name) throws IOException {
        PDDestination d = null;
        if (catalog.getNames() != null && catalog.getNames().getDests() != null) {
            d = catalog.getNames().getDests().getValue(name);
        }
        if (d == null && catalog.getDests() != null) {
            d = catalog.getDests().getDestination(name);
        }
        return d instanceof PDPageDestination p ? p : null;
    }

    /** Summary report and management deck: one HTML page, one PDF. */
    static List<String> singleReport(Printer printer, String key, Path page, Path dest, Path work, String app, Map<String, Object> run)
            throws IOException {
        String kind = REPORTS.get(key).kind();
        Path raw = work.resolve(key + ".pdf");
        try {
            printer.printPage(page, raw, key.equals("mgmt"));
        } catch (PdfError err) {
            return new ArrayList<>(List.of(kind + ": " + err.getMessage()));
        }
        try (PDDocument rawDoc = Loader.loadPDF(raw.toFile()); PDDocument out = new PDDocument()) {
            List<SectionAt> secs = key.equals("summary") ? sections(page, rawDoc) : List.of();
            PDFMergerUtility merger = new PDFMergerUtility();
            int first = 1;
            PDDocument coverDoc = null;
            try {
                if (!secs.isEmpty()) { // a cover with the contents, and bookmarks, for the parts
                    Cover cover = cover(printer, app, kind, run, n -> {
                        List<Entry> l = new ArrayList<>();
                        secs.forEach(s -> l.add(new Entry(s.title(), 0, s.page() + n + 1)));
                        return l;
                    }, work, key + "-cover");
                    coverDoc = Loader.loadPDF(cover.pdf().toFile());
                    merger.appendDocument(out, coverDoc);
                    first = cover.pages();
                }
                merger.appendDocument(out, rawDoc);
                if (!secs.isEmpty()) {
                    PDDocumentOutline outline = new PDDocumentOutline();
                    for (SectionAt s : secs) {
                        outline.addLast(outlineItem(out, s.title(), s.page() + first));
                    }
                    out.getDocumentCatalog().setDocumentOutline(outline);
                    link(out, Map.of());
                    out.getDocumentCatalog().setPageMode(PageMode.USE_OUTLINES);
                    first += 1;
                }
                if (!key.equals("mgmt")) { // the slides carry their own numbers
                    Path numbers = renderHtml(printer, numbersHtml(sizes(out), app + " · " + kind, first), work, key + "-n");
                    try (PDDocument overlay = Loader.loadPDF(numbers.toFile())) {
                        stamp(out, overlay, 0);
                        finish(out, app + ": " + kind, dest);
                    }
                } else {
                    finish(out, app + ": " + kind, dest);
                }
            } finally {
                if (coverDoc != null) {
                    coverDoc.close();
                }
            }
        } catch (PdfError err) {
            return new ArrayList<>(List.of(kind + ": " + err.getMessage()));
        }
        return new ArrayList<>();
    }

    /** Print the HTML reports of a run that exist into {@code reports/pdf/}. */
    public static PdfResult build(Path runDir, PdfConfig cfg) {
        return build(runDir, cfg, List.of("summary", "mgmt", "functional", "tech"));
    }

    public static PdfResult build(Path runDir, PdfConfig cfg, List<String> reports) {
        PdfConfig c = cfg == null ? PdfConfig.defaults() : cfg;
        PdfResult result = new PdfResult();
        Browser browser = findBrowser(c.browserPath());
        if (browser == null) {
            result.messages.add(missingMessage(c.browserPath()));
            return result;
        }
        result.browser = browser;
        Printer printer = new Printer(browser, c);
        Map<String, Object> run;
        try {
            run = D.asMap(Json.parse(Files.readString(runDir.resolve("run.json"), StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            run = new LinkedHashMap<>();
        }
        String app = D.t(run, "application") ? D.str(run, "application") : "application";
        Path out = runDir.resolve("reports").resolve("pdf");
        Path work = null;
        try {
            Files.createDirectories(out);
            work = Files.createTempDirectory("legacy-audit-pdf-");
            for (String key : reports) {
                Report r = REPORTS.get(key);
                Path folder = runDir.resolve("reports").resolve(r.folder());
                if (!Files.isRegularFile(folder.resolve("index.html"))) {
                    result.messages.add(r.kind() + ": not built yet, no PDF");
                    continue;
                }
                Path dest = out.resolve(r.file());
                Files.deleteIfExists(dest);
                List<String> problems;
                try {
                    problems = key.equals("summary") || key.equals("mgmt")
                            ? singleReport(printer, key, folder.resolve("index.html"), dest, work, app, run)
                            : joinReport(printer, key, folder, dest, work, run, app);
                } catch (PdfError | IOException | RuntimeException err) {
                    problems = List.of(r.kind() + ": " + err.getMessage());
                }
                result.messages.addAll(problems);
                if (Files.isRegularFile(dest)) {
                    result.files.put(key, dest);
                }
            }
            Path latest = Status.latestCopy(runDir);
            if (latest != null && !result.files.isEmpty()) {
                Path destDir = latest.resolve("reports").resolve("pdf");
                Files.createDirectories(destDir);
                for (Path f : result.files.values()) {
                    Files.copy(f, destDir.resolve(f.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            result.messages.add("PDF: " + e.getMessage());
        } finally {
            if (work != null) {
                try {
                    Fmt.rmtree(work);
                } catch (UncheckedIOException e) {
                    // temporary files left behind
                }
            }
        }
        return result;
    }
}
