package com.obsidian.obsidian.cards;

import com.obsidian.obsidian.cards.FsrsService.FsrsState;
import com.obsidian.obsidian.cards.NoteReviewRepository.ReviewRow;
import com.obsidian.obsidian.chrono.FrontmatterRewriter;
import com.obsidian.obsidian.chrono.FrontmatterRewriter.FsrsFields;
import com.obsidian.obsidian.notes.FileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The ONLY thing that mutates FSRS state. Every write goes to both Postgres
 * (the query source) and the note frontmatter (the offline / volume-reset
 * mirror), so the two can never drift. Reads come from the DB first; if the row
 * is missing — a fresh machine, a nuked volume, an external Obsidian edit — the
 * state is hydrated back from the frontmatter mirror and the DB is backfilled.
 *
 * Spread-style moves that only shift the calendar (not memory) use
 * {@link #reschedule}: due changes, stability/difficulty do not.
 */
@Service
public class FsrsStateWriter {

    private static final Logger log = LoggerFactory.getLogger(FsrsStateWriter.class);

    private final NoteReviewRepository reviewRepo;
    private final FileRepository fileRepo;

    public FsrsStateWriter(NoteReviewRepository reviewRepo, FileRepository fileRepo) {
        this.reviewRepo = reviewRepo;
        this.fileRepo = fileRepo;
    }

    /** DB first; hydrate from the frontmatter mirror (and backfill the DB) on a miss. */
    public ReviewRow read(String notePath) {
        ReviewRow row = reviewRepo.find(notePath);
        if (row != null) return row;

        FsrsFields f = FrontmatterRewriter.readFsrs(Paths.get(notePath));
        if (f == null) return null;  // no state anywhere — first review will seed it

        Timestamp last = f.lastReview() != null
            ? Timestamp.from(f.lastReview().atStartOfDay(ZoneId.systemDefault()).toInstant())
            : Timestamp.from(Instant.now());
        Timestamp due = Timestamp.from(f.due().atStartOfDay(ZoneId.systemDefault()).toInstant());
        ReviewRow hydrated = new ReviewRow(notePath, f.stability(), f.difficulty(), 0,
            last, due, f.bucket(), f.arm());

        if (f.arm() != null && f.bucket() != null) {
            reviewRepo.upsert(notePath, f.stability(), f.difficulty(), last, due, f.bucket(), f.arm());
            log.info("[FSRS] hydrated {} from frontmatter (DB row was missing)", notePath);
        }
        return hydrated;
    }

    /** Full state write — DB upsert + frontmatter mirror. */
    public void write(String notePath, FsrsState state, Instant lastReview,
                      Timestamp due, int intervalDays, String bucket, double arm) {
        reviewRepo.upsert(notePath, state.stability(), state.difficulty(),
            Timestamp.from(lastReview), due, bucket, arm);
        mirror(notePath, new FsrsFields(toLocalDate(due), intervalDays,
            state.stability(), state.difficulty(), toLocalDate(lastReview), arm, bucket));
    }

    /** Calendar-only move (Spread): shift the due date, leave memory state intact. */
    public void reschedule(String notePath, LocalDate newDue) {
        ReviewRow row = read(notePath);
        if (row == null) return;
        Timestamp due = Timestamp.from(newDue.atStartOfDay(ZoneId.systemDefault()).toInstant());
        reviewRepo.upsert(notePath, row.stability(), row.difficulty(), row.lastReview(), due,
            row.pendingBucket(), row.pendingArm() == null ? 1.0 : row.pendingArm());
        FsrsFields f = FrontmatterRewriter.readFsrs(Paths.get(notePath));
        if (f != null) {
            mirror(notePath, new FsrsFields(newDue, f.interval(), f.stability(), f.difficulty(),
                f.lastReview(), f.arm(), f.bucket()));
        }
    }

    private void mirror(String notePath, FsrsFields fields) {
        try {
            // writeFsrs no-ops (returns false) when the note has no frontmatter
            // block — only reindex when we actually changed the file.
            if (FrontmatterRewriter.writeFsrs(Paths.get(notePath), fields)) {
                fileRepo.reindexAfterExternalWrite(notePath);
            }
        } catch (Exception e) {
            log.warn("[FSRS] frontmatter mirror failed for {}: {}", notePath, e.getMessage());
        }
    }

    private static LocalDate toLocalDate(Timestamp ts) {
        return ts.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
    private static LocalDate toLocalDate(Instant instant) {
        return instant.atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
