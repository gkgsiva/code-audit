package com.legacyaudit.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** Name and version of the tool, as printed by the command line and the web app. */
public final class AppInfo {

    public static final String NAME = "legacy-audit";

    private static final String VERSION = loadVersion();

    private AppInfo() {}

    public static String version() {
        return VERSION;
    }

    private static String loadVersion() {
        try (InputStream in = AppInfo.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                return "dev";
            }
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("version", "dev");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
