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
              status        TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED','FLAGGED')),
              drawn_cycle   INT NOT NULL DEFAULT 0,
              created_at    TIMESTAMP NOT NULL DEFAULT now(),
              UNIQUE (note_path, card_hash)
            )
            """);
        // Widen the status CHECK to allow FLAGGED (user-quarantined bad cards).
        // CREATE TABLE IF NOT EXISTS won't touch an existing table's constraint, so
        // pre-existing installs keep the 2-value CHECK until this idempotent migration
        // runs. FLAGGED cards leave the ACTIVE draw pool but are NOT resurrected by
        // regeneration (embedder _store preserves FLAGGED on card_hash conflict).
        jdbc.execute("ALTER TABLE cards DROP CONSTRAINT IF EXISTS cards_status_check");
        jdbc.execute("ALTER TABLE cards ADD CONSTRAINT cards_status_check "
            + "CHECK (status IN ('ACTIVE','ARCHIVED','FLAGGED'))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_cards_note ON cards(note_path) WHERE status = 'ACTIVE'");
        // Flag ledger: one row per user flag, carrying WHY the card is bad. The
        // nightly regen reads unserviced flags per note, generates one replacement
        // per flag (feedback-aware, so the agent avoids the same flaw), then marks
        // them serviced so a note isn't refilled twice for the same flags.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS card_flags (
              id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              card_id     UUID NOT NULL,
              note_path   TEXT NOT NULL,
              reason      TEXT,
              created_at  TIMESTAMP NOT NULL DEFAULT now(),
              serviced_at TIMESTAMP
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_card_flags_pending "
            + "ON card_flags(note_path) WHERE serviced_at IS NULL");
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

    /**
     * Flag a card as bad: quarantine it out of the ACTIVE draw pool and record the
     * user's reason for the nightly feedback-aware regen. Returns false if the card
     * doesn't exist / is already flagged (nothing quarantined).
     */
    public boolean flag(java.util.UUID cardId, String reason) {
        String notePath = jdbc.query(
            "SELECT note_path FROM cards WHERE id = ? AND status <> 'FLAGGED'",
            rs -> rs.next() ? rs.getString(1) : null, cardId);
        if (notePath == null) return false;
        jdbc.update("UPDATE cards SET status = 'FLAGGED' WHERE id = ?", cardId);
        jdbc.update("INSERT INTO card_flags(card_id, note_path, reason) VALUES (?, ?, ?)",
            cardId, notePath, reason);
        return true;
    }

    /**
     * Notes with unserviced flags: one row per note carrying the flag count (=
     * replacement cards to generate) and the reasons to feed the regen agent.
     * body_hash comes along so the regen records against the current note version.
     */
    public List<Map<String, Object>> findNotesWithPendingFlags() {
        return jdbc.queryForList("""
            SELECT f.note_path,
                   n.body_hash,
                   COUNT(*)                                        AS flag_count,
                   ARRAY_AGG(f.reason) FILTER (WHERE f.reason IS NOT NULL
                                               AND f.reason <> '') AS reasons
            FROM card_flags f
            JOIN notes n ON n.path = f.note_path
            WHERE f.serviced_at IS NULL
              AND n.body_hash IS NOT NULL
            GROUP BY f.note_path, n.body_hash
            """);
    }

    /** Mark a note's pending flags serviced once its replacements have been generated. */
    public int markFlagsServiced(String notePath) {
        return jdbc.update(
            "UPDATE card_flags SET serviced_at = now() WHERE note_path = ? AND serviced_at IS NULL",
            notePath);
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
