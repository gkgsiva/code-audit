package com.legacyaudit.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** The web app's backend. The Angular frontend talks to it under /api. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LegacyAuditServer {

    public static void main(String[] args) {
        SpringApplication.run(LegacyAuditServer.class, args);
    }
}
