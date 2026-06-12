package com.obsidian.obsidian.cards;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    }

    // ── Bag draw: select-without-replacement per (scope, type) ───────────────

    public int currentCycle(String scope, String type) {
        jdbc.update("INSERT INTO scope_state(scope, type) VALUES (?, ?) ON CONFLICT DO NOTHING",
            scope, type);
        return jdbc.queryForObject(
            "SELECT current_cycle FROM scope_state WHERE scope = ? AND type = ?",
            Integer.class, scope, type);
    }

    public void advanceCycle(String scope, String type) {
        jdbc.update("UPDATE scope_state SET current_cycle = current_cycle + 1 WHERE scope = ? AND type = ?",
            scope, type);
    }

    /** One random not-yet-drawn card of this type in scope; marks it drawn. Null if bag empty. */
    public Map<String, Object> drawCard(String scope, String type, int cycle, Integer exactDifficulty) {
        String difficultyClause = exactDifficulty != null ? " AND difficulty = ? " : "";
        Object[] args = exactDifficulty != null
            ? new Object[]{cycle, type, scope, scope + "/%", exactDifficulty}
            : new Object[]{cycle, type, scope, scope + "/%"};
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, note_path, type, payload, difficulty FROM cards
            WHERE status = 'ACTIVE' AND drawn_cycle < ? AND type = ?
              AND (note_path = ? OR note_path LIKE ?)
            """ + difficultyClause + " ORDER BY random() LIMIT 1", args);
        if (rows.isEmpty()) return null;
        jdbc.update("UPDATE cards SET drawn_cycle = ? WHERE id = ?",
            cycle, rows.get(0).get("id"));
        return rows.get(0);
    }

    public List<String> typesInScope(String scope) {
        return jdbc.queryForList("""
            SELECT DISTINCT type FROM cards
            WHERE status = 'ACTIVE' AND (note_path = ? OR note_path LIKE ?)
            ORDER BY type
            """, String.class, scope, scope + "/%");
    }

    // ── Assignments / attempts ────────────────────────────────────────────────

    public UUID insertAssignment(String scope, int target, int actual,
                                 UUID[] cardIds, String variantsJson) {
        return jdbc.queryForObject("""
            INSERT INTO assignments(scope, target_points, actual_points, card_ids, variants)
            VALUES (?, ?, ?, ?, ?::jsonb) RETURNING id
            """, UUID.class, scope, target, actual, cardIds, variantsJson);
    }

    public Map<String, Object> findAssignment(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM assignments WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void markCompleted(UUID id) {
        jdbc.update("UPDATE assignments SET completed_at = now() WHERE id = ?", id);
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

    public Map<String, Object> findCard(UUID cardId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, note_path, type, payload, difficulty FROM cards WHERE id = ?", cardId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
