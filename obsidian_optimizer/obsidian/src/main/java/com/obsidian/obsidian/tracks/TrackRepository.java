package com.obsidian.obsidian.tracks;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
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
@DependsOn("vaultRepository")
public class TrackRepository {

    private final JdbcTemplate jdbc;

    public TrackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Track(long id, String title, String type, String status, String source,
                        LocalDate deadline, String priority, boolean includeInProgress,
                        Instant createdAt, String sourceUrl, String sourceType, Instant lastCheckedAt) {}

    public record TrackItem(long id, long trackId, int position, String title, String notePath,
                            String status, Instant completedAt, Long groupId) {}

    /** Shape only — used by a later step's {@code getOrCreateGroup()} to bucket subscription
     *  items (e.g. a YouTube upload's chapters) under one source entry. */
    public record TrackItemGroup(long id, long trackId, String title, String sourceUrl, Instant createdAt) {}

    /** One row of the Progress tab's source data (Phase 1d) — completion counts joined
     *  onto the track, before the service layer adds the deadline-aware onTrack signal. */
    public record TrackProgressRow(Track track, int itemsDone, int itemsTotal) {}

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
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS daily_capacity (
              weekday   SMALLINT PRIMARY KEY,
              capacity  NUMERIC NOT NULL
            )
            """);
        // Legacy subscription columns: unused since the Passive Feed (feed/FLOWS.md), kept (no destructive migration).
        jdbc.execute("ALTER TABLE tracks ADD COLUMN IF NOT EXISTS source_url TEXT");
        jdbc.execute("ALTER TABLE tracks ADD COLUMN IF NOT EXISTS source_type TEXT"); // 'youtube_channel' | 'feed'
        jdbc.execute("ALTER TABLE tracks ADD COLUMN IF NOT EXISTS last_checked_at TIMESTAMPTZ");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS track_item_groups (
              id          BIGSERIAL PRIMARY KEY,
              track_id    BIGINT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
              capture_id  TEXT,
              title       TEXT NOT NULL,
              source_url  TEXT,
              created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
              UNIQUE (track_id, capture_id)
            )
            """);
        jdbc.execute("""
            ALTER TABLE track_items ADD COLUMN IF NOT EXISTS group_id BIGINT
              REFERENCES track_item_groups(id) ON DELETE SET NULL
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_track_items_group ON track_items(group_id)");
        seedDefaultCapacity();
        jdbc.execute("ALTER TABLE tracks ADD COLUMN IF NOT EXISTS vault_id BIGINT NOT NULL DEFAULT 1 REFERENCES vaults(id)");
        jdbc.execute("ALTER TABLE tracks ALTER COLUMN vault_id DROP DEFAULT");
        jdbc.execute("ALTER TABLE track_item_groups ADD COLUMN IF NOT EXISTS vault_id BIGINT NOT NULL DEFAULT 1 REFERENCES vaults(id)");
        jdbc.execute("ALTER TABLE track_item_groups ALTER COLUMN vault_id DROP DEFAULT");
        // Learn units (tracks/FLOWS.md "Units"): a group row doubles as a unit->track assignment.
        // chapter = per-chapter unit of a book; key is (track, capture, chapter) with NULL chapter
        // still unique per capture (COALESCE), so the old (track_id, capture_id) constraint goes.
        jdbc.execute("ALTER TABLE track_item_groups ADD COLUMN IF NOT EXISTS chapter TEXT");
        jdbc.execute("ALTER TABLE track_item_groups DROP CONSTRAINT IF EXISTS track_item_groups_track_id_capture_id_key");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_track_item_groups_unit ON track_item_groups(track_id, capture_id, (COALESCE(chapter, '')))");
        // Today picker pin: which units were chosen for a day (carry-over + no refill after finishing).
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS today_units (
              vault_id    BIGINT NOT NULL REFERENCES vaults(id),
              plan_date   DATE NOT NULL,
              capture_id  TEXT NOT NULL,
              chapter     TEXT NOT NULL DEFAULT '',
              position    INTEGER NOT NULL,
              PRIMARY KEY (vault_id, plan_date, capture_id, chapter)
            )
            """);
        // Bin (bin/FLOWS.md): deletes are soft; every read below filters deleted_at IS NULL.
        jdbc.execute("ALTER TABLE tracks ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ");
        jdbc.execute("ALTER TABLE track_items ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ");
    }

    /** Mon-Fri=4, Sat/Sun=6 ("weekends 1.5x") — plain per-day numbers, hand-tunable later
     *  without touching the others. ON CONFLICT DO NOTHING so a user edit is never clobbered
     *  on restart. */
    private void seedDefaultCapacity() {
        for (int weekday = 0; weekday <= 6; weekday++) {
            double def = (weekday == 5 || weekday == 6) ? 6.0 : 4.0;
            jdbc.update(
                "INSERT INTO daily_capacity(weekday, capacity) VALUES (?, ?) ON CONFLICT DO NOTHING",
                weekday, def);
        }
    }

    // ── Tracks ───────────────────────────────────────────────────────────────

    public Track create(long vaultId, String title, String type, String source) {
        long id = jdbc.queryForObject("""
            INSERT INTO tracks(title, type, source, vault_id) VALUES (?, ?, ?, ?)
            RETURNING id
            """, Long.class, title, type, source == null ? "manual" : source, vaultId);
        return get(vaultId, id);
    }

    /** The feed's default target: one auto-created "Passive" track (no deadline, priority
     *  could), found by source='passive-feed'. Used by feed/ accept (see feed/FLOWS.md). */
    public Track getOrCreatePassive(long vaultId) {
        List<Track> rows = jdbc.query(
            "SELECT * FROM tracks WHERE vault_id = ? AND source = 'passive-feed' AND deleted_at IS NULL ORDER BY id LIMIT 1",
            TrackRepository::mapTrack, vaultId);
        if (!rows.isEmpty()) return rows.get(0);
        Track t = create(vaultId, "Passive", "custom", "passive-feed");
        return update(vaultId, t.id(), null, null, null, null, "could", null, false);
    }

    public Track get(long vaultId, long id) {
        List<Track> rows = jdbc.query("SELECT * FROM tracks WHERE vault_id = ? AND id = ? AND deleted_at IS NULL",
            TrackRepository::mapTrack, vaultId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Track> listAll(long vaultId) {
        return jdbc.query("SELECT * FROM tracks WHERE vault_id = ? AND deleted_at IS NULL ORDER BY created_at DESC",
            TrackRepository::mapTrack, vaultId);
    }

    public List<Track> listActive(long vaultId) {
        return jdbc.query(
            "SELECT * FROM tracks WHERE vault_id = ? AND status = 'active' AND deleted_at IS NULL ORDER BY created_at DESC",
            TrackRepository::mapTrack, vaultId);
    }

    /** Partial update — null fields are left unchanged. */
    public Track update(long vaultId, long id, String title, String type, String status, LocalDate deadline,
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
        return get(vaultId, id);
    }

    /** Soft delete (Bin): the track and every live item get the SAME timestamp, so restore can tell
     *  "deleted with the track" from "deleted on its own earlier". Schedule rows are left in place. */
    public void delete(long vaultId, long id) {
        Timestamp now = Timestamp.from(Instant.now());
        if (jdbc.update("UPDATE tracks SET deleted_at = ? WHERE id = ? AND vault_id = ? AND deleted_at IS NULL",
                now, id, vaultId) > 0) {
            jdbc.update("UPDATE track_items SET deleted_at = ? WHERE track_id = ? AND deleted_at IS NULL", now, id);
        }
    }

    // ── Bin support (bin/BinService) ─────────────────────────────────────────

    public record BinTrack(long id, String title, Instant deletedAt) {}
    public record BinTrackItem(long id, String title, String trackTitle, Instant deletedAt) {}

    public List<BinTrack> listDeletedTracks(long vaultId) {
        return jdbc.query("SELECT id, title, deleted_at FROM tracks WHERE vault_id = ? AND deleted_at IS NOT NULL ORDER BY deleted_at DESC",
            (rs, n) -> new BinTrack(rs.getLong("id"), rs.getString("title"), rs.getTimestamp("deleted_at").toInstant()), vaultId);
    }

    /** Items deleted on their own, whose track is still alive (items deleted with a track ride that track's entry). */
    public List<BinTrackItem> listDeletedItems(long vaultId) {
        return jdbc.query("""
            SELECT ti.id, ti.title, ti.deleted_at, t.title AS track_title
            FROM track_items ti JOIN tracks t ON t.id = ti.track_id
            WHERE t.vault_id = ? AND ti.deleted_at IS NOT NULL AND t.deleted_at IS NULL
            ORDER BY ti.deleted_at DESC
            """, (rs, n) -> new BinTrackItem(rs.getLong("id"), rs.getString("title"), rs.getString("track_title"),
                rs.getTimestamp("deleted_at").toInstant()), vaultId);
    }

    /** Full restore: the track plus the items that were deleted with it (same timestamp). Schedule/status untouched. */
    public boolean restoreTrack(long vaultId, long id) {
        List<Timestamp> ts = jdbc.queryForList("SELECT deleted_at FROM tracks WHERE id = ? AND vault_id = ? AND deleted_at IS NOT NULL",
            Timestamp.class, id, vaultId);
        if (ts.isEmpty()) return false;
        jdbc.update("UPDATE track_items SET deleted_at = NULL WHERE track_id = ? AND deleted_at = ?", id, ts.get(0));
        jdbc.update("UPDATE tracks SET deleted_at = NULL WHERE id = ?", id);
        return true;
    }

    /** Restore one item; false if unknown, not deleted, or its track is itself in the bin. */
    public boolean restoreItem(long vaultId, long itemId) {
        return jdbc.update("""
            UPDATE track_items SET deleted_at = NULL WHERE id = ? AND deleted_at IS NOT NULL
              AND track_id IN (SELECT id FROM tracks WHERE vault_id = ? AND deleted_at IS NULL)
            """, itemId, vaultId) > 0;
    }

    public boolean purgeTrack(long vaultId, long id) {
        return jdbc.update("DELETE FROM tracks WHERE id = ? AND vault_id = ? AND deleted_at IS NOT NULL", id, vaultId) > 0;
    }

    public boolean purgeItem(long vaultId, long itemId) {
        return jdbc.update("""
            DELETE FROM track_items WHERE id = ? AND deleted_at IS NOT NULL
              AND track_id IN (SELECT id FROM tracks WHERE vault_id = ?)
            """, itemId, vaultId) > 0;
    }

    /** Hard-delete everything in the bin older than {@code cutoff}; returns rows removed. */
    public int purgeDeletedBefore(Instant cutoff) {
        Timestamp c = Timestamp.from(cutoff);
        int n = jdbc.update("DELETE FROM tracks WHERE deleted_at IS NOT NULL AND deleted_at < ?", c);
        return n + jdbc.update("DELETE FROM track_items WHERE deleted_at IS NOT NULL AND deleted_at < ?", c);
    }

    // ── Items ────────────────────────────────────────────────────────────────

    public TrackItem addItem(long trackId, String title, String notePath) {
        return addItem(trackId, title, notePath, null);
    }

    public TrackItem addItem(long trackId, String title, String notePath, Long groupId) {
        Integer maxPos = jdbc.queryForObject(
            "SELECT COALESCE(MAX(position), -1) FROM track_items WHERE track_id = ?", Integer.class, trackId);
        int position = (maxPos == null ? -1 : maxPos) + 1;
        long id = jdbc.queryForObject("""
            INSERT INTO track_items(track_id, position, title, note_path, group_id)
            VALUES (?, ?, ?, ?, ?) RETURNING id
            """, Long.class, trackId, position, title, notePath, groupId);
        return getItem(id);
    }

    /** Bucket every note produced from the same capture under one group row — same
     *  capture-queue key whether the note came from the extension, PWA, or a subscription
     *  poller. ON CONFLICT DO NOTHING + re-select makes concurrent calls for the same
     *  (trackId, captureId) converge on the same group instead of racing two inserts. */
    public TrackItemGroup getOrCreateGroup(long vaultId, long trackId, String captureId, String title, String sourceUrl) {
        return getOrCreateGroup(vaultId, trackId, captureId, null, title, sourceUrl);
    }

    public TrackItemGroup getOrCreateGroup(long vaultId, long trackId, String captureId, String chapter,
                                           String title, String sourceUrl) {
        jdbc.update("""
            INSERT INTO track_item_groups(track_id, capture_id, chapter, title, source_url, vault_id) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (track_id, capture_id, (COALESCE(chapter, ''))) DO NOTHING
            """, trackId, captureId, chapter, title, sourceUrl, vaultId);
        return jdbc.queryForObject("""
            SELECT * FROM track_item_groups WHERE track_id = ? AND capture_id = ? AND COALESCE(chapter, '') = COALESCE(?, '')
            """, TrackRepository::mapGroup, trackId, captureId, chapter);
    }

    // ── Learn units (unit = inbox group, assigned to exactly one track) ─────────

    /** Unit key {@code captureId[/chapter]} -> track id, live tracks only (a unit whose track
     *  was binned reads as unassigned, so lazy assignment re-homes it). */
    public Map<String, Long> unitAssignments(long vaultId) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("""
            SELECT DISTINCT ON (g.capture_id, COALESCE(g.chapter, '')) g.capture_id, g.chapter, g.track_id
            FROM track_item_groups g JOIN tracks t ON t.id = g.track_id
            WHERE g.vault_id = ? AND g.capture_id IS NOT NULL AND t.deleted_at IS NULL
            ORDER BY g.capture_id, COALESCE(g.chapter, ''), g.id
            """, rs -> {
                String ch = rs.getString("chapter");
                out.put(rs.getString("capture_id") + (ch == null ? "" : "/" + ch), rs.getLong("track_id"));
            }, vaultId);
        return out;
    }

    /** Point the unit at {@code trackId}: re-home its existing group row, else insert one. */
    public void assignUnit(long vaultId, String captureId, String chapter, String title, long trackId) {
        List<Long> ids = jdbc.queryForList("""
            SELECT id FROM track_item_groups
            WHERE vault_id = ? AND capture_id = ? AND COALESCE(chapter, '') = COALESCE(?, '') ORDER BY id
            """, Long.class, vaultId, captureId, chapter);
        if (ids.isEmpty()) {
            getOrCreateGroup(vaultId, trackId, captureId, chapter, title == null ? captureId : title, null);
            return;
        }
        jdbc.update("DELETE FROM track_item_groups WHERE vault_id = ? AND capture_id = ? AND COALESCE(chapter, '') = COALESCE(?, '') AND id <> ? AND NOT EXISTS (SELECT 1 FROM track_items WHERE group_id = track_item_groups.id)",
            vaultId, captureId, chapter, ids.get(0));
        jdbc.update("UPDATE track_item_groups SET track_id = ? WHERE id = ?", trackId, ids.get(0));
    }

    /** Subscription's track for a capture accepted from a feed (sources.target_track_id), or null. */
    public Long subscriptionTrackId(long vaultId, String captureId) {
        List<Long> rows = jdbc.queryForList("""
            SELECT s.target_track_id FROM feed_items fi JOIN sources s ON s.id = fi.source_id
            WHERE fi.capture_id = ? AND s.vault_id = ? AND s.target_track_id IS NOT NULL
            """, Long.class, captureId, vaultId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** The auto-created default track (source='general'); idempotent, budget 1/day Mon..Sun. */
    public Track getOrCreateGeneral(long vaultId) {
        List<Track> rows = jdbc.query(
            "SELECT * FROM tracks WHERE vault_id = ? AND source = 'general' AND deleted_at IS NULL ORDER BY id LIMIT 1",
            TrackRepository::mapTrack, vaultId);
        if (!rows.isEmpty()) return rows.get(0);
        Track t = create(vaultId, "General", "custom", "general");
        Map<Integer, Integer> budgets = new LinkedHashMap<>();
        for (int d = 0; d <= 6; d++) budgets.put(d, 1);
        setSchedule(t.id(), budgets);
        return t;
    }

    public List<String> todayUnitKeys(long vaultId, LocalDate date) {
        return jdbc.queryForList("""
            SELECT capture_id || CASE WHEN chapter = '' THEN '' ELSE '/' || chapter END
            FROM today_units WHERE vault_id = ? AND plan_date = ? ORDER BY position
            """, String.class, vaultId, java.sql.Date.valueOf(date));
    }

    public LocalDate latestPlanDateBefore(long vaultId, LocalDate date) {
        List<java.sql.Date> rows = jdbc.queryForList(
            "SELECT MAX(plan_date) FROM today_units WHERE vault_id = ? AND plan_date < ?",
            java.sql.Date.class, vaultId, java.sql.Date.valueOf(date));
        return rows.isEmpty() || rows.get(0) == null ? null : rows.get(0).toLocalDate();
    }

    /** keys are {@code captureId[/chapter]}; chapter segment may not contain '/'. */
    public void saveTodayUnits(long vaultId, LocalDate date, List<String> keys) {
        int pos = 0;
        for (String k : keys) {
            int i = k.indexOf('/');
            jdbc.update("""
                INSERT INTO today_units(vault_id, plan_date, capture_id, chapter, position) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, vaultId, java.sql.Date.valueOf(date), i < 0 ? k : k.substring(0, i), i < 0 ? "" : k.substring(i + 1), pos++);
        }
    }

    /** Vault-checked item lookup (items inherit their vault through their track). */
    public TrackItem getItem(long vaultId, long id) {
        List<TrackItem> rows = jdbc.query(
            "SELECT ti.* FROM track_items ti JOIN tracks t ON t.id = ti.track_id WHERE ti.id = ? AND t.vault_id = ? AND ti.deleted_at IS NULL AND t.deleted_at IS NULL",
            TrackRepository::mapItem, id, vaultId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public TrackItem getItem(long id) {
        List<TrackItem> rows = jdbc.query(
            "SELECT * FROM track_items WHERE id = ? AND deleted_at IS NULL", TrackRepository::mapItem, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<TrackItem> listItems(long trackId) {
        return jdbc.query(
            "SELECT * FROM track_items WHERE track_id = ? AND deleted_at IS NULL ORDER BY position", TrackRepository::mapItem, trackId);
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
        jdbc.update("UPDATE track_items SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL", itemId);
    }

    /** Marks an item done. Returns false if it was already done (no-op, idempotent guard). */
    public boolean completeItem(long itemId) {
        return jdbc.update(
            "UPDATE track_items SET status = 'done', completed_at = now() WHERE id = ? AND status = 'pending'",
            itemId) > 0;
    }

    /** Items completed per local day per track over [from, to) - drives the Week badges.
     *  {@code zone} converts the timestamptz completed_at to the caller's calendar day. */
    public Map<LocalDate, Map<Long, Integer>> completionsByDay(long vaultId, LocalDate from,
                                                                LocalDate to, java.time.ZoneId zone) {
        Map<LocalDate, Map<Long, Integer>> out = new HashMap<>();
        if (!from.isBefore(to)) return out;
        jdbc.query("""
            SELECT (ti.completed_at AT TIME ZONE ?)::date AS day, ti.track_id, COUNT(*) AS n
            FROM track_items ti JOIN tracks t ON t.id = ti.track_id
            WHERE t.vault_id = ? AND t.deleted_at IS NULL AND ti.deleted_at IS NULL
              AND ti.status = 'done' AND ti.completed_at IS NOT NULL
              AND (ti.completed_at AT TIME ZONE ?)::date >= ? AND (ti.completed_at AT TIME ZONE ?)::date < ?
            GROUP BY 1, 2
            """, rs -> {
                out.computeIfAbsent(rs.getDate("day").toLocalDate(), k -> new java.util.LinkedHashMap<>())
                   .put(rs.getLong("track_id"), rs.getInt("n"));
            }, zone.getId(), vaultId, zone.getId(), java.sql.Date.valueOf(from), zone.getId(), java.sql.Date.valueOf(to));
        return out;
    }

    /** Next N pending items for a track, ordered by position — the read-time allocation
     *  primitive both the Phase 1 flat Today view and Phase 1c's richer algorithm pull from. */
    public List<TrackItem> nextPendingItems(long trackId, int limit) {
        if (limit <= 0) return List.of();
        return jdbc.query("""
            SELECT * FROM track_items WHERE track_id = ? AND status = 'pending' AND deleted_at IS NULL
            ORDER BY position LIMIT ?
            """, TrackRepository::mapItem, trackId, limit);
    }

    public int countPendingItems(long trackId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM track_items WHERE track_id = ? AND status = 'pending' AND deleted_at IS NULL", Integer.class, trackId);
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

    // ── Capacity (Phase 1c) ─────────────────────────────────────────────────────

    /** Falls back to 4.0 if the row is somehow missing (initSchema always seeds all 7). */
    public double getCapacity(int weekday) {
        List<Double> rows = jdbc.queryForList(
            "SELECT capacity FROM daily_capacity WHERE weekday = ?", Double.class, weekday);
        return rows.isEmpty() ? 4.0 : rows.get(0);
    }

    public Map<Integer, Double> getCapacityMap() {
        Map<Integer, Double> map = new LinkedHashMap<>();
        jdbc.query("SELECT weekday, capacity FROM daily_capacity ORDER BY weekday",
            rs -> { map.put(rs.getInt("weekday"), rs.getDouble("capacity")); });
        return map;
    }

    /** Partial upsert — unlike {@link #setSchedule}, only the given weekdays change; any
     *  day not present in the map keeps its current value. */
    public void setCapacity(Map<Integer, Double> weekdayCapacities) {
        for (Map.Entry<Integer, Double> e : weekdayCapacities.entrySet()) {
            jdbc.update("""
                INSERT INTO daily_capacity(weekday, capacity) VALUES (?, ?)
                ON CONFLICT (weekday) DO UPDATE SET capacity = EXCLUDED.capacity
                """, e.getKey(), e.getValue());
        }
    }

    /** Σ getCapacity(weekday) for each day in [fromInclusive, toExclusive) — the shared
     *  pace denominator for both Phase 1c's Today allocation (TodayPlanService) and
     *  Phase 1d's onTrack signal (TrackProgressService). Lives here rather than duplicated
     *  in each service since it only depends on the capacity table. */
    public double capacityBetween(LocalDate fromInclusive, LocalDate toExclusive) {
        double sum = 0;
        for (LocalDate d = fromInclusive; d.isBefore(toExclusive); d = d.plusDays(1)) {
            sum += getCapacity(weekdayOf(d));
        }
        return sum;
    }

    private int weekdayOf(LocalDate date) {
        return date.getDayOfWeek().getValue() - 1;
    }

    // ── Progress (Phase 1d) ─────────────────────────────────────────────────────

    /** One row per include_in_progress+active track with its completion counts —
     *  Postgres allows GROUP BY t.id with t.* selected since id is tracks' primary key
     *  (functional dependency). */
    public List<TrackProgressRow> listProgressRows(long vaultId) {
        return jdbc.query("""
            SELECT t.*,
                   COUNT(ti.id) AS items_total,
                   COUNT(ti.id) FILTER (WHERE ti.status = 'done') AS items_done
            FROM tracks t
            LEFT JOIN track_items ti ON ti.track_id = t.id AND ti.deleted_at IS NULL
            WHERE t.vault_id = ? AND t.deleted_at IS NULL AND t.include_in_progress = true AND t.status = 'active'
            GROUP BY t.id
            ORDER BY t.created_at DESC
            """, (rs, rowNum) -> new TrackProgressRow(
                mapTrack(rs, rowNum), rs.getInt("items_done"), rs.getInt("items_total")), vaultId);
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private static Track mapTrack(ResultSet rs, int rowNum) throws SQLException {
        java.sql.Date deadline = rs.getDate("deadline");
        Timestamp lastCheckedAt = rs.getTimestamp("last_checked_at");
        return new Track(
            rs.getLong("id"), rs.getString("title"), rs.getString("type"), rs.getString("status"),
            rs.getString("source"), deadline == null ? null : deadline.toLocalDate(),
            rs.getString("priority"), rs.getBoolean("include_in_progress"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("source_url"), rs.getString("source_type"),
            lastCheckedAt == null ? null : lastCheckedAt.toInstant());
    }

    private static TrackItem mapItem(ResultSet rs, int rowNum) throws SQLException {
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new TrackItem(
            rs.getLong("id"), rs.getLong("track_id"), rs.getInt("position"), rs.getString("title"),
            rs.getString("note_path"), rs.getString("status"),
            completedAt == null ? null : completedAt.toInstant(),
            (Long) rs.getObject("group_id"));
    }

    private static TrackItemGroup mapGroup(ResultSet rs, int rowNum) throws SQLException {
        return new TrackItemGroup(
            rs.getLong("id"), rs.getLong("track_id"), rs.getString("title"),
            rs.getString("source_url"), rs.getTimestamp("created_at").toInstant());
    }
}
