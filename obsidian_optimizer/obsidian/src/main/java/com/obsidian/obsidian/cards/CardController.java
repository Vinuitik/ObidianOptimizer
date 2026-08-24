package com.obsidian.obsidian.cards;

import com.fasterxml.jackson.databind.JsonNode;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
public class CardController {

    private static final Logger log = LoggerFactory.getLogger(CardController.class);

    private final CardRepository cardRepo;
    private final CardGenerationService generationService;
    private final NoteIndexRepository noteIndex;
    private final FlaggedCardRegenService flaggedCardRegen;

    CardController(CardRepository cardRepo, CardGenerationService generationService,
                   NoteIndexRepository noteIndex, FlaggedCardRegenService flaggedCardRegen) {
        this.cardRepo = cardRepo;
        this.generationService = generationService;
        this.noteIndex = noteIndex;
        this.flaggedCardRegen = flaggedCardRegen;
    }

    @GetMapping("cards")
    public List<Map<String, Object>> listCards(@RequestParam String notePath) {
        return cardRepo.findActiveByNote(notePath);
    }

    @GetMapping("cards/stats")
    public Map<String, Object> stats() {
        return cardRepo.stats();
    }

    /** Force generation for one note, synchronously (bypasses the worker batch). */
    @PostMapping("cards/generate")
    public ResponseEntity<?> generate(@RequestBody GenerateRequest req) {
        String hash = noteIndex.getContentHash(req.notePath());
        if (hash == null) {
            return ResponseEntity.badRequest().body("note not indexed: " + req.notePath());
        }
        JsonNode result = generationService.generateFor(req.notePath(), hash);
        if (result == null) {
            return ResponseEntity.internalServerError().body("generation failed — see backend logs");
        }
        cardRepo.recordAttempt(req.notePath(), hash);
        return ResponseEntity.ok(result);
    }

    /**
     * Flag a card as bad (with an optional reason). Quarantines it out of the draw pool
     * immediately; kicks off a feedback-aware replacement right away (fire-and-forget, so
     * this request returns as fast as it always has) instead of waiting for the nightly
     * chrono sweep — that sweep still runs as a retry net if this fails transiently.
     */
    @PostMapping("cards/{id}/flag")
    public ResponseEntity<?> flag(@PathVariable UUID id, @RequestBody(required = false) FlagRequest req) {
        String notePath = cardRepo.flag(id, req == null ? null : req.reason());
        if (notePath == null) {
            return ResponseEntity.badRequest().body("card not found or already flagged: " + id);
        }
        CompletableFuture.runAsync(() -> {
            try {
                flaggedCardRegen.regenerateNote(notePath);
            } catch (Exception e) {
                log.warn("[Card] immediate regen failed for {}: {} (nightly sweep will retry)",
                    notePath, e.getMessage());
            }
        });
        return ResponseEntity.ok(Map.of("id", id, "status", "FLAGGED"));
    }

    record GenerateRequest(String notePath) {}
    record FlagRequest(String reason) {}
}
