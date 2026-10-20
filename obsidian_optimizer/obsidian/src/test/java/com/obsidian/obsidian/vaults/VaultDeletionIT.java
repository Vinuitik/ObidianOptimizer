package com.obsidian.obsidian.vaults;

import com.obsidian.obsidian.chrono.ChronoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** M7b: deletion enqueue rules, backup-before-purge, purge isolation, resumability, files untouched. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "vaults.deletion.initial-delay-ms=3600000", "vaults.deletion.poll-ms=3600000"})
@Testcontainers
class VaultDeletionIT {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName
            .parse("paradedb/paradedb:latest").asCompatibleSubstituteFor("postgres"));

    static final Path V1;
    static final Path V2;
    static {
        try {
            V1 = Files.createTempDirectory("obsidian-del-v1");
            V2 = Files.createTempDirectory("obsidian-del-v2");
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
    @MockBean VaultBackup backup;

    @Autowired JdbcTemplate jdbc;
    @Autowired ActiveVault activeVault;
    @Autowired VaultDeletionRepository repo;
    @Autowired VaultDeletionWorker worker;

    MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        reset(backup);
        jdbc.update("DELETE FROM vault_deletion_jobs");
        for (String t : new String[]{"notes", "workspace_items", "vault_settings"})
            jdbc.update("DELETE FROM " + t + " WHERE vault_id > 0");
        jdbc.update("DELETE FROM vaults WHERE id > 1");
        jdbc.update("UPDATE vaults SET active = true, is_primary = true, archived_at = NULL WHERE id = 1");
        jdbc.update("""
            INSERT INTO vaults(id, name, container_path, active, is_primary)
            VALUES (2, 'Second', ?, false, false), (3, 'Third', '/vaults/3', false, false)
            """, V2.toString());
        activeVault.set(1L, V1);
        mvc = MockMvcBuilders.standaloneSetup(new VaultDeletionController(repo)).build();
    }

    void seed(long v, String note) {
        jdbc.update("INSERT INTO notes(path, title, modified_at, vault_id) VALUES (?, 't', 0, ?)", note, v);
        jdbc.update("INSERT INTO workspace_items(filename, type, added_at, vault_id) VALUES (?, 'img', 0, ?)", note, v);
        jdbc.update("INSERT INTO vault_settings(vault_id, key, value) VALUES (?, 'k', 'v')", v);
    }

    int count(String table, long v) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE vault_id = ?", Integer.class, v);
    }

    String jobStatus(long v) {
        return jdbc.queryForObject("SELECT status FROM vault_deletion_jobs WHERE target_vault_id = ?", String.class, v);
    }

    @Test
    void enqueue_rejectsActive_primary_unknown() throws Exception {
        mvc.perform(post("/vaults/1/delete")).andExpect(status().isConflict());   // active + primary
        jdbc.update("UPDATE vaults SET active = (id = 2)");
        mvc.perform(post("/vaults/1/delete")).andExpect(status().isConflict());   // primary, not active
        mvc.perform(post("/vaults/2/delete")).andExpect(status().isConflict());   // active
        mvc.perform(post("/vaults/99/delete")).andExpect(status().isNotFound());
        mvc.perform(post("/vaults/3/delete")).andExpect(status().isAccepted());
    }

    @Test
    void enqueue_lastRemaining_409() throws Exception {
        jdbc.update("DELETE FROM vaults WHERE id = 3");
        jdbc.update("UPDATE vaults SET is_primary = false");
        mvc.perform(post("/vaults/2/delete")).andExpect(status().isAccepted());   // peer 1 is live
        jdbc.update("UPDATE vaults SET active = false");
        jdbc.update("UPDATE vaults SET active = true WHERE id = 2");
        mvc.perform(post("/vaults/1/delete")).andExpect(status().isConflict());   // only peer (2) is queued
    }

    @Test
    void enqueue_isIdempotent_andPendingVaultCannotBeSwitchedTo() throws Exception {
        mvc.perform(post("/vaults/2/delete")).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED"));
        mvc.perform(post("/vaults/2/delete")).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vault_deletion_jobs", Integer.class)).isEqualTo(1);
        org.junit.jupiter.api.Assertions.assertThrows(VaultException.class, () -> activeVault.switchTo(2));
    }

    @Test
    void worker_backsUpThenPurgesOnlyThatVault_keepsFiles() throws Exception {
        seed(1, "/v/a.md"); seed(2, "/v/a.md"); seed(3, "/v/c.md");
        Path disk = V2.resolve("keep.md");
        Files.writeString(disk, "x");
        mvc.perform(post("/vaults/2/delete")).andExpect(status().isAccepted());

        worker.drainOnce();

        verify(backup).backup(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vaults WHERE id = 2", Integer.class)).isZero();
        for (String t : new String[]{"notes", "workspace_items", "vault_settings"}) {
            assertThat(count(t, 2)).as(t).isZero();
            assertThat(count(t, 1)).as(t + " v1").isEqualTo(1);
            assertThat(count(t, 3)).as(t + " v3").isEqualTo(1);
        }
        assertThat(jobStatus(2)).isEqualTo("DONE");
        assertThat(disk).exists();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vaults", Integer.class)).isEqualTo(2);
    }

    @Test
    void backupFailure_purgesNothing_failsJob_andRetryResumes() throws Exception {
        seed(2, "/v/a.md");
        doThrow(new IllegalStateException("boom")).when(backup).backup(anyLong());
        mvc.perform(post("/vaults/2/delete")).andExpect(status().isAccepted());
        worker.drainOnce();

        assertThat(count("notes", 2)).isEqualTo(1);
        assertThat(jobStatus(2)).isEqualTo("FAILED");

        reset(backup);
        mvc.perform(post("/vaults/2/delete")).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("QUEUED"));
        worker.drainOnce();
        assertThat(count("notes", 2)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vaults WHERE id = 2", Integer.class)).isZero();
    }

    @Test
    void resumedJob_withBackupDone_doesNotBackUpAgain_andRerunIsIdempotent() throws Exception {
        seed(2, "/v/a.md");
        mvc.perform(post("/vaults/2/delete")).andExpect(status().isAccepted());
        jdbc.update("UPDATE vault_deletion_jobs SET status = 'RUNNING', backup_done = true WHERE target_vault_id = 2");
        worker.drainOnce();
        verify(backup, never()).backup(anyLong());
        assertThat(count("notes", 2)).isZero();
        repo.purge(2);   // registration gone: no-op, no error
    }
}
