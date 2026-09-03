package com.obsidian.obsidian.common;

import org.springframework.amqp.core.Queue;
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

    @Bean
    public Queue embedQueue() {
        return new Queue(EMBED_QUEUE, true);
    }

    @Bean
    public Queue embedChunkQueue() {
        return new Queue(EMBED_CHUNK_QUEUE, true);
    }
}
