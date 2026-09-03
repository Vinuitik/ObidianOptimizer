package com.obsidian.obsidian.ml;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the "image-caption" queue topology (see QUEUE_UNIFICATION_PLAN.md Phase 5)
 * and its short-backoff retry ladder — the ONE thing {@code pending_image_jobs} needs
 * that Group A's plain queues (`RabbitQueueConfig`) don't: a transient captioning
 * failure must come back for another try WITHOUT tight-looping (a plain nack-requeue
 * would redeliver instantly, hammering a rate-limited/exhausted vision provider).
 *
 * <p>Classic RabbitMQ "parking lot" retry: a failed message is rejected (NOT requeued
 * into {@code image-caption} itself) so it dead-letters into {@code image-caption-wait-
 * exchange} → {@code image-caption.wait}, sits for {@code image.caption.retry.wait-ms}
 * (TTL), then dead-letters AGAIN — this time via the default exchange with routing key
 * {@code image-caption} — landing back in the main queue. No consumer ever reads
 * {@code image-caption.wait} directly; it exists purely as a timed parking spot.
 *
 * <p>Deliberately NOT a hard dead-letter queue: this table's existing self-heal shape
 * (unbounded retry; a distinct "not_found" goes to {@code SKIPPED} and self-heals via
 * the daily {@code requeueSkipped} sweep, not a Rabbit DLQ) is preserved as-is — see
 * {@link ImageProcessingWorker#onImageCaptionMessage}.
 */
@Configuration
public class ImageCaptionQueueConfig {

    public static final String IMAGE_CAPTION_QUEUE = "image-caption";
    private static final String WAIT_EXCHANGE = "image-caption-wait-exchange";
    private static final String WAIT_QUEUE = "image-caption.wait";

    // 5 minutes: "modest TTL", not a multi-rung ladder — matches the plan's ask that
    // this queue not gain capture's harsher capped-retry shape.
    @Value("${image.caption.retry.wait-ms:300000}")
    private long waitMs;

    @Bean
    public Queue imageCaptionQueue() {
        return QueueBuilder.durable(IMAGE_CAPTION_QUEUE)
            .withArgument("x-dead-letter-exchange", WAIT_EXCHANGE)
            .withArgument("x-dead-letter-routing-key", IMAGE_CAPTION_QUEUE)
            .build();
    }

    @Bean
    public DirectExchange imageCaptionWaitExchange() {
        return new DirectExchange(WAIT_EXCHANGE, true, false);
    }

    @Bean
    public Queue imageCaptionWaitQueue() {
        return QueueBuilder.durable(WAIT_QUEUE)
            .withArgument("x-message-ttl", waitMs)
            .withArgument("x-dead-letter-exchange", "") // default exchange
            .withArgument("x-dead-letter-routing-key", IMAGE_CAPTION_QUEUE) // routes straight back into the main queue
            .build();
    }

    @Bean
    public Binding imageCaptionWaitBinding(Queue imageCaptionWaitQueue, DirectExchange imageCaptionWaitExchange) {
        return BindingBuilder.bind(imageCaptionWaitQueue).to(imageCaptionWaitExchange).with(IMAGE_CAPTION_QUEUE);
    }
}
