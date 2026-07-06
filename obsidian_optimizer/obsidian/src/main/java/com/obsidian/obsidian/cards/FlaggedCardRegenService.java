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
 * Nightly refill for user-flagged cards. When a card is flagged as bad it leaves
 * the ACTIVE draw pool immediately, which thins the note's test. This pass keeps
 * the count consistent: one feedback-aware replacement per flagged card, with the
 * user's "why it's bad" reasons handed to the generation agent so it avoids the
 * same flaw. Runs inside the chrono lane (ChronoService), not on its own schedule.
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
            String path = (String) row.get("note_path");
            String hash = (String) row.get("body_hash");
            int count = ((Number) row.get("flag_count")).intValue();
            List<String> reasons = reasonsOf(row.get("reasons"));

            var result = generationService.regenerate(path, hash, count, reasons);
            // Only mark serviced when the embedder actually answered — a transport
            // failure (wrapper down) must leave the flags pending so the next run
            // retries, mirroring CardJobWorker's recordAttempt gating.
            if (result != null) {
                cardRepo.markFlagsServiced(path);
                notes++;
                requested += count;
                stored += result.path("stored").asInt();
            }
        }
        if (notes > 0) {
            log.info("[FlaggedCardRegen] refilled {} note(s): {} replacement(s) requested, {} stored",
                notes, requested, stored);
        }
        return new RegenResult(notes, requested, stored);
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
