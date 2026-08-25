package com.obsidian.obsidian.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.tracks.TrackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mobile/PWA backend additions (architecture_plans/PWA_MOBILE_ARCH.md §8). Both
 * endpoints are session-authenticated (SecurityConfig: anyRequest authenticated).
 * nginx strips the {@code /api/} prefix, so these map relative — {@code /api/capture}
 * → {@code capture} here (same convention as NotesController).
 *
 *   POST capture        — share-target / paste-a-link OR typed raw text → embedder /ingest.
 *   POST capture/file    — share a PDF / video / audio FILE → stored + ingested (standalone).
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
    // A dedicated playlist LISTING page (e.g. youtube.com/playlist?list=...) — deliberately
    // NOT any URL that merely carries a list= param, since a shared /watch?v=...&list=...
    // link is a single video the user meant to capture, not "expand the whole playlist".
    private static final Pattern PLAYLIST_PATH = Pattern.compile("/playlist(?:[/?]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST_PARAM    = Pattern.compile("[?&]list=", Pattern.CASE_INSENSITIVE);

    private final FileRepository repository;
    private final CaptureRepository captureRepo;
    private final CaptureIngestWorker ingestWorker;
    private final SettingsRepository settingsRepo;
    private final TrackRepository trackRepo;
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
                             CaptureIngestWorker ingestWorker, SettingsRepository settingsRepo,
                             TrackRepository trackRepo) {
        this.repository = repository;
        this.captureRepo = captureRepo;
        this.ingestWorker = ingestWorker;
        this.settingsRepo = settingsRepo;
        this.trackRepo = trackRepo;
    }

    /** Resolve the Learning Track a fresh capture should tag its resulting notes into
     *  (tracks/FLOWS.md Phase 1b): an explicit trackId wins; a non-blank newTrackTitle
     *  creates the track on the fly (source='manual', same as the Tracks UI) and uses its
     *  id. Neither set → null (today's exact untagged behavior). */
    private Long resolveTrackId(Long trackId, String newTrackTitle, String newTrackType) {
        if (newTrackTitle != null && !newTrackTitle.isBlank()) {
            String type = (newTrackType == null || newTrackType.isBlank()) ? "custom" : newTrackType.trim();
            return trackRepo.create(newTrackTitle.trim(), type, "manual").id();
        }
        return trackId;
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
            Long trackId = resolveTrackId(body.trackId(), body.newTrackTitle(), body.newTrackType());
            // Persist the resource into the durable ingest queue, then nudge the worker.
            // Enqueue-and-drain (not fire-and-forget HTTP) means a resource captured while
            // the embedder is down survives a restart and is submitted once it returns —
            // the embedder's own job queue is in-memory. Submission itself goes through the
            // one IngestClient gate, inside CaptureIngestWorker.
            if (hasText) {
                // Keep the original text as a resource so Learn can show it beside the
                // proposed notes; the worker reads it back for the embedder's text route.
                String title = (body.title() != null && !body.title().isBlank())
                    ? body.title() : firstLine(text);
                String sourcePath = storeTextResource(captureId, title, text);
                captureRepo.enqueue(captureId, "text", title, sourcePath, title);
            } else {
                String ref = url.trim();
                if (isPlaylistUrl(ref)) {
                    return capturePlaylist(ref);
                }
                // Misclick / re-share guard: if this exact link/file is already in the pipeline,
                // reject LOUDLY instead of making a duplicate set of notes.
                if (captureRepo.existsLiveForSource(ref)) {
                    log.info("[Capture] duplicate ignored: {}", ref);
                    return ResponseEntity.status(409).body(Map.of(
                        "error", "already captured", "duplicate", true,
                        "message", "Already in your inbox — not capturing it again."));
                }
                captureRepo.enqueue(captureId, classifyUrl(ref), ref, null, ref);
            }
            if (trackId != null) captureRepo.setTrackId(captureId, trackId);
            ingestWorker.nudge();
            log.info("[Capture] enqueued {} ({})", captureId, hasText ? "text" : url);
            return ResponseEntity.ok(Map.of("status", "queued", "captureId", captureId));
        } catch (Exception e) {
            captureRepo.updateStatus(captureId, "failed");
            log.warn("[Capture] enqueue failed for {}: {}", captureId, e.toString());
            return ResponseEntity.status(500).body(Map.of("error", "could not queue resource"));
        }
    }

    /** A dedicated playlist LISTING page for a video host (host-checked, not any URL that
     *  happens to carry a list= param — a shared /watch?v=...&list=... link is a single
     *  video the user meant to capture, not "expand the whole playlist"). */
    static boolean isPlaylistUrl(String url) {
        String host = "";
        try { host = URI.create(url).getHost(); } catch (Exception ignored) {}
        if (host == null || !VIDEO_HOST.matcher(host).find()) return false;
        return PLAYLIST_PATH.matcher(url).find() && LIST_PARAM.matcher(url).find();
    }

    /** Expand a playlist URL into individual queued captures — one durable capture row
     *  per video, sharing a playlistId — instead of a single row. Listing (no download)
     *  happens on the embedder, where yt-dlp already lives (download/downloader.py
     *  list_playlist_entries); each row then rides the SAME drain worker + single-worker
     *  ingest queue as any other capture, so videos download and get noted one at a time,
     *  each in its own capture-id folder, without the caller waiting for the playlist. */
    private ResponseEntity<Map<String, Object>> capturePlaylist(String playlistUrl) {
        List<Map<String, String>> entries;
        try {
            String payload = objectMapper.writeValueAsString(Map.of("url", playlistUrl));
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(embedderUrl + "/playlist/expand"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
            HttpResponse<String> r = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                log.warn("[Capture] playlist expand rejected ({}) for {}", r.statusCode(), playlistUrl);
                return ResponseEntity.status(422).body(Map.of(
                    "error", "could not expand playlist",
                    "message", "That link doesn't look like a playlist the downloader can list."));
            }
            entries = new ArrayList<>();
            for (JsonNode e : objectMapper.readTree(r.body()).path("entries")) {
                entries.add(Map.of("url", e.path("url").asText(), "title", e.path("title").asText()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(503).body(Map.of("error", "interrupted"));
        } catch (Exception e) {
            log.warn("[Capture] playlist expand failed for {}: {}", playlistUrl, e.toString());
            return ResponseEntity.status(502).body(Map.of("error", "downloader unreachable"));
        }

        String playlistId = UUID.randomUUID().toString().substring(0, 12);
        int queued = 0, skipped = 0;
        for (int i = 0; i < entries.size(); i++) {
            String videoUrl = entries.get(i).get("url");
            String title = entries.get(i).get("title");
            if (videoUrl == null || videoUrl.isBlank()) continue;
            if (captureRepo.existsLiveForSource(videoUrl)) { skipped++; continue; }
            String childId = UUID.randomUUID().toString().substring(0, 12);
            captureRepo.enqueuePlaylistItem(childId, classifyUrl(videoUrl), videoUrl, null, title,
                playlistId, i);
            queued++;
        }
        ingestWorker.nudge();
        log.info("[Capture] playlist {} expanded: {} queued, {} already in pipeline",
            playlistId, queued, skipped);
        return ResponseEntity.ok(Map.of(
            "status", "queued", "playlistId", playlistId, "count", queued, "skipped", skipped));
    }

    /**
     * Share a FILE into the PWA (Android share-sheet → a PDF / video / audio file) → ingest.
     * The share-sheet path in {@code public/sw.js} POSTs the shared bytes here as multipart;
     * we persist them under {@code resources/files/} and enqueue a standalone capture, so the
     * same ingest→Learn-inbox pipeline as a shared link runs — you're guaranteed to triage it.
     * Rejects types the ingest pipeline can't consume (single images use the image pipeline).
     */
    @PostMapping("capture/file")
    public ResponseEntity<Map<String, Object>> captureFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "trackId", required = false) Long trackId,
            @RequestParam(value = "newTrackTitle", required = false) String newTrackTitle,
            @RequestParam(value = "newTrackType", required = false) String newTrackType) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file required"));
        }
        String original = file.getOriginalFilename();
        String ext = fileExt(original);
        String sourceType = classifyFile(ext);
        if (sourceType == null) {
            return ResponseEntity.status(415).body(Map.of(
                "error", "unsupported file type",
                "message", "Share a PDF, video, or audio file."));
        }

        String captureId = UUID.randomUUID().toString().substring(0, 12);
        try {
            // The stored file is BOTH the capture's source_ref (what the worker submits to the
            // embedder — it resolves the vault-relative path) and its kept local copy.
            String sourcePath = storeBinaryResource(captureId, ext, file.getBytes());
            String display = (title != null && !title.isBlank()) ? title.trim()
                : (original != null && !original.isBlank() ? original : sourcePath);
            captureRepo.enqueue(captureId, sourceType, sourcePath, sourcePath, display);
            Long resolvedTrackId = resolveTrackId(trackId, newTrackTitle, newTrackType);
            if (resolvedTrackId != null) captureRepo.setTrackId(captureId, resolvedTrackId);
            ingestWorker.nudge();
            log.info("[Capture] enqueued file {} ({}, {} bytes)", captureId, sourceType, file.getSize());
            return ResponseEntity.ok(Map.of("status", "queued", "captureId", captureId));
        } catch (Exception e) {
            captureRepo.updateStatus(captureId, "failed");
            log.warn("[Capture] file enqueue failed for {}: {}", captureId, e.toString());
            return ResponseEntity.status(500).body(Map.of("error", "could not queue file"));
        }
    }

    private static final Set<String> VIDEO_EXT = Set.of("mp4", "mov", "mkv", "webm", "avi");
    private static final Set<String> AUDIO_EXT = Set.of("mp3", "m4a", "wav", "ogg", "flac");

    private static String fileExt(String name) {
        if (name == null) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String base = slash >= 0 ? name.substring(slash + 1) : name;
        int dot = base.lastIndexOf('.');
        return dot >= 0 ? base.substring(dot + 1).toLowerCase() : "";
    }

    /** source_type the ingest pipeline understands, or null for unsupported (→ 415). */
    private static String classifyFile(String ext) {
        if (ext.equals("pdf")) return "pdf";
        if (VIDEO_EXT.contains(ext)) return "video";
        if (AUDIO_EXT.contains(ext)) return "audio";
        return null;
    }

    /** Persist shared bytes under resources/files/<captureId>.<ext> (vault-relative path). */
    private String storeBinaryResource(String captureId, String ext, byte[] bytes) throws java.io.IOException {
        Path dir = Paths.get(settingsRepo.getVaultPath()).resolve("resources").resolve("files");
        Files.createDirectories(dir);
        String name = captureId + (ext.isEmpty() ? "" : "." + ext);
        Files.write(dir.resolve(name), bytes);
        return "resources/files/" + name;
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

    // ── Failed captures ───────────────────────────────────────────────────────────
    // A capture that hard-failed (yt-dlp rejected the URL, embedder 4xx, ...) no longer
    // vanishes silently — it's auto-retried on every restart + a 6h standing cadence
    // (CaptureIngestWorker.retryFailed), forever, with no lifetime cap. It stays visible
    // here with the real error and a lifetime attempt count until it either succeeds or
    // the user dismisses it — it is never auto-discarded.

    @GetMapping("capture/failed")
    public ResponseEntity<List<Map<String, Object>>> listFailed() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CaptureRepository.Capture c : captureRepo.listFailed()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("sourceType", c.sourceType());
            m.put("sourceRef", c.sourceRef());
            m.put("title", c.title());
            m.put("lastError", c.lastError());
            m.put("retryCount", c.retryCount());
            m.put("createdAt", c.createdAt());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /** Retry a failed capture right now, instead of waiting for the next scheduled pass. */
    @PostMapping("capture/{id}/retry")
    public ResponseEntity<Map<String, Object>> retryFailed(@PathVariable String id) {
        if (!captureRepo.requeueFailed(id)) {
            return ResponseEntity.status(409).body(Map.of("error", "not a failed capture"));
        }
        ingestWorker.nudge();
        log.info("[Capture] manual retry requested for {}", id);
        return ResponseEntity.ok(Map.of("status", "queued"));
    }

    /** Give up on a failed capture — done trying, stop showing it. */
    @PostMapping("capture/{id}/dismiss")
    public ResponseEntity<Map<String, Object>> dismissFailed(@PathVariable String id) {
        if (!captureRepo.dismissFailed(id)) {
            return ResponseEntity.status(409).body(Map.of("error", "not a failed capture"));
        }
        return ResponseEntity.ok(Map.of("status", "discarded"));
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

    // trackId tags the resulting note(s) to an existing Learning Track; newTrackTitle
    // (with optional newTrackType) creates the track on the fly instead — see
    // resolveTrackId(). Both null/blank ⇒ today's exact untagged behavior.
    public record CaptureRequest(String url, String text, String title,
                                 Long trackId, String newTrackTitle, String newTrackType) {}

    public record DownloadRequest(String url) {}
}
