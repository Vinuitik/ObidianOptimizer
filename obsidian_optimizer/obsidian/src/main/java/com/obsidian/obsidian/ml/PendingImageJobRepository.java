package com.obsidian.obsidian.ml;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class PendingImageJobRepository {

    private final JdbcTemplate jdbc;

    public PendingImageJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS pending_image_jobs (
                id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                note_path     TEXT NOT NULL,
                image_path    TEXT NOT NULL,
                status        TEXT NOT NULL CHECK (status IN ('PENDING','DONE','SKIPPED')),
                content_hash  TEXT,
                created_at    TIMESTAMP NOT NULL DEFAULT now(),
                processed_at  TIMESTAMP,
                UNIQUE (note_path, image_path)
            )
            """);
        jdbc.execute(
            "CREATE INDEX IF NOT EXISTS idx_pending_image_status ON pending_image_jobs(status) WHERE status = 'PENDING'");
    }

    /** Result of {@link #upsertPending}: the row's id, and whether it actually needs
     *  captioning (false when the row was already DONE — nothing to enqueue). */
    public record UpsertResult(String id, boolean needsProcessing) {}

    /** Inserts a PENDING row if (note_path, image_path) not already DONE. Returns the
     *  row's id so the caller (ImageScanService) can publish the outbox fast-path
     *  message keyed to it — see QUEUE_UNIFICATION_PLAN.md Phase 5. */
    public UpsertResult upsertPending(String notePath, String imagePath) {
        Map<String, Object> row = jdbc.queryForMap("""
            INSERT INTO pending_image_jobs(note_path, image_path, status)
            VALUES (?, ?, 'PENDING')
            ON CONFLICT (note_path, image_path) DO UPDATE
              SET status = CASE
                WHEN pending_image_jobs.status = 'DONE' THEN 'DONE'
                ELSE 'PENDING'
              END
            RETURNING id::text AS id, status
            """, notePath, imagePath);
        return new UpsertResult((String) row.get("id"), "PENDING".equals(row.get("status")));
    }

    public Optional<PendingImageJob> findById(String id) {
        List<PendingImageJob> rows = jdbc.query("""
            SELECT id, note_path, image_path, status, content_hash, created_at, processed_at
            FROM pending_image_jobs WHERE id::text = ?
            """, new JobRowMapper(), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<PendingImageJob> findPending(int batchSize) {
        // Priority = the deadline (sr_due) of the note that embeds the image. Images
        // now BLOCK flashcard generation (cards wait for captioning — see cards/FLOWS.md
        // findNotesNeedingCards), so the soonest-due notes must drain first, mirroring
        // how flashcards themselves prioritise by deadline. The INNER JOIN also drops
        // orphan jobs whose note no longer exists ("images stored by no one" are never
        // embedded; pruneOrphans() clears them daily). NULLS LAST: a note with no review
        // date is lowest priority. created_at breaks ties (stable within a deadline).
        return jdbc.query("""
            SELECT j.id, j.note_path, j.image_path, j.status, j.content_hash, j.created_at, j.processed_at
            FROM pending_image_jobs j
            JOIN notes n ON n.path = j.note_path
            WHERE j.status = 'PENDING'
            ORDER BY n.sr_due ASC NULLS LAST, j.created_at
            LIMIT ?
            """, new JobRowMapper(), batchSize);
    }

    /**
     * Deletes PENDING/SKIPPED jobs whose note no longer exists ("images stored by
     * no one"). A job row is derived state — if the note ever returns, ImageScanService
     * re-inserts it — so we DELETE rather than mark SKIPPED, which the daily
     * requeueSkipped() would otherwise revive. Returns rows removed.
     */
    public int pruneOrphans() {
        return jdbc.update("""
            DELETE FROM pending_image_jobs
            WHERE status IN ('PENDING','SKIPPED')
              AND NOT EXISTS (SELECT 1 FROM notes n WHERE n.path = pending_image_jobs.note_path)
            """);
    }

    public void markDone(String id) {
        jdbc.update(
            "UPDATE pending_image_jobs SET status = 'DONE', processed_at = now() WHERE id::text = ?", id);
    }

    public void markSkipped(String id) {
        jdbc.update(
            "UPDATE pending_image_jobs SET status = 'SKIPPED', processed_at = now() WHERE id::text = ?", id);
    }

    /** Daily safety net: gives SKIPPED jobs another chance. Returns the ids revived —
     *  the caller (ImageProcessingWorker.requeueSkipped) publishes a fresh outbox
     *  message per id so reviving a row doesn't have to wait for the (now-hourly)
     *  poll safety net to notice it. */
    public List<String> requeueSkipped() {
        return jdbc.query(
            "UPDATE pending_image_jobs SET status = 'PENDING' WHERE status = 'SKIPPED' RETURNING id::text AS id",
            (rs, rowNum) -> rs.getString("id"));
    }

    /** status → count, for the info dashboard. */
    public java.util.Map<String, Integer> countByStatus() {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        jdbc.query("SELECT status, COUNT(*) AS n FROM pending_image_jobs GROUP BY status",
            rs -> { counts.put(rs.getString("status"), rs.getInt("n")); });
        return counts;
    }

    private static class JobRowMapper implements RowMapper<PendingImageJob> {
        @Override
        public PendingImageJob mapRow(ResultSet rs, int rowNum) throws SQLException {
            PendingImageJob job = new PendingImageJob();
            job.setId(rs.getString("id"));
            job.setNotePath(rs.getString("note_path"));
            job.setImagePath(rs.getString("image_path"));
            job.setStatus(rs.getString("status"));
            job.setContentHash(rs.getString("content_hash"));
            Timestamp created = rs.getTimestamp("created_at");
            if (created != null) job.setCreatedAt(created.toInstant());
            Timestamp processed = rs.getTimestamp("processed_at");
            if (processed != null) job.setProcessedAt(processed.toInstant());
            return job;
        }
    }
}
