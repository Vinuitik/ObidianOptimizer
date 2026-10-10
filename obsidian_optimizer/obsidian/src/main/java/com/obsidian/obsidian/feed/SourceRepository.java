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
 * Raw-JDBC repo for Passive Feed sources (youtube / rss / page_watch) — same convention as
 * {@code TrackRepository}: {@code @PostConstruct initSchema()} with CREATE TABLE IF NOT EXISTS.
 * See feed/FLOWS.md.
 */
@Repository
public class SourceRepository {

    private final JdbcTemplate jdbc;

    public SourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code autoAccept} null = inherit the global {@code feedAutoAccept} setting. */
    public record Source(long id, String kind, String url, String feedUrl, String title,
                         Boolean autoAccept, Long targetTrackId, String status,
                         Instant lastCheckedAt, Instant createdAt, int pendingCount) {}

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS sources (
              id              BIGSERIAL PRIMARY KEY,
              kind            TEXT NOT NULL,
              url             TEXT NOT NULL UNIQUE,
              feed_url        TEXT,
              title           TEXT,
              auto_accept     BOOLEAN,
              target_track_id BIGINT,
              last_checked_at TIMESTAMPTZ,
              created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
              status          TEXT NOT NULL DEFAULT 'active'
            )
            """);
    }

    private static final String SELECT = """
        SELECT s.*, (SELECT COUNT(*) FROM feed_items fi WHERE fi.source_id = s.id AND fi.status = 'pending')
               AS pending_count
        FROM sources s
        """;

    public Source create(String kind, String url, String feedUrl, String title,
                         Boolean autoAccept, Long targetTrackId) {
        long id = jdbc.queryForObject("""
            INSERT INTO sources(kind, url, feed_url, title, auto_accept, target_track_id)
            VALUES (?, ?, ?, ?, ?, ?) RETURNING id
            """, Long.class, kind, url, feedUrl, title, autoAccept, targetTrackId);
        return get(id);
    }

    public Source get(long id) {
        List<Source> rows = jdbc.query(SELECT + " WHERE s.id = ?", SourceRepository::map, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Source getByUrl(String url) {
        List<Source> rows = jdbc.query(SELECT + " WHERE s.url = ?", SourceRepository::map, url);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Source> list() {
        return jdbc.query(SELECT + " ORDER BY s.created_at DESC, s.id DESC", SourceRepository::map);
    }

    public List<Source> listActive() {
        return jdbc.query(SELECT + " WHERE s.status = 'active' ORDER BY s.id", SourceRepository::map);
    }

    /** Partial update — null fields unchanged. {@code clearAutoAccept}/{@code clearTargetTrack}
     *  reset to "inherit" (the PATCH body sent an explicit null). */
    public Source update(long id, Boolean autoAccept, boolean clearAutoAccept,
                         Long targetTrackId, boolean clearTargetTrack, String status, String title) {
        if (clearAutoAccept) jdbc.update("UPDATE sources SET auto_accept = NULL WHERE id = ?", id);
        else if (autoAccept != null) jdbc.update("UPDATE sources SET auto_accept = ? WHERE id = ?", autoAccept, id);
        if (clearTargetTrack) jdbc.update("UPDATE sources SET target_track_id = NULL WHERE id = ?", id);
        else if (targetTrackId != null) jdbc.update("UPDATE sources SET target_track_id = ? WHERE id = ?", targetTrackId, id);
        if (status != null) jdbc.update("UPDATE sources SET status = ? WHERE id = ?", status, id);
        if (title != null) jdbc.update("UPDATE sources SET title = ? WHERE id = ?", title, id);
        return get(id);
    }

    /** Poll cursor — advances even for a valid-but-empty feed. */
    public void markChecked(long id, Instant when) {
        jdbc.update("UPDATE sources SET last_checked_at = ? WHERE id = ?", Timestamp.from(when), id);
    }

    public void updateResolved(long id, String kind, String feedUrl) {
        jdbc.update("UPDATE sources SET kind = ?, feed_url = ? WHERE id = ?", kind, feedUrl, id);
    }

    /** Cascades feed_items; already-ingested notes are untouched. */
    public boolean delete(long id) {
        return jdbc.update("DELETE FROM sources WHERE id = ?", id) > 0;
    }

    private static Source map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp checked = rs.getTimestamp("last_checked_at");
        Boolean auto = (Boolean) rs.getObject("auto_accept");
        return new Source(rs.getLong("id"), rs.getString("kind"), rs.getString("url"),
            rs.getString("feed_url"), rs.getString("title"), auto,
            (Long) rs.getObject("target_track_id"), rs.getString("status"),
            checked == null ? null : checked.toInstant(), rs.getTimestamp("created_at").toInstant(),
            rs.getInt("pending_count"));
    }
}
