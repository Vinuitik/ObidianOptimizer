package com.obsidian.obsidian.workspace;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Index over _workspace/ — the manual drag-and-drop media shelf the Learn page
 * browses (PDF/video/audio). Filesystem holds the bytes; this table holds
 * type/provenance/sort order so a listing never has to open every file.
 * Unrelated to ingest review — that lives in _inbox/ (see InboxController).
 */
@Repository
public class WorkspaceRepository {

    public record WorkspaceItem(String filename, String type, String sourceNote, int importance, long addedAt) {}

    private final JdbcTemplate jdbc;

    public WorkspaceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS workspace_items (
                filename     TEXT PRIMARY KEY,
                type         TEXT NOT NULL,
                source_note  TEXT,
                importance   INT NOT NULL DEFAULT 0,
                added_at     BIGINT NOT NULL
            )
            """);
    }

    /** Upsert — re-accumulating the same filename refreshes its row rather than duplicating. */
    public void insert(String filename, String type, String sourceNote, int importance) {
        jdbc.update("""
            INSERT INTO workspace_items(filename, type, source_note, importance, added_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (filename) DO UPDATE SET
                type = EXCLUDED.type, source_note = EXCLUDED.source_note,
                importance = EXCLUDED.importance, added_at = EXCLUDED.added_at
            """, filename, type, sourceNote, importance, System.currentTimeMillis());
    }

    public List<WorkspaceItem> listAll() {
        return jdbc.query("""
            SELECT filename, type, source_note, importance, added_at
            FROM workspace_items
            ORDER BY importance DESC, added_at DESC
            """, (rs, i) -> new WorkspaceItem(
                rs.getString("filename"),
                rs.getString("type"),
                rs.getString("source_note"),
                rs.getInt("importance"),
                rs.getLong("added_at")));
    }

    public void delete(String filename) {
        jdbc.update("DELETE FROM workspace_items WHERE filename = ?", filename);
    }
}
