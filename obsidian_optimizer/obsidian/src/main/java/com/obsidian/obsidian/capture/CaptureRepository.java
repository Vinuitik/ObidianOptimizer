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
                          String sourcePath, String title, String status, long createdAt) {}

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS capture (
                id          TEXT    PRIMARY KEY,
                source_type TEXT    NOT NULL,   -- text | web_dom | pdf | md | audio | video
                source_ref  TEXT,               -- url / original filename / paste label
                source_path TEXT,               -- vault-relative path of the kept original
                title       TEXT,
                status      TEXT    NOT NULL DEFAULT 'processing', -- queued|processing|ready|filed|failed
                created_at  BIGINT  NOT NULL
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_capture_status ON capture(status)");
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

    public void updateStatus(String id, String status) {
        jdbc.update("UPDATE capture SET status = ? WHERE id = ?", status, id);
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
        return new Capture(
            rs.getString("id"), rs.getString("source_type"), rs.getString("source_ref"),
            rs.getString("source_path"), rs.getString("title"), rs.getString("status"),
            rs.getLong("created_at"));
    }
}
