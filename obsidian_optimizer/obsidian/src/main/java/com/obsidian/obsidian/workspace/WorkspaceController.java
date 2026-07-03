package com.obsidian.obsidian.workspace;

import com.obsidian.obsidian.settings.SettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/workspace")
public class WorkspaceController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceController.class);

    private static final Set<String> VIDEO_EXTS   = Set.of(".mp4", ".mov", ".mkv", ".webm", ".avi");
    private static final Set<String> AUDIO_EXTS   = Set.of(".mp3", ".wav", ".ogg", ".m4a", ".flac");
    private static final Set<String> PDF_EXTS     = Set.of(".pdf");
    private static final Set<String> ALLOWED_EXTS;

    static {
        Set<String> all = new HashSet<>();
        all.addAll(VIDEO_EXTS);
        all.addAll(AUDIO_EXTS);
        all.addAll(PDF_EXTS);
        ALLOWED_EXTS = Collections.unmodifiableSet(all);
    }

    private static final long MAX_BYTES = 512L * 1024 * 1024; // 512 MB

    private static final Pattern CD_FILENAME = Pattern.compile(
        "filename[^;=\n]*=\\s*['\"]?([^'\";\n]+)['\"]?", Pattern.CASE_INSENSITIVE);

    private final SettingsRepository settingsRepo;
    private final WorkspaceRepository workspaceRepo;

    public WorkspaceController(SettingsRepository settingsRepo, WorkspaceRepository workspaceRepo) {
        this.settingsRepo = settingsRepo;
        this.workspaceRepo = workspaceRepo;
    }

    record WorkspaceFile(String name, String type) {}

    // ── List ─────────────────────────────────────────────────────────────────────

    /**
     * DB-backed, not a directory scan — workspace_items is the source of truth for
     * type/order so a listing never has to open every file. Prunes rows whose file
     * no longer exists (the user removed it by hand; nothing else writes here).
     */
    @GetMapping("/files")
    public ResponseEntity<List<WorkspaceFile>> listFiles() {
        Path dir = Paths.get(settingsRepo.getVaultPath()).resolve("_workspace");
        List<WorkspaceFile> files = new ArrayList<>();
        for (WorkspaceRepository.WorkspaceItem item : workspaceRepo.listAll()) {
            if (!Files.isRegularFile(dir.resolve(item.filename()))) {
                workspaceRepo.delete(item.filename());
                continue;
            }
            files.add(new WorkspaceFile(item.filename(), item.type()));
        }
        return ResponseEntity.ok(files);
    }

    // ── Save from URL ─────────────────────────────────────────────────────────────

    @PostMapping("/save")
    public ResponseEntity<?> saveToWorkspace(@RequestBody Map<String, String> body) {
        String rawUrl = body.get("url");
        if (rawUrl == null || rawUrl.isBlank()) {
            return ResponseEntity.badRequest().body("url required");
        }

        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                return ResponseEntity.badRequest().body("Only http/https URLs are supported");
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid URL");
        }

        try {
            Path workspaceDir = Paths.get(settingsRepo.getVaultPath()).resolve("_workspace");
            Files.createDirectories(workspaceDir);

            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .header("User-Agent", "ObsidianOptimizer/1.0")
                    .build();

            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() >= 400) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body("Remote server returned " + response.statusCode());
            }

            // Reject oversized files if Content-Length is declared
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > MAX_BYTES) {
                return ResponseEntity.badRequest().body("File too large (> 512 MB)");
            }

            String filename = resolveFilename(uri, response.headers());

            int dot = filename.lastIndexOf('.');
            String ext = dot >= 0 ? filename.substring(dot).toLowerCase() : "";
            if (!ALLOWED_EXTS.contains(ext)) {
                return ResponseEntity.badRequest()
                        .body("Unsupported type '" + ext + "'. Accepted: pdf, mp4, mov, mkv, webm, avi, mp3, m4a, wav, ogg, flac");
            }

            // Sanitize — no path components, no shell-special chars
            filename = filename.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
            if (filename.isBlank()) filename = "download-" + System.currentTimeMillis() + ext;

            Path target = workspaceDir.resolve(filename).normalize();
            if (!target.startsWith(workspaceDir)) {
                return ResponseEntity.badRequest().body("Invalid filename");
            }

            try (InputStream stream = response.body()) {
                Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
            }

            workspaceRepo.insert(filename, typeFor(filename), null, 0);
            log.info("[workspace] saved {} ({} bytes) from {}", filename, Files.size(target), uri.getHost());
            return ResponseEntity.ok(Map.of("filename", filename));

        } catch (Exception e) {
            log.error("[workspace] save failed for {}: {}", rawUrl, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    // ── Upload a local file (extension drag-drop) ──────────────────────────────────

    @PostMapping("/upload")
    public ResponseEntity<?> uploadToWorkspace(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body("file required");
        }
        if (file.getSize() > MAX_BYTES) {
            return ResponseEntity.badRequest().body("File too large (> 512 MB)");
        }

        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            return ResponseEntity.badRequest().body("filename required");
        }
        int dot = original.lastIndexOf('.');
        String ext = dot >= 0 ? original.substring(dot).toLowerCase() : "";
        if (!ALLOWED_EXTS.contains(ext)) {
            return ResponseEntity.badRequest()
                    .body("Unsupported type '" + ext + "'. Accepted: pdf, mp4, mov, mkv, webm, avi, mp3, m4a, wav, ogg, flac");
        }

        try {
            Path workspaceDir = Paths.get(settingsRepo.getVaultPath()).resolve("_workspace");
            Files.createDirectories(workspaceDir);

            // Strip path components (browsers may send a full path) then sanitize.
            String filename = original.replaceAll(".*[/\\\\]", "")
                                      .replaceAll("[/\\\\:*?\"<>|]", "_").trim();
            if (filename.isBlank()) filename = "upload-" + System.currentTimeMillis() + ext;

            Path target = workspaceDir.resolve(filename).normalize();
            if (!target.startsWith(workspaceDir)) {
                return ResponseEntity.badRequest().body("Invalid filename");
            }

            try (InputStream stream = file.getInputStream()) {
                Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
            }

            workspaceRepo.insert(filename, typeFor(filename), null, 0);
            log.info("[workspace] uploaded {} ({} bytes)", filename, Files.size(target));
            return ResponseEntity.ok(Map.of("filename", filename, "path", "_workspace/" + filename));

        } catch (Exception e) {
            log.error("[workspace] upload failed for {}: {}", original, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static String resolveFilename(URI uri, java.net.http.HttpHeaders headers) {
        // 1. Content-Disposition header
        String cd = headers.firstValue("Content-Disposition").orElse("");
        if (!cd.isBlank()) {
            Matcher m = CD_FILENAME.matcher(cd);
            if (m.find()) {
                String name = m.group(1).trim();
                if (!name.isBlank()) return name;
            }
        }

        // 2. Last meaningful path segment
        String path = uri.getPath();
        if (path != null && !path.isBlank()) {
            String[] parts = path.split("/");
            for (int i = parts.length - 1; i >= 0; i--) {
                String seg = URLDecoder.decode(parts[i], StandardCharsets.UTF_8).trim();
                if (!seg.isBlank() && seg.contains(".")) return seg;
            }
        }

        // 3. Fallback: infer extension from Content-Type
        String ct = headers.firstValue("Content-Type").orElse("").split(";")[0].trim().toLowerCase();
        String fallbackExt = switch (ct) {
            case "application/pdf" -> ".pdf";
            case "video/mp4"       -> ".mp4";
            case "video/webm"      -> ".webm";
            case "audio/mpeg"      -> ".mp3";
            case "audio/ogg"       -> ".ogg";
            default                -> ".bin";
        };
        return "download-" + System.currentTimeMillis() + fallbackExt;
    }

    private static String typeFor(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return "other";
        String ext = filename.substring(dot).toLowerCase();
        if (VIDEO_EXTS.contains(ext)) return "video";
        if (AUDIO_EXTS.contains(ext)) return "audio";
        if (PDF_EXTS.contains(ext))   return "pdf";
        return "other";
    }
}
