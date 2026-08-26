package com.obsidian.obsidian.internalapi;

import com.obsidian.obsidian.capture.CaptureRepository;
import com.obsidian.obsidian.media.MediaController;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.tracks.TrackRepository;
import com.obsidian.obsidian.tracks.TrackRepository.Track;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

/**
 * Service-to-service write API for the ingest agent (embedder container).
 * Vault writes MUST go through here (never direct file writes from Python) so
 * the notes index, embedding diff, and sync queue stay consistent — the same
 * invariant that keeps create_note out of the MCP tool set.
 *
 * Auth: X-Internal-Token header, constant-time compare against MCP_API_TOKEN
 * (one shared secret for all service-to-service calls). Empty token ⇒ everything
 * is rejected (fail closed). Session auth does not apply (permitted in
 * SecurityConfig, guarded here).
 */
@RestController
@RequestMapping("/api/internal")
public class InternalAgentController {

    private static final Logger log = LoggerFactory.getLogger(InternalAgentController.class);

    private final FileRepository fileRepository;
    private final SettingsRepository settingsRepo;
    private final MediaController mediaController;
    private final CaptureRepository captureRepo;
    private final TrackRepository trackRepo;

    @Value("${mcp.api.token:}")
    private String internalToken;

    public InternalAgentController(FileRepository fileRepository,
                                   SettingsRepository settingsRepo,
                                   MediaController mediaController,
                                   CaptureRepository captureRepo,
                                   TrackRepository trackRepo) {
        this.fileRepository = fileRepository;
        this.settingsRepo = settingsRepo;
        this.mediaController = mediaController;
        this.captureRepo = captureRepo;
        this.trackRepo = trackRepo;
    }

