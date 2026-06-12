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
 * Deterministic session layer: point-budget assignments over bag draws,
 * answer verification, and completion → per-note scores → ReviewService.
 *
 * Points per card = its difficulty (1-5). Fixed budget + bag coverage keep
 * scores comparable across sessions on the same scope — that comparability
 * is what makes the FSRS grade bands meaningful.
 */
@Service
public class AssignmentService {

    private static final Logger log = LoggerFactory.getLogger(AssignmentService.class);

    @Value("${embedder.url:http://localhost:8000}")
    private String embedderUrl;

    private final AssignmentRepository repo;
    private final ReviewService reviewService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    public AssignmentService(AssignmentRepository repo, ReviewService reviewService) {
        this.repo = repo;
        this.reviewService = reviewService;
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    public Map<String, Object> build(String scope, int targetPoints) {
        List<String> types = repo.typesInScope(scope);
        if (types.isEmpty()) {
            throw new IllegalArgumentException("no active cards in scope: " + scope);
        }

        List<Map<String, Object>> picked = new ArrayList<>();
        ObjectNode variants = mapper.createObjectNode();
        int budget = targetPoints;

        // Greedy round-robin across types; each (scope,type) bag refills via cycle bump.
        int typeIdx = 0, exhaustedStreak = 0;
        while (budget >= 1 && exhaustedStreak < types.size() * 2) {
            String type = types.get(typeIdx % types.size());
            typeIdx++;
            int cycle = repo.currentCycle(scope, type);
            // final slot: try to land exactly on the remaining budget
            Integer exact = budget <= 5 ? budget : null;
            Map<String, Object> card = repo.drawCard(scope, type, cycle, exact);
            if (card == null && exact != null) {
                card = repo.drawCard(scope, type, cycle, null);   // closest available
            }
            if (card == null) {
                repo.advanceCycle(scope, type);                    // bag refilled
                card = repo.drawCard(scope, type, repo.currentCycle(scope, type), null);
            }
            if (card == null) {                                    // type truly empty
                exhaustedStreak++;
                continue;
            }
            exhaustedStreak = 0;
            int difficulty = ((Number) card.get("difficulty")).intValue();
            if (difficulty > budget && !picked.isEmpty()) {
                continue;  // overshoot guard — try another type for the remainder
            }
            if ("exercise".equals(card.get("type")) && !rollExercise(card, variants)) {
                continue;  // unrollable exercise (embedder down / bad card) — skip it
            }
            picked.add(card);
            budget -= difficulty;
        }

        if (picked.isEmpty()) {
            throw new IllegalArgumentException("could not fill assignment for scope: " + scope);
        }
        int actual = targetPoints - budget;
        UUID[] ids = picked.stream().map(c -> (UUID) c.get("id")).toArray(UUID[]::new);
        UUID id = repo.insertAssignment(scope, targetPoints, actual, ids, variants.toString());
        log.info("[Assignment] built {} scope={} cards={} points={}/{}",
            id, scope, picked.size(), actual, targetPoints);
        return Map.of("id", id, "scope", scope, "targetPoints", targetPoints,
            "actualPoints", actual, "cards", picked, "variants", variants);
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
