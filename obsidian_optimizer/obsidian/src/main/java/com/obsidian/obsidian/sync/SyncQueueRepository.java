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
                status          TEXT NOT NULL DEFAULT 'PENDING'
                                    CHECK (status IN ('PENDING','DONE','FAILED')),
                last_synced_at  BIGINT,
                drive_file_id   TEXT,
                retry_count     INT NOT NULL DEFAULT 0
            )
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

    public void markDone(String path, String driveFileId) {
        jdbc.update("""
            UPDATE sync_queue
            SET status = 'DONE', last_synced_at = ?, drive_file_id = ?
            WHERE path = ?
            """, System.currentTimeMillis(), driveFileId, path);
    }

    public void markFailed(String path) {
        jdbc.update("""
            UPDATE sync_queue
            SET status = 'FAILED', retry_count = retry_count + 1
            WHERE path = ?
            """, path);
    }

    /** Remove entry — used on soft-delete and rename (old path). */
    public void delete(String path) {
        jdbc.update("DELETE FROM sync_queue WHERE path = ?", path);
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
