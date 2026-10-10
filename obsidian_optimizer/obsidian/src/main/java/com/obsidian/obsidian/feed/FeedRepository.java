package com.obsidian.obsidian.feed;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Raw-JDBC repo for {@code feed_items}. {@code item_url} is UNIQUE (after normalization), so
 * a binned/accepted item is never re-shown by a later poll. Takes {@link SourceRepository} only
 * to guarantee the {@code sources} table exists first (FK). See feed/FLOWS.md.
 */
@Repository
public class FeedRepository {

    private final JdbcTemplate jdbc;

    public FeedRepository(JdbcTemplate jdbc, SourceRepository sources) {
        this.jdbc = jdbc;
    }

    public record FeedItem(long id, long sourceId, String sourceTitle, String itemUrl, String title,
                           String thumbUrl, Integer durationS, Instant publishedAt, String status,
                           Instant decidedAt, String captureId) {}

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS feed_items (
              id           BIGSERIAL PRIMARY KEY,
              source_id    BIGINT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
              item_url     TEXT NOT NULL UNIQUE,
              title        TEXT,
              thumb_url    TEXT,
              duration_s   INTEGER,
              published_at TIMESTAMPTZ,
              status       TEXT NOT NULL DEFAULT 'pending',
              decided_at   TIMESTAMPTZ,
              capture_id   TEXT,
              created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_feed_items_status ON feed_items(status, published_at DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_feed_items_source ON feed_items(source_id)");
    }

    private static final String SELECT = """
        SELECT fi.*, s.title AS source_title FROM feed_items fi JOIN sources s ON s.id = fi.source_id
        """;

    /** Insert a pending item; returns its id, or null if {@code item_url} already exists
     *  (dedup — whatever its status). */
    public Long insertIfNew(long sourceId, String itemUrl, String title, String thumbUrl,
                            Integer durationS, Instant publishedAt) {
        List<Long> ids = jdbc.queryForList("""
            INSERT INTO feed_items(source_id, item_url, title, thumb_url, duration_s, published_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (item_url) DO NOTHING RETURNING id
            """, Long.class, sourceId, itemUrl, title, thumbUrl, durationS,
            publishedAt == null ? null : Timestamp.from(publishedAt));
        return ids.isEmpty() ? null : ids.get(0);
    }

    public FeedItem get(long id) {
        List<FeedItem> rows = jdbc.query(SELECT + " WHERE fi.id = ?", FeedRepository::map, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Newest published first (unknown dates last, then newest inserted). */
    public List<FeedItem> list(String status, int limit) {
        return jdbc.query(SELECT + """
             WHERE fi.status = ? ORDER BY fi.published_at DESC NULLS LAST, fi.id DESC LIMIT ?
            """, FeedRepository::map, status, limit);
    }

    /** Atomic pending/binned → accepted claim. Returns false if already accepted (caller then
     *  reads the existing capture_id — this is what makes accept idempotent under concurrency). */
    public boolean claimAccepted(long id, String captureId) {
        return jdbc.update("""
            UPDATE feed_items SET status = 'accepted', decided_at = now(), capture_id = ?
            WHERE id = ? AND status <> 'accepted'
            """, captureId, id) > 0;
    }

    /** Idempotent; an accepted item is left alone (undo goes through restore). */
    public void bin(long id) {
        jdbc.update("""
            UPDATE feed_items SET status = 'binned', decided_at = now()
            WHERE id = ? AND status = 'pending'
            """, id);
    }

    /** → pending, clearing the decision and capture link. */
    public void markPending(long id) {
        jdbc.update("UPDATE feed_items SET status = 'pending', decided_at = NULL, capture_id = NULL WHERE id = ?", id);
    }

    private static FeedItem map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp pub = rs.getTimestamp("published_at");
        Timestamp dec = rs.getTimestamp("decided_at");
        return new FeedItem(rs.getLong("id"), rs.getLong("source_id"), rs.getString("source_title"),
            rs.getString("item_url"), rs.getString("title"), rs.getString("thumb_url"),
            (Integer) rs.getObject("duration_s"), pub == null ? null : pub.toInstant(),
            rs.getString("status"), dec == null ? null : dec.toInstant(), rs.getString("capture_id"));
    }
}
