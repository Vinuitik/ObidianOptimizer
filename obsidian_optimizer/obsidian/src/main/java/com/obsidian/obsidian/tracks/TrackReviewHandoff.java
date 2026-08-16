package com.obsidian.obsidian.tracks;

import com.obsidian.obsidian.cards.FsrsService;
import com.obsidian.obsidian.cards.FsrsStateWriter;
import com.obsidian.obsidian.cards.NoteReviewRepository.ReviewRow;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Complete-item → FSRS handoff: reuses {@link FsrsStateWriter} exactly as
 * {@code ReviewPreparationService} does for legacy migration — no new scheduling math,
 * just flips the note into the pool the existing nightly BankruptcyService/SpreadService/
 * CardJobWorker already watch, due today.
 */
@Service
public class TrackReviewHandoff {

    private final FsrsStateWriter stateWriter;
    private final FsrsService fsrs;

    public TrackReviewHandoff(FsrsStateWriter stateWriter, FsrsService fsrs) {
        this.stateWriter = stateWriter;
        this.fsrs = fsrs;
    }

    /** Idempotent: a note already in the FSRS pool (legacy or current) is just rescheduled
     *  to today, preserving its memory state; a genuinely new note gets a fresh initial
     *  state seeded at GOOD (neutral) and due today. */
    public void seedDueToday(String notePath) {
        ReviewRow migrated = stateWriter.normalizeLegacy(notePath);
        if (migrated != null) {
            stateWriter.reschedule(notePath, LocalDate.now());
            return;
        }
        ReviewRow existing = stateWriter.read(notePath);
        if (existing != null) {
            stateWriter.reschedule(notePath, LocalDate.now());
            return;
        }
        FsrsService.FsrsState state = fsrs.initialState(FsrsService.GRADE_GOOD);
        stateWriter.writeState(notePath, state, Instant.now(), LocalDate.now(), 1);
    }
}
