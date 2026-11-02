package com.legacyaudit.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.legacyaudit.engine.scan.GitClones;
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
 * Git projects on the hosted site: creating one, a scan of another branch is checked, the clone
 * list, the clean-up by age and by free disk, deleting one clone, and a project's own clones going
 * with it (as the Python test_hosted clone test; the clones are written by hand, there is no Git
 * server in the test).
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:gitflow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "legacy-audit.admins=boss"
})
class GitFlowTest {

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

    static Path fakeClone(Path cache, String key, String url, double daysAgo) throws Exception {
        Path folder = cache.resolve(key);
        Files.createDirectories(folder.resolve(".git"));
        Files.write(folder.resolve("big.bin"), new byte[10]);
        double used = System.currentTimeMillis() / 1000.0 - daysAgo * 86400;
        Files.writeString(folder.resolve(".git").resolve(GitClones.USED_FILE), JSON.writeValueAsString(
                Map.of("url", url, "ref", key.substring(key.lastIndexOf('@') + 1), "commit", "a".repeat(40), "last_used", used)));
        return folder;
    }

    @Test
    void gitProjectsAndClones() throws Exception {
        MockHttpSession boss = login("boss");
        String urlA = "https://git.example.com/team/a.git";
        String urlB = "https://git.example.com/team/b.git";

        // a project on repository a
        mvc.perform(json(post("/api/projects"), boss, Map.of("title", "Billing", "git", Map.of("url", "", "ref", "main"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Give the Git address"));
        mvc.perform(json(post("/api/projects"), boss, Map.of("title", "Local", "git", Map.of("url", "/srv/git/a.git"))))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value(Matchers.containsString("must be an https://")));
        mvc.perform(json(post("/api/projects"), boss, Map.of("title", "Billing",
                        "git", Map.of("url", urlA, "ref", "main", "paths", List.of("app", " "), "tokenEnv", "GIT_TOKEN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("billing"));
        String yaml = Files.readString(audits.resolve("billing/audit.yaml"));
        assertThat(yaml).contains("type: git").contains("url: \"" + urlA + "\"").contains("ref: \"main\"")
                .contains("token_env: \"GIT_TOKEN\"").contains("- \"app\"");
        mvc.perform(get("/api/projects/billing").session(boss))
                .andExpect(jsonPath("$.git[0].url").value(urlA))
                .andExpect(jsonPath("$.git[0].ref").value("main"));

        // another branch for one scan: checked before anything runs
        for (String bad : List.of("--upload-pack=x", "a b", "../x")) {
            mvc.perform(json(post("/api/projects/billing/scans"), boss, Map.of("ref", bad))).andExpect(status().isUnprocessableContent());
        }
        mvc.perform(json(post("/api/projects"), boss, Map.of("title", "Orders", "sourcePath", SAMPLES.resolve("struts-oracle-app").toString())))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/projects/orders/scans"), boss, Map.of("ref", "release")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("A branch can only be chosen for a Git source"));

        // the clone cache
        String listingText = mvc.perform(get("/api/admin").session(boss)).andReturn().getResponse().getContentAsString();
        Path cache = Path.of(JSON.readTree(listingText).path("host").path("git_cache").asString());
        assertThat(cache).isEqualTo(audits.resolve(".legacy-audit/git").toAbsolutePath().normalize());
        Path old = fakeClone(cache, "git.example.com/team/a@main", urlA, 30);
        Path fresh = fakeClone(cache, "git.example.com/team/a@release", urlA, 1);
        Path other = fakeClone(cache, "git.example.com/team/b@main", urlB, 1);
        mvc.perform(get("/api/admin/clones").session(boss))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clones[2].key").value("git.example.com/team/a@main"))
                .andExpect(jsonPath("$.clones[2].projects[0]").value("billing"))
                .andExpect(jsonPath("$.clones[2].commit").value("aaaaaaaaaaaa"))
                .andExpect(jsonPath("$.keep_days").value(14))
                .andExpect(jsonPath("$.total").value(Matchers.greaterThanOrEqualTo(30)));
        mvc.perform(post("/api/admin/clones/prune").session(boss).with(csrf()))
                .andExpect(jsonPath("$.removed").value(Matchers.contains("git.example.com/team/a@main")));
        assertThat(old).doesNotExist();
        assertThat(fresh).exists();
        assertThat(other).exists();

        // low disk: the least recently used go first until enough is free (here never enough)
        String adminText = mvc.perform(get("/api/admin").session(boss)).andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = (Map<String, Object>) JSON.readValue(adminText, Map.class).get("settings");
        @SuppressWarnings("unchecked")
        Map<String, Object> limits = (Map<String, Object>) settings.get("limits");
        limits.put("min_free_gb", 100000);
        mvc.perform(json(put("/api/admin"), boss, settings)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/clones/prune").session(boss).with(csrf())).andExpect(jsonPath("$.removed.length()").value(2));
        limits.put("min_free_gb", 0);
        mvc.perform(json(put("/api/admin"), boss, settings)).andExpect(status().isOk());

        // one clone by hand, and the project's clones go with the project
        Path keep = fakeClone(cache, "git.example.com/team/b@main", urlB, 1);
        Path mine = fakeClone(cache, "git.example.com/team/a@main", urlA, 1);
        mvc.perform(json(post("/api/admin/clones/delete"), boss, Map.of("key", "../x"))).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/projects/billing/delete"), boss, Map.of("confirm", "billing"))).andExpect(status().isOk());
        assertThat(mine).doesNotExist();
        assertThat(keep).exists();
        mvc.perform(json(post("/api/admin/clones/delete"), boss, Map.of("key", "git.example.com/team/b@main"))).andExpect(status().isOk());
        assertThat(keep).doesNotExist();
        String activity = mvc.perform(get("/api/admin/activity").session(boss)).andReturn().getResponse().getContentAsString();
        assertThat(activity.split("\"delete clone\"", -1).length - 1).isEqualTo(5);
        assertThat(activity).contains("\"clean up clones\"");

        MockHttpSession siva = login("siva");
        mvc.perform(get("/api/admin/clones").session(siva)).andExpect(status().isForbidden());
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
