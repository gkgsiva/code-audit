package com.legacyaudit.server;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:auth;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "legacy-audit.admins=admin, boss"
})
class AuthFlowTest {

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void healthIsOpenAndSeesTheDatabase() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.database").value("up"));
    }

    @Test
    void apiNeedsALogin() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void newNameBecomesOwnerAndStaysLoggedIn() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(login("siva", session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("owner"));
        mvc.perform(get("/api/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("siva"));
    }

    @Test
    void listedAdminsAreAdmins() throws Exception {
        mvc.perform(login("boss", new MockHttpSession())).andExpect(jsonPath("$.role").value("admin"));
    }

    @Test
    void badNamesAreRefusedInPlainWords() throws Exception {
        mvc.perform(login(" ", new MockHttpSession()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Type a user name"));
        mvc.perform(login("../etc", new MockHttpSession())).andExpect(status().isBadRequest());
    }

    @Test
    void loginWithoutTheCsrfTokenIsRefused() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(login("anna", session)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
    }

    private static org.springframework.test.web.servlet.RequestBuilder login(String name, MockHttpSession session) {
        return post("/api/auth/login")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}");
    }
}
