package com.obsidian.obsidian.cards;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * FSRS state per note (NOT per card — see FLASHCARDS_ARCH "FSRS layer").
 * pending_bucket/pending_arm carry the bandit's last scheduling decision until
 * the next review delivers its delayed reward.
 */
@Repository
public class NoteReviewRepository {

    private final JdbcTemplate jdbc;

    public NoteReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initSchema() {
        // No lapses column on purpose: there is no Again grade (no fire-on-fail).
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS note_reviews (
              note_path      TEXT PRIMARY KEY,
              stability      REAL NOT NULL,
              difficulty     REAL NOT NULL,
              reps           INT  NOT NULL DEFAULT 0,
              last_review    TIMESTAMP NOT NULL,
              due            TIMESTAMP NOT NULL,
              pending_bucket TEXT,
              pending_arm    REAL
            )
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_note_reviews_due ON note_reviews(due)");
    }

    public record ReviewRow(String notePath, double stability, double difficulty, int reps,
                            Timestamp lastReview, Timestamp due,
                            String pendingBucket, Double pendingArm) {}

    public ReviewRow find(String notePath) {
        List<ReviewRow> rows = jdbc.query(
            "SELECT * FROM note_reviews WHERE note_path = ?",
            (rs, i) -> new ReviewRow(
                rs.getString("note_path"), rs.getDouble("stability"), rs.getDouble("difficulty"),
                rs.getInt("reps"), rs.getTimestamp("last_review"), rs.getTimestamp("due"),
                rs.getString("pending_bucket"),
                // pending_arm is a Postgres REAL → JDBC hands back a java.lang.Float,
                // so a straight (Double) cast throws ClassCastException whenever the
                // arm is set (which is every note that's been reviewed once). Widen
                // through Number so both null and Float are handled.
                rs.getObject("pending_arm") instanceof Number n ? n.doubleValue() : null),
            notePath);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void upsert(String notePath, double stability, double difficulty,
                       Timestamp lastReview, Timestamp due, String pendingBucket, Double pendingArm) {
        jdbc.update("""
            INSERT INTO note_reviews(note_path, stability, difficulty, reps, last_review, due,
                                     pending_bucket, pending_arm)
            VALUES (?, ?, ?, 1, ?, ?, ?, ?)
            ON CONFLICT (note_path) DO UPDATE SET
              stability = EXCLUDED.stability, difficulty = EXCLUDED.difficulty,
              reps = note_reviews.reps + 1,
              last_review = EXCLUDED.last_review, due = EXCLUDED.due,
              pending_bucket = EXCLUDED.pending_bucket, pending_arm = EXCLUDED.pending_arm
            """, notePath, stability, difficulty, lastReview, due, pendingBucket, pendingArm);
    }

    /** Notes due now, most overdue first. */
    public List<Map<String, Object>> findDue(int limit) {
        return jdbc.queryForList("""
            SELECT note_path, stability, difficulty, reps, last_review, due
            FROM note_reviews
            WHERE due <= now()
            ORDER BY due ASC
            LIMIT ?
            """, limit);
    }

    public void delete(String notePath) {
        jdbc.update("DELETE FROM note_reviews WHERE note_path = ?", notePath);
    }
}
