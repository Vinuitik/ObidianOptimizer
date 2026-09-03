package com.obsidian.obsidian.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link WorkQueue} backed by a RabbitMQ queue instead of a DB predicate — see
 * QUEUE_UNIFICATION_PLAN.md Phase 3/4. Built alongside the existing anonymous
 * {@code WorkQueue} implementations (Group A) to prove the interface actually
 * decouples backend choice: {@link PollingQueueWorker} drives this exactly like it
 * drives a DB-backed one, with no changes to that class.
 *
 * <p>Uses manual ack ({@code basicGet}/{@code basicAck}/{@code basicNack}) rather than
 * a push {@code @RabbitListener} so the poll-shaped {@code claimBatch} contract holds:
 * a claimed-but-unacked message is invisible to other consumers until this queue's
 * mark* method resolves it, same as a DB row's claim step. {@code markFailed} nacks
 * without requeue (dead-letters it, once a later phase configures a DLX on this queue);
 * {@code markDeferred} nacks WITH requeue — immediately visible again, no backoff yet
 * (that's {@link RetryPolicy}'s job in a later phase).
 *
 * <p>Items are opaque JSON payloads deserialized via Jackson; each claimed instance is
 * tracked by identity (not equality) in an {@link IdentityHashMap} so two
 * coincidentally-equal payloads in the same batch don't collide when acking. One
 * channel is opened lazily and reused across calls (single-consumer, like every other
 * {@code WorkQueue} today drains on its own lane) and is transparently reopened if it's
 * found closed (broker restart, network blip).
 */
public final class RabbitBackedQueue<T> implements WorkQueue<T> {

    private final ConnectionFactory connectionFactory;
    private final String queueName;
    private final Class<T> itemType;
    private final ObjectMapper mapper = new ObjectMapper();

    private record Delivery(Channel channel, long deliveryTag) {}
    private final Map<T, Delivery> inFlight = new IdentityHashMap<>();

    private volatile Channel channel;

    public RabbitBackedQueue(ConnectionFactory connectionFactory, String queueName, Class<T> itemType) {
        this.connectionFactory = connectionFactory;
        this.queueName = queueName;
        this.itemType = itemType;
    }

    @Override
    public synchronized List<T> claimBatch(int limit) {
        List<T> out = new ArrayList<>();
        Channel ch = channel();
        try {
            for (int i = 0; i < limit; i++) {
                GetResponse response = ch.basicGet(queueName, false); // manual ack
                if (response == null) break; // queue empty
                T item = mapper.readValue(response.getBody(), itemType);
                inFlight.put(item, new Delivery(ch, response.getEnvelope().getDeliveryTag()));
                out.add(item);
            }
        } catch (IOException e) {
            throw new IllegalStateException("RabbitBackedQueue(" + queueName + ") claimBatch failed", e);
        }
        return out;
    }

    @Override
    public void markDone(T item) {
        withDelivery(item, (ch, tag) -> ch.basicAck(tag, false));
    }

    @Override
    public void markFailed(T item, Exception error) {
        withDelivery(item, (ch, tag) -> ch.basicNack(tag, false, false)); // no requeue → DLX (later phase)
    }

    @Override
    public void markDeferred(T item) {
        withDelivery(item, (ch, tag) -> ch.basicNack(tag, false, true)); // requeue → visible again
    }

    @FunctionalInterface
    private interface AckOp {
        void apply(Channel channel, long deliveryTag) throws IOException;
    }

    private void withDelivery(T item, AckOp op) {
        Delivery d = inFlight.remove(item);
        if (d == null) {
            throw new IllegalStateException("RabbitBackedQueue(" + queueName + "): item was not claimed from this queue");
        }
        try {
            op.apply(d.channel(), d.deliveryTag());
        } catch (IOException e) {
            throw new IllegalStateException("RabbitBackedQueue(" + queueName + ") ack/nack failed", e);
        }
    }

    private synchronized Channel channel() {
        if (channel != null && channel.isOpen()) {
            return channel;
        }
        try {
            channel = connectionFactory.createConnection().createChannel(false);
            channel.queueDeclare(queueName, true, false, false, null); // idempotent, matches RabbitQueueConfig
            return channel;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("RabbitBackedQueue(" + queueName + ") could not open channel", e);
        }
    }
}
