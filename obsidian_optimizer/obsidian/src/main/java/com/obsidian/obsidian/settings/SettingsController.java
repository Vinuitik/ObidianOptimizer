package com.obsidian.obsidian.settings;

import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.sync.DriveService;
import com.obsidian.obsidian.sync.VaultEncryptionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class SettingsController {

    private final com.obsidian.obsidian.vaults.ActiveVault activeVault;

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final SettingsRepository settingsRepo;
    private final FileRepository repository;
    private final VaultEncryptionService encryptionService;
    private final DriveService driveService;

    SettingsController(SettingsRepository settingsRepo, FileRepository repository,
                       VaultEncryptionService encryptionService, DriveService driveService,
            com.obsidian.obsidian.vaults.ActiveVault activeVault) {
        this.activeVault = activeVault;
        this.settingsRepo      = settingsRepo;
        this.repository        = repository;
        this.encryptionService = encryptionService;
        this.driveService      = driveService;
    }

    @GetMapping("settings")
    public SettingsResponse getSettings() {
        return new SettingsResponse(
            activeVault.root().toString(),
            settingsRepo.getResourcePath(activeVault.id()),
            settingsRepo.getReviewPageSize(),
            settingsRepo.getStartupSyncMode(activeVault.id()),
            settingsRepo.getMaxDailyReviews(),
            settingsRepo.getMaxDailyFlashcards(),
            settingsRepo.getBankruptcyLimit(),
            settingsRepo.getChronicNeglectDays(),
            settingsRepo.getEmbedModel(),
            settingsRepo.isFlashcardsEnabled(),
            settingsRepo.isTracksEnabled(),
            settingsRepo.isFeedAutoAccept(),
            settingsRepo.isSyncEnabled(activeVault.id()),
            settingsRepo.getSyncClientId(),
            // Secrets never leave the server — the UI only needs "is it saved?"
            !settingsRepo.getSyncClientSecret().isBlank(),
            !settingsRepo.getSyncPassphrase(activeVault.id()).isBlank()
        );
    }

    @PutMapping("settings")
    public ResponseEntity<?> updateSettings(@RequestBody UpdateSettingsRequest req) {
        try {
            if (req.vaultPath() != null) {
                return ResponseEntity.badRequest().body("use /api/vaults");
            }
            if (req.resourcePath() != null) {
                settingsRepo.setResourcePath(activeVault.id(), req.resourcePath());
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
                settingsRepo.setStartupSyncMode(activeVault.id(), req.startupSyncMode());
            }
            if (req.maxDailyReviews() != null) {
                if (req.maxDailyReviews() < 1) {
                    return ResponseEntity.badRequest().body("maxDailyReviews must be a positive integer");
                }
                settingsRepo.set("maxDailyReviews", String.valueOf(req.maxDailyReviews()));
            }
            if (req.maxDailyFlashcards() != null) {
                // 0 = no flashcards (all review is read-and-self-grade); must not exceed the total cap.
                if (req.maxDailyFlashcards() < 0) {
                    return ResponseEntity.badRequest().body("maxDailyFlashcards must be zero or a positive integer");
                }
                settingsRepo.set("maxDailyFlashcards", String.valueOf(req.maxDailyFlashcards()));
            }
            if (req.bankruptcyLimit() != null) {
                if (req.bankruptcyLimit() < 1) {
                    return ResponseEntity.badRequest().body("bankruptcyLimit must be a positive integer");
                }
                settingsRepo.set("bankruptcyLimit", String.valueOf(req.bankruptcyLimit()));
            }
            if (req.chronicNeglectDays() != null) {
                if (req.chronicNeglectDays() < 1) {
                    return ResponseEntity.badRequest().body("chronicNeglectDays must be a positive integer");
                }
                settingsRepo.set("chronicNeglectDays", String.valueOf(req.chronicNeglectDays()));
            }
            if (req.flashcardsEnabled() != null) {
                settingsRepo.set("flashcardsEnabled", String.valueOf(req.flashcardsEnabled()));
            }
            if (req.tracksEnabled() != null) {
                settingsRepo.set("tracksEnabled", String.valueOf(req.tracksEnabled()));
            }
            if (req.feedAutoAccept() != null) {
                settingsRepo.set("feedAutoAccept", String.valueOf(req.feedAutoAccept()));
            }
            if (req.embedModel() != null) {
                String model = req.embedModel().trim();
                if (model.isBlank()) {
                    return ResponseEntity.badRequest().body("embedModel cannot be empty");
                }
                settingsRepo.set("ollamaEmbedModel", model);
            }
            if (req.syncEnabled() != null) {
                long vid = activeVault.id();
                if (req.syncEnabled()) {
                    // A folder already bound to another vault would mean a cross-vault push.
                    String folder = settingsRepo.getSyncDriveFolderId(vid);
                    Long other = settingsRepo.otherVaultUsingDriveFolder(vid, folder);
                    if (other != null) {
                        return ResponseEntity.badRequest().body(
                            "Drive folder " + folder + " is already used by vault " + other);
                    }
                }
                settingsRepo.setSyncEnabled(vid, req.syncEnabled());
            }
            if (req.syncClientId() != null) {
                settingsRepo.set("syncClientId", req.syncClientId().trim());
                driveService.reset(activeVault.id());
            }
            // Secret fields: blank means "leave as is" (the form shows a saved-marker
            // placeholder, not the value). Disconnect clears the token, not these.
            if (req.syncClientSecret() != null && !req.syncClientSecret().isBlank()) {
                settingsRepo.set("syncClientSecret", req.syncClientSecret().trim());
                driveService.reset(activeVault.id());
            }
            if (req.syncPassphrase() != null && !req.syncPassphrase().isBlank()) {
                settingsRepo.setSyncPassphrase(activeVault.id(), req.syncPassphrase().trim());
                encryptionService.reload(activeVault.id());
            }
            return ResponseEntity.ok(getSettings());
        } catch (Exception e) {
            log.error("[updateSettings] failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record SettingsResponse(String vaultPath, String resourcePath, int reviewPageSize,
                                   String startupSyncMode, int maxDailyReviews, int maxDailyFlashcards,
                                   int bankruptcyLimit,
                                   int chronicNeglectDays, String embedModel, boolean flashcardsEnabled,
                                   boolean tracksEnabled, boolean feedAutoAccept,
                                   boolean syncEnabled, String syncClientId,
                                   boolean syncClientSecretSet, boolean syncPassphraseSet) {}
    record UpdateSettingsRequest(String vaultPath, String resourcePath, Integer reviewPageSize,
                                 String startupSyncMode, Integer maxDailyReviews, Integer maxDailyFlashcards,
                                 Integer bankruptcyLimit,
                                 Integer chronicNeglectDays, String embedModel, Boolean flashcardsEnabled,
                                 Boolean tracksEnabled, Boolean feedAutoAccept,
                                 Boolean syncEnabled, String syncClientId,
                                 String syncClientSecret, String syncPassphrase) {}
}
