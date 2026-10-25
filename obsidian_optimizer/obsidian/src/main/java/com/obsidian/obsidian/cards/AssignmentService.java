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
import java.util.concurrent.*;

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

    /** Total budget (ms) for an open answer before we answer with the provisional cosine verdict. */
    @Value("${judge.wait-ms:1000}")
    long judgeWaitMs = 1000;
    /** complete() waits at most this long for the assignment's pending refinements. */
    @Value("${judge.complete-wait-ms:8000}")
    long judgeCompleteWaitMs = 8000;

    /** Bounded: at most 4 concurrent LLM judges, 64 queued; overflow degrades to the cosine verdict. */
    private final ExecutorService judgePool = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(64), r -> {
            Thread t = new Thread(r, "judge-refine");
            t.setDaemon(true);
            return t;
        });
    /** assignment id → refinement stages still in flight. In-memory: lost on restart. */
    final ConcurrentMap<UUID, List<CompletableFuture<Void>>> pendingRefinements = new ConcurrentHashMap<>();

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

    public Map<String, Object> build(long vaultId, String scope, int requestedCap) {
        // On-demand: if this is a single note the background jobs haven't reached
        // yet (legacy state / no cards / not embedded), bring it up to date now so
        // the session has cards to draw. No-op for folders and prepared notes.
        reviewPrep.prepare(vaultId, scope);

        List<String> notes = repo.notesInScope(vaultId, scope);
        if (notes.isEmpty()) {
            throw new IllegalArgumentException("no active cards in scope: " + scope);
        }
        int sessionCap = requestedCap > 0 ? requestedCap : SESSION_MAX_CARDS;

        List<Map<String, Object>> picked = new ArrayList<>();
        ObjectNode variants = mapper.createObjectNode();
        for (String note : notes) {
            if (picked.size() >= sessionCap) break;
            int noteCap = Math.min(MAX_CARDS_PER_NOTE, sessionCap - picked.size());
            pickForNote(vaultId, note, noteCap, picked, variants);
        }

        if (picked.isEmpty()) {
            throw new IllegalArgumentException("could not fill assignment for scope: " + scope);
        }
        picked = orderCards(picked);
        int actual = picked.stream().mapToInt(c -> ((Number) c.get("difficulty")).intValue()).sum();
        UUID[] ids = picked.stream().map(c -> (UUID) c.get("id")).toArray(UUID[]::new);
        UUID id = repo.insertAssignment(vaultId, scope, requestedCap, actual, ids, variants.toString());
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

    static boolean isHard(Map<String, Object> c) {
        return "open".equals(c.get("type")) || ((Number) c.get("difficulty")).intValue() >= 3;
    }

    /**
     * Session order: alternate hard / easy (hard = difficulty >= 3 or open), starting hard,
     * open cards in the earliest hard slots so their background judges start early.
     * When one pool runs dry the rest is appended by ascending difficulty.
     */
    static List<Map<String, Object>> orderCards(List<Map<String, Object>> cards) {
        java.util.Comparator<Map<String, Object>> byDiff =
            java.util.Comparator.comparingInt(c -> ((Number) c.get("difficulty")).intValue());
        List<Map<String, Object>> hard = new ArrayList<>(cards.stream().filter(AssignmentService::isHard).toList());
        List<Map<String, Object>> easy = new ArrayList<>(cards.stream().filter(c -> !isHard(c)).toList());
        // open first (stable), then ascending difficulty within each group
        hard.sort(java.util.Comparator.<Map<String, Object>, Boolean>comparing(c -> !"open".equals(c.get("type")))
            .thenComparing(byDiff));
        easy.sort(byDiff);
        List<Map<String, Object>> out = new ArrayList<>(cards.size());
        int h = 0, e = 0;
        while (h < hard.size() && e < easy.size()) {
            out.add(hard.get(h++));
            out.add(easy.get(e++));
        }
        List<Map<String, Object>> rest = new ArrayList<>(hard.subList(h, hard.size()));
        rest.addAll(easy.subList(e, easy.size()));
        rest.sort(byDiff);
        out.addAll(rest);
        return out;
    }

    /**
     * Tiered knapsack for one note: first one card from each tier it has
     * (coverage), then fill round-robin up to MAX_PER_TIER / the note cap.
     */
    private void pickForNote(long vaultId, String note, int cap, List<Map<String, Object>> picked, ObjectNode variants) {
        List<Map<String, Object>> noteCards = new ArrayList<>();

        // Pass 1 — coverage: one card from each tier present.
        for (Tier t : Tier.values()) {
            if (noteCards.size() >= cap) break;
            drawOne(vaultId, note, t, picked, noteCards, variants);
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
                if (drawOne(vaultId, note, t, picked, noteCards, variants)) progress = true;
            }
        }
        picked.addAll(noteCards);
    }

    /** Draw one card from a note's tier bag (refilling once if exhausted), roll exercises. */
    private boolean drawOne(long vaultId, String note, Tier tier, List<Map<String, Object>> sessionPicked,
                            List<Map<String, Object>> noteCards, ObjectNode variants) {
        UUID[] exclude = java.util.stream.Stream.concat(sessionPicked.stream(), noteCards.stream())
            .map(c -> (UUID) c.get("id")).toArray(UUID[]::new);
        int cycle = repo.currentCycle(vaultId, note, tier.label);
        Map<String, Object> card = repo.drawCardInTier(vaultId, note, tier.min, tier.max, cycle, exclude);
        if (card == null) {                                  // bag exhausted — refill once
            repo.advanceCycle(vaultId, note, tier.label);
            card = repo.drawCardInTier(vaultId, note, tier.min, tier.max, repo.currentCycle(vaultId, note, tier.label), exclude);
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

    public Map<String, Object> submitAttempt(long vaultId, UUID assignmentId, UUID cardId, String answer) {
        Map<String, Object> card = repo.findCard(vaultId, cardId);
        if (card == null) throw new IllegalArgumentException("unknown card: " + cardId);
        int difficulty = ((Number) card.get("difficulty")).intValue();

        // Flagged mid-test (the flag button is available before AND after answering — see
        // FlashcardSession.jsx): skip verification (no LLM judge spend on a card whose result
        // won't count) and don't record an attempt at all. perNoteScores only sums attempts
        // that exist, so no row = excluded from both the note's score and its difficulty
        // denominator, same as if the card had never been drawn.
        if ("FLAGGED".equals(card.get("status"))) {
            return mapOfNullable("verdict", "FLAGGED", "pointsEarned", 0,
                "maxPoints", difficulty, "feedback", null);
        }

        long startNanos = System.nanoTime();
        String verdict;
        boolean judgeUsed = false;
        String feedback = null;
        String referenceAnswer = null;
        UUID provisionalId = null;
        try {
            JsonNode payload = mapper.readTree(card.get("payload").toString());
            switch ((String) card.get("type")) {
                case "mcq" -> verdict = String.valueOf(payload.get("correct").asInt())
                    .equals(answer == null ? "" : answer.trim()) ? "CORRECT" : "WRONG";
                case "exercise" -> verdict = verifyExercise(vaultId, assignmentId, cardId, payload, answer);
                case "open" -> {
                    JsonNode refs = payload.path("reference_answers");
                    referenceAnswer = refs.isArray() && refs.size() > 0 ? refs.get(0).asText() : null;
                    // Pass 1: cosine only (~0.3s). Clear band = final.
                    JsonNode judged = postJson("/flashcards/judge", judgeRequest(payload, answer, false));
                    if (judged == null) throw new IllegalStateException("judge unavailable");
                    verdict = judged.get("verdict").asText();
                    feedback = textOrNull(judged.path("feedback"));
                    boolean midBand = "PARTIAL".equals(verdict) && !judged.path("judge_used").asBoolean();
                    if (midBand) {
                        // Pass 2: full LLM judge in the background; wait for what's left of the budget.
                        CompletableFuture<JsonNode> full = startFullJudge(payload, answer);
                        long remainingMs = Math.max(0,
                            judgeWaitMs - (System.nanoTime() - startNanos) / 1_000_000);
                        JsonNode refined = null;
                        boolean late = false;
                        if (full == null) {
                            // pool saturated — stay on the cosine verdict, finalise
                        } else {
                            try {
                                refined = full.get(remainingMs, TimeUnit.MILLISECONDS);
                            } catch (TimeoutException te) {
                                late = true;
                            } catch (ExecutionException ee) {
                                log.warn("[Assignment] full judge failed: {}", ee.getCause().getMessage());
                            }
                        }
                        if (refined != null) {
                            verdict = refined.get("verdict").asText();
                            judgeUsed = refined.path("judge_used").asBoolean();
                            feedback = textOrNull(refined.path("feedback"));
                        } else if (late) {
                            int provisionalPoints = pointsFor(verdict, difficulty);
                            provisionalId = repo.insertAttempt(cardId, assignmentId, answer, verdict,
                                false, provisionalPoints, null, true);
                            registerRefinement(assignmentId, provisionalId, difficulty, full);
                            Map<String, Object> out = new java.util.LinkedHashMap<>();
                            out.put("verdict", verdict);
                            out.put("provisional", true);
                            out.put("referenceAnswer", referenceAnswer);
                            out.put("pointsEarned", provisionalPoints);
                            out.put("maxPoints", difficulty);
                            out.put("feedback", null);
                            return out;
                        }
                    }
                }
                default -> throw new IllegalStateException("unknown type");
            }
        } catch (Exception e) {
            throw new IllegalStateException("verification failed: " + e.getMessage(), e);
        }

        int points = pointsFor(verdict, difficulty);
        repo.insertAttempt(cardId, assignmentId, answer, verdict, judgeUsed, points, feedback, false);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("verdict", verdict);
        out.put("provisional", false);
        if ("open".equals(card.get("type"))) out.put("referenceAnswer", referenceAnswer);
        out.put("pointsEarned", points);
        out.put("maxPoints", difficulty);
        out.put("feedback", feedback);
        return out;
    }

    static int pointsFor(String verdict, int difficulty) {
        return switch (verdict) {
            case "CORRECT" -> difficulty;
            case "PARTIAL" -> Math.max(1, difficulty / 2);
            default -> 0;
        };
    }

    private static String textOrNull(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    /** Fire the full (LLM) judge on the bounded pool; null if the pool is saturated. */
    private CompletableFuture<JsonNode> startFullJudge(JsonNode payload, String answer) {
        try {
            return CompletableFuture.supplyAsync(
                () -> postJson("/flashcards/judge", judgeRequest(payload, answer, true)), judgePool);
        } catch (RejectedExecutionException e) {
            log.warn("[Assignment] judge pool saturated — keeping cosine verdict");
            return null;
        }
    }

    /** When the late judge lands, UPDATE the provisional attempt; track the stage for complete(). */
    private void registerRefinement(UUID assignmentId, UUID attemptId, int difficulty,
                                    CompletableFuture<JsonNode> full) {
        CompletableFuture<Void> stage = full.handle((refined, err) -> {
            try {
                if (err == null && refined != null && refined.hasNonNull("verdict")) {
                    String v = refined.get("verdict").asText();
                    repo.refineAttempt(attemptId, v, refined.path("judge_used").asBoolean(),
                        pointsFor(v, difficulty), textOrNull(refined.path("feedback")));
                }  // else: attempt stays provisional PARTIAL
            } catch (Exception e) {
                log.warn("[Assignment] refine update failed for attempt {}: {}", attemptId, e.getMessage());
            }
            return (Void) null;
        });
        pendingRefinements.computeIfAbsent(assignmentId, k -> new CopyOnWriteArrayList<>()).add(stage);
        stage.whenComplete((v, t) -> {
            List<CompletableFuture<Void>> l = pendingRefinements.get(assignmentId);
            if (l != null) {
                l.remove(stage);
                if (l.isEmpty()) pendingRefinements.remove(assignmentId, l);
            }
        });
    }

    private String verifyExercise(long vaultId, UUID assignmentId, UUID cardId, JsonNode payload, String answer)
            throws Exception {
        Map<String, Object> assignment = repo.findAssignment(vaultId, assignmentId);
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

    public Map<String, Object> complete(long vaultId, UUID assignmentId) {
        Map<String, Object> assignment = repo.findAssignment(vaultId, assignmentId);
        if (assignment == null) throw new IllegalArgumentException("unknown assignment");
        awaitPendingRefinements(assignmentId);
        List<Map<String, Object>> scores = repo.perNoteScores(assignmentId);
        List<Map<String, Object>> graded = new ArrayList<>();
        for (Map<String, Object> row : scores) {
            double score = row.get("score") == null ? 0 : ((Number) row.get("score")).doubleValue();
            ReviewService.Band band = ReviewService.Band.fromScore(score);
            var result = reviewService.grade(vaultId, (String) row.get("note_path"), band);
            graded.add(Map.of("notePath", row.get("note_path"), "score", score,
                "band", band.name(), "due", result.due()));
        }
        repo.markCompleted(assignmentId);
        return Map.of("assignmentId", assignmentId, "notes", graded);
    }

    /** Wait (bounded) for this assignment's background judges so FSRS sees refined verdicts. */
    private void awaitPendingRefinements(UUID assignmentId) {
        List<CompletableFuture<Void>> pending = pendingRefinements.get(assignmentId);
        if (pending == null || pending.isEmpty()) return;
        try {
            CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]))
                .get(judgeCompleteWaitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.info("[Assignment] {} complete(): refinements still pending after {}ms — grading with provisional",
                assignmentId, judgeCompleteWaitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // stages never complete exceptionally (handle()), nothing to do
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ObjectNode judgeRequest(JsonNode payload, String answer, boolean llm) {
        ObjectNode req = mapper.createObjectNode();
        req.put("question", payload.path("question").asText());
        req.put("answer", answer == null ? "" : answer);
        req.set("reference_answers", payload.path("reference_answers"));
        req.set("key_points", payload.path("key_points"));
        req.put("llm", llm);
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
