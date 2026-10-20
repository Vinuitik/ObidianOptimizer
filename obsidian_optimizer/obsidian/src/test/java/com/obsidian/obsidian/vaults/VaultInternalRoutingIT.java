package com.obsidian.obsidian.vaults;

import com.obsidian.obsidian.chrono.ChronoService;
import com.obsidian.obsidian.internalapi.InternalAgentController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M5: embedder callbacks (/api/internal/**) carry the job's vault_id and write into THAT
 * vault, not whichever vault is active when the (slow) ingest job finally calls back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class VaultInternalRoutingIT {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName
            .parse("paradedb/paradedb:latest").asCompatibleSubstituteFor("postgres"));

    static final Path V1;
    static final Path V2;
    static {
        try {
            V1 = Files.createTempDirectory("obsidian-route-v1");
            V2 = Files.createTempDirectory("obsidian-route-v2");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static final String TOKEN = "route-test-token";

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry r) {
        r.add("VAULT_PATH",                          V1::toString);
        r.add("mcp.api.token",                       () -> TOKEN);
        r.add("spring.datasource.url",               postgres::getJdbcUrl);
        r.add("spring.datasource.username",          postgres::getUsername);
        r.add("spring.datasource.password",          postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @MockBean ChronoService chronoService;

    @Autowired JdbcTemplate jdbc;
    @Autowired ActiveVault activeVault;
    @Autowired InternalAgentController controller;

    MockMvc mvc;

    @BeforeEach
    void reset() {
        // rootFor(non-active) reads the registry: point vault 1's row at its temp dir (seed says /vault)
        jdbc.update("UPDATE vaults SET container_path = ? WHERE id = 1", V1.toString());
        jdbc.update("""
            INSERT INTO vaults(id, name, container_path, active, is_primary)
            VALUES (2, 'Second', ?, false, false) ON CONFLICT DO NOTHING
            """, V2.toString());
        jdbc.update("UPDATE vaults SET active = false");
        jdbc.update("UPDATE vaults SET active = true WHERE id = 1");
        activeVault.set(1L, V1);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private void create(Long vaultId, String name) throws Exception {
        String vid = vaultId == null ? "" : ",\"vaultId\":" + vaultId;
        mvc.perform(post("/api/internal/notes").header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"folder\":\"_inbox/cap\",\"name\":\"" + name + "\",\"content\":\"# hi\"" + vid + "}"))
            .andExpect(status().isOk());
    }

    private long rows(String path, long vault) {
        return jdbc.queryForObject("SELECT count(*) FROM notes WHERE path = ? AND vault_id = ?",
            Long.class, path, vault);
    }

    @Test
    void callbackAfterSwitch_writesIntoTheJobsOwnVault() throws Exception {
        // job started while vault 1 was active ... user switched to vault 2 ... job calls back
        activeVault.set(2L, V2);
        create(1L, "FromJobOne");

        Path expected = V1.resolve("_inbox/cap/FromJobOne.md");
        assertThat(expected).exists();
        assertThat(V2.resolve("_inbox/cap/FromJobOne.md")).doesNotExist();
        assertThat(rows(expected.toString(), 1)).isEqualTo(1);
        assertThat(rows(expected.toString(), 2)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM sync_queue WHERE vault_id = 1 AND path = ?", Long.class,
            "_inbox/cap/FromJobOne.md")).isEqualTo(1);
    }

    @Test
    void callbackForNonActiveVault2_andPutAndFolderAndMedia() throws Exception {
        // active stays 1; the job belongs to vault 2
        create(2L, "FromJobTwo");
        Path note = V2.resolve("_inbox/cap/FromJobTwo.md");
        assertThat(note).exists();
        assertThat(V1.resolve("_inbox/cap/FromJobTwo.md")).doesNotExist();
        assertThat(rows(note.toString(), 2)).isEqualTo(1);
        assertThat(rows(note.toString(), 1)).isZero();

        mvc.perform(put("/api/internal/notes").header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"_inbox/cap/FromJobTwo.md\",\"content\":\"# rewritten\",\"vaultId\":2}"))
            .andExpect(status().isOk());
        assertThat(Files.readString(note)).isEqualTo("# rewritten");

        mvc.perform(post("/api/internal/folders").header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"newdir/deep\",\"vaultId\":2}"))
            .andExpect(status().isOk());
        assertThat(V2.resolve("newdir/deep")).isDirectory();
        assertThat(V1.resolve("newdir")).doesNotExist();

        mvc.perform(post("/api/internal/media").header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"filename\":\"k.png\",\"dataB64\":\"AAEC\",\"vaultId\":2}"))
            .andExpect(status().isOk());
        assertThat(V2.resolve("resources/images/k.png")).exists();
        assertThat(V1.resolve("resources/images/k.png")).doesNotExist();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM sync_queue WHERE vault_id = 2 AND path = 'resources/images/k.png'",
            Long.class)).isEqualTo(1);
    }

    @Test
    void callbackWithoutVaultId_isVault1_notActive() throws Exception {
        activeVault.set(2L, V2);              // legacy embedder: no vaultId in the body
        create(null, "Legacy");
        assertThat(V1.resolve("_inbox/cap/Legacy.md")).exists();
        assertThat(V2.resolve("_inbox/cap/Legacy.md")).doesNotExist();
    }

    @Test
    void callbackCannotEscapeItsVaultRoot() throws Exception {
        mvc.perform(post("/api/internal/folders").header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"../escape\",\"vaultId\":2}"))
            .andExpect(status().isBadRequest());
    }
}
