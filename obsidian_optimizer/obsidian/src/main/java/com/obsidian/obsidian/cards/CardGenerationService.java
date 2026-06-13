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
        try {
            String body = objectMapper.writeValueAsString(
                Map.of("note_path", notePath, "source_hash", sourceHash));
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(embedderUrl + "/flashcards/generate"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(10))   // 3 CLI calls worst case
                .POST(HttpRequest.BodyPublishers.ofString(body))
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
