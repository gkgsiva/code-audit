package com.legacyaudit.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
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

/**
 * The project pages added for the complete web app: settings as a form and as text (kept inside
 * the allowed folders), input files, the component picker, the tool checks, and the runs list
 * with compare, component and evidence views.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:workspace;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "legacy-audit.admins=admin"
})
class WorkspaceFlowTest {

    static final Path SAMPLES = Path.of("..", "..", "samples").toAbsolutePath().normalize();
    static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    static Path audits;

    @DynamicPropertySource
    static void folders(DynamicPropertyRegistry registry) {
        registry.add("legacy-audit.audits-root", audits::toString);
        registry.add("legacy-audit.sources-roots", SAMPLES::toString);
    }

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void settingsInputsComponentsAndRuns() throws Exception {
        MockHttpSession siva = login("siva");
        String source = SAMPLES.resolve("struts-oracle-app").toString();
        mvc.perform(json(post("/api/projects"), siva, Map.of("title", "Orders", "sourcePath", source))).andExpect(status().isOk());
        Path cfg = audits.resolve("orders/audit.yaml");

        // settings: the form, the text, and the folders the site allows
        mvc.perform(get("/api/projects/orders/config").session(siva))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.application.name").value("Orders"))
                .andExpect(jsonPath("$.fields.source.type").value("local"))
                .andExpect(jsonPath("$.text").value(Matchers.containsString("application:")));
        mvc.perform(json(put("/api/projects/orders/config/fields"), siva, Map.of("exclude", List.of("**/*.bak"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.exclude[0]").value("**/*.bak"));
        assertThat(Files.readString(cfg)).contains("exclude:\n- '**/*.bak'").contains("# legacy-audit project created on the web app");
        String before = Files.readString(cfg);
        mvc.perform(json(put("/api/projects/orders/config/fields"), siva, Map.of("source", Map.of("type", "local", "paths", List.of("/etc")))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("outside the folders this site may scan")));
        assertThat(Files.readString(cfg)).isEqualTo(before);
        mvc.perform(json(put("/api/projects/orders/config/text"), siva, Map.of("text", "application: [\n")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(put("/api/projects/orders/config/text"), siva, Map.of("text", before.replace("audit-output", "../escape"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("must be a folder inside the project")));
        assertThat(Files.readString(cfg)).isEqualTo(before);
        mvc.perform(json(put("/api/projects/orders/config/text"), siva, Map.of("text", before + "# checked on the settings page\n")))
                .andExpect(status().isOk());

        // input files
        mvc.perform(get("/api/projects/orders/inputs").session(siva))
                .andExpect(jsonPath("$.kinds[0].kind").value("autosys"))
                .andExpect(jsonPath("$.kinds[3].files").isEmpty());
        mvc.perform(put("/api/projects/orders/inputs/ddl/schema.sql").session(siva).with(csrf())
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content("CREATE TABLE t (id INT);\n"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(25));
        mvc.perform(get("/api/projects/orders/inputs").session(siva))
                .andExpect(jsonPath("$.kinds[3].files[0].name").value("schema.sql"));
        mvc.perform(put("/api/projects/orders/inputs/ddl/..evil").session(siva).with(csrf()).content("x"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/projects/orders/inputs/nope/a.txt").session(siva).with(csrf()).content("x"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/projects/orders/inputs/ddl/schema.sql").session(siva).with(csrf())).andExpect(status().isOk());
        mvc.perform(delete("/api/projects/orders/inputs/ddl/schema.sql").session(siva).with(csrf())).andExpect(status().isNotFound());

        // component picker
        mvc.perform(get("/api/projects/orders/component-choices").session(siva))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[0].name").value("struts-oracle-app"))
                .andExpect(jsonPath("$.components[0].included").value(true))
                .andExpect(jsonPath("$.components[0].scan_type").doesNotExist());
        mvc.perform(json(put("/api/projects/orders/component-choices"), siva, Map.of("included", Map.of(), "types", Map.of("struts-oracle-app", "batch"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components[0].type_override").value("batch"));
        mvc.perform(json(put("/api/projects/orders/component-choices"), siva, Map.of("types", Map.of("struts-oracle-app", "nope"))))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(json(put("/api/projects/orders/component-choices"), siva, java.util.Collections.singletonMap("types",
                java.util.Collections.singletonMap("struts-oracle-app", null)))).andExpect(jsonPath("$.components[0].type_override").isEmpty());

        // tool checks
        mvc.perform(get("/api/projects/orders/doctor").session(siva))
                .andExpect(jsonPath("$[0].name").value("Java"))
                .andExpect(jsonPath("$[1].name").value("Output folder"))
                .andExpect(jsonPath("$[?(@.name == 'AI model')].detail").value("Copilot in VS Code"));

        // two scans: runs, compare, component, evidence
        scan(siva, true);
        scan(siva, false);
        mvc.perform(get("/api/projects/orders/runs").session(siva))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].latest").value(true))
                .andExpect(jsonPath("$[1].latest").value(false))
                .andExpect(jsonPath("$[0].files").isNumber());
        mvc.perform(get("/api/projects/orders/compare").session(siva))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entities.components.added_count").value(0));
        mvc.perform(get("/api/projects/orders/compare").param("a", "../x").session(siva)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/projects/orders/runs/latest/components/struts-oracle-app").session(siva))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.component.name").value("struts-oracle-app"));
        mvc.perform(get("/api/projects/orders/runs/latest/components/nope").session(siva)).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/orders/runs/latest/evidence").param("file", "orders-web/build.xml").param("line", "1").session(siva))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.start").value(1))
                .andExpect(jsonPath("$.text").value(Matchers.containsString("<project")));
        mvc.perform(get("/api/projects/orders/runs/latest/evidence").param("file", "../../etc/passwd").session(siva))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/orders/dashboard").param("run", "previous").session(siva))
                .andExpect(jsonPath("$.dashboard.kpis[0].label").value("Lines of code"));
        mvc.perform(get("/api/projects/orders/facts/components").param("run", "previous").session(siva))
                .andExpect(jsonPath("$.result.rows[?(@.name == 'struts-oracle-app')]").exists());
        mvc.perform(get("/api/projects/orders/component-choices").session(siva))
                .andExpect(jsonPath("$.components[0].scan_type").isString());

        // a viewer reads but changes nothing
        MockHttpSession anna = login("anna");
        mvc.perform(json(put("/api/projects/orders/members"), siva, Map.of("members", Map.of("anna", "viewer")))).andExpect(status().isOk());
        mvc.perform(get("/api/projects/orders/config").session(anna)).andExpect(status().isOk());
        mvc.perform(json(put("/api/projects/orders/config/text"), anna, Map.of("text", before))).andExpect(status().isForbidden());
        mvc.perform(put("/api/projects/orders/inputs/ddl/a.sql").session(anna).with(csrf()).content("x")).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/projects/orders/component-choices"), anna, Map.of())).andExpect(status().isForbidden());
    }

    private void scan(MockHttpSession session, boolean full) throws Exception {
        String started = mvc.perform(json(post("/api/projects/orders/scans"), session, Map.of("full", full)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = JSON.readTree(started).path("scan").path("id").asLong();
        String state = "queued";
        for (int i = 0; i < 300 && (state.equals("queued") || state.equals("running")); i++) {
            Thread.sleep(100);
            String body = mvc.perform(get("/api/projects/orders/scans/" + id).session(session)).andReturn().getResponse().getContentAsString();
            state = JSON.readTree(body).path("scan").path("status").asString();
        }
        assertThat(state).isEqualTo("done");
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
