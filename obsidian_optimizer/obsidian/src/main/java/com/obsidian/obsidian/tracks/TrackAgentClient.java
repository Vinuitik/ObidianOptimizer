package com.obsidian.obsidian.tracks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java-side gate for the embedder's Learning Tracks agent endpoints — mini-course
 * generation now, CSV import + discovery later (see tracks/FLOWS.md). Kept separate
 * from {@link com.obsidian.obsidian.common.IngestClient}, which is scoped specifically
 * to the ingest-submission pipeline ({@code POST /ingest}) — a different resource family.
 *
 * <p>HTTP_1_1 is mandatory for the same reason as IngestClient: the embedder is uvicorn,
 * which drops POST bodies on the JDK client's default h2c cleartext upgrade.
 */
@Component
public class TrackAgentClient {

    private static final Logger log = LoggerFactory.getLogger(TrackAgentClient.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3))
        .build();

    @Value("${embedder.url:http://localhost:8000}")
    private String embedderUrl;

    @Value("${ingest.submit.timeout-ms:10000}")
    private long timeoutMs;

    /** Outcome of a call to an embedder tracks-agent endpoint. {@code ok} = HTTP 200;
     *  {@code body} is always raw JSON (the job dict, or an error object) so callers
     *  can pass it straight through to the frontend. */
    public record Result(boolean ok, int status, String body) {
        public static Result unreachable() {
            return new Result(false, 0, "{\"error\":\"embedder unreachable\"}");
        }
    }

    public Result submitMinicourse(long trackId) {
        return post("/tracks/minicourse", Map.of("track_id", trackId));
    }

    public Result pollMinicourse(String jobId) {
        return get("/tracks/minicourse/" + jobId);
    }

    public Result approveMinicourse(String jobId, List<Integer> approvedIndexes) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approved_indexes", approvedIndexes);
        return post("/tracks/minicourse/" + jobId + "/approve", payload);
    }

    // ── transport (small + reusable so later methods — importCsv, discover — just add
    //    a get()/post() call, not their own HttpClient plumbing) ────────────────────

    private Result get(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(embedderUrl + path))
                .timeout(Duration.ofMillis(timeoutMs))
                .GET().build();
            return send(req);
        } catch (Exception e) {
            log.debug("[TrackAgentClient] GET {} failed: {}", path, e.toString());
            return Result.unreachable();
        }
    }

    private Result post(String path, Map<String, Object> payload) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(embedderUrl + path))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            return send(req);
        } catch (Exception e) {
            log.debug("[TrackAgentClient] POST {} failed: {}", path, e.toString());
            return Result.unreachable();
        }
    }

    private Result send(HttpRequest req) {
        try {
            HttpResponse<String> r = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                log.warn("[TrackAgentClient] {} -> {}: {}", req.uri(), r.statusCode(), r.body());
            }
            return new Result(r.statusCode() == 200, r.statusCode(), r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.unreachable();
        } catch (Exception e) {
            log.debug("[TrackAgentClient] send failed: {}", e.toString());
            return Result.unreachable();
        }
    }
}
