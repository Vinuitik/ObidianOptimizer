package com.obsidian.obsidian.cards;

import com.obsidian.obsidian.settings.SettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Refill for user-flagged cards. When a card is flagged as bad it leaves the ACTIVE
 * draw pool immediately, which thins the note's test. This keeps the count consistent:
 * one feedback-aware replacement per flagged card, with the user's "why it's bad"
 * reasons handed to the generation agent so it avoids the same flaw.
 *
 * Two entry points: {@link #regenerateNote} fires immediately (fire-and-forget from
 * {@code CardController.flag()}) so the replacement lands within seconds/minutes; {@link
 * #run} is the nightly sweep (inside the chrono lane, ChronoService) that catches
 * anything the immediate path missed (a transport failure, or a flag from mid-flight
 * mailbox replay).
 */
@Service
public class FlaggedCardRegenService {

    private static final Logger log = LoggerFactory.getLogger(FlaggedCardRegenService.class);

    @Value("${cards.enabled:true}")
    private boolean cardsEnabled;

    private final CardRepository cardRepo;
    private final CardGenerationService generationService;
    private final SettingsRepository settingsRepo;

    public FlaggedCardRegenService(CardRepository cardRepo, CardGenerationService generationService,
                                   SettingsRepository settingsRepo) {
        this.cardRepo = cardRepo;
        this.generationService = generationService;
        this.settingsRepo = settingsRepo;
    }

    public record RegenResult(int notes, int replacementsRequested, int stored) {}

    public RegenResult run() {
        // Same gate as CardJobWorker: no generation when cards are off or the review
        // system is the self-rated list. Flags stay pending (unserviced) until re-enabled.
        if (!cardsEnabled || !settingsRepo.isFlashcardsEnabled()) {
            return new RegenResult(0, 0, 0);
        }
        List<Map<String, Object>> pending = cardRepo.findNotesWithPendingFlags();
        if (pending.isEmpty()) return new RegenResult(0, 0, 0);

        int notes = 0, requested = 0, stored = 0;
        for (Map<String, Object> row : pending) {
            RegenResult r = tryRegen(row);
            notes += r.notes(); requested += r.replacementsRequested(); stored += r.stored();
        }
        if (notes > 0) {
            log.info("[FlaggedCardRegen] refilled {} note(s): {} replacement(s) requested, {} stored",
                notes, requested, stored);
        }
        return new RegenResult(notes, requested, stored);
    }

    /**
     * Regen a single note's pending flags right away, instead of waiting for the nightly
     * sweep above. Called from {@code CardController.flag()} fire-and-forget, right after a
     * card is flagged, so the replacement lands in seconds/minutes rather than by 2am. The
     * nightly {@link #run()} sweep stays in place as a retry net for a transport failure here.
     */
    public RegenResult regenerateNote(String notePath) {
        if (!cardsEnabled || !settingsRepo.isFlashcardsEnabled()) {
            return new RegenResult(0, 0, 0);
        }
        Map<String, Object> row = cardRepo.findPendingFlagsForNote(notePath);
        if (row == null) return new RegenResult(0, 0, 0);
        RegenResult r = tryRegen(row);
        if (r.notes() > 0) {
            log.info("[FlaggedCardRegen] immediate refill for {}: {} replacement(s) requested, {} stored",
                notePath, r.replacementsRequested(), r.stored());
        }
        return r;
    }

    private RegenResult tryRegen(Map<String, Object> row) {
        String path = (String) row.get("note_path");
        String hash = (String) row.get("body_hash");
        int count = ((Number) row.get("flag_count")).intValue();
        List<String> reasons = reasonsOf(row.get("reasons"));

        var result = generationService.regenerate(path, hash, count, reasons);
        // Only mark serviced when the embedder actually answered — a transport failure
        // (wrapper down) must leave the flags pending so the nightly sweep retries,
        // mirroring CardJobWorker's recordAttempt gating.
        if (result == null) return new RegenResult(0, count, 0);
        cardRepo.markFlagsServiced(path);
        return new RegenResult(1, count, result.path("stored").asInt());
    }

    /** Postgres ARRAY_AGG arrives as a java.sql.Array; unwrap to a List<String>. */
    private static List<String> reasonsOf(Object aggregated) {
        List<String> out = new ArrayList<>();
        if (aggregated instanceof java.sql.Array sqlArray) {
            try {
                Object arr = sqlArray.getArray();
                if (arr instanceof Object[] elems) {
                    for (Object e : elems) if (e != null) out.add(e.toString());
                }
            } catch (Exception e) {
                log.warn("[FlaggedCardRegen] could not read reasons array: {}", e.getMessage());
            }
        }
        return out;
    }
}
