package com.obsidian.obsidian.capture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class CaptureRepositoryIT {

    @org.springframework.beans.factory.annotation.Autowired com.obsidian.obsidian.vaults.ActiveVault activeVault;

    @org.junit.jupiter.api.BeforeEach
    void pinActiveVaultToTempRoot() { activeVault.set(1L, VAULT); }

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(org.testcontainers.utility.DockerImageName
            .parse("paradedb/paradedb:latest").asCompatibleSubstituteFor("postgres"));

    static final Path VAULT;
    static {
        try {
            VAULT = Files.createTempDirectory("obsidian-capture-vault");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry r) {
        r.add("VAULT_PATH",                          VAULT::toString);
        r.add("spring.datasource.url",               postgres::getJdbcUrl);
        r.add("spring.datasource.username",          postgres::getUsername);
        r.add("spring.datasource.password",          postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired CaptureRepository repo;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanTable() {
        jdbc.execute("TRUNCATE capture");
    }

    @Test
    void existsForSource_falseForFreshRef_trueOnceAnyRowExists() {
        String ref = "https://example.com/" + UUID.randomUUID();

        assertThat(repo.existsForSource(1L, ref)).isFalse();

        repo.enqueue(1L, UUID.randomUUID().toString(), "web_dom", ref, null, "title");

        assertThat(repo.existsForSource(1L, ref)).isTrue();
    }

    @Test
    void existsForSource_staysTrue_afterStatusMovesPastLive_unlikeExistsLiveForSource() {
        String ref = "https://example.com/" + UUID.randomUUID();
        String id = UUID.randomUUID().toString();
        repo.enqueue(1L, id, "web_dom", ref, null, "title");

        jdbc.update("UPDATE capture SET status = 'filed' WHERE id = ?", id);

        assertThat(repo.existsLiveForSource(1L, ref)).isFalse();
        assertThat(repo.existsForSource(1L, ref)).isTrue();
    }
}
