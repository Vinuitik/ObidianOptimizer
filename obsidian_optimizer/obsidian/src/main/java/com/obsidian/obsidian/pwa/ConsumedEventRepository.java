package com.obsidian.obsidian.pwa;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Idempotency ledger for mailbox replay. The phone stamps every write-back event with a
 * uuid; re-consuming a mailbox file that wasn't deleted last pass (transient failure) is
 * then a no-op instead of a double-grade. See DRIVE_OFFLINE_SYNC_ARCH §7.
 */
@Repository
public class ConsumedEventRepository {

    private final JdbcTemplate jdbc;

    public ConsumedEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS consumed_events (
                event_id     TEXT PRIMARY KEY,
                consumed_at  BIGINT NOT NULL
            )
            """);
    }

    public boolean alreadyConsumed(String eventId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM consumed_events WHERE event_id = ?", Integer.class, eventId);
        return n != null && n > 0;
    }

    public void markConsumed(String eventId) {
        jdbc.update("""
            INSERT INTO consumed_events(event_id, consumed_at) VALUES (?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """, eventId, System.currentTimeMillis());
    }
}
