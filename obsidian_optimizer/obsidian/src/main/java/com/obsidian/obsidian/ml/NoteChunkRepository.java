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
        // 768-dim = gte-base output (was 1024 for mxbai). Drop the postgres volume when
        // changing this; CREATE TABLE IF NOT EXISTS won't resize a live column.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS note_chunks (
                id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                note_path     TEXT NOT NULL,
                chunk_index   INT  NOT NULL,
                source        TEXT NOT NULL DEFAULT 'image',
                text          TEXT NOT NULL,
                embedding     vector(768),
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
        // Real BM25 keyword ranking via ParadeDB pg_search (Tantivy). Indexes the raw
        // `text` column directly; key_field must be the table PK (id). This is the index
        // findByTextSearch queries through the @@@ operator + paradedb.score().
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_search");
        jdbc.execute(
            "CREATE INDEX IF NOT EXISTS idx_note_chunks_bm25 ON note_chunks USING bm25 (id, text) WITH (key_field='id')");
        // LEGACY: stock-Postgres FTS index. No longer used by search (superseded by the
        // BM25 index above). Kept only until the fts_vector column is dropped in a
        // follow-up migration; still populated by the upsert methods for now.
        jdbc.execute(
            "CREATE INDEX IF NOT EXISTS idx_note_chunks_fts ON note_chunks USING GIN(fts_vector)");
        // ivfflat index requires rows to exist first; created lazily by EmbeddingService after bulk insert
    }

    public void upsertChunk(String notePath, int chunkIndex, String source, String text,
                            float[] embedding, String contentHash) {
        jdbc.update("""
            INSERT INTO note_chunks(note_path, chunk_index, source, text, embedding, content_hash, fts_vector)
            VALUES (?, ?, ?, ?, ?::vector, ?, to_tsvector('english', ?))
            ON CONFLICT (note_path, source, chunk_index) DO UPDATE SET
              text         = EXCLUDED.text,
              embedding    = EXCLUDED.embedding,
              content_hash = EXCLUDED.content_hash,
              fts_vector   = EXCLUDED.fts_vector
            """,
            notePath, chunkIndex, source, text, floatArrayToString(embedding), contentHash, text);
    }

    /**
     * Upsert a chunk's TEXT with no embedding yet (embedding = NULL). Used by the
     * caption stage so an expensive VLM caption is persisted immediately; the embed
     * reconciler ({@link #findChunksNeedingEmbedding}) fills the vector later. On
     * conflict the embedding is reset to NULL so a changed caption gets re-embedded.
     */
    public void upsertChunkTextOnly(String notePath, int chunkIndex, String source,
                                    String text, String contentHash) {
        jdbc.update("""
            INSERT INTO note_chunks(note_path, chunk_index, source, text, embedding, content_hash, fts_vector)
            VALUES (?, ?, ?, ?, NULL, ?, to_tsvector('english', ?))
            ON CONFLICT (note_path, source, chunk_index) DO UPDATE SET
              text         = EXCLUDED.text,
              embedding    = NULL,
              content_hash = EXCLUDED.content_hash,
              fts_vector   = EXCLUDED.fts_vector
            """,
            notePath, chunkIndex, source, text, contentHash, text);
    }

    /** Row awaiting a vector — the embed queue's unit of work. */
    public record PendingChunk(String notePath, String source, int chunkIndex, String text) {}

    /**
     * Claim a batch of chunks that still need embedding (the "embed queue"). Any
     * source (image/text) with a NULL vector qualifies, so one reconciler covers
     * every stage. Single-consumer today (one lane); add {@code FOR UPDATE SKIP
     * LOCKED} here when you want concurrent embed consumers.
     */
    public List<PendingChunk> findChunksNeedingEmbedding(int limit) {
        return jdbc.query("""
            SELECT note_path, source, chunk_index, text
            FROM note_chunks
            WHERE embedding IS NULL
            ORDER BY note_path, source, chunk_index
            LIMIT ?
            """,
            (rs, i) -> new PendingChunk(rs.getString("note_path"), rs.getString("source"),
                                        rs.getInt("chunk_index"), rs.getString("text")),
            limit);
    }

    /**
     * Set a chunk's vector, but ONLY if it is still NULL (idempotent ack). The
     * {@code embedding IS NULL} guard makes double-processing a no-op without row
     * locks. Returns true if this call actually wrote the vector.
     */
    public boolean setChunkEmbedding(String notePath, String source, int chunkIndex, float[] embedding) {
        int n = jdbc.update("""
            UPDATE note_chunks SET embedding = ?::vector
            WHERE note_path = ? AND source = ? AND chunk_index = ? AND embedding IS NULL
            """,
            floatArrayToString(embedding), notePath, source, chunkIndex);
        return n > 0;
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

    /** Text of a chunk still awaiting a vector, or null if the chunk is gone or already
     *  embedded — the outbox consumer's idempotency check (a re-delivered or
     *  superseded message is then a safe no-op instead of re-embedding). */
    public String getChunkText(String notePath, String source, int chunkIndex) {
        List<String> rows = jdbc.queryForList("""
            SELECT text FROM note_chunks
            WHERE note_path = ? AND source = ? AND chunk_index = ? AND embedding IS NULL
            """, String.class, notePath, source, chunkIndex);
        return rows.isEmpty() ? null : rows.get(0);
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
            SELECT note_path, chunk_index, source, text
            FROM note_chunks
            WHERE embedding IS NOT NULL
            ORDER BY embedding <=> ?::vector
            LIMIT ?
            """, new NoteChunkRowMapper(), floatArrayToString(queryVec), limit);
    }

    /**
     * Keyword search using real BM25 relevance (ParadeDB pg_search / Tantivy).
     * paradedb.match() tokenizes the query safely — the raw {@code @@@ 'string'}
     * syntax throws Tantivy parse errors on punctuation like "C++" or "a/b",
     * whereas match() treats the input as plain terms. Ranked by BM25 score.
     */
    public List<NoteChunk> findByTextSearch(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        return jdbc.query("""
            SELECT note_path, chunk_index, source, text
            FROM note_chunks
            WHERE id @@@ paradedb.match('text', ?)
            ORDER BY paradedb.score(id) DESC
            LIMIT ?
            """, new NoteChunkRowMapper(), query, limit);
    }

    /** Count of distinct notes that have at least one chunk of the given source —
     *  a DB-truth progress signal for one-off backfills (survives app restarts,
     *  unlike an in-memory job flag). */
    public int countDistinctNotesForSource(String source) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(DISTINCT note_path) FROM note_chunks WHERE source = ?",
            Integer.class, source);
        return n == null ? 0 : n;
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
                rs.getString("source"),
                rs.getString("text"),
                List.of()
            );
        }
    }
}
