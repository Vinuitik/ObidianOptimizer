package com.obsidian.obsidian.tracks;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Raw-JDBC repo for Learning Tracks — same convention as {@code CardRepository}/
 * {@code CaptureRepository}: {@code @PostConstruct initSchema()} with
 * {@code CREATE TABLE IF NOT EXISTS} + {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS}
 * for additive migrations. See tracks/FLOWS.md.
 */
@Repository
public class TrackRepository {

    private final JdbcTemplate jdbc;

    public TrackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Track(long id, String title, String type, String status, String source,
                        LocalDate deadline, String priority, boolean includeInProgress,
                        Instant createdAt) {}

    public record TrackItem(long id, long trackId, int position, String title, String notePath,
                            String status, Instant completedAt) {}

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS tracks (
              id          BIGSERIAL PRIMARY KEY,
              title       TEXT NOT NULL,
              type        TEXT NOT NULL,
              status      TEXT NOT NULL DEFAULT 'active',
              source      TEXT NOT NULL DEFAULT 'manual',
              deadline    DATE,
              priority    TEXT,
              include_in_progress BOOLEAN NOT NULL DEFAULT true,
              created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS track_items (
              id            BIGSERIAL PRIMARY KEY,
              track_id      BIGINT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
              position      INTEGER NOT NULL,
              title         TEXT NOT NULL,
              note_path     TEXT,
              status        TEXT NOT NULL DEFAULT 'pending',
              completed_at  TIMESTAMPTZ
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_track_items_track ON track_items(track_id, position)");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS track_schedule (
              track_id           BIGINT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
              weekday            SMALLINT NOT NULL,
              daily_item_budget  INTEGER NOT NULL DEFAULT 1,
              PRIMARY KEY (track_id, weekday)
            )
            """);
    }

    // ── Tracks ───────────────────────────────────────────────────────────────

    public Track create(String title, String type, String source) {
        long id = jdbc.queryForObject(
            "INSERT INTO tracks(title, type, source) VALUES (?, ?, ?) RETURNING id",
            Long.class, title, type, source == null ? "manual" : source);
        return get(id);
    }

    public Track get(long id) {
        List<Track> rows = jdbc.query("SELECT * FROM tracks WHERE id = ?", TrackRepository::mapTrack, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Track> listAll() {
        return jdbc.query("SELECT * FROM tracks ORDER BY created_at DESC", TrackRepository::mapTrack);
    }

    public List<Track> listActive() {
        return jdbc.query(
            "SELECT * FROM tracks WHERE status = 'active' ORDER BY created_at DESC",
            TrackRepository::mapTrack);
    }

    /** Partial update — null fields are left unchanged. */
    public Track update(long id, String title, String type, String status, LocalDate deadline,
                        String priority, Boolean includeInProgress, boolean clearDeadline) {
        if (title != null) jdbc.update("UPDATE tracks SET title = ? WHERE id = ?", title, id);
        if (type != null) jdbc.update("UPDATE tracks SET type = ? WHERE id = ?", type, id);
        if (status != null) jdbc.update("UPDATE tracks SET status = ? WHERE id = ?", status, id);
        if (clearDeadline) {
            jdbc.update("UPDATE tracks SET deadline = NULL, priority = NULL WHERE id = ?", id);
        } else if (deadline != null) {
            jdbc.update("UPDATE tracks SET deadline = ? WHERE id = ?", deadline, id);
        }
        if (priority != null) jdbc.update("UPDATE tracks SET priority = ? WHERE id = ?", priority, id);
        if (includeInProgress != null) {
            jdbc.update("UPDATE tracks SET include_in_progress = ? WHERE id = ?", includeInProgress, id);
        }
        return get(id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM tracks WHERE id = ?", id);
    }

    // ── Items ────────────────────────────────────────────────────────────────

    public TrackItem addItem(long trackId, String title, String notePath) {
        Integer maxPos = jdbc.queryForObject(
            "SELECT COALESCE(MAX(position), -1) FROM track_items WHERE track_id = ?", Integer.class, trackId);
        int position = (maxPos == null ? -1 : maxPos) + 1;
        long id = jdbc.queryForObject("""
            INSERT INTO track_items(track_id, position, title, note_path)
            VALUES (?, ?, ?, ?) RETURNING id
            """, Long.class, trackId, position, title, notePath);
        return getItem(id);
    }

    public TrackItem getItem(long id) {
        List<TrackItem> rows = jdbc.query(
            "SELECT * FROM track_items WHERE id = ?", TrackRepository::mapItem, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<TrackItem> listItems(long trackId) {
        return jdbc.query(
            "SELECT * FROM track_items WHERE track_id = ? ORDER BY position", TrackRepository::mapItem, trackId);
    }

    public TrackItem updateItemTitle(long itemId, String title) {
        jdbc.update("UPDATE track_items SET title = ? WHERE id = ?", title, itemId);
        return getItem(itemId);
    }

    /** Move an item to a new position within its track, shifting siblings — same shape as
     *  any ordered-list reorder (see capture.playlist_position for the sibling idea). */
    public TrackItem reorderItem(long itemId, int newPosition) {
        TrackItem item = getItem(itemId);
        if (item == null) return null;
        int oldPosition = item.position();
        if (newPosition == oldPosition) return item;
        if (newPosition > oldPosition) {
            jdbc.update("""
                UPDATE track_items SET position = position - 1
                WHERE track_id = ? AND position > ? AND position <= ?
                """, item.trackId(), oldPosition, newPosition);
        } else {
            jdbc.update("""
                UPDATE track_items SET position = position + 1
                WHERE track_id = ? AND position >= ? AND position < ?
                """, item.trackId(), newPosition, oldPosition);
        }
        jdbc.update("UPDATE track_items SET position = ? WHERE id = ?", newPosition, itemId);
        return getItem(itemId);
    }

    public void deleteItem(long itemId) {
        jdbc.update("DELETE FROM track_items WHERE id = ?", itemId);
    }

    /** Marks an item done. Returns false if it was already done (no-op, idempotent guard). */
    public boolean completeItem(long itemId) {
        return jdbc.update(
            "UPDATE track_items SET status = 'done', completed_at = now() WHERE id = ? AND status = 'pending'",
            itemId) > 0;
    }

    /** Next N pending items for a track, ordered by position — the read-time allocation
     *  primitive both the Phase 1 flat Today view and Phase 1c's richer algorithm pull from. */
    public List<TrackItem> nextPendingItems(long trackId, int limit) {
        if (limit <= 0) return List.of();
        return jdbc.query("""
            SELECT * FROM track_items WHERE track_id = ? AND status = 'pending'
            ORDER BY position LIMIT ?
            """, TrackRepository::mapItem, trackId, limit);
    }

    public int countPendingItems(long trackId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM track_items WHERE track_id = ? AND status = 'pending'", Integer.class, trackId);
        return n == null ? 0 : n;
    }

    // ── Schedule ─────────────────────────────────────────────────────────────

    /** weekday -> daily_item_budget, only for days this track is actually scheduled. */
    public Map<Integer, Integer> getSchedule(long trackId) {
        Map<Integer, Integer> schedule = new LinkedHashMap<>();
        jdbc.query("SELECT weekday, daily_item_budget FROM track_schedule WHERE track_id = ? ORDER BY weekday",
            rs -> { schedule.put(rs.getInt("weekday"), rs.getInt("daily_item_budget")); }, trackId);
        return schedule;
    }

    /** Full replace — the weekly editor always submits the complete Mon..Sun map. */
    public void setSchedule(long trackId, Map<Integer, Integer> weekdayBudgets) {
        jdbc.update("DELETE FROM track_schedule WHERE track_id = ?", trackId);
        for (Map.Entry<Integer, Integer> e : weekdayBudgets.entrySet()) {
            jdbc.update("""
                INSERT INTO track_schedule(track_id, weekday, daily_item_budget) VALUES (?, ?, ?)
                """, trackId, e.getKey(), e.getValue());
        }
    }

    public Integer getScheduleBudget(long trackId, int weekday) {
        List<Integer> rows = jdbc.queryForList(
            "SELECT daily_item_budget FROM track_schedule WHERE track_id = ? AND weekday = ?",
            Integer.class, trackId, weekday);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private static Track mapTrack(ResultSet rs, int rowNum) throws SQLException {
        java.sql.Date deadline = rs.getDate("deadline");
        return new Track(
            rs.getLong("id"), rs.getString("title"), rs.getString("type"), rs.getString("status"),
            rs.getString("source"), deadline == null ? null : deadline.toLocalDate(),
            rs.getString("priority"), rs.getBoolean("include_in_progress"),
            rs.getTimestamp("created_at").toInstant());
    }

    private static TrackItem mapItem(ResultSet rs, int rowNum) throws SQLException {
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new TrackItem(
            rs.getLong("id"), rs.getLong("track_id"), rs.getInt("position"), rs.getString("title"),
            rs.getString("note_path"), rs.getString("status"),
            completedAt == null ? null : completedAt.toInstant());
    }
}
