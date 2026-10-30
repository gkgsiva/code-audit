package com.legacyaudit.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** Create a project on a sample, scan it and read its inventory, as the screens do. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:projects;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "legacy-audit.admins=admin"
})
class ProjectScanFlowTest {

    static final Path SAMPLES = Path.of("..", "..", "samples").toAbsolutePath().normalize();

    @TempDir
    static Path audits;

    @DynamicPropertySource
    static void folders(DynamicPropertyRegistry registry) {
        registry.add("legacy-audit.audits-root", audits::toString);
        registry.add("legacy-audit.sources-roots", SAMPLES::toString);
    }

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void createScanAndReadTheInventory() throws Exception {
        MockHttpSession siva = login("siva");

        mvc.perform(get("/api/sources").session(siva))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folders[0].path").value(SAMPLES.toString()));
        mvc.perform(get("/api/sources").param("path", SAMPLES.toString()).session(siva))
                .andExpect(jsonPath("$.folders[?(@.name == 'struts-oracle-app')]").exists());
        mvc.perform(get("/api/sources").param("path", "/etc").session(siva)).andExpect(status().isNotFound());

        String source = SAMPLES.resolve("struts-oracle-app").toString();
        mvc.perform(json(post("/api/projects"), siva, Map.of("title", "Struts Billing!", "sourcePath", source)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("struts-billing"))
                .andExpect(jsonPath("$.role").value("owner"));
        assertThat(audits.resolve("struts-billing/audit.yaml")).exists();
        assertThat(audits.resolve("struts-billing/audit-inputs/autosys/.gitkeep")).exists();
        mvc.perform(json(post("/api/projects"), siva, Map.of("title", "Struts Billing!", "sourcePath", source)))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/api/projects"), siva, Map.of("title", "Elsewhere", "sourcePath", "/etc")))
                .andExpect(status().isBadRequest());

        String started = mvc.perform(json(post("/api/projects/struts-billing/scans"), siva, Map.of("full", true)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long scanId = JSON.readTree(started).path("scan").path("id").asLong();

        String status = "queued";
        for (int i = 0; i < 300 && (status.equals("queued") || status.equals("running")); i++) {
            Thread.sleep(100);
            String body = mvc.perform(get("/api/projects/struts-billing/scans/" + scanId).session(siva))
                    .andReturn().getResponse().getContentAsString();
            status = JSON.readTree(body).path("scan").path("status").asString();
        }
        assertThat(status).isEqualTo("done");

        mvc.perform(get("/api/projects/struts-billing/scans/" + scanId).session(siva))
                .andExpect(jsonPath("$.scan.files").isNumber())
                .andExpect(jsonPath("$.log[0]").exists());
        mvc.perform(get("/api/projects/struts-billing/inventory").session(siva))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThan(10)))
                .andExpect(jsonPath("$.languages[0].name").exists());
        mvc.perform(get("/api/projects/struts-billing/inventory").param("language", "java").param("q", "action").session(siva))
                .andExpect(jsonPath("$.files[0].language").value("java"));
        mvc.perform(get("/api/projects/struts-billing/components").session(siva))
                .andExpect(jsonPath("$.components[0].name").exists());
        mvc.perform(get("/api/projects/struts-billing/dashboard").session(siva))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dashboard.kpis[0].label").value("Lines of code"))
                .andExpect(jsonPath("$.dashboard.effort.unit").value("person-days"));
        mvc.perform(get("/api/projects/struts-billing/facts/findings").param("severity", "high").session(siva))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.total").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.result.rows[0].severity").value("high"));
        mvc.perform(get("/api/projects/struts-billing/facts/libraries").param("q", "log4j").session(siva))
                .andExpect(jsonPath("$.result.rows[0].artifact").value("log4j"));
        mvc.perform(get("/api/projects/struts-billing/facts/nothing").session(siva)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/projects/struts-billing/facts/findings").param("colour", "red").session(siva))
                .andExpect(status().isBadRequest());

        // Others see it only as members; a viewer may read but not scan.
        MockHttpSession anna = login("anna");
        mvc.perform(get("/api/projects/struts-billing").session(anna)).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/projects/struts-billing/members"), siva, Map.of("members", Map.of("anna", "viewer"))))
                .andExpect(jsonPath("$.members.anna").value("viewer"));
        mvc.perform(get("/api/projects/struts-billing/inventory").session(anna)).andExpect(status().isOk());
        mvc.perform(json(post("/api/projects/struts-billing/scans"), anna, Map.of()))
                .andExpect(status().isForbidden());
        mvc.perform(json(put("/api/projects/struts-billing/members"), anna, Map.of("members", Map.of())))
                .andExpect(status().isForbidden());

        // The admin sees every project.
        mvc.perform(get("/api/projects").session(login("admin")))
                .andExpect(jsonPath("$[?(@.id == 'struts-billing')].lastScan.status").value("done"));
    }

    @Test
    void slugsFollowThePythonRule() {
        assertThat(com.legacyaudit.server.projects.ProjectService.slugify("  Customer Billing (v2) ")).isEqualTo("customer-billing-v2");
        assertThat(com.legacyaudit.server.projects.ProjectService.slugify("***")).isEqualTo("project");
        assertThat(Files.isDirectory(SAMPLES)).isTrue();
    }

    private MockHttpSession login(String name) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(json(post("/api/auth/login"), session, Map.of("name", name))).andExpect(status().isOk());
        return session;
    }

    private static org.springframework.test.web.servlet.RequestBuilder json(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, MockHttpSession session, Object body) {
        return request.session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
    }
}
