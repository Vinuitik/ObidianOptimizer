package com.obsidian.obsidian.cards;

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

    public CardJobWorker(CardRepository cardRepo, CardGenerationService generationService) {
        this.cardRepo = cardRepo;
        this.generationService = generationService;
    }

    @Scheduled(fixedDelayString = "${cards.scan.delay-ms:1800000}",
               initialDelayString = "${cards.scan.initial-delay-ms:120000}")
    public void scanAndGenerate() {
        if (!enabled) return;

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
