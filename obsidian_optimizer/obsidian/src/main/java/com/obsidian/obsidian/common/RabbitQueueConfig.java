package com.obsidian.obsidian.common;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the durable queues Group A's outbox producers and {@code @RabbitListener}
 * consumers agree on by name. Spring Boot's auto-configured RabbitAdmin declares these
 * on startup — without it, publishing to a queue name nothing has declared yet just
 * silently drops the message (default-exchange routing to a non-existent queue).
 */
@Configuration
public class RabbitQueueConfig {

    public static final String EMBED_QUEUE = "embed";
    public static final String EMBED_CHUNK_QUEUE = "embed-chunk";

    // sync_queue (Phase 6) — a "parking lot" pair, not a plain queue: the main queue's
    // dead-letter-exchange points at the wait queue; the wait queue's TTL expiry
    // dead-letters back to the main queue. A message that fails ends up back at the
    // front of "sync-upload" after retryDelayMs, up to sync.upload.max-retries times
    // (counted via sync_queue.retry_count, not RabbitMQ's x-death header — see
    // SyncService.onSyncUploadMessage). No separate DLX ladder step per attempt
    // (unlike the plan's Phase 4/5 sketch of 1h/6h/24h) — Drive's transient errors
    // clear in seconds, not hours, and DriveService.withRetry already exhausts its
    // own exponential backoff before a message ever lands here.
    public static final String SYNC_UPLOAD_QUEUE = "sync-upload";
    public static final String SYNC_UPLOAD_WAIT_QUEUE = "sync-upload-wait";

    private final long syncUploadRetryDelayMs;

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
}
