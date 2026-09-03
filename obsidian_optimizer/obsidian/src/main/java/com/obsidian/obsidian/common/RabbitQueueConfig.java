package com.obsidian.obsidian.common;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Declares the durable queues Group A/B's outbox producers and {@code @RabbitListener}
 * consumers agree on by name. Spring Boot's auto-configured RabbitAdmin declares these
 * on startup — without it, publishing to a queue name nothing has declared yet just
 * silently drops the message (default-exchange routing to a non-existent queue).
 *
 * <p>Group B pilot (QUEUE_UNIFICATION_PLAN.md Phase 4): {@code capture} is the live
 * queue; {@code capture.wait.*} are backoff rungs (no consumer ever attaches — a
 * message just sits until its TTL expires, then the broker dead-letters it back onto
 * {@code capture} via the default exchange); {@code capture.deadletter} is the final
 * graveyard once the ladder is exhausted (see CaptureIngestWorker).
 *
 * <p>Phase 6: {@code sync-upload} is a "parking lot" pair, not a plain queue: the main
 * queue's dead-letter-exchange points at the wait queue; the wait queue's TTL expiry
 * dead-letters back to the main queue. A message that fails ends up back at the front
 * of {@code sync-upload} after retryDelayMs, up to sync.upload.max-retries times
 * (counted via sync_queue.retry_count, not RabbitMQ's x-death header — see
 * SyncService.onSyncUploadMessage). No separate DLX ladder step per attempt (unlike
 * capture's Phase 4 sketch of 1h/6h/24h) — Drive's transient errors clear in seconds,
 * not hours, and DriveService.withRetry already exhausts its own exponential backoff
 * before a message ever lands here.
 */
@Configuration
public class RabbitQueueConfig {

    public static final String EMBED_QUEUE = "embed";
    public static final String EMBED_CHUNK_QUEUE = "embed-chunk";

    public static final String SYNC_UPLOAD_QUEUE = "sync-upload";
    public static final String SYNC_UPLOAD_WAIT_QUEUE = "sync-upload-wait";

    public static final String CAPTURE_QUEUE = "capture";
    public static final String CAPTURE_DEADLETTER_QUEUE = "capture.deadletter";
    public static final String CAPTURE_WAIT_1H_QUEUE = "capture.wait.1h";
    public static final String CAPTURE_WAIT_6H_QUEUE = "capture.wait.6h";
    public static final String CAPTURE_WAIT_24H_QUEUE = "capture.wait.24h";
    /** Ladder order — index 0 is rung 1 (first retry after the initial attempt). */
    public static final String[] CAPTURE_RUNG_QUEUES = {
        CAPTURE_WAIT_1H_QUEUE, CAPTURE_WAIT_6H_QUEUE, CAPTURE_WAIT_24H_QUEUE
    };

    private final long syncUploadRetryDelayMs;

    // Rung durations: broker config, not per-message (see plan doc — deliberately not
    // meant to be tunable per capture). Overridable only so an *IT can shrink them to
    // prove the ladder without a real multi-hour test.
    @Value("${capture.retry.rung1-ttl-ms:3600000}")    // 1h
    private long rung1TtlMs;
    @Value("${capture.retry.rung2-ttl-ms:21600000}")   // 6h
    private long rung2TtlMs;
    @Value("${capture.retry.rung3-ttl-ms:86400000}")   // 24h
    private long rung3TtlMs;

    public RabbitQueueConfig(
            @Value("${sync.upload.retry-delay-ms:60000}") long syncUploadRetryDelayMs) {
        this.syncUploadRetryDelayMs = syncUploadRetryDelayMs;
    }

    @Bean
    public Queue embedQueue() {
        return new Queue(EMBED_QUEUE, true);
    }

    @Bean
    public Queue embedChunkQueue() {
        return new Queue(EMBED_CHUNK_QUEUE, true);
    }

    @Bean
    public Queue syncUploadQueue() {
        return QueueBuilder.durable(SYNC_UPLOAD_QUEUE)
            .withArgument("x-dead-letter-exchange", "")
            .withArgument("x-dead-letter-routing-key", SYNC_UPLOAD_WAIT_QUEUE)
            .build();
    }

    @Bean
    public Queue syncUploadWaitQueue() {
        return QueueBuilder.durable(SYNC_UPLOAD_WAIT_QUEUE)
            .withArgument("x-message-ttl", syncUploadRetryDelayMs)
            .withArgument("x-dead-letter-exchange", "")
            .withArgument("x-dead-letter-routing-key", SYNC_UPLOAD_QUEUE)
            .build();
    }

    @Bean
    public Queue captureQueue() {
        return new Queue(CAPTURE_QUEUE, true);
    }

    @Bean
    public Queue captureDeadletterQueue() {
        return new Queue(CAPTURE_DEADLETTER_QUEUE, true);
    }

    @Bean
    public Queue captureWait1hQueue() {
        return waitQueue(CAPTURE_WAIT_1H_QUEUE, rung1TtlMs);
    }

    @Bean
    public Queue captureWait6hQueue() {
        return waitQueue(CAPTURE_WAIT_6H_QUEUE, rung2TtlMs);
    }

    @Bean
    public Queue captureWait24hQueue() {
        return waitQueue(CAPTURE_WAIT_24H_QUEUE, rung3TtlMs);
    }

    /** A backoff rung: {@code x-message-ttl} holds the message for exactly this long,
     *  then {@code x-dead-letter-exchange=""} + {@code x-dead-letter-routing-key} routes
     *  it back onto {@code capture} via the default exchange (routing key = queue name).
     *  This IS the retry ladder — no Java-side timer or scheduler involved. */
    private Queue waitQueue(String name, long ttlMs) {
        return new Queue(name, true, false, false, Map.of(
            "x-message-ttl", ttlMs,
            "x-dead-letter-exchange", "",
            "x-dead-letter-routing-key", CAPTURE_QUEUE
        ));
    }
}
