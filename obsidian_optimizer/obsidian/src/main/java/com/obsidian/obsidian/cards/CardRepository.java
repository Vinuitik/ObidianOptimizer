package com.obsidian.obsidian.cards;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class CardRepository {

    private final JdbcTemplate jdbc;

    public CardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initSchema() {
        // Same DDL as embedder/flashcards/generate.py ensure_schema() — both
        // sides run IF NOT EXISTS so either may start first.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS cards (
              id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              note_path     TEXT NOT NULL,
              type          TEXT NOT NULL CHECK (type IN ('mcq','open','exercise')),
              payload       JSONB NOT NULL,
              difficulty    INT NOT NULL CHECK (difficulty BETWEEN 1 AND 5),
              card_hash     TEXT NOT NULL,
              source_hash   TEXT NOT NULL,
              status        TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
              drawn_cycle   INT NOT NULL DEFAULT 0,
              created_at    TIMESTAMP NOT NULL DEFAULT now(),
              UNIQUE (note_path, card_hash)
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_cards_note ON cards(note_path) WHERE status = 'ACTIVE'");
        // Attempt ledger: a note whose generation yields nothing must not be
        // retried every cycle — that burns CLI credits. One attempt per
        // (note_path, source_hash); a real edit changes the hash and re-arms it.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS card_gen_attempts (
              note_path    TEXT PRIMARY KEY,
              source_hash  TEXT NOT NULL,
              attempted_at TIMESTAMP NOT NULL DEFAULT now()
            )
            """);
    }

    /**
     * Notes that need (re)generation: review notes (sr_due set) whose current
     * content has no ACTIVE cards AND hasn't already been attempted.
     */
    public List<Map<String, Object>> findNotesNeedingCards(int limit) {
        // Keys on body_hash (frontmatter stripped), NOT content_hash: the sr-due
        // rewrite on every review and chrono's frontmatter edits change content_hash
        // but not body_hash, so cards are no longer regenerated (LLM spend) for them.
        return jdbc.queryForList("""
            SELECT n.path, n.body_hash
            FROM notes n
            WHERE n.sr_due IS NOT NULL
              AND n.body_hash IS NOT NULL
              AND n.ingest_pending = false
              AND NOT EXISTS (
                  SELECT 1 FROM pending_image_jobs j
                  WHERE j.note_path = n.path
                    AND j.status = 'PENDING')
              AND NOT EXISTS (
                  SELECT 1 FROM cards c
                  WHERE c.note_path = n.path
                    AND c.source_hash = n.body_hash
                    AND c.status = 'ACTIVE')
              AND NOT EXISTS (
                  SELECT 1 FROM card_gen_attempts a
                  WHERE a.note_path = n.path
                    AND a.source_hash = n.body_hash)
            ORDER BY n.sr_due ASC NULLS LAST, n.path
            LIMIT ?
            """, limit);
    }

    /**
     * Is this note ready for card generation right now? Mirrors the readiness
     * gate inside {@link #findNotesNeedingCards}: ingest finished and no image
     * jobs still PENDING. Used by the on-demand prep path so JIT generation
     * doesn't produce image-blind cards from a note whose preprocessing
     * (ingest → image transcription) hasn't landed — those cards would never be
     * regenerated (image text lives in note_chunks, not the note body, so
     * body_hash never changes when it arrives).
     */
    public boolean isReadyForCards(String notePath) {
        Boolean ready = jdbc.queryForObject("""
            SELECT n.ingest_pending = false
               AND NOT EXISTS (
                   SELECT 1 FROM pending_image_jobs j
                   WHERE j.note_path = n.path AND j.status = 'PENDING')
            FROM notes n
            WHERE n.path = ?
            """, Boolean.class, notePath);
        return Boolean.TRUE.equals(ready);
    }

    public void recordAttempt(String notePath, String sourceHash) {
        jdbc.update("""
            INSERT INTO card_gen_attempts(note_path, source_hash, attempted_at)
            VALUES (?, ?, now())
            ON CONFLICT (note_path) DO UPDATE SET
              source_hash = EXCLUDED.source_hash, attempted_at = now()
            """, notePath, sourceHash);
    }

    public List<Map<String, Object>> findActiveByNote(String notePath) {
        return jdbc.queryForList("""
            SELECT id, type, payload, difficulty, card_hash, created_at
            FROM cards WHERE note_path = ? AND status = 'ACTIVE'
            ORDER BY difficulty, created_at
            """, notePath);
    }

    public Map<String, Object> stats() {
        return jdbc.queryForMap("""
            SELECT COUNT(*) FILTER (WHERE status = 'ACTIVE')                       AS active_cards,
                   COUNT(*) FILTER (WHERE status = 'ARCHIVED')                     AS archived_cards,
                   COUNT(DISTINCT note_path) FILTER (WHERE status = 'ACTIVE')      AS notes_with_cards
            FROM cards
            """);
    }
}
