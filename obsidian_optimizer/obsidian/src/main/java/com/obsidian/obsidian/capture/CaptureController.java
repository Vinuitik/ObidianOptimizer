package com.obsidian.obsidian.capture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.settings.SettingsRepository;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    private static final Pattern VIDEO_HOST = Pattern.compile(
        "(?:^|\\.)(youtube\\.com|youtu\\.be|vimeo\\.com|dailymotion\\.com)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PDF_URL   = Pattern.compile("\\.pdf(?:[?#]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AV_URL    = Pattern.compile("\\.(mp4|mov|mkv|webm|avi|mp3|m4a|wav|ogg|flac)(?:[?#]|$)", Pattern.CASE_INSENSITIVE);

    private final FileRepository repository;
    private final CaptureRepository captureRepo;
    private final SettingsRepository settingsRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // HTTP_1_1 is mandatory: the embedder is uvicorn, which drops POST bodies on the
    // JDK client's default h2c upgrade attempt (yields 422). See ResourceScanService.
    private final HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3))
        .build();

    @Value("${embedder.url:http://embedder:8000}")
    private String embedderUrl;

    public CaptureController(FileRepository repository, CaptureRepository captureRepo,
                             SettingsRepository settingsRepo) {
        this.repository = repository;
        this.captureRepo = captureRepo;
        this.settingsRepo = settingsRepo;
    }

    // ── Capture ────────────────────────────────────────────────────────────────

    @PostMapping("capture")
    public ResponseEntity<Map<String, Object>> capture(@RequestBody CaptureRequest body) {
        String url  = body == null ? null : body.url();
        String text = body == null ? null : body.text();
        boolean hasText = text != null && !text.isBlank();
        if ((url == null || url.isBlank()) && !hasText) {
            return ResponseEntity.badRequest().body(Map.of("error", "url or text required"));
        }

        String captureId = UUID.randomUUID().toString().substring(0, 12);
        try {
            Map<String, Object> ingest = new LinkedHashMap<>();
            ingest.put("capture_id", captureId);

            if (hasText) {
                // Keep the original text as a resource so Learn can show it beside the
                // proposed notes; the capture row records where it lives + lifecycle.
                String title = (body.title() != null && !body.title().isBlank())
                    ? body.title() : firstLine(text);
                String sourcePath = storeTextResource(captureId, title, text);
                captureRepo.create(captureId, "text", title, sourcePath, title);
                ingest.put("text", text);
                ingest.put("source_type", "text");
                ingest.put("title", title);
            } else {
                String ref = url.trim();
                String sourceType = classifyUrl(ref);
                captureRepo.create(captureId, sourceType, ref, null, ref);
                ingest.put("ref", ref);
                ingest.put("source_type", sourceType);
            }

            String payload = objectMapper.writeValueAsString(ingest);
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(embedderUrl + "/ingest"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
            HttpResponse<String> r = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                log.info("[Capture] queued ingest {} ({})", captureId, hasText ? "text" : url);
                return ResponseEntity.ok(Map.of("status", "queued", "captureId", captureId));
            }
            // Ingest never started → the capture is dead on arrival; mark it failed.
            captureRepo.updateStatus(captureId, "failed");
            log.warn("[Capture] embedder {} for {}: {}", r.statusCode(), captureId, r.body());
            return ResponseEntity.status(502).body(Map.of("error", "embedder " + r.statusCode()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            captureRepo.updateStatus(captureId, "failed");
            return ResponseEntity.status(503).body(Map.of("error", "interrupted"));
        } catch (Exception e) {
            captureRepo.updateStatus(captureId, "failed");
            log.warn("[Capture] failed for {}: {}", captureId, e.toString());
            return ResponseEntity.status(502).body(Map.of("error", "embedder unreachable"));
        }
    }

    /** Coarse source_type for display/grouping (the embedder router does the real
     *  routing). youtube/vimeo → video, .pdf → pdf, a/v ext → video, else web. */
    private static String classifyUrl(String url) {
        String host = "";
        try { host = URI.create(url).getHost(); } catch (Exception ignored) {}
        if (host != null && VIDEO_HOST.matcher(host).find()) return "video";
        if (PDF_URL.matcher(url).find()) return "pdf";
        if (AV_URL.matcher(url).find()) return "video";
        return "web_dom";
    }

    /** Write captured text as a markdown resource under resources/files/ (served by
     *  MediaController like any other resource). Returns the vault-relative path. */
    private String storeTextResource(String captureId, String title, String text) throws java.io.IOException {
        Path dir = Paths.get(settingsRepo.getVaultPath()).resolve("resources").resolve("files");
        Files.createDirectories(dir);
        String name = captureId + ".md";
        String content = "# " + title.replace("\n", " ").trim() + "\n\n" + text.trim() + "\n";
        Files.writeString(dir.resolve(name), content);
        return "resources/files/" + name;
    }

    private static String firstLine(String text) {
        for (String line : text.split("\n")) {
            String t = line.replaceAll("^#+\\s*", "").trim();
            if (!t.isEmpty()) return t.length() > 80 ? t.substring(0, 80) : t;
        }
        return "Captured text";
    }

    // ── Offline media download (yt-dlp, proxied to the embedder) ──────────────────
    // The embedder is loopback-only, so the browser extension can't hit it directly.
    // These thin proxies forward to the embedder's /download endpoints (the yt-dlp
    // code salvaged from the former VideoManager app).

    @PostMapping("download")
    public ResponseEntity<String> download(@RequestBody DownloadRequest body) {
        String url = body == null ? null : body.url();
        if (url == null || url.isBlank()) {
            return jsonError(400, "url required");
        }
        try {
            String payload = objectMapper.writeValueAsString(Map.of("url", url.trim()));
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(embedderUrl + "/download"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
            HttpResponse<String> r = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return jsonPassthrough(r);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return jsonError(503, "interrupted");
        } catch (Exception e) {
            log.warn("[Download] start failed for {}: {}", url, e.toString());
            return jsonError(502, "downloader unreachable");
        }
    }

    @GetMapping("download/{jobId}")
    public ResponseEntity<String> downloadStatus(@PathVariable String jobId) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(embedderUrl + "/download/" + jobId))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
            HttpResponse<String> r = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return jsonPassthrough(r);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return jsonError(503, "interrupted");
        } catch (Exception e) {
            return jsonError(502, "downloader unreachable");
        }
    }

    private static ResponseEntity<String> jsonPassthrough(HttpResponse<String> r) {
        return ResponseEntity.status(r.statusCode())
            .header("Content-Type", "application/json")
            .body(r.body());
    }

    private static ResponseEntity<String> jsonError(int status, String msg) {
        return ResponseEntity.status(status)
            .header("Content-Type", "application/json")
            .body("{\"error\":\"" + msg + "\"}");
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

    public record CaptureRequest(String url, String text, String title) {}

    public record DownloadRequest(String url) {}
}
