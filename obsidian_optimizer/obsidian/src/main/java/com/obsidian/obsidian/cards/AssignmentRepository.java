package com.obsidian.obsidian.cards;

import com.obsidian.obsidian.vaults.VaultKeys;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// attempts.card_id REFERENCES cards(id) — CardRepository.initSchema must run first.
// Without this, first boot on a FRESH database fails (alphabetical bean order
// initializes AssignmentRepository before CardRepository).
@DependsOn({"cardRepository", "vaultRepository"})
@Repository
public class AssignmentRepository {

    private final JdbcTemplate jdbc;

    public AssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS assignments (
              id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              scope         TEXT NOT NULL,
              target_points INT NOT NULL,
              actual_points INT NOT NULL,
              card_ids      UUID[] NOT NULL,
              variants      JSONB,
              completed_at  TIMESTAMP,
              created_at    TIMESTAMP NOT NULL DEFAULT now()
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS attempts (
              id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
              card_id       UUID NOT NULL REFERENCES cards(id),
              assignment_id UUID REFERENCES assignments(id),
              user_answer   TEXT,
              verdict       TEXT NOT NULL CHECK (verdict IN ('CORRECT','WRONG','PARTIAL')),
              judge_used    BOOLEAN NOT NULL DEFAULT false,
              points_earned INT NOT NULL,
              created_at    TIMESTAMP NOT NULL DEFAULT now()
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS scope_state (
              scope         TEXT NOT NULL,
              type          TEXT NOT NULL,
              current_cycle INT NOT NULL DEFAULT 1,
              PRIMARY KEY (scope, type)
            )""");
        jdbc.execute("ALTER TABLE assignments ADD COLUMN IF NOT EXISTS vault_id BIGINT NOT NULL DEFAULT 1 REFERENCES vaults(id)");
        jdbc.execute("ALTER TABLE assignments ALTER COLUMN vault_id DROP DEFAULT");
        jdbc.execute("ALTER TABLE scope_state ADD COLUMN IF NOT EXISTS vault_id BIGINT NOT NULL DEFAULT 1 REFERENCES vaults(id)");
        jdbc.execute("ALTER TABLE scope_state ALTER COLUMN vault_id DROP DEFAULT");
        VaultKeys.swapPrimaryKey(jdbc, "scope_state", "scope, type");
    }

    // ── Bag draw: select-without-replacement per (scope, type) ───────────────

    public int currentCycle(long vaultId, String scope, String type) {
        jdbc.update("INSERT INTO scope_state(scope, type, vault_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
            scope, type, vaultId);
        return jdbc.queryForObject(
            "SELECT current_cycle FROM scope_state WHERE scope = ? AND type = ? AND vault_id = ?",
            Integer.class, scope, type, vaultId);
    }

    public void advanceCycle(long vaultId, String scope, String type) {
        jdbc.update("UPDATE scope_state SET current_cycle = current_cycle + 1 WHERE scope = ? AND type = ? AND vault_id = ?",
            scope, type, vaultId);
    }

    /** One random not-yet-drawn card of this type in scope; marks it drawn. Null if bag empty. */
    public Map<String, Object> drawCard(long vaultId, String scope, String type, int cycle, Integer exactDifficulty) {
        String difficultyClause = exactDifficulty != null ? " AND difficulty = ? " : "";
        Object[] args = exactDifficulty != null
            ? new Object[]{vaultId, cycle, type, scope, scope + "/%", exactDifficulty}
            : new Object[]{vaultId, cycle, type, scope, scope + "/%"};
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, note_path, type, payload, difficulty FROM cards
            WHERE vault_id = ? AND status = 'ACTIVE' AND drawn_cycle < ? AND type = ?
              AND (note_path = ? OR note_path LIKE ?)
            """ + difficultyClause + " ORDER BY random() LIMIT 1", args);
        if (rows.isEmpty()) return null;
        jdbc.update("UPDATE cards SET drawn_cycle = ? WHERE id = ?",
            cycle, rows.get(0).get("id"));
        return rows.get(0);
    }

