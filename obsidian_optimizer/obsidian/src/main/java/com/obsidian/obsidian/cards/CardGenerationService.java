package com.obsidian.obsidian.cards;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Thin client for the embedder's card-generation agent. The embedder reads the
 * note from its read-only /vault mount and routes LLM calls through the
 * host-wrapper's claude CLI endpoint (subscription credits, not API).
 */
@Service
public class CardGenerationService {

    private static final Logger log = LoggerFactory.getLogger(CardGenerationService.class);

    @Value("${embedder.url:http://localhost:8000}")
    private String embedderUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();
    // HTTP_1_1: uvicorn embedder can't do the JDK client's default h2c upgrade,
    // which drops POST bodies (422). See ResourceScanService for the full detail.
    private final HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1).build();

    /** Returns the embedder's result summary, or null on failure (logged). */
    public JsonNode generateFor(String notePath, String sourceHash) {
        return post(objectMapper.createObjectNode()
            .put("note_path", notePath).put("source_hash", sourceHash), notePath);
    }

    /**
     * Feedback-aware replacement generation (nightly regen for flagged cards): asks
     * the agent for {@code targetCount} fresh cards, feeding it the user's flag
     * reasons so the replacements avoid the same flaws. Keeps a note's card count
     * consistent — one replacement per flagged card. Returns null on failure.
     */
    public JsonNode regenerate(String notePath, String sourceHash, int targetCount, java.util.List<String> feedback) {
        var body = objectMapper.createObjectNode()
            .put("note_path", notePath).put("source_hash", sourceHash)
            .put("target_count", Math.max(1, targetCount));
        var arr = body.putArray("feedback");
        if (feedback != null) feedback.stream().filter(r -> r != null && !r.isBlank()).forEach(arr::add);
        return post(body, notePath);
    }

    private JsonNode post(com.fasterxml.jackson.databind.node.ObjectNode body, String notePath) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(embedderUrl + "/flashcards/generate"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(10))   // 3 CLI calls worst case
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[CardGeneration] embedder returned {} for {}: {}",
                    response.statusCode(), notePath, response.body());
                return null;
            }
            JsonNode result = objectMapper.readTree(response.body());
            log.info("[CardGeneration] {} → stored={} archived={} selfCheckDropped={} rejected={}",
                notePath, result.path("stored").asInt(), result.path("archived").asInt(),
                result.path("self_check_dropped").asInt(), result.path("rejected").asInt());
            return result;
        } catch (Exception e) {
            log.warn("[CardGeneration] failed for {}: {}", notePath, e.getMessage());
            return null;
        }
    }
}
