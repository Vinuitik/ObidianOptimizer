package com.obsidian.obsidian.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsidian.obsidian.cards.CardRepository;
import com.obsidian.obsidian.ml.PendingImageJobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight count aggregates for the info dashboard (INFO_DASHBOARD_ARCH).
 * Polled every few seconds by the frontend — every query here must stay an
 * indexed COUNT, never a row fetch.
 */
@RestController
public class StatsController {

    private final JdbcTemplate jdbc;
    private final CardRepository cardRepo;
    private final PendingImageJobRepository imageJobRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();
    // HTTP_1_1: uvicorn (embedder/wrapper) can't do the JDK client's default h2c
    // upgrade, which corrupts POST bodies. See ResourceScanService for the detail.
    private final HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    @Value("${wrapper.url:http://host.docker.internal:5001}")
    private String wrapperUrl;

    @Value("${embedder.url:http://embedder:8000}")
    private String embedderUrl;

    public StatsController(JdbcTemplate jdbc,
                           CardRepository cardRepo,
                           PendingImageJobRepository imageJobRepo) {
        this.jdbc = jdbc;
        this.cardRepo = cardRepo;
        this.imageJobRepo = imageJobRepo;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();

        // 1. Vector embedding progress — a note counts as embedded when its
        //    current content_hash equals embedded_hash (NoteEmbeddingWorker)
        long notesTotal    = count("SELECT COUNT(*) FROM notes");
        long notesEmbedded = count(
            "SELECT COUNT(*) FROM notes WHERE embedded_hash IS NOT NULL AND embedded_hash = content_hash");
        long chunksTotal   = count("SELECT COUNT(*) FROM note_chunks");
        out.put("embedding", Map.of(
            "notesTotal", notesTotal,
            "notesEmbedded", Math.min(notesEmbedded, notesTotal),
            "chunksTotal", chunksTotal));

        // 2. Image pipeline queue (the multi-day, rate-limited operation)
        Map<String, Integer> byStatus = imageJobRepo.countByStatus();
        out.put("images", Map.of(
            "pending", byStatus.getOrDefault("PENDING", 0),
            "done",    byStatus.getOrDefault("DONE", 0),
            "skipped", byStatus.getOrDefault("SKIPPED", 0)));

        // 3. Flashcard generation coverage — eligible = notes with sr_due set
        long eligible = count("SELECT COUNT(*) FROM notes WHERE sr_due IS NOT NULL");
        Map<String, Object> cardStats = cardRepo.stats();
        out.put("flashcards", Map.of(
            "eligibleNotes",  eligible,
            "notesWithCards", ((Number) cardStats.get("notes_with_cards")).longValue(),
            "activeCards",    ((Number) cardStats.get("active_cards")).longValue(),
            "archivedCards",  ((Number) cardStats.get("archived_cards")).longValue()));

        // 4. Video & external resource queue — ingest agent jobs (embedder)
        out.put("resources", ingestJobs());

        // 5. LLM provider health, proxied from the host wrapper's router
        out.put("wrapper", wrapperProviders());

        return out;
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private Map<String, Object> ingestJobs() {
        Map<String, Object> res = new HashMap<>();
        res.put("implemented", true);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(embedderUrl + "/ingest"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
            HttpResponse<String> r = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                JsonNode jobs = objectMapper.readTree(r.body()).path("jobs");
                Map<String, Integer> byStatus = new HashMap<>();
                for (JsonNode j : jobs) {
                    byStatus.merge(j.path("status").asText("?"), 1, Integer::sum);
                }
                res.put("up", true);
                res.put("counts", byStatus);
                res.put("recent", jobs.size() > 5 ? null : jobs); // small payload only
                return res;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // embedder down — dashboard shows it offline
        }
        res.put("up", false);
        return res;
    }

    private Map<String, Object> wrapperProviders() {
        Map<String, Object> wrapper = new HashMap<>();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(wrapperUrl + "/providers"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                JsonNode providers = objectMapper.readTree(res.body());
                wrapper.put("up", true);
                wrapper.put("providers", providers);
                return wrapper;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // wrapper down — report and move on, dashboard shows it offline
        }
        wrapper.put("up", false);
        wrapper.put("providers", null);
        return wrapper;
    }
}
