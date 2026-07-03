package com.obsidian.obsidian.cards;

import com.obsidian.obsidian.common.WorkerLane;
import com.obsidian.obsidian.settings.SettingsRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Drains card generation work on a schedule. No queue table: the diff between
 * notes.content_hash and cards.source_hash (minus already-attempted hashes) IS
 * the work list — covers app edits, downloads, chrono rewrites, and external
 * Obsidian edits with zero call-site hooks. Deviation from FLASHCARDS_ARCH's
 * pending_card_jobs table, documented there.
 *
 * Batch-capped per pass so a fresh vault doesn't burn a night of CLI credits
 * in one go. Only review notes (sr_due set) are eligible.
 */
@Component
public class CardJobWorker {

    private static final Logger log = LoggerFactory.getLogger(CardJobWorker.class);

    @Value("${cards.enabled:true}")
    private boolean enabled;

    @Value("${cards.batch-limit:10}")
    private int batchLimit;

    private final CardRepository cardRepo;
    private final CardGenerationService generationService;
    private final SettingsRepository settingsRepo;

    // Own lane: card generation calls the LLM (slow / rate-limited), so it must not
    // run on the shared scheduler thread where it would starve the other workers.
    private final WorkerLane lane = new WorkerLane("cards");

    public CardJobWorker(CardRepository cardRepo, CardGenerationService generationService,
                         SettingsRepository settingsRepo) {
        this.cardRepo = cardRepo;
        this.generationService = generationService;
        this.settingsRepo = settingsRepo;
    }

    @PreDestroy
    void stopLane() { lane.shutdown(); }

    /** Tick: hand the drain to the cards lane and return immediately. */
    @Scheduled(fixedDelayString = "${cards.scan.delay-ms:1800000}",
               initialDelayString = "${cards.scan.initial-delay-ms:120000}")
    public void scanAndGenerate() {
        if (!enabled) return;
        // User setting: when the review system is the self-rated list (not flashcards), stop
        // generating NEW cards. Read live so flipping the setting takes effect next tick.
        // Existing cards are never touched here — generation only, never deletion.
        if (!settingsRepo.isFlashcardsEnabled()) return;
        lane.trigger(this::drain);
    }

    /** Runs on the cards lane. One batch per tick — batch-capped so a fresh vault
     *  doesn't burn a night of credits at once; the scan delay paces retries. */
    void drain() {
        List<Map<String, Object>> pending = cardRepo.findNotesNeedingCards(batchLimit);
        if (pending.isEmpty()) return;

        log.info("[CardJobWorker] {} note(s) need cards (batch limit {})", pending.size(), batchLimit);
        for (Map<String, Object> row : pending) {
            String path = (String) row.get("path");
            // body_hash (frontmatter-stripped) is the card identity/diff key — see
            // CardRepository.findNotesNeedingCards. Stored embedder-side as source_hash.
            String hash = (String) row.get("body_hash");
            var result = generationService.generateFor(path, hash);
            // Record the attempt only when the embedder actually answered:
            // a zero-yield generation must not retry every cycle (credits),
            // but a transport failure (wrapper/embedder down — typical right
            // after boot) must NOT burn the ledger, or those notes would
            // never retry until edited.
            if (result != null) {
                cardRepo.recordAttempt(path, hash);
            }
        }
    }
}
