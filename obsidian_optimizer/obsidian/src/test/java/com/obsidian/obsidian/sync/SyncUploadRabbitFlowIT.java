package com.obsidian.obsidian.sync;

import com.obsidian.obsidian.common.ContentHashing;
import com.obsidian.obsidian.sync.SyncQueueRepository.SyncEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * End-to-end proof of the sync_queue -> outbox -> RabbitMQ -> upload fast path
 * (QUEUE_UNIFICATION_PLAN.md Phase 6): a real Postgres AND a real RabbitMQ broker —
 * only DriveService (the external Google Drive dependency) is mocked. Exercises both
 * the happy path (uploaded within seconds of markPending, no polling involved) and the
 * retry ladder (repeated Drive failures ride the "sync-upload" -> "sync-upload-wait"
 * DLX+TTL loop up to sync.upload.max-retries, then land on FAILED exactly like the
 * pre-Rabbit polling model did).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class SyncUploadRabbitFlowIT {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(DockerImageName.parse("paradedb/paradedb:latest")
            .asCompatibleSubstituteFor("postgres"));

    @Container
    static final RabbitMQContainer rabbit =
        new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    static final Path VAULT;
    static {
        try {
            VAULT = Files.createTempDirectory("obsidian-sync-upload-vault");
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
        r.add("spring.rabbitmq.host",                rabbit::getHost);
        r.add("spring.rabbitmq.port",                rabbit::getAmqpPort);
        r.add("spring.rabbitmq.username",             rabbit::getAdminUsername);
        r.add("spring.rabbitmq.password",             rabbit::getAdminPassword);
        // Off by default (see application.properties) — this IT has a real broker via
        // Testcontainers, so the "sync-upload" @RabbitListener needs to actually run.
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
        r.add("sync.passphrase", () -> "it-test-passphrase");
        // Fast ladder for the test's sake — production default is 60s.
        r.add("sync.upload.retry-delay-ms", () -> "300");
        r.add("sync.upload.max-retries", () -> "2");
        // Irrelevant to this flow, but explicit so nobody mistakes this test for
        // exercising the 6h polling safety net.
        r.add("sync.upload.cron", () -> "0 0 */6 * * *");
    }

    @MockBean DriveService driveService;

    @Autowired SyncQueueRepository syncQueueRepo;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        reset(driveService);
        when(driveService.isConfigured()).thenReturn(true);
        jdbc.execute("TRUNCATE sync_queue");
        jdbc.execute("TRUNCATE outbox_events");
    }

    private Path writeVaultFile(String rel, String content) throws IOException {
        Path f = VAULT.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
        return f;
    }

    private void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        org.junit.jupiter.api.Assertions.fail("condition not met within " + timeoutMs + "ms: " + condition);
    }

    @Test
    void markPending_publishesToRabbit_andListenerUploadsWithinSeconds() throws Exception {
        String content = "# hello from the sync-upload fast path";
        String hash = ContentHashing.sha256(content.getBytes(StandardCharsets.UTF_8));
        writeVaultFile("phase6/note.md", content);
        when(driveService.uploadFile(anyString(), any(), anyString(), anyString(), any()))
            .thenReturn("drive-id-phase6");

        syncQueueRepo.markPending("phase6/note.md", hash);

        waitUntil(() -> {
            SyncEntry e = syncQueueRepo.findByPath("phase6/note.md");
            return e != null && "DONE".equals(e.status());
        }, 5000);

        SyncEntry done = syncQueueRepo.findByPath("phase6/note.md");
        assertThat(done.driveFileId()).isEqualTo("drive-id-phase6");
        org.mockito.Mockito.verify(driveService).uploadFile(
            eq("phase6/note.md"), any(), eq(hash), anyString(), any());
    }

    @Test
    void repeatedDriveFailure_ridesTheLadder_thenDeadLettersAsFailed() throws Exception {
        String content = "# this upload always fails";
        String hash = ContentHashing.sha256(content.getBytes(StandardCharsets.UTF_8));
        writeVaultFile("phase6/broken.md", content);
        when(driveService.uploadFile(anyString(), any(), anyString(), anyString(), any()))
            .thenThrow(new IOException("simulated persistent Drive failure"));

        syncQueueRepo.markPending("phase6/broken.md", hash);

        // sync.upload.max-retries=2 in this test: two failed attempts, ~300ms apart on
        // the retry ladder, then dead-lettered — exactly like the pre-Rabbit polling
        // model's retry_count cap, just delivered by the DLX+TTL wait queue instead of
        // a slow poll tick.
        waitUntil(() -> {
            SyncEntry e = syncQueueRepo.findByPath("phase6/broken.md");
            return e != null && "FAILED".equals(e.status()) && e.retryCount() >= 2;
        }, 8000);

        SyncEntry failed = syncQueueRepo.findByPath("phase6/broken.md");
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.retryCount()).isEqualTo(2);

        // No further redelivery past the cap — retry_count stays put after a settle window.
        Thread.sleep(1000);
        assertThat(syncQueueRepo.findByPath("phase6/broken.md").retryCount()).isEqualTo(2);
    }
}