    public List<String> typesInScope(long vaultId, String scope) {
        return jdbc.queryForList("""
            SELECT DISTINCT type FROM cards
            WHERE vault_id = ? AND status = 'ACTIVE' AND (note_path = ? OR note_path LIKE ?)
            ORDER BY type
            """, String.class, vaultId, scope, scope + "/%");
    }

    /** Distinct notes with active cards under a scope (single note path or folder prefix). */
    public List<String> notesInScope(long vaultId, String scope) {
        return jdbc.queryForList("""
            SELECT DISTINCT note_path FROM cards
            WHERE vault_id = ? AND status = 'ACTIVE' AND (note_path = ? OR note_path LIKE ?)
            ORDER BY note_path
            """, String.class, vaultId, scope, scope + "/%");
    }

    /**
     * Bag draw within one difficulty tier of one note: a random not-yet-drawn
     * card whose difficulty is in [minDiff, maxDiff], excluding ids already
     * picked this session (so a mid-session bag refill can't repeat a card).
     * Marks it drawn. Null if the tier bag is empty at this cycle.
     */
    public Map<String, Object> drawCardInTier(long vaultId, String notePath, int minDiff, int maxDiff,
                                               int cycle, UUID[] excludeIds) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, note_path, type, payload, difficulty FROM cards
            WHERE vault_id = ? AND status = 'ACTIVE' AND note_path = ?
              AND difficulty BETWEEN ? AND ?
              AND drawn_cycle < ?
              AND NOT (id = ANY (?))
            ORDER BY random() LIMIT 1
            """, vaultId, notePath, minDiff, maxDiff, cycle, excludeIds);
        if (rows.isEmpty()) return null;
        jdbc.update("UPDATE cards SET drawn_cycle = ? WHERE id = ?", cycle, rows.get(0).get("id"));
        return rows.get(0);
    }

    // ── Assignments / attempts ────────────────────────────────────────────────

    public UUID insertAssignment(long vaultId, String scope, int target, int actual,
                                 UUID[] cardIds, String variantsJson) {
        return jdbc.queryForObject("""
            INSERT INTO assignments(scope, target_points, actual_points, card_ids, variants, vault_id)
            VALUES (?, ?, ?, ?, ?::jsonb, ?) RETURNING id
            """, UUID.class, scope, target, actual, cardIds, variantsJson, vaultId);
    }

    public Map<String, Object> findAssignment(long vaultId, UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM assignments WHERE id = ? AND vault_id = ?", id, vaultId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void markCompleted(UUID id) {
        jdbc.update("UPDATE assignments SET completed_at = now() WHERE id = ?", id);
    }

    /** Has this card already been attempted in this assignment? Lets the mailbox consumer
     *  replay an assignment event idempotently (a retried file re-submits nothing). */
    public boolean attemptExists(UUID assignmentId, UUID cardId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM attempts WHERE assignment_id = ? AND card_id = ?",
            Integer.class, assignmentId, cardId);
        return n != null && n > 0;
    }

    public void insertAttempt(UUID cardId, UUID assignmentId, String answer,
                              String verdict, boolean judgeUsed, int pointsEarned) {
        jdbc.update("""
            INSERT INTO attempts(card_id, assignment_id, user_answer, verdict, judge_used, points_earned)
            VALUES (?, ?, ?, ?, ?, ?)
            """, cardId, assignmentId, answer, verdict, judgeUsed, pointsEarned);
    }

    /** Per-note score over this assignment: earned/possible grouped by source note. */
    public List<Map<String, Object>> perNoteScores(UUID assignmentId) {
        return jdbc.queryForList("""
            SELECT c.note_path,
                   SUM(a.points_earned)::float / NULLIF(SUM(c.difficulty), 0) AS score
            FROM attempts a JOIN cards c ON c.id = a.card_id
            WHERE a.assignment_id = ?
            GROUP BY c.note_path
            """, assignmentId);
    }

    public Map<String, Object> findCard(long vaultId, UUID cardId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, note_path, type, payload, difficulty, status FROM cards WHERE id = ? AND vault_id = ?", cardId, vaultId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
