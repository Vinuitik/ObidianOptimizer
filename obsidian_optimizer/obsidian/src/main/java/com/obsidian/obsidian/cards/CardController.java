package com.obsidian.obsidian.cards;

import com.fasterxml.jackson.databind.JsonNode;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class CardController {

    private final CardRepository cardRepo;
    private final CardGenerationService generationService;
    private final NoteIndexRepository noteIndex;

    CardController(CardRepository cardRepo, CardGenerationService generationService,
                   NoteIndexRepository noteIndex) {
        this.cardRepo = cardRepo;
        this.generationService = generationService;
        this.noteIndex = noteIndex;
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
        cardRepo.recordAttempt(req.notePath(), hash);
        JsonNode result = generationService.generateFor(req.notePath(), hash);
        if (result == null) {
            return ResponseEntity.internalServerError().body("generation failed — see backend logs");
        }
        return ResponseEntity.ok(result);
    }

    record GenerateRequest(String notePath) {}
}
