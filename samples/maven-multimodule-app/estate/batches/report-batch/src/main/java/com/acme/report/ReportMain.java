package com.acme.report;

/** Daily activity report sent to the back office. */
public final class ReportMain {
    private ReportMain() {
    }

    public static void main(String[] args) {
        System.out.println("report " + String.join(" ", args));
    }
}
