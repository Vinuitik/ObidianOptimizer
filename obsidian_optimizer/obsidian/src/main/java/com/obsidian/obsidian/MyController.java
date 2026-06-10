package com.obsidian.obsidian;

import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.obsidian.obsidian.FileRepository.ChildrenResult;
import com.obsidian.obsidian.FileRepository.ReviewPage;

@RestController
public class MyController {

    private static final Logger log = LoggerFactory.getLogger(MyController.class);

    private final FileRepository repository;
    private final SettingsRepository settingsRepo;
    private final ChronoService chronoService;

    MyController(FileRepository repository, SettingsRepository settingsRepo, ChronoService chronoService) {
        this.repository    = repository;
        this.settingsRepo  = settingsRepo;
        this.chronoService = chronoService;
    }

    // ── Read endpoints (public) ──────────────────────────────────────────────

    @GetMapping("names")
    public ArrayList<String> getNames() {
        return repository.getNoteNames();
    }

    // Returns immediate children of a folder (lazy tree loading).
    // No folder param = vault root.
    @GetMapping("children")
    public ChildrenResult getChildren(@RequestParam(required = false) String folder) {
        String path = (folder == null || folder.isBlank())
                ? repository.getRootPath()
                : folder;
        return repository.getDirectChildren(path);
    }

    // Returns paginated review-due notes.
    // offset defaults to 0, limit defaults to 40.
    @GetMapping("review")
    public ReviewPage getReviewNames(
            @RequestParam(defaultValue = "0")  int offset,
            @RequestParam(defaultValue = "40") int limit) {
        return repository.getReviewNotesPaged(offset, limit);
    }

    @GetMapping("text")
    public String getText(@RequestParam String noteName) {
        String content = repository.getText(noteName);
        log.debug("[getText] path={} responseBytes={}", noteName, content == null ? 0 : content.length());
        return content;
    }

    // ── Auth ─────────────────────────────────────────────────────────────────

    @GetMapping("me")
    public String getMe(Principal principal) {
        return principal.getName();
    }

    // ── Write endpoints (require session auth) ───────────────────────────────

