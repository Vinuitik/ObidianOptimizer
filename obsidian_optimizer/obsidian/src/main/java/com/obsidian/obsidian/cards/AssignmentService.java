package com.obsidian.obsidian.cards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic session layer: per-note tiered-knapsack assignments over bag
 * draws, answer verification, and completion → per-note scores → ReviewService.
 *
 * Selection guarantees difficulty coverage — basic / mid / advanced — per note,
 * capped so a test stays short. Because every note's score is computed over a
 * representative spread (not a random budget-greedy sample), the per-note
 * fraction maps cleanly onto an FSRS band. Points per card still = difficulty
 * (1-5), so advanced cards naturally weigh more in the score.
 */
@Service
public class AssignmentService {

    private static final Logger log = LoggerFactory.getLogger(AssignmentService.class);

    /** Difficulty tiers (inclusive). A note's test covers each tier it has cards in. */
    enum Tier {
        BASIC("basic", 1, 2), MID("mid", 3, 3), ADVANCED("advanced", 4, 5);
        final String label; final int min, max;
        Tier(String label, int min, int max) { this.label = label; this.min = min; this.max = max; }
        static Tier of(int difficulty) {
            for (Tier t : values()) if (difficulty >= t.min && difficulty <= t.max) return t;
            return ADVANCED;  // difficulty > 5 shouldn't happen; clamp into advanced
        }
    }

    static final int MAX_PER_TIER       = 2;   // at most this many cards per tier per note
    static final int MAX_CARDS_PER_NOTE = 6;   // hard cap so a single-note test stays short
    static final int SESSION_MAX_CARDS  = 30;  // folder-scope safety cap across all notes

    @Value("${embedder.url:http://localhost:8000}")
    private String embedderUrl;

