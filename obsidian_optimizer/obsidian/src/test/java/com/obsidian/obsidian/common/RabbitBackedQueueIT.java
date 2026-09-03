package com.obsidian.obsidian.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves RabbitBackedQueue's claim/ack contract against a REAL broker (no fake — this
 * is exactly the behavior a later phase's Group B migration would depend on): a
 * claimed message is invisible to further claims until markDone/markFailed/
 * markDeferred resolves it, matching the claim-then-resolve shape every other
 * WorkQueue implementation in this codebase already has.
 */
@Testcontainers
class RabbitBackedQueueIT {

    @Container
    static final RabbitMQContainer rabbit =
        new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    private final ObjectMapper mapper = new ObjectMapper();
    private CachingConnectionFactory connectionFactory;
    private String queueName;
    private RabbitBackedQueue<Item> queue;
    private RabbitTemplate seedTemplate;

    record Item(String name) {}

    @BeforeEach
    void setUp() {
        connectionFactory = new CachingConnectionFactory(rabbit.getHost(), rabbit.getAmqpPort());
        connectionFactory.setUsername(rabbit.getAdminUsername());
        connectionFactory.setPassword(rabbit.getAdminPassword());

        queueName = "rbq-test-" + UUID.randomUUID();
        new RabbitAdmin(connectionFactory).declareQueue(new Queue(queueName, true));

        queue = new RabbitBackedQueue<>(connectionFactory, queueName, Item.class);
        seedTemplate = new RabbitTemplate(connectionFactory);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    private void seed(String name) {
        try {
            seedTemplate.convertAndSend(queueName, mapper.writeValueAsString(new Item(name)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void claimBatch_returnsEmpty_whenQueueHasNoMessages() {
        assertThat(queue.claimBatch(5)).isEmpty();
    }

    @Test
    void claimBatch_deserializesPublishedMessages_upToLimit() {
        seed("a");
        seed("b");
        seed("c");

        List<Item> batch = queue.claimBatch(2);

        assertThat(batch).extracting(Item::name).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void markDone_acksSoTheMessageNeverComesBack() {
        seed("a");
        Item claimed = queue.claimBatch(1).get(0);

        queue.markDone(claimed);

        assertThat(queue.claimBatch(10)).isEmpty();
    }

    @Test
    void markDeferred_requeuesForImmediateRedelivery() {
        seed("a");
        Item claimed = queue.claimBatch(1).get(0);

        queue.markDeferred(claimed);

        List<Item> redelivered = queue.claimBatch(10);
        assertThat(redelivered).extracting(Item::name).containsExactly("a");
    }

    @Test
    void markFailed_doesNotRequeue_messageIsGoneWithNoDlxConfigured() {
        seed("a");
        Item claimed = queue.claimBatch(1).get(0);

        queue.markFailed(claimed, new RuntimeException("boom"));

        // No DLX bound to this ad hoc test queue (a later phase's job) — nacked
        // without requeue just discards it, which is the correct "gone" behavior today.
        assertThat(queue.claimBatch(10)).isEmpty();
    }

    @Test
    void markingAnItemNeverClaimedFromThisQueue_throws() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> queue.markDone(new Item("ghost"))))
            .isInstanceOf(IllegalStateException.class);
    }
}
