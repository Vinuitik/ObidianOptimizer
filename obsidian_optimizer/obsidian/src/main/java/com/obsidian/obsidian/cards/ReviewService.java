package com.obsidian.obsidian.cards;

import com.obsidian.obsidian.cards.FsrsService.FsrsState;
import com.obsidian.obsidian.cards.NoteReviewRepository.ReviewRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The grading pipeline both UI modes converge on:
 *   slideshow mode  → user presses one of the four band buttons
 *   flashcards mode → AssignmentService computes the band from the test score
 * Either way: band → FSRS grade → state update → bandit arm → due date,
 * plus the delayed reward for the PREVIOUS scheduling decision.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    /** Four user-facing bands — deliberately no Again/lapse (no fire-on-fail). */
    public enum Band {
        HARD(FsrsService.GRADE_HARD),
        GOOD(FsrsService.GRADE_GOOD),
        EASY(FsrsService.GRADE_EASY),
        VERY_EASY(FsrsService.GRADE_EASY);  // FSRS-wise Easy; distinction feeds the bandit reward

        final int fsrsGrade;
        Band(int fsrsGrade) { this.fsrsGrade = fsrsGrade; }

        /** Score → band mapping for flashcards mode (GRADE_BANDS: 40/70/90). */
        public static Band fromScore(double scoreFraction) {
            if (scoreFraction < 0.40) return HARD;
            if (scoreFraction < 0.70) return GOOD;
            if (scoreFraction < 0.90) return EASY;
            return VERY_EASY;
        }
    }

    /** Reviewing a note more than this many days after its due date is a forced lapse. */
    static final int LATE_LAPSE_DAYS = 7;

    public record GradeResult(String notePath, String band, double stability, double difficulty,
                              int baseIntervalDays, double banditArm, Timestamp due, boolean lapsed) {}

    private final FsrsService fsrs;
    private final BanditService bandit;
    private final FsrsStateWriter stateWriter;

    public ReviewService(FsrsService fsrs, BanditService bandit, FsrsStateWriter stateWriter) {
        this.fsrs = fsrs;
        this.bandit = bandit;
        this.stateWriter = stateWriter;
    }

    public GradeResult grade(String notePath, Band band) {
        return grade(notePath, band, Instant.now());
    }

    GradeResult grade(String notePath, Band band, Instant now) {
        ReviewRow existing = stateWriter.read(notePath);

        // Reviewing >7 days after the due date is a forced lapse regardless of
        // the band pressed — the note was effectively forgotten in the gap.
        boolean lapsed = existing != null
            && now.isAfter(existing.due().toInstant().plus(LATE_LAPSE_DAYS, ChronoUnit.DAYS));

        // 1. Delayed reward for the arm that scheduled THIS review:
        //    recalled = Good or better, on time. A lapse always counts as failed.
        if (existing != null && existing.pendingArm() != null) {
            boolean recalled = !lapsed && band != Band.HARD;
            bandit.reward(existing.pendingBucket(), existing.pendingArm(), recalled);
        }

        // 2. Pure FSRS state update (the bandit never touches this).
        FsrsState state;
        if (existing == null) {
            state = fsrs.initialState(band.fsrsGrade);
        } else {
            double elapsedDays = Math.max(0, ChronoUnit.SECONDS.between(
                existing.lastReview().toInstant(), now) / 86400.0);
            FsrsState prior = new FsrsState(existing.stability(), existing.difficulty());
            state = lapsed
                ? fsrs.forget(prior, elapsedDays)
                : fsrs.review(prior, band.fsrsGrade, elapsedDays);
        }

        // 3. Bandit picks the multiplier for the NEXT interval (Option A:
        //    applied to the scheduled date only).
        int baseInterval = fsrs.intervalDays(state.stability());
        String bucket = bandit.bucket(state.difficulty(), state.stability());
        double arm = bandit.chooseArm(bucket);
        long scheduledDays = Math.max(1, Math.round(baseInterval * arm));

        Timestamp due = Timestamp.from(now.plus(scheduledDays, ChronoUnit.DAYS));
        // Single write path: DB + frontmatter mirror together (FsrsStateWriter).
        stateWriter.write(notePath, state, now, due, (int) scheduledDays, bucket, arm);

        log.info("[Review] {} band={}{} S={} D={} base={}d arm={} due={}",
            notePath, band, lapsed ? " LAPSED(>7d late)" : "", String.format("%.2f", state.stability()),
            String.format("%.2f", state.difficulty()), baseInterval, arm, due);
        return new GradeResult(notePath, band.name(), state.stability(), state.difficulty(),
            baseInterval, arm, due, lapsed);
    }
}