    private final AssignmentRepository repo;
    private final ReviewService reviewService;
    private final ReviewPreparationService reviewPrep;
    private final ObjectMapper mapper = new ObjectMapper();
    // HTTP_1_1: uvicorn embedder can't do the JDK client's default h2c upgrade,
    // which drops POST bodies (422). See ResourceScanService for the full detail.
    private final HttpClient http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1).build();

    public AssignmentService(AssignmentRepository repo, ReviewService reviewService,
                             ReviewPreparationService reviewPrep) {
        this.repo = repo;
        this.reviewService = reviewService;
        this.reviewPrep = reviewPrep;
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    public Map<String, Object> build(String scope, int requestedCap) {
        // On-demand: if this is a single note the background jobs haven't reached
        // yet (legacy state / no cards / not embedded), bring it up to date now so
        // the session has cards to draw. No-op for folders and prepared notes.
        reviewPrep.prepare(scope);

        List<String> notes = repo.notesInScope(scope);
        if (notes.isEmpty()) {
            throw new IllegalArgumentException("no active cards in scope: " + scope);
        }
        int sessionCap = requestedCap > 0 ? requestedCap : SESSION_MAX_CARDS;

        List<Map<String, Object>> picked = new ArrayList<>();
        ObjectNode variants = mapper.createObjectNode();
        for (String note : notes) {
            if (picked.size() >= sessionCap) break;
            int noteCap = Math.min(MAX_CARDS_PER_NOTE, sessionCap - picked.size());
            pickForNote(note, noteCap, picked, variants);
        }

        if (picked.isEmpty()) {
            throw new IllegalArgumentException("could not fill assignment for scope: " + scope);
        }
        int actual = picked.stream().mapToInt(c -> ((Number) c.get("difficulty")).intValue()).sum();
        UUID[] ids = picked.stream().map(c -> (UUID) c.get("id")).toArray(UUID[]::new);
        UUID id = repo.insertAssignment(scope, requestedCap, actual, ids, variants.toString());
        // payload arrives as PGobject — hand the frontend parsed JSON, not a JDBC wrapper
        for (Map<String, Object> c : picked) {
            try {
                c.put("payload", mapper.readTree(c.get("payload").toString()));
            } catch (Exception e) {
                log.warn("[Assignment] unparseable payload on card {}", c.get("id"));
            }
        }
        log.info("[Assignment] built {} scope={} notes={} cards={} points={}",
            id, scope, notes.size(), picked.size(), actual);
        return Map.of("id", id, "scope", scope, "targetPoints", requestedCap,
            "actualPoints", actual, "cards", picked, "variants", variants);
    }

    /**
     * Tiered knapsack for one note: first one card from each tier it has
     * (coverage), then fill round-robin up to MAX_PER_TIER / the note cap.
     */
    private void pickForNote(String note, int cap, List<Map<String, Object>> picked, ObjectNode variants) {
        List<Map<String, Object>> noteCards = new ArrayList<>();

        // Pass 1 — coverage: one card from each tier present.
        for (Tier t : Tier.values()) {
            if (noteCards.size() >= cap) break;
            drawOne(note, t, picked, noteCards, variants);
        }
        // Pass 2 — fill: round-robin tiers up to MAX_PER_TIER until the cap or no progress.
        boolean progress = true;
        while (noteCards.size() < cap && progress) {
            progress = false;
            for (Tier t : Tier.values()) {
                if (noteCards.size() >= cap) break;
                long inTier = noteCards.stream()
                    .filter(c -> Tier.of(((Number) c.get("difficulty")).intValue()) == t).count();
                if (inTier >= MAX_PER_TIER) continue;
                if (drawOne(note, t, picked, noteCards, variants)) progress = true;
            }
        }
        picked.addAll(noteCards);
    }

    /** Draw one card from a note's tier bag (refilling once if exhausted), roll exercises. */
    private boolean drawOne(String note, Tier tier, List<Map<String, Object>> sessionPicked,
                            List<Map<String, Object>> noteCards, ObjectNode variants) {
        UUID[] exclude = java.util.stream.Stream.concat(sessionPicked.stream(), noteCards.stream())
            .map(c -> (UUID) c.get("id")).toArray(UUID[]::new);
        int cycle = repo.currentCycle(note, tier.label);
        Map<String, Object> card = repo.drawCardInTier(note, tier.min, tier.max, cycle, exclude);
        if (card == null) {                                  // bag exhausted — refill once
            repo.advanceCycle(note, tier.label);
            card = repo.drawCardInTier(note, tier.min, tier.max, repo.currentCycle(note, tier.label), exclude);
        }
        if (card == null) return false;                      // tier truly empty for this note
        if ("exercise".equals(card.get("type")) && !rollExercise(card, variants)) return false;
        noteCards.add(card);
        return true;
    }

    /** Roll the variant NOW and freeze params + expected answer into the assignment. */
    private boolean rollExercise(Map<String, Object> card, ObjectNode variants) {
        try {
            JsonNode payload = mapper.readTree(card.get("payload").toString());
            JsonNode rolled = postJson("/flashcards/roll",
                mapper.createObjectNode().set("payload", payload));
            if (rolled == null) return false;
            variants.set(card.get("id").toString(), rolled);
            return true;
        } catch (Exception e) {
            log.warn("[Assignment] roll failed for card {}: {}", card.get("id"), e.getMessage());
            return false;
        }
    }

    // ── Attempt verification ──────────────────────────────────────────────────

    public Map<String, Object> submitAttempt(UUID assignmentId, UUID cardId, String answer) {
        Map<String, Object> card = repo.findCard(cardId);
        if (card == null) throw new IllegalArgumentException("unknown card: " + cardId);
        int difficulty = ((Number) card.get("difficulty")).intValue();

        String verdict;
        boolean judgeUsed = false;
        String feedback = null;
        try {
            JsonNode payload = mapper.readTree(card.get("payload").toString());
            switch ((String) card.get("type")) {
                case "mcq" -> verdict = String.valueOf(payload.get("correct").asInt())
                    .equals(answer == null ? "" : answer.trim()) ? "CORRECT" : "WRONG";
                case "exercise" -> verdict = verifyExercise(assignmentId, cardId, payload, answer);
                case "open" -> {
                    JsonNode judged = postJson("/flashcards/judge", judgeRequest(payload, answer));
                    if (judged == null) throw new IllegalStateException("judge unavailable");
                    verdict = judged.get("verdict").asText();
                    judgeUsed = judged.path("judge_used").asBoolean();
                    feedback = judged.path("feedback").isNull() ? null : judged.path("feedback").asText();
                }
                default -> throw new IllegalStateException("unknown type");
            }
        } catch (Exception e) {
            throw new IllegalStateException("verification failed: " + e.getMessage(), e);
        }

        int points = switch (verdict) {
            case "CORRECT" -> difficulty;
            case "PARTIAL" -> Math.max(1, difficulty / 2);
            default -> 0;
        };
        repo.insertAttempt(cardId, assignmentId, answer, verdict, judgeUsed, points);
        return mapOfNullable("verdict", verdict, "pointsEarned", points,
            "maxPoints", difficulty, "feedback", feedback);
    }

    private String verifyExercise(UUID assignmentId, UUID cardId, JsonNode payload, String answer)
            throws Exception {
        Map<String, Object> assignment = repo.findAssignment(assignmentId);
        JsonNode variants = mapper.readTree(assignment.get("variants").toString());
        JsonNode variant = variants.get(cardId.toString());
        if (variant == null) throw new IllegalStateException("no frozen variant for card");
        JsonNode expected = variant.get("expected");

        if ("numeric".equals(payload.path("answer_kind").asText())) {
            double tolerance = payload.path("tolerance").asDouble(0);
            try {
                return Math.abs(Double.parseDouble(answer.trim()) - expected.asDouble()) <= tolerance
                    ? "CORRECT" : "WRONG";
            } catch (NumberFormatException | NullPointerException e) {
                return "WRONG";
            }
        }
        return normalize(answer).equals(normalize(expected.asText())) ? "CORRECT" : "WRONG";
    }

    // ── Completion → FSRS ─────────────────────────────────────────────────────

    public Map<String, Object> complete(UUID assignmentId) {
        Map<String, Object> assignment = repo.findAssignment(assignmentId);
        if (assignment == null) throw new IllegalArgumentException("unknown assignment");
        List<Map<String, Object>> scores = repo.perNoteScores(assignmentId);
        List<Map<String, Object>> graded = new ArrayList<>();
        for (Map<String, Object> row : scores) {
            double score = row.get("score") == null ? 0 : ((Number) row.get("score")).doubleValue();
            ReviewService.Band band = ReviewService.Band.fromScore(score);
            var result = reviewService.grade((String) row.get("note_path"), band);
            graded.add(Map.of("notePath", row.get("note_path"), "score", score,
                "band", band.name(), "due", result.due()));
        }
        repo.markCompleted(assignmentId);
        return Map.of("assignmentId", assignmentId, "notes", graded);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ObjectNode judgeRequest(JsonNode payload, String answer) {
        ObjectNode req = mapper.createObjectNode();
        req.put("question", payload.path("question").asText());
        req.put("answer", answer == null ? "" : answer);
        req.set("reference_answers", payload.path("reference_answers"));
        req.set("key_points", payload.path("key_points"));
        return req;
    }

    JsonNode postJson(String path, JsonNode body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(embedderUrl + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(3))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[Assignment] embedder {} returned {}: {}", path,
                    response.statusCode(), response.body());
                return null;
            }
            return mapper.readTree(response.body());
        } catch (Exception e) {
            log.warn("[Assignment] embedder {} failed: {}", path, e.getMessage());
            return null;
        }
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private static Map<String, Object> mapOfNullable(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
