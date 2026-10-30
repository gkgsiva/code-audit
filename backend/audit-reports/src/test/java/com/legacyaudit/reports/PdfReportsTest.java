package com.legacyaudit.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The PDF settings added to a page, the cover and page numbers, and (with a browser) a real print. */
class PdfReportsTest {

    @Test
    void settingsGoAtTheTopOfTheHead() {
        String page = PdfReports.inject("<html><head><title>x</title></head></html>", PdfReports.PdfConfig.defaults(), false);
        assertTrue(page.startsWith("<html><head><meta name=\"color-scheme\" content=\"light\">"
                + "<script>window.LEGACY_AUDIT_PDF = {\"maxRows\": 500, \"fit\": true, \"landscapePx\": 1039};</script><style>@page"));
        String deck = PdfReports.inject("<head></head>", PdfReports.PdfConfig.defaults(), true);
        assertTrue(deck.contains("\"fit\": false") && !deck.contains("<style>"));
        assertTrue(deck.contains("/\\S{24,}/"));
    }

    @Test
    void configComesFromTheDump() {
        PdfReports.PdfConfig c = PdfReports.PdfConfig.of(Map.of("paper", "Letter", "max_table_rows", 20, "enabled", false));
        assertEquals("Letter", c.paper());
        assertEquals(20, c.maxTableRows());
        assertTrue(!c.enabled() && c.detailPages());
        assertEquals(PdfReports.PdfConfig.defaults(), PdfReports.PdfConfig.of(null));
    }

    @Test
    void coverAndNumbers() {
        String cover = PdfReports.coverHtml("Billing <app>", "Technical report", Map.of("run_id", "r1", "started_at", "2026-10-30T01:02:03"),
                List.of(new PdfReports.Entry("Overview", 0, 2), new PdfReports.Entry("Gone", 1, null)), PdfReports.PdfConfig.defaults());
        assertTrue(cover.contains("<h1>Billing &lt;app&gt;</h1>"));
        assertTrue(cover.contains("Run r1 · 2026-10-30 01:02"));
        assertTrue(cover.contains("<a href=\"" + PdfReports.GOTO + "1\"><span>Overview</span><i></i><b>2</b></a>"));
        assertTrue(cover.contains("<span>Gone</span><i></i><b>not printed</b>"));
        String numbers = PdfReports.numbersHtml(List.of(new PdfReports.Size(210, 297), new PdfReports.Size(297, 210), new PdfReports.Size(210, 297)),
                "Billing · Technical report", 2);
        assertTrue(numbers.contains("@page s0 { size: 210.0mm 297.0mm; margin: 0; }"));
        assertTrue(numbers.contains("<div class=\"p s0\"></div><div class=\"p s1\"><span>Billing · Technical report</span><span>Page 2 of 3</span></div>"));
    }

    @Test
    void titlesAndBrowsers(@TempDir Path tmp) throws Exception {
        Path page = tmp.resolve("x.html");
        Files.writeString(page, "<title>T</title><h1 class=\"a\">Order <em>flow</em> &amp; more · tech</h1>", StandardCharsets.UTF_8);
        assertEquals("Order flow & more", PdfReports.pageTitle(page));
        assertEquals("Microsoft Edge", PdfReports.label("C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"));
        assertEquals("Chromium", PdfReports.label("/opt/pw-browsers/chromium-1/chrome-linux/chrome"));
        assertEquals(null, PdfReports.findBrowser(tmp.resolve("no-browser").toString()));
        assertTrue(PdfReports.missingMessage("/x").contains("pdf.browser_path was not found at /x"));
    }

    /** Prints the summary and the functional document when PDF_BROWSER names a browser (or on Linux CI). */
    @Test
    void printsWithABrowser(@TempDir Path tmp) throws Exception {
        String configured = System.getenv("PDF_BROWSER");
        boolean linuxCi = "true".equals(System.getenv("CI")) && PdfReports.isLinux();
        assumeTrue(configured != null || linuxCi, "no browser named in PDF_BROWSER");
        PdfReports.Browser browser = PdfReports.findBrowser(configured);
        assumeTrue(browser != null, "no browser found");
        Path run = tmp.resolve("runs").resolve("r");
        Files.createDirectories(run);
        for (var f : ReportParityTest.unzip(Path.of("src/test/resources/report-parity/enriched/struts-oracle-app/run.zip")).entrySet()) {
            Path target = run.resolve(f.getKey().substring(f.getKey().indexOf('/') + 1));
            Files.createDirectories(target.getParent());
            Files.write(target, f.getValue());
        }
        RunData rd = RunData.load(run, Path.of("../../samples/struts-oracle-app/audit-inputs"));
        SummaryReport.buildInto(rd, run.resolve("reports/summary"), Set.of());
        FunctionalReport.buildInto(rd, run.resolve("reports/functional"));
        PdfReports.PdfConfig cfg = new PdfReports.PdfConfig(true, browser.path(), "A4", 500, true, 60);
        PdfReports.PdfResult res = PdfReports.build(run, cfg, List.of("summary", "functional"));
        assertEquals(Set.of("summary", "functional"), res.files.keySet(), String.valueOf(res.messages));
        try (PDDocument d = Loader.loadPDF(res.files.get("functional").toFile())) {
            assertTrue(d.getNumberOfPages() > 5);
            assertTrue(d.getDocumentCatalog().getDocumentOutline().children().iterator().hasNext());
            assertEquals("legacy-audit", d.getDocumentInformation().getCreator());
        }
    }
}