    private boolean badToken(String header) {
        if (internalToken == null || internalToken.isBlank()) return true; // fail closed
        if (header == null) return true;
        return !MessageDigest.isEqual(
            internalToken.getBytes(StandardCharsets.UTF_8),
            header.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("bad internal token");
    }

    /** Create a note with full content. folder is vault-relative ("" = vault root).
     *  captureId (optional): when the producing capture row is tagged with a Learning
     *  Track (tracks/FLOWS.md Phase 1b — capture.track_id, set via CaptureController's
     *  extension/PWA capture-time picker), the note is also appended as a track_items row.
     *  Best-effort: a track-tagging hiccup must never fail note delivery. */
    @PostMapping("/notes")
    public ResponseEntity<?> createNote(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                        @RequestBody CreateNoteRequest req) {
        if (badToken(token)) return unauthorized();
        try {
            String absFolder = resolveFolder(req.folder());
            String path = fileRepository.createNote(absFolder, req.name());
            fileRepository.updateNote(path, req.content());
            log.info("[internal.createNote] agent created {}", path);
            linkToTrack(req.captureId(), req.name(), path);
            return ResponseEntity.ok(Map.of("path", path));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    private void linkToTrack(String captureId, String noteTitle, String notePath) {
        if (captureId == null || captureId.isBlank()) return;
        try {
            CaptureRepository.Capture cap = captureRepo.get(captureId);
            if (cap != null && cap.trackId() != null) {
                trackRepo.addItem(cap.trackId(), noteTitle, notePath);
            }
        } catch (Exception e) {
            log.warn("[internal.createNote] track link failed for capture {}: {}", captureId, e.toString());
        }
    }

    /** MCP-originated track creation ("okay, let's make this a course" mid-conversation) —
     *  the embedder never gets a session cookie, so this mirrors the session-authed
     *  POST /tracks (TrackController) behind the same internal-token gate as every other
     *  agent write. */
    @PostMapping("/tracks")
    public ResponseEntity<?> createTrack(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                         @RequestBody CreateTrackRequest req) {
        if (badToken(token)) return unauthorized();
        if (req.title() == null || req.title().isBlank()) {
            return ResponseEntity.badRequest().body("title is required");
        }
        String type = (req.type() == null || req.type().isBlank()) ? "custom" : req.type();
        return ResponseEntity.ok(trackRepo.create(req.title().trim(), type, "manual"));
    }

    /** MCP-originated track item append (ingest_resource/create_note with track_id — the
     *  async-job path, where the note lands well after the tool call returns, so the
     *  embedder appends the item itself once synthesis actually produces the note). */
    @PostMapping("/tracks/{id}/items")
    public ResponseEntity<?> addTrackItem(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                          @PathVariable long id,
                                          @RequestBody AddTrackItemRequest req) {
        if (badToken(token)) return unauthorized();
        if (trackRepo.get(id) == null) return ResponseEntity.notFound().build();
        if (req.title() == null || req.title().isBlank()) {
            return ResponseEntity.badRequest().body("title is required");
        }
        return ResponseEntity.ok(trackRepo.addItem(id, req.title().trim(), req.notePath()));
    }

    /** Mini-course generation (step 1): the embedder reads a track's title + existing
     *  items (title, notePath, status) to build a bundle from every resource already
     *  attached to a track — this is the ONLY way the embedder learns the track's title
     *  (it never carries a session cookie, so it can't call the session-authed track-get
     *  endpoint). Same internal-token gate as every other embedder-facing endpoint. */
    @GetMapping("/tracks/{id}/items")
    public ResponseEntity<?> trackItems(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                        @PathVariable long id) {
        if (badToken(token)) return unauthorized();
        Track track = trackRepo.get(id);
        if (track == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("title", track.title(), "items", trackRepo.listItems(id)));
    }

    /** Overwrite an existing note (used by the note splitter's hub rewrite). */
    @PutMapping("/notes")
    public ResponseEntity<?> updateNote(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                        @RequestBody UpdateNoteRequest req) {
        if (badToken(token)) return unauthorized();
        try {
            String abs = resolveUnderVault(req.path()).toString();
            fileRepository.updateNote(abs, req.content());
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /**
     * Stash a pre-rewrite snapshot of an in-place note as its Capture source, and
     * create the capture row. A note can hold multiple embeds, so the note itself
     * (not any one embed) is always the single source — see INGESTION_V2_FLOWS.
     */
    @PostMapping("/capture")
    public ResponseEntity<?> createCapture(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                           @RequestBody CreateCaptureRequest req) {
        if (badToken(token)) return unauthorized();
        try {
            Path sourcesDir = resolveUnderVault("_inbox/_sources");
            Files.createDirectories(sourcesDir);
            String filename = req.captureId() + ".md";
            Files.writeString(sourcesDir.resolve(filename), req.content());
            String sourcePath = "_inbox/_sources/" + filename;

            String title = req.sourceRef().replaceAll(".*[/\\\\]", "").replaceAll("\\.md$", "");
            captureRepo.create(req.captureId(), "note", req.sourceRef(), sourcePath, title);
            return ResponseEntity.ok(Map.of("sourcePath", sourcePath));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /** Ensure a vault-relative folder chain exists (e.g. "ingest"). */
    @PostMapping("/folders")
    public ResponseEntity<?> ensureFolder(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                          @RequestBody EnsureFolderRequest req) {
        if (badToken(token)) return unauthorized();
        try {
            Path abs = resolveUnderVault(req.path());
            Files.createDirectories(abs);
            return ResponseEntity.ok(Map.of("path", abs.toString()));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /** Store agent-produced media (e.g. video keyframes) under resources/. */
    @PostMapping("/media")
    public ResponseEntity<?> storeMedia(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                        @RequestBody StoreMediaRequest req) {
        if (badToken(token)) return unauthorized();
        String filename = req.filename();
        if (filename == null || filename.contains("/") || filename.contains("\\")
                || filename.contains("..")) {
            return ResponseEntity.badRequest().body("invalid filename");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(req.dataB64());
            String stored = mediaController.storeResourceBytes(filename, bytes);
            return ResponseEntity.ok(Map.of("relPath", stored));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("invalid base64");
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    private String resolveFolder(String relFolder) throws IOException {
        Path abs = resolveUnderVault(relFolder == null ? "" : relFolder);
        Files.createDirectories(abs);
        return abs.toString();
    }

    private Path resolveUnderVault(String rel) throws IOException {
        Path vault = Paths.get(settingsRepo.getVaultPath()).toAbsolutePath().normalize();
        Path target = vault.resolve(rel == null ? "" : rel).normalize();
        if (!target.startsWith(vault)) {
            throw new IOException("path escapes vault: " + rel);
        }
        return target;
    }

    record CreateNoteRequest(String folder, String name, String content, String captureId) {}
    record UpdateNoteRequest(String path, String content) {}
    record EnsureFolderRequest(String path) {}
    record StoreMediaRequest(String filename, String dataB64) {}
    record CreateCaptureRequest(String captureId, String sourceRef, String content) {}
    record CreateTrackRequest(String title, String type) {}
    record AddTrackItemRequest(String title, String notePath) {}
}
