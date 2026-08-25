package com.obsidian.obsidian.capture;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The Capture table — one original resource and the proposed notes an agent makes from
 * it (see CAPTURE_ARCH.md). Unlike the {@code notes} index (a rebuildable cache over the
 * vault), this is AUTHORED state: it must be backed up with the DB, not regenerated from
 * disk. Notes link back via frontmatter {@code capture-id} (mirrored into
 * {@code notes.capture_id}); this table holds the resource side + lifecycle status.
 *
 * Lifecycle: queued → processing → ready → filed (all child notes triaged) → source
 * trashed. {@code queued} = the resource has been received but not yet handed to the
 * embedder; the {@link CaptureIngestWorker} drains those continuously (durable across
 * restart, unlike the embedder's in-memory job queue). A capture whose ingest already
 * ran when the row was created (in-place note snapshots) is inserted at {@code processing}.
 */
@Component
public class CaptureRepository {

    private final JdbcTemplate jdbc;

    public CaptureRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Capture(String id, String sourceType, String sourceRef,
                          String sourcePath, String title, String status,
                          String bundleRef, long createdAt,
                          String playlistId, Integer playlistPosition, Long trackId,
                          int retryCount, String lastError) {}

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS capture (
                id          TEXT    PRIMARY KEY,
                source_type TEXT    NOT NULL,   -- text | web_dom | pdf | md | audio | video
                source_ref  TEXT,               -- url / original filename / paste label
                source_path TEXT,               -- vault-relative path of the kept original
                title       TEXT,
                status      TEXT    NOT NULL DEFAULT 'processing', -- queued|processing|deferred|ready|filed|failed
                created_at  BIGINT  NOT NULL
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_capture_status ON capture(status)");
        // bundle_ref: the embedder's saved-bundle path for a DEFERRED synthesis, so the retry
        // resumes without re-extracting. Added by migration (existing DBs predate the column).
        jdbc.execute("ALTER TABLE capture ADD COLUMN IF NOT EXISTS bundle_ref TEXT");
        // playlist_id/playlist_position: set only for captures expanded from a playlist URL
        // (CaptureController) — groups the individual video rows and preserves their original
        // order for progress display. Null for every other capture.
        jdbc.execute("ALTER TABLE capture ADD COLUMN IF NOT EXISTS playlist_id TEXT");
        jdbc.execute("ALTER TABLE capture ADD COLUMN IF NOT EXISTS playlist_position INTEGER");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_capture_playlist ON capture(playlist_id)");
        // track_id: capture-time Learning Track tag (tracks/FLOWS.md Phase 1b). Soft reference
        // (no FK) by the same convention as note_path/source_path — tracks lives in a separate
        // repository with its own initSchema(), and Spring gives no ordering guarantee between
        // @PostConstruct methods in different beans.
        jdbc.execute("ALTER TABLE capture ADD COLUMN IF NOT EXISTS track_id BIGINT");
        // retry_count/last_error: a 'failed' capture (real ingest error, e.g. yt-dlp rejected
        // the URL) used to just sit there until the cleanup sweep silently discarded it 30 min
        // later — no retry, no visibility. These back an unbounded auto-retry (CaptureIngestWorker
        // .retryFailed — every restart, forever) and let the failed-list endpoint show the actual
        // error and lifetime attempt count instead of nothing.
        jdbc.execute("ALTER TABLE capture ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0");
        jdbc.execute("ALTER TABLE capture ADD COLUMN IF NOT EXISTS last_error TEXT");
    }

    public void create(String id, String sourceType, String sourceRef,
                       String sourcePath, String title) {
        jdbc.update("""
            INSERT INTO capture(id, source_type, source_ref, source_path, title, status, created_at)
            VALUES (?, ?, ?, ?, ?, 'processing', ?)
            ON CONFLICT (id) DO NOTHING
            """, id, sourceType, sourceRef, sourcePath, title, System.currentTimeMillis());
    }

    /** Insert a resource into the durable ingest queue (status {@code queued}); the
     *  {@link CaptureIngestWorker} submits it to the embedder on its next drain. */
    public void enqueue(String id, String sourceType, String sourceRef,
                        String sourcePath, String title) {
        jdbc.update("""
            INSERT INTO capture(id, source_type, source_ref, source_path, title, status, created_at)
            VALUES (?, ?, ?, ?, ?, 'queued', ?)
            ON CONFLICT (id) DO NOTHING
            """, id, sourceType, sourceRef, sourcePath, title, System.currentTimeMillis());
    }

    /** Same as {@link #enqueue} but tags the row as part of a playlist expansion
     *  (CaptureController) — {@code playlistId} groups the sibling rows, {@code position}
     *  preserves their order in the source playlist. */
    public void enqueuePlaylistItem(String id, String sourceType, String sourceRef,
                                    String sourcePath, String title,
                                    String playlistId, int position) {
        jdbc.update("""
            INSERT INTO capture(id, source_type, source_ref, source_path, title, status,
                                created_at, playlist_id, playlist_position)
            VALUES (?, ?, ?, ?, ?, 'queued', ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """, id, sourceType, sourceRef, sourcePath, title,
            System.currentTimeMillis(), playlistId, position);
    }

    public void updateStatus(String id, String status) {
        jdbc.update("UPDATE capture SET status = ? WHERE id = ?", status, id);
    }

    /** Tag a capture with the Learning Track its resulting notes should be filed into
     *  (tracks/FLOWS.md Phase 1b) — set right after enqueue, read back by
     *  InternalAgentController.createNote() when each synthesized note lands. */
    public void setTrackId(String id, long trackId) {
        jdbc.update("UPDATE capture SET track_id = ? WHERE id = ?", trackId, id);
    }

    /** Mark a capture failed ONLY if it's still processing — so a job that fails after a
     *  successful submit stops being a silent 'processing' strand, without clobbering a
     *  capture the user already filed/acknowledged. Records the real error so the failed-list
     *  endpoint (and the eventual retry) can show *why*, not just *that*. Returns true if it
     *  flipped. */
    public boolean markFailed(String id, String error) {
        return jdbc.update(
            "UPDATE capture SET status = 'failed', last_error = ? WHERE id = ? AND status = 'processing'",
            error, id) > 0;
    }

    /** Unbounded auto-retry: atomically claim a FAILED row for another attempt (→ queued,
     *  retry_count+1) so a scheduled tick and a manual "retry now" can't double-claim it. No
     *  cap — a failure only stops being retried by succeeding or by the user dismissing it
     *  from the failed-list; retry_count is kept purely as telemetry ("tried N times"), never
     *  as a silent give-up threshold. Returns true if THIS caller won it. */
    public boolean requeueFailed(String id) {
        return jdbc.update(
            "UPDATE capture SET status = 'queued', retry_count = retry_count + 1 WHERE id = ? AND status = 'failed'",
            id) > 0;
    }

    /** Oldest-first batch of FAILED captures — candidates for {@code requeueFailed}. */
    public List<Capture> findFailedRetryable(int limit) {
        return jdbc.query(
            "SELECT * FROM capture WHERE status = 'failed' ORDER BY created_at ASC LIMIT ?",
            CaptureRepository::map, limit);
    }

    /** Every live FAILED capture (retry-exhausted or not) for the frontend's failed-list —
     *  newest first, same convention as {@link #listAll}. */
    public List<Capture> listFailed() {
        return jdbc.query(
            "SELECT * FROM capture WHERE status = 'failed' ORDER BY created_at DESC",
            CaptureRepository::map);
    }

    /** User dismissed a failed capture from the failed-list UI — done trying. */
    public boolean dismissFailed(String id) {
        return jdbc.update(
            "UPDATE capture SET status = 'discarded' WHERE id = ? AND status = 'failed'",
            id) > 0;
    }

    /** Mark a capture DEFERRED (synthesis waiting on LLM providers) ONLY if it's still
     *  'processing', storing the bundle to resume from. Returns true if it flipped. */
    public boolean markDeferred(String id, String bundleRef) {
        return jdbc.update(
            "UPDATE capture SET status = 'deferred', bundle_ref = ? " +
            "WHERE id = ? AND status = 'processing'",
            bundleRef, id) > 0;
    }

    /** Oldest-first batch of DEFERRED captures awaiting a synthesis retry. */
    public List<Capture> findDeferred(int limit) {
        return jdbc.query(
            "SELECT * FROM capture WHERE status = 'deferred' ORDER BY created_at ASC LIMIT ?",
            CaptureRepository::map, limit);
    }

    /** Atomically claim a DEFERRED row for a retry (→ {@code processing}) so two ticks
     *  can't double-resume. Returns true if THIS caller won it. */
    public boolean claimDeferred(String id) {
        return jdbc.update(
            "UPDATE capture SET status = 'processing' WHERE id = ? AND status = 'deferred'",
            id) > 0;
    }

    /** Captures still 'processing' and older than a cutoff — candidates for the orphan-source
     *  cleanup sweep (a source with no notes left → trash it). The age gate keeps mid-ingest
     *  captures out. 'failed' is handled separately ({@link #findExhaustedFailed}) — it gets a
     *  bounded auto-retry first instead of being swept the moment it's stale. */
    public List<Capture> findStaleActive(long olderThanEpochMillis) {
        return jdbc.query(
            "SELECT * FROM capture WHERE status = 'processing' AND created_at < ? " +
            "ORDER BY created_at ASC",
            CaptureRepository::map, olderThanEpochMillis);
    }


    /** Oldest-first batch of queued resources awaiting submission (FIFO fairness). */
    public List<Capture> findQueued(int limit) {
        return jdbc.query(
            "SELECT * FROM capture WHERE status = 'queued' ORDER BY created_at ASC LIMIT ?",
            CaptureRepository::map, limit);
    }

    /** Atomically claim a queued row (→ {@code processing}) so a nudge and a scheduled
     *  tick can't double-submit the same resource. Returns true if THIS caller won it. */
    public boolean claim(String id) {
        return jdbc.update(
            "UPDATE capture SET status = 'processing' WHERE id = ? AND status = 'queued'",
            id) > 0;
    }

    /** Is this exact source already in the inbox pipeline (queued/processing/ready)? Used to
     *  reject a duplicate capture LOUDLY — re-sharing the same link/file that's already here.
     *  A discarded/failed capture does NOT block (re-capturing after cleanup is fine). */
    public boolean existsLiveForSource(String sourceRef) {
        if (sourceRef == null || sourceRef.isBlank()) return false;
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM capture WHERE source_ref = ? " +
            "AND status IN ('queued','processing','deferred','ready')",
            Integer.class, sourceRef);
        return n != null && n > 0;
    }

    /** How many OTHER live (non-discarded) captures still reference this vault file as their
     *  source/original. Guards the cleanup sweep against trashing a file shared by a duplicate
     *  capture (same upload twice → same filename) whose sibling still has notes. */
    public int countLiveReferencesToFile(String vaultRelPath, String excludeId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM capture WHERE id <> ? AND status <> 'discarded' " +
            "AND (source_ref = ? OR source_path = ?)",
            Integer.class, excludeId, vaultRelPath, vaultRelPath);
        return n == null ? 0 : n;
    }

    /** Clear the source path once the original has been trashed on completion. */
    public void setSourcePath(String id, String sourcePath) {
        jdbc.update("UPDATE capture SET source_path = ? WHERE id = ?", sourcePath, id);
    }

    public Capture get(String id) {
        List<Capture> rows = jdbc.query(
            "SELECT * FROM capture WHERE id = ?", CaptureRepository::map, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Capture> listAll() {
        return jdbc.query(
            "SELECT * FROM capture ORDER BY created_at DESC", CaptureRepository::map);
    }

    private static Capture map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        int rawPosition = rs.getInt("playlist_position");
        Integer playlistPosition = rs.wasNull() ? null : rawPosition;
        long rawTrackId = rs.getLong("track_id");
        Long trackId = rs.wasNull() ? null : rawTrackId;
        return new Capture(
            rs.getString("id"), rs.getString("source_type"), rs.getString("source_ref"),
            rs.getString("source_path"), rs.getString("title"), rs.getString("status"),
            rs.getString("bundle_ref"), rs.getLong("created_at"),
            rs.getString("playlist_id"), playlistPosition, trackId,
            rs.getInt("retry_count"), rs.getString("last_error"));
    }
}
