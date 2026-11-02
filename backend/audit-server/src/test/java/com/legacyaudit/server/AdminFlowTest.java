package com.legacyaudit.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.legacyaudit.model.config.ConfigLoader;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The admin pages: site settings (report switches, sign-up, AI switch, limits, project defaults
 * and locked keys), users, project changes and deletion, the activity log and its CSV, the
 * dashboards, health, status and logs.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:admin;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "legacy-audit.admins=admin"
})
class AdminFlowTest {

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
    void adminPagesStatusAndLogs() throws Exception {
        MockHttpSession admin = login("admin");
        MockHttpSession siva = login("siva");
        mvc.perform(get("/api/admin").session(siva)).andExpect(status().isForbidden());

        // settings: the defaults, refusals, and saving
        JsonNode view = read(mvc.perform(get("/api/admin").session(admin)).andExpect(status().isOk()));
        assertThat(view.path("settings").path("limits").path("max_scans").asInt()).isEqualTo(2);
        assertThat(view.path("settings").path("reports").path("enabled").path("pptx").isMissingNode()).isTrue();
        assertThat(view.path("env_locked").isEmpty()).isTrue();
        mvc.perform(json(put("/api/admin"), admin, Map.of("limits", Map.of("max_scans", 99), "nope", 1)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(Matchers.allOf(
                        Matchers.containsString("nope: Extra inputs are not permitted"),
                        Matchers.containsString("limits.max_scans: Input should be less than or equal to 32"))));
        mvc.perform(json(put("/api/admin/defaults"), admin, Map.of("text", "application:\n  name: x\n")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("'application' is set per project, not in the defaults"));
        mvc.perform(json(put("/api/admin/defaults"), admin, Map.of("text", "sql:\n  dialect: db2\npdf:\n  enabled: false\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaults_yaml").value("sql:\n  dialect: db2\npdf:\n  enabled: false\n"));
        Map<String, Object> settings = JSON.convertValue(read(mvc.perform(get("/api/admin").session(admin))).path("settings"), Map.class);
        settings.put("locked", List.of("sql.dialect"));
        settings.put("limits", Map.of("max_scans", 3, "keep_runs", 1));
        settings.put("reports", Map.of("enabled", Map.of("summary", true, "tech", true, "pdf", false, "datapack", false)));
        settings.put("users", Map.of("open_signup", false, "who_can_create", "admins"));
        settings.put("ai", Map.of("allowed", false));
        mvc.perform(json(put("/api/admin"), admin, settings)).andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.limits.keep_runs").value(1));
        assertThat(Files.readString(audits.resolve(".legacy-audit/admin.yaml")))
                .startsWith("# legacy-audit hosting settings, written by the admin page. No secrets here.\nreports:\n");

        // sign-up closed, who creates projects
        mvc.perform(json(post("/api/auth/login"), new MockHttpSession(), Map.of("name", "newcomer")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("This name is not a user here; ask an admin to add you"));
        mvc.perform(get("/api/me").session(siva)).andExpect(jsonPath("$.canCreate").value(false));
        String source = SAMPLES.resolve("struts-oracle-app").toString();
        mvc.perform(json(post("/api/projects"), siva, Map.of("title", "Nope", "sourcePath", source))).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/projects"), admin, Map.of("title", "Orders", "sourcePath", source))).andExpect(status().isOk());

        // the defaults and locked keys reach the project's settings
        Path cfg = audits.resolve("orders/audit.yaml");
        Files.writeString(cfg, Files.readString(cfg) + "sql:\n  dialect: oracle\n");
        assertThat(((Map<?, ?>) ConfigLoader.load(cfg).config().dump().get("pdf")).get("enabled")).isEqualTo(false);
        assertThat(ConfigLoader.load(cfg).config().sqlDialect()).isEqualTo("db2");

        // users
        mvc.perform(json(put("/api/admin/users"), admin, Map.of("name", "anna", "role", "viewer"))).andExpect(status().isOk());
        mvc.perform(json(put("/api/admin/users"), admin, Map.of("name", "admin", "role", "viewer"))).andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/admin/users"), admin, Map.of("name", "bob", "role", "king"))).andExpect(status().isBadRequest());
        MockHttpSession anna = login("anna");
        mvc.perform(get("/api/me").session(anna)).andExpect(jsonPath("$.role").value("viewer"));
        mvc.perform(get("/api/admin/users").session(admin))
                .andExpect(jsonPath("$[?(@.name == 'admin')].fixed").value(true))
                .andExpect(jsonPath("$[?(@.name == 'admin')].projects").value(1))
                .andExpect(jsonPath("$[?(@.name == 'anna')].role").value("viewer"));
        mvc.perform(get("/api/users").session(siva)).andExpect(jsonPath("$").value(Matchers.hasItems("anna", "siva")));
        mvc.perform(json(put("/api/admin/users"), admin, Map.of("name", "anna", "role", "viewer", "disabled", true))).andExpect(status().isOk());
        mvc.perform(get("/api/me").session(anna)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/admin/users/anna").session(admin).with(csrf())).andExpect(status().isOk());

        // project changes: owner, hidden reports, archive
        mvc.perform(json(put("/api/projects/orders"), admin, Map.of("owner", "nobody"))).andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/projects/orders"), admin, Map.of("hiddenReports", List.of("pptx")))).andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/projects/orders"), admin, Map.of("owner", "siva", "hiddenReports", List.of("tech"),
                        "hiddenSections", List.of("batch"), "title", "Orders system")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value("siva"))
                .andExpect(jsonPath("$.title").value("Orders system"))
                .andExpect(jsonPath("$.hiddenReports[0]").value("tech"));

        // two scans; keep_runs 1 keeps only the newest run
        scan(siva, true);
        scan(siva, false);
        mvc.perform(get("/api/projects/orders/runs").session(siva)).andExpect(jsonPath("$.length()").value(1));

        // report switches: tech hidden by the owner, PDF and data pack off, functional and mgmt not enabled
        mvc.perform(get("/api/projects/orders/reports").session(siva))
                .andExpect(jsonPath("$.allowed").value(Matchers.contains("summary")))
                .andExpect(jsonPath("$.reports.tech").doesNotExist())
                .andExpect(jsonPath("$.reports").value(Matchers.hasKey("summary")))
                .andExpect(jsonPath("$.datapack").isEmpty());
        mvc.perform(json(post("/api/projects/orders/reports"), siva, Map.of("type", "tech")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("The admin has switched off: tech"));
        String runId = read(mvc.perform(get("/api/projects/orders/reports").session(siva))).path("run_id").asString();
        mvc.perform(get("/api/projects/orders/runs/" + runId + "/datapack.zip").session(siva)).andExpect(status().isForbidden());

        // AI switch
        mvc.perform(get("/api/projects/orders/enrichment").session(siva)).andExpect(jsonPath("$.ai_allowed").value(false));
        mvc.perform(json(post("/api/projects/orders/enrichment/run"), siva, Map.of()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("The admin has switched off AI enrichment on this site"));

        // status, logs
        mvc.perform(get("/api/status").session(siva))
                .andExpect(jsonPath("$.slots").value(3))
                .andExpect(jsonPath("$.jobs[0].kind").value("scan"))
                .andExpect(jsonPath("$.jobs[0].state").value("done"));
        JsonNode logs = read(mvc.perform(get("/api/logs").session(siva)));
        String logId = logs.get(0).path("id").asString();
        assertThat(logId).isEqualTo("orders/" + runId + "/scan.log");
        mvc.perform(get("/api/logs/view").param("id", logId).param("q", "analyzers").session(siva))
                .andExpect(jsonPath("$.lines[0]").value(Matchers.containsString("Analyzers")));
        mvc.perform(get("/api/logs/download").param("id", logId).session(siva))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("legacy_audit:")));
        mvc.perform(get("/api/logs/view").param("id", "orders/../../etc/passwd").session(siva)).andExpect(status().isNotFound());
        mvc.perform(get("/api/logs/view").param("id", "server/server.log").session(siva)).andExpect(status().isForbidden());

        // activity, CSV, dashboards, health
        mvc.perform(get("/api/admin/activity").session(admin))
                .andExpect(jsonPath("$[?(@.action == 'job finished')].kind").value(Matchers.hasItem("scan")))
                .andExpect(jsonPath("$[?(@.action == 'change project')].fields").exists())
                .andExpect(jsonPath("$[?(@.action == 'log in')].user").value(Matchers.hasItems("admin", "siva")));
        mvc.perform(get("/api/admin/activity.csv").session(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.startsWith("time,user,action,project,detail\r\n")))
                .andExpect(content().string(Matchers.containsString("delete user,,target=anna")));
        mvc.perform(get("/api/admin/dashboard/usage").param("days", "7").session(admin))
                .andExpect(jsonPath("$.series.length()").value(7))
                .andExpect(jsonPath("$.totals.scans_done").value(2))
                .andExpect(jsonPath("$.totals.success_percent").value(100.0))
                .andExpect(jsonPath("$.top_projects[0].name").value("orders"));
        mvc.perform(get("/api/admin/dashboard/system").session(admin))
                .andExpect(jsonPath("$.slots").value(3))
                .andExpect(jsonPath("$.cpu.cpus").isNumber())
                .andExpect(jsonPath("$.disk.free").isNumber())
                .andExpect(jsonPath("$.history").isArray());
        mvc.perform(get("/api/admin/health").session(admin))
                .andExpect(jsonPath("$.checks[0].name").value("Java"))
                .andExpect(jsonPath("$.projects[0].project").value("orders"))
                .andExpect(jsonPath("$.queue.slots").value(3));
        mvc.perform(get("/api/admin/projects").session(admin))
                .andExpect(jsonPath("$[0].runs").value(1))
                .andExpect(jsonPath("$[0].application").value("Orders"));

        // delete: admins only, with the folder name typed
        mvc.perform(json(post("/api/projects/orders/delete"), siva, Map.of("confirm", "orders"))).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/projects/orders/delete"), admin, Map.of("confirm", "Orders"))).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/projects/orders/delete"), admin, Map.of("confirm", "orders"))).andExpect(status().isOk());
        assertThat(audits.resolve("orders")).doesNotExist();
        mvc.perform(get("/api/projects/orders").session(admin)).andExpect(status().isNotFound());
    }

    private JsonNode read(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString());
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
        // the job ends (and is logged) just after the scan row
        Thread.sleep(200);
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
