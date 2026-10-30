package com.legacyaudit.engine.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.legacyaudit.engine.rules.Packs;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Schemas;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Dependency security: versions, EOL, upgrades, report import, SBOM, tools (the Python tool's tests). */
class SecurityTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples");
    private static final Path DC_REPORT = SAMPLES.resolve("struts-oracle-app/audit-inputs/security/dependency-check-report.json");
    private static final Path OSV_REPORT = SAMPLES.resolve("spring-mvc-batch-app/audit-inputs/security/osv-scanner.json");
    private static final Map<String, Object> DEP = Packs.load("dependency", null).data;
    private static final Map<String, Object> EOL = Packs.load("eol", null).data;

    private static Doc lib(String artifact, String version, String group, Object... more) {
        List<Object> kv = new ArrayList<>(List.of("id", "library:" + group + ":" + artifact + ":" + version, "artifact", artifact,
                "source", "maven"));
        kv.add("version");
        kv.add(version);
        kv.add("group");
        kv.add(group);
        Doc d = Doc.of(Schemas.LIBRARY, kv.toArray());
        for (int i = 0; i < more.length; i += 2) {
            d.set((String) more[i], more[i + 1]);
        }
        return d;
    }

    private static Doc vuln(String id, String severity, List<String> fixed) {
        return Doc.of(Schemas.VULNERABILITY, "id", id, "severity", severity, "fixed_versions", new ArrayList<>(fixed));
    }

    @Test
    void versionOrder() {
        assertEquals(List.of("1.2.9", "1.2.17", "2.0-beta1", "2.0-rc1", "2.0", "2.0.1", "10.0"),
                Versions.sort(List.of("2.0", "1.2.17", "2.0-rc1", "1.2.9", "2.0.1", "2.0-beta1", "10.0")));
        assertEquals(0, Versions.compare("2.0", "2.0.0.RELEASE"));
        assertTrue(Versions.compare("4.3.11.Final", "5.4.24.Final") < 0);
        assertTrue(Versions.compare("2.0-sp1", "2.0") > 0);
    }

    @Test
    void ranges() {
        assertEquals(Boolean.TRUE, Versions.inRange("1.2.17", "<2"));
        assertEquals(Boolean.TRUE, Versions.inRange("3.2", ">=3.0,<3.5"));
        assertEquals(Boolean.FALSE, Versions.inRange("3.5", ">=3.0,<3.5"));
        assertNull(Versions.inRange(null, "<2"));
        assertEquals(Boolean.TRUE, Versions.inRange(null, "*"));
    }

    @Test
    void eolLibrariesAndTech() {
        List<Doc> libs = List.of(lib("struts", "1.2.9", null), lib("log4j-core", "2.24.0", "org.apache.logging.log4j"),
                lib("spring-webmvc", "4.3.30.RELEASE", "org.springframework"),
                lib("spring-batch-core", "3.0.10.RELEASE", "org.springframework.batch"), lib("spring-webmvc", "6.2.0", "org.springframework"),
                lib("axis", null, null), lib("commons-lang", null, null));
        List<Doc> tech = List.of(Doc.of(Schemas.TECH_ITEM, "id", "t1", "name", "Java", "version", "1.8", "category", "language"),
                Doc.of(Schemas.TECH_ITEM, "id", "t2", "name", "Java", "version", "21", "category", "language"),
                Doc.of(Schemas.TECH_ITEM, "id", "t3", "name", "Struts 1", "category", "framework"));
        Eol.apply(libs, tech, EOL);
        assertEquals(java.util.Arrays.asList(true, false, true, true, false, true, null), libs.stream().map(d -> d.get("eol")).toList());
        assertEquals("2013-04-05", libs.get(0).str("eol_date"));
        assertTrue(libs.get(0).str("replacement").contains("Spring MVC"));
        assertTrue(libs.get(3).str("replacement").contains("Batch")); // the specific entry wins over Spring Framework
        assertEquals(List.of(true, false, true), tech.stream().map(d -> d.get("eol")).toList());
    }

    @Test
    void recommendationOrder() {
        Doc eolJar = lib("log4j", "1.2.17", "log4j");
        Eol.apply(List.of(eolJar), List.of(), EOL);
        Doc r = Upgrades.recommend(eolJar, DEP);
        assertEquals(List.of("replace", "eol"), List.of(r.str("action"), r.str("rule")));
        assertTrue(r.str("recipe").contains("Log4j1ToLog4j2"));

        Doc spring = lib("spring-jdbc", "4.3.30.RELEASE", "org.springframework");
        Eol.apply(List.of(spring), List.of(), EOL);
        assertEquals("use-bom", Upgrades.recommend(spring, DEP).str("action"));

        r = Upgrades.recommend(lib("servlet-api", "2.5", "javax.servlet"), DEP);
        assertEquals("migrate-jakarta", r.str("action"));
        assertTrue(r.str("target").contains("jakarta.servlet-api"));
        assertTrue(r.str("recipe").contains("Jakarta"));

        assertEquals("org.postgresql:postgresql", Upgrades.recommend(lib("ojdbc8", "19.3", "com.oracle.database.jdbc"), DEP).str("target"));
        assertEquals("use-bom", Upgrades.recommend(lib("jackson-databind", "2.9.0", "com.fasterxml.jackson.core"), DEP).str("action"));
        assertEquals("keep", Upgrades.recommend(lib("slf4j-api", "1.7", null), DEP).str("action")); // group unknown

        Doc angular = lib("angular", "1.5.8", null, "language", "javascript", "source", "js-file");
        Eol.apply(List.of(angular), List.of(), EOL);
        assertEquals("rewrite", Upgrades.recommend(angular, DEP).str("action"));
        assertEquals("remove", Upgrades.recommend(lib("jquery", "3.7.1", null, "language", "javascript", "source", "npm"), DEP).str("action"));
    }

    @Test
    void vulnerableLibraryUpgradesToLowestFix() {
        Doc mybatis = lib("mybatis", "3.4.6", "org.mybatis", "vulnerabilities",
                new ArrayList<>(List.of(vuln("CVE-1", "high", List.of("3.5.6")), vuln("CVE-2", "medium", List.of("3.5.0", "3.5.9")))));
        Doc r = Upgrades.recommend(mybatis, DEP);
        assertEquals(List.of("upgrade", "org.mybatis:mybatis:3.5.6", false), List.of(r.str("action"), r.str("target"), r.bool("verify")));
        Doc unknown = lib("x", "1.0", "g", "vulnerabilities", new ArrayList<>(List.of(vuln("CVE-3", "low", List.of()))));
        r = Upgrades.recommend(unknown, DEP);
        assertEquals("upgrade", r.str("action"));
        assertNull(r.str("target"));
        assertTrue(r.bool("verify"));
    }

    @Test
    void dependencyCheckReport() {
        Vulns.Report report = Vulns.loadReport(DC_REPORT, "dc.json");
        assertNotNull(report);
        assertEquals("dependency-check", report.tool);
        assertEquals("2026-09-30", report.databaseDate);
        Vulns.Entry log4j = report.entries.stream().filter(e -> "log4j-1.2.17.jar".equals(e.fileName())).findFirst().orElseThrow();
        assertEquals("CVE-2019-17571", log4j.vulnerabilities().get(0).str("id"));
        Vulns.Entry cc = report.entries.stream().filter(e -> e.fileName() != null && e.fileName().contains("commons-collections"))
                .findFirst().orElseThrow();
        assertEquals(List.of("3.2.2"), cc.vulnerabilities().get(0).list("fixed_versions"));
        Vulns.Entry struts = report.entries.stream().filter(e -> "struts-1.2.9.jar".equals(e.fileName())).findFirst().orElseThrow();
        Doc v2 = struts.vulnerabilities().stream().filter(v -> v.str("id").equals("CVE-2014-0114")).findFirst().orElseThrow();
        assertEquals(List.of(7.5, "high"), List.of(v2.get("cvss"), v2.str("severity"))); // CVSS v2 only
        assertTrue(report.entries.stream().noneMatch(e -> "ojdbc14.jar".equals(e.fileName()))); // no vulnerabilities
    }

    @Test
    void osvReport() {
        Vulns.Report report = Vulns.loadReport(OSV_REPORT, "osv.json");
        assertNotNull(report);
        assertEquals("osv-scanner", report.tool);
        Vulns.Entry hib = report.entries.stream().filter(e -> e.purls().get(0).contains("hibernate-core")).findFirst().orElseThrow();
        assertEquals(1, hib.vulnerabilities().size());
        Doc v = hib.vulnerabilities().get(0);
        assertEquals(List.of("CVE-2020-25638"), v.list("aliases"));
        assertEquals(7.4, v.get("cvss"));
        assertEquals("high", v.str("severity"));
        assertEquals(List.of("5.3.20.Final", "5.4.24.Final"), v.list("fixed_versions"));
    }

    @Test
    void unknownJsonIsIgnored(@TempDir Path tmp) throws Exception {
        Path p = tmp.resolve("x.json");
        Files.writeString(p, "{\"hello\": 1}");
        assertNull(Vulns.loadReport(p, "x.json"));
        Files.writeString(p, "not json");
        assertNull(Vulns.loadReport(p, "x.json"));
    }

    @Test
    void matchingBySha1PurlAndFileNameAndMerging() {
        Doc bySha1 = lib("axis", "1.4", null, "sha1", "5B4FF5E75BD75ED8F8E2BECAB121B4530E450B41");
        Doc byFile = lib("struts", "1.2.9", null);
        Doc byPurl = lib("log4j", "1.2.17", "log4j");
        Vulns.Report dc = Vulns.loadReport(DC_REPORT, "dc.json");
        Doc ghsa = Doc.of(Schemas.VULNERABILITY, "id", "GHSA-2qrg-x229-3v8q", "aliases", new ArrayList<>(List.of("CVE-2019-17571")),
                "severity", "critical", "sources", new ArrayList<>(List.of("osv-scanner")), "fixed_versions", new ArrayList<>(List.of("2.0")));
        Vulns.Report osv = new Vulns.Report("osv-scanner", "osv.json", null);
        osv.entries.add(new Vulns.Entry(null, List.of("pkg:maven/log4j/log4j@1.2.17"), null, new ArrayList<>(List.of(ghsa))));
        int unmatched = Vulns.applyReports(List.of(bySha1, byFile, byPurl), List.of(dc, osv));
        assertEquals(1, unmatched); // commons-collections is not in this inventory
        assertEquals(List.of("CVE-2018-8032", "CVE-2019-0227"), bySha1.list("cves"));
        assertEquals("high", byFile.str("max_severity"));
        List<Doc> vulns = byPurl.list("vulnerabilities");
        Doc merged = vulns.stream().filter(v -> v.str("id").equals("CVE-2019-17571")).findFirst().orElseThrow();
        assertEquals(List.of("dependency-check", "osv-scanner"), merged.list("sources"));
        assertTrue(merged.list("aliases").contains("GHSA-2qrg-x229-3v8q"));
        assertTrue(merged.list("fixed_versions").contains("2.0"));
        assertEquals(3, vulns.size());
        assertEquals("critical", byPurl.str("max_severity"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sbomScopesAndHashes() {
        List<Doc> libs = List.of(lib("junit", "4.12", "junit", "scope", "test"),
                lib("servlet-api", "2.5", "javax.servlet", "scope", "provided", "sha1", "ab".repeat(20)), lib("x", null, null, "sha1", "not-a-sha"));
        Map<String, Object> sbom = Sbom.build("app", "run1", "2026-10-10T00:00:00+00:00", "0.1.0", libs);
        List<Map<String, Object>> comps = (List<Map<String, Object>>) sbom.get("components");
        assertEquals(List.of("excluded", "optional", "required"), comps.stream().map(c -> c.get("scope")).toList());
        assertEquals(List.of(Map.of("alg", "SHA-1", "content", "ab".repeat(20))), comps.get(1).get("hashes"));
        assertTrue(!comps.get(2).containsKey("hashes") && !comps.get(2).containsKey("purl"));
        assertEquals("pkg:maven/junit/junit@4.12", comps.get(0).get("purl"));
    }

    private static Vulns.ToolConfig tool(String path, String dataDir, boolean offline) {
        return new Vulns.ToolConfig(true, path, dataDir, null, offline, 1);
    }

    @Test
    void configuredPathThatDoesNotExist() {
        Vulns.Outcome o = Vulns.runDependencyCheck(tool(Path.of("missing", "dependency-check.sh").toString(), null, true), List.of(),
                Path.of("unused"), "app");
        assertNull(o.report());
        assertEquals("missing", o.source().str("status"));
    }

    private static Path script(Path file, String body) throws Exception {
        Files.writeString(file, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void runningTools(@TempDir Path tmp) throws Exception {
        Path fake = script(tmp.resolve("dependency-check.sh"), "while [ $# -gt 0 ]; do [ \"$1\" = \"--out\" ] && out=\"$2\"; shift; done\n"
                + "cp \"" + DC_REPORT.toAbsolutePath() + "\" \"$out/dependency-check-report.json\"\n");
        Vulns.Outcome o = Vulns.runDependencyCheck(tool(fake.toString(), null, true), List.of(tmp), tmp.resolve("out"), "app");
        assertNotNull(o.report());
        assertEquals("ran", o.source().str("status"));
        assertEquals("2026-09-30", o.source().str("database_date"));

        Path broken = script(tmp.resolve("osv-scanner"), "echo 'no database' >&2\nexit 127\n");
        o = Vulns.runOsvScanner(tool(broken.toString(), null, false), List.of(tmp), tmp.resolve("out2"));
        assertNull(o.report());
        assertEquals("failed", o.source().str("status"));
        assertTrue(o.source().str("detail").contains("no database"));
    }

    @Test
    void aWrongToolPathIsNamed(@TempDir Path tmp) {
        String wrong = tmp.resolve("tools").resolve("osv-scanner").toString();
        Vulns.Outcome o = Vulns.runOsvScanner(tool(wrong, null, false), List.of(tmp), tmp.resolve("o"));
        assertEquals("missing", o.source().str("status"));
        assertTrue(o.source().str("detail").startsWith("not found at " + wrong));
        assertEquals("not installed", Vulns.missingDetail(null, List.of("no-such-tool-anywhere")));
    }

    @Test
    void knownScannerFailuresAreExplained() {
        String osv = "Error during extraction: (extracting as vulnmatch/osvlocal) unable to fetch OSV "
                + "database: no offline version of the OSV database is available";
        assertTrue(Vulns.failureHint(Vulns.OSV, osv).contains("set security.osv_scanner.offline: false"));
        assertTrue(Vulns.failureHint(Vulns.DC, "[ERROR] No documents exist").contains("NVD database is not downloaded"));
        assertNull(Vulns.failureHint(Vulns.DC, osv)); // each hint belongs to its own tool
        assertNull(Vulns.failureHint(Vulns.OSV, "something else"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void osvScannerCommandLine(@TempDir Path tmp) throws Exception {
        Path fake = script(tmp.resolve("osv-scanner"), "echo \"$@ DB=$OSV_SCANNER_LOCAL_DB_CACHE_DIRECTORY\" > \"" + tmp + "/args.txt\"\n"
                + "while [ $# -gt 0 ]; do [ \"$1\" = \"--output\" ] && out=\"$2\"; shift; done\n"
                + "cp \"" + OSV_REPORT.toAbsolutePath() + "\" \"$out\"\nexit 1\n");
        Vulns.Outcome o = Vulns.runOsvScanner(tool(fake.toString(), tmp.resolve("db").toString(), true), List.of(tmp), tmp.resolve("o"));
        assertNotNull(o.report());
        assertEquals("ran", o.source().str("status"));
        String args = Files.readString(tmp.resolve("args.txt"));
        assertTrue(args.contains("--offline ") && args.contains("--experimental-plugins java/archive"));
        assertTrue(args.strip().endsWith("DB=" + tmp.resolve("db")));
        Vulns.runOsvScanner(tool(fake.toString(), null, false), List.of(tmp), tmp.resolve("o2"));
        args = Files.readString(tmp.resolve("args.txt"));
        assertTrue(!args.contains("--offline") && args.contains("--experimental-plugins java/archive"));
    }

    @Test
    void jdkToolOutputParsing() {
        String line = "   org.apache.axis.%s   -> sun.misc.BASE64Encoder   JDK internal API (JDK removed)";
        String jdeps = "axis-1.4.jar -> JDK removed internal API\naxis-1.4.jar -> java.base\n" + String.format(line, "Foo") + "\n"
                + String.format(line, "Bar");
        List<Map<String, Object>> items = Jdk.parseJdeps(jdeps, "lib/axis-1.4.jar");
        assertEquals(List.of(List.of("jdeps", "sun.misc.BASE64Encoder", "axis-1.4.jar")),
                items.stream().map(i -> List.of(i.get("tool"), i.get("api"), i.get("name"))).toList());
        String out = "Jar file lib/x.jar:\nclass a/B uses deprecated method java/lang/Thread::stop()V (forRemoval=true)\n";
        List<Map<String, Object>> found = Jdk.parseJdeprscan(out, "lib/x.jar");
        assertEquals(1, found.size());
        assertEquals("a.B", found.get(0).get("class"));
        assertTrue(String.valueOf(found.get(0).get("api")).startsWith("java/lang/Thread::stop"));
    }
}
