package com.obsidian.obsidian.settings;

import com.obsidian.obsidian.notes.FileRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final SettingsRepository settingsRepo;
    private final FileRepository repository;

    SettingsController(SettingsRepository settingsRepo, FileRepository repository) {
        this.settingsRepo = settingsRepo;
        this.repository   = repository;
    }

    @GetMapping("settings")
    public SettingsResponse getSettings() {
        return new SettingsResponse(
            settingsRepo.getVaultPath(),
            settingsRepo.getResourcePath(),
            settingsRepo.getReviewPageSize(),
            settingsRepo.getStartupSyncMode(),
            settingsRepo.getMaxDailyReviews(),
            settingsRepo.getBankruptcyLimit(),
            settingsRepo.getEmbedModel(),
            settingsRepo.isFlashcardsEnabled()
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
            if (req.flashcardsEnabled() != null) {
                settingsRepo.set("flashcardsEnabled", String.valueOf(req.flashcardsEnabled()));
            }
            if (req.embedModel() != null) {
                String model = req.embedModel().trim();
                if (model.isBlank()) {
                    return ResponseEntity.badRequest().body("embedModel cannot be empty");
                }
                settingsRepo.set("ollamaEmbedModel", model);
            }
            return ResponseEntity.ok(getSettings());
        } catch (Exception e) {
            log.error("[updateSettings] failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record SettingsResponse(String vaultPath, String resourcePath, int reviewPageSize,
                                   String startupSyncMode, int maxDailyReviews, int bankruptcyLimit,
                                   String embedModel, boolean flashcardsEnabled) {}
    record UpdateSettingsRequest(String vaultPath, String resourcePath, Integer reviewPageSize,
                                 String startupSyncMode, Integer maxDailyReviews, Integer bankruptcyLimit,
                                 String embedModel, Boolean flashcardsEnabled) {}
}
