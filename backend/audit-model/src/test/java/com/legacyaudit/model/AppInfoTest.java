package com.legacyaudit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class AppInfoTest {

    @Test
    void versionComesFromTheBuild() {
        assertEquals("legacy-audit", AppInfo.NAME);
        assertFalse(AppInfo.version().contains("${"), "version was not filled in by Maven");
    }
}
