package com.obsidian.obsidian.config;

import com.obsidian.obsidian.cards.CardRepository;
import com.obsidian.obsidian.ml.PendingImageJobRepository;
import com.obsidian.obsidian.stats.StatsController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real SecurityFilterChain (SecurityConfig.filterChain()) through MockMvc —
 * not a standalone/mocked chain — so a change to authorizeHttpRequests, formLogin, logout,
 * rememberMe, or csrf actually breaks these tests. StatsController is the mounted endpoint
 * purely as a cheap "anyRequest().authenticated()" probe; its own business logic is covered
 * by StatsControllerTest.
 */
@WebMvcTest(StatsController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
    "app.auth.username=testuser",
    "app.auth.password=testpass",
    "app.auth.remember-key=test-remember-key-0123456789",
    "app.auth.remember-validity-seconds=86400"
})
class SecurityConfigTest {

    @Autowired private MockMvc mvc;

    @MockBean JdbcTemplate jdbc;
    @MockBean CardRepository cardRepo;
    @MockBean PendingImageJobRepository imageJobRepo;

    private void stubStats() {
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.anyString(), (Class<Long>) org.mockito.ArgumentMatchers.eq(Long.class)))
            .thenReturn(0L);
        when(cardRepo.stats()).thenReturn(Map.of("notes_with_cards", 0, "active_cards", 0, "archived_cards", 0));
        when(imageJobRepo.countByStatus()).thenReturn(Map.of());
    }

    // ── Protected vs public ──────────────────────────────────────────────────

    @Test
    void protectedEndpoint_withoutAuth_returns401NotHtmlLoginPage() throws Exception {
        mvc.perform(get("/stats"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_withAuthenticatedSession_returns200() throws Exception {
        stubStats();
        var login = mvc.perform(SecurityMockMvcRequestBuilders.formLogin().user("testuser").password("testpass"))
            .andReturn();
        org.springframework.mock.web.MockHttpSession session =
            (org.springframework.mock.web.MockHttpSession) login.getRequest().getSession();
        mvc.perform(get("/stats").session(session))
            .andExpect(status().isOk());
    }

    @Test
    void internalApiPath_isPermitAll_requestReachesDispatcherInsteadOfBeingBlocked() throws Exception {
        // No controller for /api/internal/** is mounted in this slice, so a request
        // that gets PAST security falls through to a 404 (no handler) rather than a
        // 401 (blocked by security). If authorizeHttpRequests ever drops the
        // "/api/internal/**".permitAll() matcher, this flips to 401.
        mvc.perform(post("/api/internal/notes").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound());
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    void login_correctCredentials_returns200WithSessionAndRememberMeCookies() throws Exception {
        var result = mvc.perform(SecurityMockMvcRequestBuilders.formLogin().user("testuser").password("testpass"))
            .andExpect(status().isOk())
            .andReturn();
        org.junit.jupiter.api.Assertions.assertNotNull(result.getRequest().getSession(false),
            "a successful login must establish a session");
        // remember-me's actual signed token (not a clearing marker — see the wrong-password
        // test for that shape): non-empty value, positive Max-Age.
        jakarta.servlet.http.Cookie rememberMe = result.getResponse().getCookie("remember-me");
        org.junit.jupiter.api.Assertions.assertNotNull(rememberMe);
        org.junit.jupiter.api.Assertions.assertFalse(rememberMe.getValue().isBlank());
        org.junit.jupiter.api.Assertions.assertTrue(rememberMe.getMaxAge() > 0);
    }

    @Test
    void login_wrongPassword_returns401AndClearsAnyStaleRememberMeCookie() throws Exception {
        // AbstractRememberMeServices clears the cookie on a failed attempt (Max-Age=0,
        // empty value) rather than omitting it — a real signed token must never be issued
        // for a failed login.
        mvc.perform(SecurityMockMvcRequestBuilders.formLogin().user("testuser").password("wrong-password"))
            .andExpect(status().isUnauthorized())
            .andExpect(cookie().maxAge("remember-me", 0))
            .andExpect(cookie().value("remember-me", (String) null));
    }

    @Test
    void login_unknownUser_returns401() throws Exception {
        mvc.perform(SecurityMockMvcRequestBuilders.formLogin().user("ghost").password("testpass"))
            .andExpect(status().isUnauthorized());
    }

    // ── Logout + CSRF-disabled mutating requests ────────────────────────────────

    @Test
    void logout_withoutCsrfToken_succeeds_becauseCsrfIsDisabled() throws Exception {
        // No _csrf parameter/header attached. If SecurityConfig ever re-enables CSRF
        // (removes .csrf(csrf -> csrf.disable())), this mutating POST starts getting
        // rejected with 403 before reaching the logout handler.
        var login = mvc.perform(SecurityMockMvcRequestBuilders.formLogin().user("testuser").password("testpass"))
            .andReturn();
        org.springframework.mock.web.MockHttpSession session =
            (org.springframework.mock.web.MockHttpSession) login.getRequest().getSession();
        mvc.perform(post("/logout").session(session))
            .andExpect(status().isOk());
    }

    @Test
    void logout_deletesRememberMeCookieAndInvalidatesSession() throws Exception {
        var login = mvc.perform(SecurityMockMvcRequestBuilders.formLogin().user("testuser").password("testpass"))
            .andReturn();
        org.springframework.mock.web.MockHttpSession session =
            (org.springframework.mock.web.MockHttpSession) login.getRequest().getSession();

        mvc.perform(post("/logout").session(session))
            .andExpect(status().isOk())
            .andExpect(cookie().maxAge("remember-me", 0));

        // The invalidated session no longer authenticates a follow-up request.
        mvc.perform(get("/stats").session(session))
            .andExpect(status().isUnauthorized());
    }
}
