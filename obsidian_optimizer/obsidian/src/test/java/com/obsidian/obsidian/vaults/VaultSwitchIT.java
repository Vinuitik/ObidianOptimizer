package com.obsidian.obsidian.vaults;

import com.obsidian.obsidian.chrono.ChronoService;
import com.obsidian.obsidian.settings.SettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * M4: vault_settings migration + per-vault accessors, switching (DB flag, in-memory state,
 * event, rescan, header filter) and the archive/restore rules, against real Postgres.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@RecordApplicationEvents
class VaultSwitchIT {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName
            .parse("paradedb/paradedb:latest").asCompatibleSubstituteFor("postgres"));

    static final Path V1;
    static final Path V2;
    static final Path V3;
    static {
        try {
            V1 = Files.createTempDirectory("obsidian-switch-v1");
            V2 = Files.createTempDirectory("obsidian-switch-v2");
            V3 = Files.createTempDirectory("obsidian-switch-v3");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry r) {
        r.add("VAULT_PATH",                          V1::toString);
        r.add("spring.datasource.url",               postgres::getJdbcUrl);
        r.add("spring.datasource.username",          postgres::getUsername);
        r.add("spring.datasource.password",          postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @MockBean ChronoService chronoService;

    @Autowired JdbcTemplate jdbc;
    @Autowired ActiveVault activeVault;
    @Autowired VaultRepository vaults;
    @Autowired SettingsRepository settings;
    @Autowired ApplicationEvents events;

    MockMvc mvc;

    @BeforeEach
    void reset() {
        jdbc.update("""
            INSERT INTO vaults(id, name, container_path, active, is_primary)
            VALUES (2, 'Second', ?, false, false), (3, 'Third', ?, false, false)
            ON CONFLICT DO NOTHING
            """, V2.toString(), V3.toString());
        jdbc.update("UPDATE vaults SET archived_at = NULL");
        jdbc.update("UPDATE vaults SET active = false");
        jdbc.update("UPDATE vaults SET active = true WHERE id = 1");
        activeVault.set(1L, V1);
        jdbc.update("DELETE FROM vault_settings WHERE vault_id > 1");
        jdbc.update("DELETE FROM vaults WHERE id > 3");
        wrapper = org.mockito.Mockito.mock(VaultWrapperClient.class);
        mvc = MockMvcBuilders.standaloneSetup(new VaultController(vaults, activeVault, wrapper)).build();
    }

    VaultWrapperClient wrapper;

    // ── POST /vaults (M6) ─────────────────────────────────────────────────────

    @Test
    void register_insertsInactiveRowAtVaultsId_callsWrapper_returns202() throws Exception {
        org.mockito.Mockito.when(wrapper.register(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq("/h/new")))
            .thenReturn(new VaultWrapperClient.Result(202, "{}"));
        mvc.perform(post("/vaults").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" Fourth \",\"hostPath\":\"/h/new\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.name").value("Fourth"))
            .andExpect(jsonPath("$.active").value(false))
            .andExpect(jsonPath("$.container_path").value("/vaults/4"));
        org.mockito.Mockito.verify(wrapper).register(4L, "/h/new");
        assertThat(activeVault.id()).isEqualTo(1L);
    }

    @Test
    void register_wrapperRejects_rollsBackRow_andRelaysStatus() throws Exception {
        org.mockito.Mockito.when(wrapper.register(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(new VaultWrapperClient.Result(409, "{\"error\":\"overlaps\"}"));
        mvc.perform(post("/vaults").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"hostPath\":\"/h/bad\"}"))
            .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vaults WHERE host_path = '/h/bad'", Integer.class)).isZero();
    }

    @Test
    void register_wrapperDown_503_rollsBack_andBlankFieldsAre400() throws Exception {
        org.mockito.Mockito.when(wrapper.register(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString()))
            .thenThrow(new VaultException(503, "host helper unreachable"));
        mvc.perform(post("/vaults").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"X\",\"hostPath\":\"/h/down\"}"))
            .andExpect(status().isServiceUnavailable());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vaults WHERE host_path = '/h/down'", Integer.class)).isZero();
        mvc.perform(post("/vaults").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/vaults").contentType(MediaType.APPLICATION_JSON).content("{\"hostPath\":\"/h\"}"))
            .andExpect(status().isBadRequest());
    }

    // ── vault_settings ────────────────────────────────────────────────────────

    @Test
    void migration_copiesLegacyKeysToVault1_andNeverOverwritesLaterEdits() {
        jdbc.update("INSERT INTO app_settings(key, value) VALUES ('syncPassphrase', 'legacy-pass') "
            + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value");
        jdbc.update("DELETE FROM vault_settings WHERE vault_id = 1 AND key = 'syncPassphrase'");

        settings.initSchema();
        assertThat(settings.getSyncPassphrase(1L)).isEqualTo("legacy-pass");

        settings.setSyncPassphrase(1L, "edited-per-vault");
        settings.initSchema();                                   // idempotent re-run
        assertThat(settings.getSyncPassphrase(1L)).isEqualTo("edited-per-vault");
        assertThat(jdbc.queryForObject("SELECT value FROM app_settings WHERE key = 'syncPassphrase'",
            String.class)).isEqualTo("legacy-pass");             // legacy row left in place
    }

    @Test
    void perVaultKeys_areIsolated_andChronoDateIsPerVault() {
        settings.setChronoLastRunDate(1L, "2026-10-01");
        assertThat(settings.getChronoLastRunDate(1L)).isEqualTo("2026-10-01");
        assertThat(settings.getChronoLastRunDate(2L)).isEqualTo("");
        settings.setSyncPassphrase(2L, "only-two");
        assertThat(settings.getSyncPassphrase(1L)).isNotEqualTo("only-two");
        assertThat(settings.getStartupSyncMode(2L)).isEqualTo("blocking");
        assertThat(settings.isSyncEnabled(2L)).isFalse();        // new vaults start sync-off
    }

    @Test
    void syncEnabledVaultIds_skipsOffAndArchivedVaults() {
        settings.setSyncEnabled(1L, true);
        settings.setSyncEnabled(2L, true);
        settings.setSyncEnabled(3L, false);
        assertThat(settings.syncEnabledVaultIds()).containsExactly(1L, 2L);

        vaults.archive(2);
        assertThat(settings.syncEnabledVaultIds()).containsExactly(1L);
    }

    @Test
    void driveFolder_cannotBeSharedBetweenVaults() {
        settings.setSyncDriveFolderId(1L, "folder-A");
        assertThatThrownBy(() -> settings.setSyncDriveFolderId(2L, "folder-A"))
            .isInstanceOf(SettingsRepository.DuplicateDriveFolderException.class);
        settings.setSyncDriveFolderId(2L, "folder-B");           // distinct folder is fine
        assertThat(settings.otherVaultUsingDriveFolder(2L, "folder-A")).isEqualTo(1L);
        settings.setSyncDriveFolderId(1L, "");                   // clearing never conflicts
        settings.setSyncDriveFolderId(2L, "");
    }

    // ── switching ─────────────────────────────────────────────────────────────

    @Test
    void switchTo_flipsDbFlagAndState_publishesEvent_rescansNewRoot_andHeaderFilterFollows() throws Exception {
        Files.writeString(V2.resolve("hello.md"), "# Hello from vault two\n");

        mvc.perform(put("/vaults/active").contentType(MediaType.APPLICATION_JSON).content("{\"id\":2}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(2))
            .andExpect(jsonPath("$.active").value(true));

        assertThat(activeVault.id()).isEqualTo(2L);
        assertThat(activeVault.root()).isEqualTo(V2);
        assertThat(vaults.findActiveId()).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vaults WHERE active", Integer.class)).isEqualTo(1);
        assertThat(events.stream(VaultSwitchedEvent.class).map(VaultSwitchedEvent::newVaultId).toList())
            .contains(2L);

        // FileRepository's listener reran the startup sync against the NEW root
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notes WHERE vault_id = 2 AND path LIKE '%hello.md'", Integer.class))
                .isEqualTo(1));
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM notes WHERE vault_id = 1 AND path LIKE '%hello.md'", Integer.class)).isZero();

        VaultHeaderFilter filter = new VaultHeaderFilter(activeVault);
        assertThat(passes(filter, "/notes", "2")).isTrue();
        assertThat(passes(filter, "/notes", "1")).isFalse();     // old vault id now mismatches
    }

    private static boolean passes(VaultHeaderFilter filter, String uri, String header) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        req.addHeader(VaultHeaderFilter.HEADER, header);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        return chain.getRequest() != null;
    }

    @Test
    void switchTo_unknownIs404_archivedIs409_sameVaultIsNoOp() throws Exception {
        mvc.perform(put("/vaults/active").contentType(MediaType.APPLICATION_JSON).content("{\"id\":99}"))
            .andExpect(status().isNotFound());

        vaults.archive(3);
        mvc.perform(put("/vaults/active").contentType(MediaType.APPLICATION_JSON).content("{\"id\":3}"))
            .andExpect(status().isConflict());
        assertThat(activeVault.id()).isEqualTo(1L);

        long before = events.stream(VaultSwitchedEvent.class).count();
        mvc.perform(put("/vaults/active").contentType(MediaType.APPLICATION_JSON).content("{\"id\":1}"))
            .andExpect(status().isOk());
        assertThat(events.stream(VaultSwitchedEvent.class).count()).isEqualTo(before);
    }

    // ── registry REST + archive rules ─────────────────────────────────────────

    @Test
    void list_andRename() throws Exception {
        mvc.perform(get("/vaults")).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].active").value(true));
        mvc.perform(patch("/vaults/2").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Work\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Work"));
        mvc.perform(patch("/vaults/99").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(patch("/vaults/2").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void archive_setsAndRestoreClears_butNeverTheActiveOrLastVault() throws Exception {
        mvc.perform(post("/vaults/1/archive")).andExpect(status().isConflict());   // active
        mvc.perform(post("/vaults/2/archive")).andExpect(status().isOk())
            .andExpect(jsonPath("$.archived_at").isNotEmpty());
        mvc.perform(post("/vaults/2/restore")).andExpect(status().isOk())
            .andExpect(jsonPath("$.archived_at").doesNotExist());

        // With 2 and 3 archived, vault 1 is both active and the last non-archived one: refused.
        mvc.perform(post("/vaults/2/archive")).andExpect(status().isOk());
        mvc.perform(post("/vaults/3/archive")).andExpect(status().isOk());
        assertThat(activeVault.id()).isEqualTo(1L);
        mvc.perform(post("/vaults/1/archive")).andExpect(status().isConflict());
        mvc.perform(post("/vaults/99/archive")).andExpect(status().isNotFound());
    }
}
