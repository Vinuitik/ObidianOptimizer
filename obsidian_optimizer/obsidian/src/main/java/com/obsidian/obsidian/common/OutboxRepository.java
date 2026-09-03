package com.obsidian.obsidian.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The transactional-outbox table (see QUEUE_UNIFICATION_PLAN.md "Group A revised").
 * A write that changes work-triggering state (e.g. a note's content_hash) also calls
 * {@link #enqueue} in the SAME transaction — so the fact "a message needs to go out"
 * can never be lost independently of the DB write it describes.
 *
 * <p>{@link #enqueue} both inserts the row (participates in the caller's ambient
 * transaction, like every other JdbcTemplate call in this codebase) and publishes an
 * {@link OutboxRowWritten} event; {@link OutboxRelay} listens for that event with
 * {@code phase = AFTER_COMMIT} to attempt an immediate publish only once the write is
 * durable, and separately sweeps unpublished rows on a timer as the fallback path.
 */
@Component
public class OutboxRepository {

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper = new ObjectMapper();

    public OutboxRepository(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS outbox_events (
                id           UUID PRIMARY KEY,
                queue_name   TEXT NOT NULL,
                payload      JSONB NOT NULL,
                created_at   TIMESTAMP NOT NULL DEFAULT now(),
                published_at TIMESTAMP
            )
            """);
        jdbc.execute(
            "CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox_events(created_at) WHERE published_at IS NULL");
    }

    public record OutboxRow(UUID id, String queueName, String payloadJson) {}

    /** Fired after {@link #enqueue} inserts a row; {@link OutboxRelay} reacts to it
     *  AFTER the enclosing transaction commits. */
    public record OutboxRowWritten(UUID id, String queueName, String payloadJson) {}

    /** Writes one outbox row and requests an immediate publish attempt once the
     *  caller's transaction commits. The caller MUST be running inside a Spring
     *  transaction (e.g. {@code @Transactional}) for the immediate-publish path to
     *  fire — {@link OutboxRelay}'s scheduled sweep still picks the row up otherwise,
     *  just on its slower cadence rather than instantly. */
    public UUID enqueue(String queueName, Map<String, Object> payload) {
        UUID id = UUID.randomUUID();
        String json = toJson(payload);
        jdbc.update("INSERT INTO outbox_events(id, queue_name, payload) VALUES (?, ?, ?::jsonb)",
            id, queueName, json);
        events.publishEvent(new OutboxRowWritten(id, queueName, json));
        return id;
    }

    public List<OutboxRow> findUnpublished(int limit) {
        return jdbc.query("""
            SELECT id, queue_name, payload::text AS payload
            FROM outbox_events
            WHERE published_at IS NULL
            ORDER BY created_at
            LIMIT ?
            """, ROW_MAPPER, limit);
    }

    public void markPublished(UUID id) {
        jdbc.update("UPDATE outbox_events SET published_at = now() WHERE id = ? AND published_at IS NULL", id);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("outbox payload not serializable: " + e.getMessage(), e);
        }
    }

    private static final RowMapper<OutboxRow> ROW_MAPPER = new RowMapper<>() {
        @Override
        public OutboxRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new OutboxRow(
                UUID.fromString(rs.getString("id")),
                rs.getString("queue_name"),
                rs.getString("payload"));
        }
    };
}
