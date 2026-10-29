package com.legacyaudit.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void versionRuns() {
        assertEquals(0, Main.run("version"));
    }

    @Test
    void missingSamplesFolderIsAClearError() {
        assertEquals(2, Main.run("proofs", "--samples", "no-such-folder"));
    }
}
