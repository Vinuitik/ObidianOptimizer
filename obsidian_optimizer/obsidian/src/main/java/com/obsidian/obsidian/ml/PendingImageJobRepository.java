package com.obsidian.obsidian.ml;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

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

    /** Inserts a PENDING row if (note_path, image_path) not already DONE. */
    public void upsertPending(String notePath, String imagePath) {
        jdbc.update("""
            INSERT INTO pending_image_jobs(note_path, image_path, status)
            VALUES (?, ?, 'PENDING')
            ON CONFLICT (note_path, image_path) DO UPDATE
              SET status = CASE
                WHEN pending_image_jobs.status = 'DONE' THEN 'DONE'
                ELSE 'PENDING'
              END
            """, notePath, imagePath);
    }

    public List<PendingImageJob> findPending(int batchSize) {
        return jdbc.query("""
            SELECT id, note_path, image_path, status, content_hash, created_at, processed_at
            FROM pending_image_jobs
            WHERE status = 'PENDING'
            ORDER BY created_at
            LIMIT ?
            """, new JobRowMapper(), batchSize);
    }

    public void markDone(String id) {
        jdbc.update(
            "UPDATE pending_image_jobs SET status = 'DONE', processed_at = now() WHERE id::text = ?", id);
    }

    public void markSkipped(String id) {
        jdbc.update(
            "UPDATE pending_image_jobs SET status = 'SKIPPED', processed_at = now() WHERE id::text = ?", id);
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
