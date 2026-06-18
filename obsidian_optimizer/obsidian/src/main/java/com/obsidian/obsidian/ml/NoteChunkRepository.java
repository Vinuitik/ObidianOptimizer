package com.obsidian.obsidian.ml;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

@Component
public class NoteChunkRepository {

    private final JdbcTemplate jdbc;

    public NoteChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS note_chunks (
                id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                note_path     TEXT NOT NULL,
                chunk_index   INT  NOT NULL,
                source        TEXT NOT NULL DEFAULT 'image',
                text          TEXT NOT NULL,
                embedding     vector(1024),
                content_hash  TEXT NOT NULL,
                fts_vector    TSVECTOR
            )
            """);
        // Migration: text and image chunks have independent index ranges.
        // Pre-source rows were ALL written by the image worker → default 'image' is correct.
        jdbc.execute(
            "ALTER TABLE note_chunks ADD COLUMN IF NOT EXISTS source TEXT NOT NULL DEFAULT 'image'");
        jdbc.execute(
            "ALTER TABLE note_chunks DROP CONSTRAINT IF EXISTS note_chunks_note_path_chunk_index_key");
        jdbc.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS uq_note_chunks_path_source_idx ON note_chunks(note_path, source, chunk_index)");
        jdbc.execute(
            "CREATE INDEX IF NOT EXISTS idx_note_chunks_fts ON note_chunks USING GIN(fts_vector)");
        // Provenance: for source='image' rows, which image file produced this chunk and
        // which LLM provider transcribed it. Lets the flashcard trace attribute a bad
        // description back to a specific image + provider. NULL for text chunks.
        jdbc.execute("ALTER TABLE note_chunks ADD COLUMN IF NOT EXISTS image_path TEXT");
        jdbc.execute("ALTER TABLE note_chunks ADD COLUMN IF NOT EXISTS provider TEXT");
        // ivfflat index requires rows to exist first; created lazily by EmbeddingService after bulk insert
    }

    /** Text chunks (or any chunk without image provenance). */
    public void upsertChunk(String notePath, int chunkIndex, String source, String text,
                            float[] embedding, String contentHash) {
        upsertChunk(notePath, chunkIndex, source, text, embedding, contentHash, null, null);
    }

    /** Image chunks: also records the source image file and the provider that transcribed it. */
    public void upsertChunk(String notePath, int chunkIndex, String source, String text,
                            float[] embedding, String contentHash, String imagePath, String provider) {
        jdbc.update("""
            INSERT INTO note_chunks(note_path, chunk_index, source, text, embedding, content_hash, fts_vector, image_path, provider)
            VALUES (?, ?, ?, ?, ?::vector, ?, to_tsvector('english', ?), ?, ?)
            ON CONFLICT (note_path, source, chunk_index) DO UPDATE SET
              text         = EXCLUDED.text,
              embedding    = EXCLUDED.embedding,
              content_hash = EXCLUDED.content_hash,
              fts_vector   = EXCLUDED.fts_vector,
              image_path   = EXCLUDED.image_path,
              provider     = EXCLUDED.provider
            """,
            notePath, chunkIndex, source, text, floatArrayToString(embedding), contentHash, text,
            imagePath, provider);
    }

    /** Removes chunks of one source beyond newMaxIndex to handle note shrinkage. */
    public void deleteStaleChunks(String notePath, String source, int newMaxIndex) {
        jdbc.update(
            "DELETE FROM note_chunks WHERE note_path = ? AND source = ? AND chunk_index > ?",
            notePath, source, newMaxIndex);
    }

    /** Removes chunks for notes that no longer exist in the notes index (deleted/renamed). */
    public int deleteOrphanChunks() {
        return jdbc.update(
            "DELETE FROM note_chunks WHERE NOT EXISTS (SELECT 1 FROM notes WHERE notes.path = note_chunks.note_path)");
    }

    public String getContentHash(String notePath, String source, int chunkIndex) {
        List<String> rows = jdbc.queryForList(
            "SELECT content_hash FROM note_chunks WHERE note_path = ? AND source = ? AND chunk_index = ?",
            String.class, notePath, source, chunkIndex);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Vector similarity search using pgvector cosine distance. */
    public List<NoteChunk> findByVectorSimilarity(float[] queryVec, int limit) {
        return jdbc.query("""
            SELECT note_path, chunk_index, text
            FROM note_chunks
            WHERE embedding IS NOT NULL
            ORDER BY embedding <=> ?::vector
            LIMIT ?
            """, new NoteChunkRowMapper(), floatArrayToString(queryVec), limit);
    }

    /** Full-text search using PostgreSQL ts_rank_cd (BM25-approximating). */
    public List<NoteChunk> findByTextSearch(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        return jdbc.query("""
            SELECT note_path, chunk_index, text
            FROM note_chunks
            WHERE fts_vector @@ plainto_tsquery('english', ?)
            ORDER BY ts_rank_cd(fts_vector, plainto_tsquery('english', ?)) DESC
            LIMIT ?
            """, new NoteChunkRowMapper(), query, query, limit);
    }

    /** Returns the highest chunk_index for a note within one source, or null if none exist. */
    public Integer queryMaxChunkIndex(String notePath, String source) {
        List<Integer> rows = jdbc.queryForList(
            "SELECT MAX(chunk_index) FROM note_chunks WHERE note_path = ? AND source = ?",
            Integer.class, notePath, source);
        return (rows.isEmpty() || rows.get(0) == null) ? null : rows.get(0);
    }

    /** Builds Ollama-compatible vector string: [0.1,0.2,...] */
    static String floatArrayToString(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private static class NoteChunkRowMapper implements RowMapper<NoteChunk> {
        @Override
        public NoteChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new NoteChunk(
                rs.getString("note_path"),
                rs.getInt("chunk_index"),
                rs.getString("text"),
                List.of()
            );
        }
    }
}
