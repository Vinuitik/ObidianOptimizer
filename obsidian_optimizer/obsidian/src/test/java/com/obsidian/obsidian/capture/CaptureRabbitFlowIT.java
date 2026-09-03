package com.obsidian.obsidian.capture;

import com.obsidian.obsidian.common.IngestClient;
import com.obsidian.obsidian.common.OutboxRepository;
import com.obsidian.obsidian.common.RabbitQueueConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * End-to-end proof of the capture pilot (QUEUE_UNIFICATION_PLAN.md Phase 4): a real
 * Postgres AND a real RabbitMQ broker, no mocking of the mechanism itself — only
 * IngestClient (the HTTP call to the embedder, genuinely out of scope here) is a mock.
 * The poll-based safety net ({@code CaptureIngestWorker.tick()}) is effectively disabled
 * for the test's duration by its 1h default, so anything reaching 'processing'/'failed'
 * within the test's few-second window can only be explained by the outbox -> RabbitMQ ->
 * @RabbitListener chain (and, for the failure case, the retry-ladder DLX/TTL chain)
 * actually working. Rung TTLs are shrunk via property overrides so the ladder can be
 * proven without a real multi-hour test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class CaptureRabbitFlowIT {

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
            VAULT = Files.createTempDirectory("obsidian-capture-rabbit-vault");
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
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
        // Safety net effectively off — anything landing within the test window must have
        // come through the instant Rabbit path, not this poll.
        r.add("ingest.capture.delay-ms",             () -> "3600000");
        // Shrink the retry ladder's rungs from hours to milliseconds so the dead-letter
        // test can actually observe all 3 rungs expire within a normal test timeout.
        r.add("capture.retry.rung1-ttl-ms",           () -> "300");
        r.add("capture.retry.rung2-ttl-ms",           () -> "300");
        r.add("capture.retry.rung3-ttl-ms",           () -> "300");
    }

    @MockBean IngestClient ingestClient;

    @Autowired CaptureRepository captureRepo;
    @Autowired OutboxRepository outboxRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitAdmin rabbitAdmin;

    /** Inserts the row and publishes the outbox message directly — bypassing
     *  CaptureController (whose enqueue path also calls {@code ingestWorker.nudge()},
     *  which would immediately trigger the poll-based safety net too and muddy which
     *  path actually did the work). This isolates the thing Phase 4 actually added: the
     *  outbox -> RabbitMQ -> @RabbitListener chain. */
    private String enqueueViaOutboxOnly(String ref) {
        String id = java.util.UUID.randomUUID().toString().substring(0, 12);
        captureRepo.enqueue(id, "video", ref, null, ref);
        outboxRepo.enqueue(RabbitQueueConfig.CAPTURE_QUEUE, Map.of("id", id));
        return id;
    }

    @BeforeEach
    void cleanState() {
        reset(ingestClient);
        jdbc.execute("TRUNCATE capture CASCADE");
        jdbc.execute("TRUNCATE pipeline_failures");
        for (String q : List.of(RabbitQueueConfig.CAPTURE_QUEUE, RabbitQueueConfig.CAPTURE_DEADLETTER_QUEUE,
                RabbitQueueConfig.CAPTURE_WAIT_1H_QUEUE, RabbitQueueConfig.CAPTURE_WAIT_6H_QUEUE,
                RabbitQueueConfig.CAPTURE_WAIT_24H_QUEUE)) {
            rabbitAdmin.purgeQueue(q, false);
        }
    }

    private void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        org.junit.jupiter.api.Assertions.fail("condition not met within " + timeoutMs + "ms: " + condition);
    }

    private String statusOf(String captureId) {
        CaptureRepository.Capture c = captureRepo.get(captureId);
        return c == null ? null : c.status();
    }

    @Test
    void capture_submittedViaRabbitListener_notPollFallback() throws Exception {
        when(ingestClient.submitStandalone(anyString(), eq("https://youtu.be/happy"), eq("video")))
            .thenReturn(new IngestClient.Result(true, 200, "job-happy", "{}"));

        String captureId = enqueueViaOutboxOnly("https://youtu.be/happy");

        waitUntil(() -> "processing".equals(statusOf(captureId)), 5000);
        org.mockito.Mockito.verify(ingestClient)
            .submitStandalone(eq(captureId), eq("https://youtu.be/happy"), eq("video"));
    }

    @Test
    void capture_persistentFailure_ridesLadderThenDeadLetters() throws Exception {
        when(ingestClient.submitStandalone(anyString(), eq("https://youtu.be/broken"), eq("video")))
            .thenReturn(IngestClient.Result.unreachable());

        String captureId = enqueueViaOutboxOnly("https://youtu.be/broken");

        // 3 rungs x 300ms TTL each, plus redelivery/processing overhead — comfortably
        // done well inside 15s.
        waitUntil(() -> "failed".equals(statusOf(captureId)), 15000);

        CaptureRepository.Capture c = captureRepo.get(captureId);
        assertThat(c.status()).isEqualTo("failed");
        assertThat(c.lastError()).contains("retry ladder exhausted");

        Map<String, Object> failure = jdbc.queryForMap(
            "SELECT source, stage, input_payload::text AS input_payload, error_message " +
            "FROM pipeline_failures WHERE input_payload->>'captureId' = ?", captureId);
        assertThat(failure.get("source")).isEqualTo("capture");
        assertThat(failure.get("stage")).isEqualTo("ingest_submit");
        assertThat((String) failure.get("error_message")).contains("retry ladder exhausted");
    }
}
