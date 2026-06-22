package com.obsidian.obsidian.cards;

import com.fasterxml.jackson.databind.JsonNode;
import com.obsidian.obsidian.ml.EmbeddingService;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * On-demand "bring this note up to date for review" — runs the work the
 * background jobs would have done eventually, now, for a single note the user is
 * about to review in flashcards mode. Three steps, each idempotent and each
 * leaving the DB state that makes the corresponding background job skip the note
 * next run:
 *
 *   1. Migrate legacy → FSRS (timeline preserved, schedule untouched) so the
 *      nightly chrono run sees current FSRS state — {@link FsrsStateWriter#normalizeLegacy}.
 *   2. Generate cards if the note has none yet AND its preprocessing is done
 *      (ingest finished + images transcribed — {@link CardRepository#isReadyForCards}),
 *      recording the attempt against body_hash so {@code CardJobWorker} skips it.
 *   3. Chunk + embed the note ({@link EmbeddingService#indexNote}, hash-gated)
 *      so the embedding worker finds nothing to do.
 *
 * Ordering matters: ingest → image transcription → cards. We do NOT JIT-generate
 * cards for a note still mid-preprocessing — those would be image-blind and,
 * because image text lives in note_chunks (not the body), never regenerated. The
 * background worker fills them in once preprocessing lands.
 *
 * Best-effort: any step failing is logged, not fatal — the session still builds
 * from whatever cards exist, and the background jobs remain the safety net.
 */
@Service
public class ReviewPreparationService {

    private static final Logger log = LoggerFactory.getLogger(ReviewPreparationService.class);

    private final FsrsStateWriter stateWriter;
    private final CardRepository cardRepo;
    private final CardGenerationService generationService;
    private final NoteIndexRepository noteIndex;
    private final EmbeddingService embeddingService;

    public ReviewPreparationService(FsrsStateWriter stateWriter, CardRepository cardRepo,
                                    CardGenerationService generationService,
                                    NoteIndexRepository noteIndex, EmbeddingService embeddingService) {
        this.stateWriter = stateWriter;
        this.cardRepo = cardRepo;
        this.generationService = generationService;
        this.noteIndex = noteIndex;
        this.embeddingService = embeddingService;
    }

    /** Prepare one note. No-op fast path when it's already FSRS + has cards. */
    public void prepare(String notePath) {
        // 1. Legacy → FSRS, schedule preserved. Idempotent (returns existing if already FSRS).
        try {
            stateWriter.normalizeLegacy(notePath);
        } catch (Exception e) {
            log.warn("[ReviewPrep] FSRS normalize failed for {}: {}", notePath, e.getMessage());
        }

        // 2/3 only matter for a note the background jobs haven't reached yet.
        if (!cardRepo.findActiveByNote(notePath).isEmpty()) return;

        String bodyHash = noteIndex.getBodyHash(notePath);
        if (bodyHash == null) return;  // not indexed yet — nothing safe to generate against

        // Respect the preprocessing order: don't JIT cards from a note whose
        // ingest/image transcription is still pending — the background worker
        // will generate them (with image text) once preprocessing lands.
        if (!cardRepo.isReadyForCards(notePath)) {
            log.info("[ReviewPrep] {} not ready for cards (ingest/images pending) — deferring to worker", notePath);
        } else {
            try {
                JsonNode result = generationService.generateFor(notePath, bodyHash);
                if (result != null) {
                    // Record against body_hash — the exact key the worker diffs on — so it skips this note.
                    cardRepo.recordAttempt(notePath, bodyHash);
                }
            } catch (Exception e) {
                log.warn("[ReviewPrep] on-demand card generation failed for {}: {}", notePath, e.getMessage());
            }
        }

        try {
            embeddingService.indexNote(notePath);  // chunk + embed (hash-gated, marks chunks done)
        } catch (Exception e) {
            log.warn("[ReviewPrep] on-demand embed failed for {}: {}", notePath, e.getMessage());
        }
    }
}
