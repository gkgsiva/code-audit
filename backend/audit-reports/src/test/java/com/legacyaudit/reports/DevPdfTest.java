package com.legacyaudit.reports;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Developer check: print the PDFs of the run in PDF_RUN (needs Edge, Chrome or Chromium). */
@EnabledIfEnvironmentVariable(named = "PDF_RUN", matches = ".+")
class DevPdfTest {

    @Test
    void print() {
        PdfReports.PdfResult r = PdfReports.build(Path.of(System.getenv("PDF_RUN")), new PdfReports.PdfConfig(true, System.getenv("PDF_BROWSER"), "A4", 500, true, 180));
        System.out.println("browser " + r.browser);
        r.files.forEach((k, v) -> System.out.println(k + " -> " + v));
        r.messages.forEach(System.out::println);
    }
}