    @PostMapping("folders")
    public ResponseEntity<?> createFolder(@RequestBody CreateFolderRequest req) {
        log.info("[createFolder] parent={} name={}", req.parentPath(), req.name());
        try {
            String path = repository.createFolder(req.parentPath(), req.name());
            return ResponseEntity.ok(Map.of("path", path));
        } catch (IOException e) {
            log.error("[createFolder] failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("notes")
    public ResponseEntity<?> createNote(@RequestBody CreateNoteRequest req) {
        log.info("[createNote] folder={} name={}", req.folder(), req.name());
        try {
            String path = repository.createNote(req.folder(), req.name());
            log.info("[createNote] created path={}", path);
            return ResponseEntity.ok(Map.of("path", path));
        } catch (IOException e) {
            log.error("[createNote] failed folder={} name={}: {}", req.folder(), req.name(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping("notes")
    public ResponseEntity<?> updateNote(@RequestBody UpdateNoteRequest req) {
        try {
            repository.updateNote(req.path(), req.content());
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PatchMapping("notes/content")
    public ResponseEntity<?> patchNote(@RequestBody PatchNoteRequest req) {
        log.info("[patchNote] path={} hunks={}", req.path(), req.hunks().size());
        try {
            repository.patchNote(req.path(), req.hunks());
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            log.error("[patchNote] failed path={}: {}", req.path(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PatchMapping("notes/rename")
    public ResponseEntity<?> renameNote(@RequestBody RenameNoteRequest req) {
        log.info("[renameNote] oldPath={} newName={}", req.oldPath(), req.newName());
        try {
            String newPath = repository.renameNote(req.oldPath(), req.newName());
            log.info("[renameNote] renamed to {}", newPath);
            return ResponseEntity.ok(Map.of("path", newPath));
        } catch (IOException e) {
            log.error("[renameNote] failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PatchMapping("notes/move")
    public ResponseEntity<?> moveNote(@RequestBody MoveNoteRequest req) {
        log.info("[moveNote] source={} target={}", req.sourcePath(), req.targetFolder());
        try {
            String newPath = repository.moveNote(req.sourcePath(), req.targetFolder());
            return ResponseEntity.ok(Map.of("path", newPath));
        } catch (IOException e) {
            log.error("[moveNote] failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("notes")
    public ResponseEntity<?> deleteNote(@RequestBody DeleteNoteRequest req) {
        log.info("[deleteNote] path={}", req.path());
        try {
            repository.softDeleteNote(req.path());
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            log.error("[deleteNote] failed path={}: {}", req.path(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    @GetMapping("settings")
    public SettingsResponse getSettings() {
        return new SettingsResponse(
            settingsRepo.getVaultPath(),
            settingsRepo.getResourcePath(),
            settingsRepo.getReviewPageSize(),
            settingsRepo.getStartupSyncMode(),
            settingsRepo.getMaxDailyReviews(),
            settingsRepo.getBankruptcyLimit()
        );
    }

    @PutMapping("settings")
    public ResponseEntity<?> updateSettings(@RequestBody UpdateSettingsRequest req) {
        try {
            if (req.vaultPath() != null) {
                repository.updateVaultPath(req.vaultPath());
            }
            if (req.resourcePath() != null) {
                settingsRepo.set("resourcePath", req.resourcePath());
            }
            if (req.reviewPageSize() != null) {
                if (req.reviewPageSize() < 1 || req.reviewPageSize() > 500) {
                    return ResponseEntity.badRequest().body("reviewPageSize must be between 1 and 500");
                }
                settingsRepo.set("reviewPageSize", String.valueOf(req.reviewPageSize()));
            }
            if (req.startupSyncMode() != null) {
                if (!req.startupSyncMode().equals("blocking") && !req.startupSyncMode().equals("async")) {
                    return ResponseEntity.badRequest().body("startupSyncMode must be 'blocking' or 'async'");
                }
                settingsRepo.set("startupSyncMode", req.startupSyncMode());
            }
            if (req.maxDailyReviews() != null) {
                if (req.maxDailyReviews() < 1) {
                    return ResponseEntity.badRequest().body("maxDailyReviews must be a positive integer");
                }
                settingsRepo.set("maxDailyReviews", String.valueOf(req.maxDailyReviews()));
            }
            if (req.bankruptcyLimit() != null) {
                if (req.bankruptcyLimit() < 1) {
                    return ResponseEntity.badRequest().body("bankruptcyLimit must be a positive integer");
                }
                settingsRepo.set("bankruptcyLimit", String.valueOf(req.bankruptcyLimit()));
            }
            return ResponseEntity.ok(getSettings());
        } catch (Exception e) {
            log.error("[updateSettings] failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ── Chrono ────────────────────────────────────────────────────────────────

    @GetMapping("chrono/status")
    public ChronoStatusResponse getChronoStatus() {
        return new ChronoStatusResponse(chronoService.getLastRunDate());
    }

    @PostMapping("chrono/run")
    public ResponseEntity<?> runChrono() {
        try {
            ChronoService.ChronoResult result = chronoService.runAllJobs();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[runChrono] failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    record CreateFolderRequest(String parentPath, String name) {}
    record CreateNoteRequest(String folder, String name) {}
    record UpdateNoteRequest(String path, String content) {}
    record PatchNoteRequest(String path, List<FileRepository.PatchHunk> hunks) {}
    record RenameNoteRequest(String oldPath, String newName) {}
    record DeleteNoteRequest(String path) {}
    record MoveNoteRequest(String sourcePath, String targetFolder) {}
    record SettingsResponse(String vaultPath, String resourcePath, int reviewPageSize, String startupSyncMode, int maxDailyReviews, int bankruptcyLimit) {}
    record UpdateSettingsRequest(String vaultPath, String resourcePath, Integer reviewPageSize, String startupSyncMode, Integer maxDailyReviews, Integer bankruptcyLimit) {}
    record ChronoStatusResponse(String lastRunDate) {}
}
