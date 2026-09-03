package com.obsidian.obsidian.ml;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * End-to-end proof of the outbox+RabbitMQ fast path (QUEUE_UNIFICATION_PLAN.md Phase 3):
 * a real Postgres AND a real RabbitMQ broker, no mocking of the mechanism itself — only
 * EmbeddingService (an external HTTP dependency, genuinely out of scope here) is a mock.
 * Both scheduled safety nets are effectively disabled for the test's duration by their
 * new 1h/1h defaults, so a note reaching {@code embedded_hash == content_hash} (or a
 * chunk getting its vector) within the test's few-second window can only be explained
 * by the chokepoint -> outbox -> RabbitMQ -> @RabbitListener chain actually working.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class OutboxEmbedFlowIT {

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
            VAULT = Files.createTempDirectory("obsidian-outbox-vault");
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
        // Testcontainers, so it needs the @RabbitListener consumers actually running.
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
        // Both safety nets slow (see Phase 3): irrelevant to boot time, but explicit
        // here so nobody mistakes this test for exercising the polling path.
        r.add("embedding.scan.delay-ms",             () -> "3600000");
        r.add("embedding.reconcile.delay-ms",        () -> "3600000");
    }

    @MockBean EmbeddingService embeddingService;

    @Autowired ImageScanService imageScanService;
    @Autowired ImageProcessingWorker imageProcessingWorker;
    @Autowired JdbcTemplate jdbc;
    @Autowired NoteChunkRepository chunkRepo;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void cleanState() {
        reset(embeddingService);
        jdbc.execute("TRUNCATE notes, note_chunks, pending_image_jobs CASCADE");
    }

    private void insertNote(String path) {
        jdbc.update("INSERT INTO notes(path, title, modified_at) VALUES (?, ?, ?)",
            path, path.substring(path.lastIndexOf('/') + 1), System.currentTimeMillis());
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
    void registerImages_publishesToRabbit_andListenerEmbedsWithinSeconds() throws Exception {
        String path = "/vault/outbox-note.md";
        insertNote(path);
        when(embeddingService.indexNote(path)).thenReturn(true);

        imageScanService.registerImages(path, "plain note text, no embeds");

        waitUntil(() -> {
            String contentHash = jdbc.queryForObject(
                "SELECT content_hash FROM notes WHERE path = ?", String.class, path);
            String embeddedHash = jdbc.queryForObject(
                "SELECT embedded_hash FROM notes WHERE path = ?", String.class, path);
            return contentHash != null && contentHash.equals(embeddedHash);
        }, 5000);

        org.mockito.Mockito.verify(embeddingService).indexNote(path);
    }

    @Test
    void handleResult_publishesEmbedChunk_andListenerFillsVectorWithinSeconds() throws Exception {
        // First call = handleResult's own inline attempt (embedder "down" so it takes
        // the text-only + outbox branch); every call after that = the embed-chunk
        // consumer's attempt, whenever it actually runs — deterministic on CALL ORDER
        // rather than wall-clock timing, since the consumer's exact fire time is async.
        when(embeddingService.embed(anyString()))
            .thenReturn(null)
            .thenReturn(new float[768]); // note_chunks.embedding is vector(768) — pgvector rejects any other size

        var job = new PendingImageJob("/vault/diagram.md", "fig1.png");
        job.setId("job-outbox-1");
        imageProcessingWorker.handleResult(job, mapper.readTree("{\"text\":\"a diagram caption\"}"), "test-provider");

        // Caption persisted immediately with a NULL vector (embedder "down" above).
        assertThat(chunkRepo.getChunkText("/vault/diagram.md", "image", 0)).isEqualTo("a diagram caption");

        waitUntil(() ->
            jdbc.queryForObject(
                "SELECT embedding IS NOT NULL FROM note_chunks WHERE note_path = ? AND source = 'image' AND chunk_index = 0",
                Boolean.class, "/vault/diagram.md"),
            5000);
    }
}
