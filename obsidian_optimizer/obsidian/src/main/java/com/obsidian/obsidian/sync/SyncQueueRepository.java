package com.obsidian.obsidian.sync;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class SyncQueueRepository {

    private final JdbcTemplate jdbc;

    public SyncQueueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS sync_queue (
                path            TEXT PRIMARY KEY,
                content_hash    TEXT NOT NULL,
                status          TEXT NOT NULL DEFAULT 'PENDING',
                last_synced_at  BIGINT,
                drive_file_id   TEXT,
                retry_count     INT NOT NULL DEFAULT 0
            )
            """);
        // Migration: the original CHECK didn't allow DELETE_PENDING (tombstones for
        // Drive-side delete propagation). Recreate it — idempotent drop+add each boot.
        jdbc.execute("ALTER TABLE sync_queue DROP CONSTRAINT IF EXISTS sync_queue_status_check");
        jdbc.execute("""
            ALTER TABLE sync_queue ADD CONSTRAINT sync_queue_status_check
                CHECK (status IN ('PENDING','DONE','FAILED','DELETE_PENDING'))
            """);
    }

    /** Upsert to PENDING. Idempotent — safe to call on every write. */
    public void markPending(String path, String contentHash) {
        jdbc.update("""
            INSERT INTO sync_queue(path, content_hash, status, retry_count)
            VALUES (?, ?, 'PENDING', 0)
            ON CONFLICT (path) DO UPDATE SET
                content_hash = EXCLUDED.content_hash,
                status       = 'PENDING',
                retry_count  = 0
            """, path, contentHash);
    }

    /**
     * Marks DONE only if the row still holds the hash that was uploaded.
     * If a fresh edit re-marked the row PENDING with a new hash between the
     * upload read and this call, the update matches nothing and the row stays
     * PENDING — the newer content is uploaded on the next pass.
     * Returns true if the row transitioned to DONE.
     */
    public boolean markDoneIfHashMatches(String path, String driveFileId, String uploadedHash) {
        return jdbc.update("""
            UPDATE sync_queue
            SET status = 'DONE', last_synced_at = ?, drive_file_id = ?
            WHERE path = ? AND content_hash = ?
            """, System.currentTimeMillis(), driveFileId, path, uploadedHash) > 0;
    }

    /**
     * Records a downloaded file as in-sync: upserts the row to DONE with the
     * hash from Drive metadata. Unlike markDoneIfHashMatches this must upsert —
     * files created on another device have no local queue row yet.
     */
    public void markSynced(String path, String contentHash, String driveFileId) {
        jdbc.update("""
            INSERT INTO sync_queue(path, content_hash, status, last_synced_at, drive_file_id, retry_count)
            VALUES (?, ?, 'DONE', ?, ?, 0)
            ON CONFLICT (path) DO UPDATE SET
                content_hash   = EXCLUDED.content_hash,
                status         = 'DONE',
                last_synced_at = EXCLUDED.last_synced_at,
                drive_file_id  = EXCLUDED.drive_file_id,
                retry_count    = 0
            """, path, contentHash, System.currentTimeMillis(), driveFileId);
    }

    public void markFailed(String path) {
        jdbc.update("""
            UPDATE sync_queue
            SET status = 'FAILED', retry_count = retry_count + 1
            WHERE path = ?
            """, path);
    }

    /** Remove entry (local bookkeeping only — does NOT touch Drive). */
    public void delete(String path) {
        jdbc.update("DELETE FROM sync_queue WHERE path = ?", path);
    }

    /**
     * Local file went away (soft-delete / rename old path): if the file ever reached
     * Drive, leave a DELETE_PENDING tombstone so the next upload pass removes the
     * Drive copy; if it never uploaded, just drop the row. Replaces a pending upload
     * atomically — we never upload-then-orphan.
     */
    public void tombstone(String path) {
        int updated = jdbc.update("""
            UPDATE sync_queue SET status = 'DELETE_PENDING'
            WHERE path = ? AND drive_file_id IS NOT NULL
            """, path);
        if (updated == 0) {
            delete(path);
        }
    }

    public List<SyncEntry> findByStatus(String status) {
        return jdbc.query("""
            SELECT path, content_hash, status, last_synced_at, drive_file_id, retry_count
            FROM sync_queue WHERE status = ? ORDER BY path
            """,
            (rs, __) -> new SyncEntry(
                rs.getString("path"),
                rs.getString("content_hash"),
                rs.getString("status"),
                (Long) rs.getObject("last_synced_at"),
                rs.getString("drive_file_id"),
                rs.getInt("retry_count")
            ),
            status);
    }

    /**
     * Rows an upload pass should attempt: all PENDING, plus FAILED rows still under the
     * retry cap (self-healing — a transient Drive error no longer strands a file forever).
     * Once retry_count reaches maxRetries a FAILED row is skipped = dead-letter.
     */
    public List<SyncEntry> findUploadable(int maxRetries) {
        return jdbc.query("""
            SELECT path, content_hash, status, last_synced_at, drive_file_id, retry_count
            FROM sync_queue
            WHERE status = 'PENDING'
               OR (status = 'FAILED' AND retry_count < ?)
            ORDER BY path
            """,
            (rs, __) -> new SyncEntry(
                rs.getString("path"),
                rs.getString("content_hash"),
                rs.getString("status"),
                (Long) rs.getObject("last_synced_at"),
                rs.getString("drive_file_id"),
                rs.getInt("retry_count")
            ),
            maxRetries);
    }

    public SyncEntry findByPath(String path) {
        List<SyncEntry> rows = jdbc.query("""
            SELECT path, content_hash, status, last_synced_at, drive_file_id, retry_count
            FROM sync_queue WHERE path = ?
            """,
            (rs, __) -> new SyncEntry(
                rs.getString("path"),
                rs.getString("content_hash"),
                rs.getString("status"),
                (Long) rs.getObject("last_synced_at"),
                rs.getString("drive_file_id"),
                rs.getInt("retry_count")
            ),
            path);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> getStatusSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        jdbc.queryForList("SELECT status, COUNT(*) AS count FROM sync_queue GROUP BY status")
            .forEach(row -> result.put(row.get("status").toString().toLowerCase() + "Count", row.get("count")));
        List<Long> ts = jdbc.queryForList("SELECT MAX(last_synced_at) FROM sync_queue", Long.class);
        result.put("lastSyncedAt", ts.isEmpty() ? null : ts.get(0));
        return result;
    }

    public record SyncEntry(
        String path,
        String contentHash,
        String status,
        Long lastSyncedAt,
        String driveFileId,
        int retryCount
    ) {}
}
