package com.obsidian.obsidian.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end proof of the image-caption outbox+RabbitMQ fast path
 * (QUEUE_UNIFICATION_PLAN.md Phase 5): a real Postgres AND a real RabbitMQ broker, no
 * mocking of the mechanism itself. A tiny JDK HttpServer stands in for the
 * host-wrapper's {@code /process-image} endpoint (same technique as
 * EmbeddingServiceBatchTest) so each test can script its response — success,
 * transient failure, or not-found — without touching a real VLM provider.
 *
 * <p>The poll fallback's cadence is pushed out to 1h (via {@code image.scan.delay-ms}),
 * so a job reaching DONE/SKIPPED within the test's few-second window can only be
 * explained by the chokepoint → outbox → RabbitMQ → {@code @RabbitListener} chain, not
 * the polling safety net.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ImageCaptionFlowIT {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(DockerImageName.parse("paradedb/paradedb:latest")
            .asCompatibleSubstituteFor("postgres"));

    @Container
    static final RabbitMQContainer rabbit =
        new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    private record WrapperResponse(int status, String body) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile Function<String, WrapperResponse> handler =
        path -> new WrapperResponse(200, "{\"text\":\"unset\"}");
    private static final HttpServer WRAPPER;
    static {
        try {
            WRAPPER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            WRAPPER.createContext("/process-image", exchange -> {
                JsonNode req = MAPPER.readTree(exchange.getRequestBody());
                WrapperResponse resp = handler.apply(req.path("image_path").asText());
                byte[] body = resp.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(resp.status(), body.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
            });
            WRAPPER.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static final Path VAULT;
    static {
        try {
            VAULT = Files.createTempDirectory("obsidian-image-caption-vault");
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
        r.add("spring.rabbitmq.username",            rabbit::getAdminUsername);
        r.add("spring.rabbitmq.password",            rabbit::getAdminPassword);
        // Off by default (application.properties) — this IT has a real broker.
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
        r.add("wrapper.url", () -> "http://127.0.0.1:" + WRAPPER.getAddress().getPort());
        // Safety net pushed out — only the fast path can explain a result landing
        // inside this test's window.
        r.add("image.scan.delay-ms",         () -> "3600000");
        r.add("image.scan.initial-delay-ms", () -> "3600000");
        // Short retry-wait so the backoff test doesn't take 5 real minutes.
        r.add("image.caption.retry.wait-ms", () -> "2000");
    }

    @Autowired ImageScanService imageScanService;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        handler = path -> new WrapperResponse(200, "{\"text\":\"unset\"}");
        jdbc.execute("TRUNCATE notes, note_chunks, pending_image_jobs CASCADE");
    }

    @AfterEach
    void resetHandler() {
        handler = path -> new WrapperResponse(200, "{\"text\":\"unset\"}");
    }

    private void insertNote(String path) {
        jdbc.update("INSERT INTO notes(path, title, modified_at) VALUES (?, ?, ?)",
            path, path.substring(path.lastIndexOf('/') + 1), System.currentTimeMillis());
    }

    private String statusOf(String notePath, String imagePath) {
        return jdbc.queryForObject(
            "SELECT status FROM pending_image_jobs WHERE note_path = ? AND image_path = ?",
            String.class, notePath, imagePath);
    }

    private void waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail("condition not met within " + timeoutMs + "ms");
    }

    @Test
    void registerImages_publishesToRabbit_andListenerCaptionsWithinSeconds() throws Exception {
        String notePath = "/vault/caption-note.md";
        insertNote(notePath);
        handler = path -> new WrapperResponse(200, "{\"text\":\"a real caption\",\"provider\":\"test\"}");

        imageScanService.registerImages(notePath, "![[fig1.png]]");

        waitUntil(() -> "DONE".equals(statusOf(notePath, "fig1.png")), 5000);

        String chunkText = jdbc.queryForObject(
            "SELECT text FROM note_chunks WHERE note_path = ? AND source = 'image'",
            String.class, notePath);
        assertThat(chunkText).isEqualTo("a real caption");
    }

    @Test
    void transientFailure_retriesAfterBackoff_andEventuallySucceeds() throws Exception {
        String notePath = "/vault/retry-note.md";
        insertNote(notePath);
        AtomicInteger calls = new AtomicInteger();
        handler = path -> calls.getAndIncrement() == 0
            ? new WrapperResponse(503, "{\"error\":\"exhausted\"}")
            : new WrapperResponse(200, "{\"text\":\"caption after retry\",\"provider\":\"test\"}");

        imageScanService.registerImages(notePath, "![[fig2.png]]");

        // First delivery gets a 503 -> rejected -> parked in image-caption.wait for
        // image.caption.retry.wait-ms (2s here) -> dead-letters back into
        // image-caption -> redelivered -> succeeds. Never dropped: status is PENDING
        // throughout the wait, then DONE — proves it comes back, not lost.
        waitUntil(() -> "DONE".equals(statusOf(notePath, "fig2.png")), 8000);
        assertThat(calls.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void notFound_marksSkipped_viaListener_sameAsPollFallback() throws Exception {
        String notePath = "/vault/skip-note.md";
        insertNote(notePath);
        handler = path -> new WrapperResponse(404, "{\"error\":\"not_found: " + path + "\"}");

        imageScanService.registerImages(notePath, "![[gone.png]]");

        // The listener normalizes the wrapper's "not_found: <path>" 404 body into the
        // same {"error":"not_found"} shape handleResult already checks for — proves
        // the existing SKIPPED self-heal semantics (not a hard dead-letter) survive
        // the move to the fast path.
        waitUntil(() -> "SKIPPED".equals(statusOf(notePath, "gone.png")), 5000);
    }
}
