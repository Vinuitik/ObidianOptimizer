package com.obsidian.obsidian.common;

import com.obsidian.obsidian.common.OutboxRepository.OutboxRow;
import com.obsidian.obsidian.common.OutboxRepository.OutboxRowWritten;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.UUID;

/**
 * Delivers outbox rows to RabbitMQ. Two paths, one publish method:
 * <ul>
 *   <li>PRIMARY: {@link #onRowWritten} fires once the writing transaction commits
 *       ({@code phase = AFTER_COMMIT}) — the common case, instant delivery.</li>
 *   <li>FALLBACK: {@link #sweepUnpublished} — a short-interval safety net that only
 *       ever finds real work when the immediate attempt above failed to run at all
 *       (crash between the DB commit and the publish call) or the broker was briefly
 *       unreachable.</li>
 * </ul>
 * Both paths converge on {@link #publish}, so there is exactly one delivery code path
 * to reason about. {@code fallbackExecution = true} means a row written outside any
 * transaction (shouldn't happen given {@link OutboxRepository#enqueue}'s contract, but
 * cheap to guard) still gets an immediate attempt rather than silently waiting for the
 * sweep.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepo;
    private final RabbitTemplate rabbitTemplate;

    public OutboxRelay(OutboxRepository outboxRepo, RabbitTemplate rabbitTemplate) {
        this.outboxRepo = outboxRepo;
        this.rabbitTemplate = rabbitTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRowWritten(OutboxRowWritten event) {
        publish(event.id(), event.queueName(), event.payloadJson());
    }

    @Scheduled(fixedDelayString = "${outbox.relay.delay-ms:5000}",
               initialDelayString = "${outbox.relay.initial-delay-ms:5000}")
    public void sweepUnpublished() {
        List<OutboxRow> rows = outboxRepo.findUnpublished(100);
        for (OutboxRow row : rows) {
            publish(row.id(), row.queueName(), row.payloadJson());
        }
    }

    private void publish(UUID id, String queueName, String payloadJson) {
        try {
            rabbitTemplate.convertAndSend(queueName, payloadJson);
            outboxRepo.markPublished(id);
        } catch (Exception e) {
            // Broker down/unreachable — row stays unpublished, sweepUnpublished retries it.
            log.warn("[OutboxRelay] publish to {} failed for outbox row {}: {}", queueName, id, e.getMessage());
        }
    }
}
