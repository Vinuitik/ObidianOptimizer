package com.obsidian.obsidian.media;

import com.obsidian.obsidian.ml.ImageScanService;
import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.sync.SyncQueueRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;

@RestController
public class MediaController {

    private static final Logger log = LoggerFactory.getLogger(MediaController.class);

    static final Set<String> IMAGE_EXTS = Set.of(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg");
    static final Set<String> VIDEO_EXTS = Set.of(".mp4", ".mov", ".mkv", ".webm", ".avi");
    static final Set<String> AUDIO_EXTS = Set.of(".mp3", ".wav", ".ogg", ".m4a", ".flac");
    static final Set<String> PDF_EXTS   = Set.of(".pdf");

    private static final String[] SEARCH_SUBDIRS = {"images", "videos", "pdf", "audio", "files"};

    private final SettingsRepository settingsRepo;
    private final SyncQueueRepository syncQueueRepo;

    public MediaController(SettingsRepository settingsRepo, SyncQueueRepository syncQueueRepo) {
        this.settingsRepo  = settingsRepo;
        this.syncQueueRepo = syncQueueRepo;
    }

    @GetMapping("/images/{filename}")
    public ResponseEntity<Resource> getImage(@PathVariable String filename) {
        Path resourcesRoot = Paths.get(settingsRepo.getVaultPath()).resolve("resources");
        for (String subdir : SEARCH_SUBDIRS) {
            try {
                Path candidate = resourcesRoot.resolve(subdir).resolve(filename).normalize();
                if (!candidate.startsWith(resourcesRoot)) continue;
                Resource resource = new UrlResource(candidate.toUri());
                if (resource.exists() && resource.isReadable()) {
                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType(mimeFor(filename)))
                            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                            .body(resource);
                }
            } catch (Exception ignored) {}
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadEndpoint(
            @RequestParam MultipartFile file,
            @RequestParam String filename) {
        log.info("[upload] filename={} size={}", filename, file.getSize());
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            return ResponseEntity.badRequest().body("Invalid filename");
        }
        try {
            uploadFile(file, filename);
            return ResponseEntity.ok(Map.of(
                "filename", filename,
                "url", "/api/images/" + filename
            ));
        } catch (IOException e) {
            log.error("[upload] failed filename={}: {}", filename, e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    void uploadFile(MultipartFile file, String filename) throws IOException {
        String subdir = subdirFor(filename);
        Path targetDir = Paths.get(settingsRepo.getVaultPath()).resolve("resources").resolve(subdir);
        Files.createDirectories(targetDir);
        file.transferTo(targetDir.resolve(filename).toFile());
        log.info("[upload] saved {} → resources/{}", filename, subdir);

        try {
            byte[] bytes = Files.readAllBytes(targetDir.resolve(filename));
            String relPath = "resources/" + subdir + "/" + filename;
            syncQueueRepo.markPending(relPath, ImageScanService.sha256(bytes));
        } catch (Exception e) {
            log.warn("[upload] sync queue update failed for {}: {}", filename, e.getMessage());
        }
    }

    static String subdirFor(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return "files";
        String ext = filename.substring(dot).toLowerCase();
        if (IMAGE_EXTS.contains(ext)) return "images";
        if (VIDEO_EXTS.contains(ext)) return "videos";
        if (AUDIO_EXTS.contains(ext)) return "audio";
        if (PDF_EXTS.contains(ext))   return "pdf";
        return "files";
    }

    static String mimeFor(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        return switch (filename.substring(dot).toLowerCase()) {
            case ".png"          -> "image/png";
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".gif"          -> "image/gif";
            case ".webp"         -> "image/webp";
            case ".svg"          -> "image/svg+xml";
            case ".mp4"          -> "video/mp4";
            case ".mov"          -> "video/quicktime";
            case ".mkv"          -> "video/x-matroska";
            case ".webm"         -> "video/webm";
            case ".avi"          -> "video/x-msvideo";
            case ".mp3"          -> "audio/mpeg";
            case ".wav"          -> "audio/wav";
            case ".ogg"          -> "audio/ogg";
            case ".m4a"          -> "audio/mp4";
            case ".flac"         -> "audio/flac";
            case ".pdf"          -> "application/pdf";
            default              -> "application/octet-stream";
        };
    }
}
