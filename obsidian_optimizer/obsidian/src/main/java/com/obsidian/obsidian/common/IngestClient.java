package com.obsidian.obsidian.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ONE gate every publisher uses to submit a resource to the embedder's ingest
 * pipeline (embedder {@code POST /ingest} → {@code jobs.submit}). Before this, each
 * caller (ResourceScanService for in-place note embeds, CaptureController for shared
 * links/text) hand-rolled its own {@link HttpClient} + payload + h2c workaround —
 * three copies drifting apart (even the {@code embedder.url} default differed). This
 * centralises that: callers build a typed request, this owns the transport.
 *
 * <p>The embedder side is already single-gated at {@code jobs.submit} (MCP, the HTTP
 * endpoint, and the queue drainer all funnel through it); this is the matching
 * single gate on the Java side.
 *
 * <p>HTTP_1_1 is mandatory: the embedder is uvicorn, which drops POST bodies on the
 * JDK client's default h2c cleartext upgrade (yields a 422 "body field required").
 * See ResourceScanService history for the full diagnosis.
 */
@Component
public class IngestClient {

    private static final Logger log = LoggerFactory.getLogger(IngestClient.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3))
        .build();

    @Value("${embedder.url:http://localhost:8000}")
    private String embedderUrl;

    @Value("${ingest.submit.timeout-ms:10000}")
    private long submitTimeoutMs;

    /** A row from the embedder job registry (GET /ingest) — enough to spot failures and
     *  DEFERRALs and map them back to their capture (failure visibility + durability).
     *  {@code bundlePath} is set on a DEFERRED job so the capture can resume from it. */
    public record JobView(String jobId, String captureId, String status, String error,
                          String bundlePath) {}

    private static String txt(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asText();
    }

    /** Poll the embedder's in-memory job registry. Used to detect jobs that FAILED AFTER a
     *  successful submit (the capture was left 'processing'), so they stop being silent drops.
     *  Best-effort: any transport/parse error yields an empty list (no false failures). */
    public List<JobView> listJobs() {
        List<JobView> out = new ArrayList<>();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(embedderUrl + "/ingest"))
                .timeout(Duration.ofMillis(submitTimeoutMs))
                .GET().build();
            HttpResponse<String> r = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return out;
            JsonNode root = objectMapper.readTree(r.body());
            JsonNode jobs = root.has("jobs") ? root.get("jobs") : root;
            if (jobs != null && jobs.isArray()) {
                for (JsonNode j : jobs) {
                    out.add(new JobView(txt(j, "id"), txt(j, "capture_id"),
                                        txt(j, "status"), txt(j, "error"),
                                        txt(j, "bundle_path")));
                }
            }
        } catch (Exception e) {
            log.debug("[IngestClient] listJobs failed: {}", e.toString());
        }
        return out;
    }

    /** Outcome of a submit. {@code ok} = the embedder accepted the job (HTTP 200);
     *  {@code jobId} is the embedder job id when present. A non-ok result is the
     *  caller's cue to retry (transport/5xx) or fail the resource (4xx). */
    public record Result(boolean ok, int status, String jobId, String body) {
        public static Result unreachable() { return new Result(false, 0, null, "unreachable"); }
    }

    // ── typed helpers (the vocabulary of the pipeline) ───────────────────────────

    /** In-place: synthesize a block and inject it below {@code ref} in {@code notePath}. */
    public Result submitInPlace(String ref, String notePath) {
        return submit(Map.of("ref", ref, "note_path", notePath));
    }

    /** Standalone URL/file resource → new note(s), tracked by {@code captureId}. */
    public Result submitStandalone(String captureId, String ref, String sourceType) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("capture_id", captureId);
        p.put("ref", ref);
        if (sourceType != null) p.put("source_type", sourceType);
        return submit(p);
    }

    /** Resume a DEFERRED synthesis from its saved bundle — no re-extract. Called by the
     *  capture queue when LLM providers recover (embedder {@code POST /ingest/resume}). */
    public Result resume(String bundleRef, String captureId, String ref,
                         String sourceType, String title, String notePath) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("bundle_path", bundleRef);
        if (captureId != null)  p.put("capture_id", captureId);
        if (ref != null)        p.put("ref", ref);
        if (sourceType != null) p.put("source_type", sourceType);
        if (title != null)      p.put("title", title);
        if (notePath != null)   p.put("note_path", notePath);
        return submit(p, "/ingest/resume");
    }

    /** Standalone captured prose (no fetch) → new note(s), tracked by {@code captureId}. */
    public Result submitText(String captureId, String text, String title) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("capture_id", captureId);
        p.put("text", text);
        p.put("source_type", "text");
        if (title != null) p.put("title", title);
        return submit(p);
    }

    // ── the single transport ─────────────────────────────────────────────────────

    public Result submit(Map<String, Object> payload) {
        return submit(payload, "/ingest");
    }

    public Result submit(Map<String, Object> payload, String path) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(embedderUrl + path))
                .timeout(Duration.ofMillis(submitTimeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> r = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                return new Result(true, 200, extractJobId(r.body()), r.body());
            }
            log.warn("[IngestClient] embedder /ingest -> {}: {}", r.statusCode(), r.body());
            return new Result(false, r.statusCode(), null, r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.unreachable();
        } catch (Exception e) {
            // embedder down / slow — the caller decides whether to retry or fail.
            log.debug("[IngestClient] submit failed: {}", e.toString());
            return Result.unreachable();
        }
    }

    private String extractJobId(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String id = root.path("id").asText(null);
            return id != null ? id : root.path("job_id").asText(null);
        } catch (Exception e) {
            return null;
        }
    }
}
