package com.obsidian.obsidian.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsidian.obsidian.notes.FileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mobile/PWA backend additions (architecture_plans/PWA_MOBILE_ARCH.md §8). Both
 * endpoints are session-authenticated (SecurityConfig: anyRequest authenticated).
 * nginx strips the {@code /api/} prefix, so these map relative — {@code /api/capture}
 * → {@code capture} here (same convention as NotesController).
 *
 *   POST capture        — share-target / paste-a-link → embedder /ingest (standalone).
 *   GET  review/bundle   — due notes WITH text + media URLs, so "Download for offline"
 *                          is one round-trip instead of N.
 *
 * The PWA must NOT call the embedder directly (it's loopback/internal); this thin
 * controller is the trust boundary, reusing the HTTP/1.1 client pattern that the
 * embedder (uvicorn) requires — see ResourceScanService for why h2c corrupts bodies.
 */
@RestController
public class CaptureController {

    private static final Logger log = LoggerFactory.getLogger(CaptureController.class);

    // ![[clip.mp4]] / ![[img.png]] → the same media URL renderMarkdown() emits.
    private static final Pattern EMBED = Pattern.compile("!\\[\\[(.*?)\\]\\]", Pattern.DOTALL);

    private final FileRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // HTTP_1_1 is mandatory: the embedder is uvicorn, which drops POST bodies on the
    // JDK client's default h2c upgrade attempt (yields 422). See ResourceScanService.
    private final HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3))
        .build();

    @Value("${embedder.url:http://embedder:8000}")
    private String embedderUrl;

    public CaptureController(FileRepository repository) {
        this.repository = repository;
    }

    // ── Capture ────────────────────────────────────────────────────────────────

    @PostMapping("capture")
    public ResponseEntity<Map<String, Object>> capture(@RequestBody CaptureRequest body) {
        String url = body == null ? null : body.url();
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "url required"));
        }
        try {
            // Standalone ingest: no note_path → the embedder synthesizes a note via find_home.
            String payload = objectMapper.writeValueAsString(Map.of("ref", url.trim()));
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(embedderUrl + "/ingest"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
            HttpResponse<String> r = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                log.info("[Capture] queued ingest of {}", url);
                return ResponseEntity.ok(Map.of("status", "queued", "ref", url));
            }
            log.warn("[Capture] embedder {} for {}: {}", r.statusCode(), url, r.body());
            return ResponseEntity.status(502).body(Map.of("error", "embedder " + r.statusCode()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(503).body(Map.of("error", "interrupted"));
        } catch (Exception e) {
            log.warn("[Capture] failed for {}: {}", url, e.toString());
            return ResponseEntity.status(502).body(Map.of("error", "embedder unreachable"));
        }
    }

    // ── Offline review bundle ────────────────────────────────────────────────────

    @GetMapping("review/bundle")
    public Map<String, Object> bundle(@RequestParam(defaultValue = "40") int limit) {
        FileRepository.ReviewPage page = repository.getReviewNotesPaged(0, Math.min(limit, 200));
        List<Map<String, Object>> notes = new ArrayList<>();
        for (String path : page.notes()) {
            String content = repository.getText(path);
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("path", path);
            rec.put("shortName", shortName(path));
            rec.put("content", content == null ? "" : content);
            rec.put("media", mediaUrls(content));
            notes.add(rec);
        }
        return Map.of("notes", notes, "hasMore", page.hasMore());
    }

    private static String shortName(String path) {
        String base = path.replaceAll(".*[/\\\\]", "");
        return base.replaceAll("\\.md$", "");
    }

    private static List<String> mediaUrls(String content) {
        List<String> out = new ArrayList<>();
        if (content == null) return out;
        Matcher m = EMBED.matcher(content);
        while (m.find()) {
            out.add("/api/images/" + URLEncoder.encode(m.group(1), StandardCharsets.UTF_8));
        }
        return out;
    }

    public record CaptureRequest(String url) {}
}
