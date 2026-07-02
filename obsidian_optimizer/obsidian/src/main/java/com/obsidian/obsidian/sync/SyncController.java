package com.obsidian.obsidian.sync;

import com.obsidian.obsidian.settings.SettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NOTE on paths: nginx (and the Vite dev proxy) strip the {@code /api/} prefix, so
 * this controller maps {@code /sync/**} — the browser calls {@code /api/sync/**}.
 * (It originally mapped {@code /api/sync}, which was unreachable through the proxy —
 * part of why the sync feature never got a UI.)
 */
@RestController
@RequestMapping("/sync")
public class SyncController {

    private static final Logger log = LoggerFactory.getLogger(SyncController.class);

    private final SyncService            syncService;
    private final SyncQueueRepository    syncQueueRepo;
    private final DeviceIdentityService  deviceIdentityService;
    private final VaultEncryptionService encryptionService;
    private final DriveService           driveService;
    private final SyncOAuthService       oauthService;
    private final SettingsRepository     settingsRepo;

    public SyncController(SyncService syncService,
                          SyncQueueRepository syncQueueRepo,
                          DeviceIdentityService deviceIdentityService,
                          VaultEncryptionService encryptionService,
                          DriveService driveService,
                          SyncOAuthService oauthService,
                          SettingsRepository settingsRepo) {
        this.syncService           = syncService;
        this.syncQueueRepo         = syncQueueRepo;
        this.deviceIdentityService = deviceIdentityService;
        this.encryptionService     = encryptionService;
        this.driveService          = driveService;
        this.oauthService          = oauthService;
        this.settingsRepo          = settingsRepo;
    }

    /** GET /api/sync/status — queue counts + configuration/connection state */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> body = new LinkedHashMap<>(syncQueueRepo.getStatusSummary());
        body.put("deviceId",             deviceIdentityService.getDeviceId());
        body.put("enabled",              settingsRepo.isSyncEnabled());
        body.put("encryptionConfigured", encryptionService.isConfigured());
        body.put("driveConfigured",      driveService.isConfigured());
        body.putAll(oauthService.statusFragment());
        return ResponseEntity.ok(body);
    }

    /** POST /api/sync/upload — immediately drain the PENDING queue */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> triggerUpload() {
        log.info("[SyncController] manual upload triggered");
        syncService.uploadPending();
        return ResponseEntity.ok(syncQueueRepo.getStatusSummary());
    }

    /** POST /api/sync/download — pull all Drive files and write newer ones to disk */
    @PostMapping("/download")
    public ResponseEntity<Map<String, Object>> triggerDownload() {
        log.info("[SyncController] manual download triggered");
        try {
            syncService.downloadAll();
            return ResponseEntity.ok(syncQueueRepo.getStatusSummary());
        } catch (IOException e) {
            log.error("[SyncController] download failed: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    // ── OAuth (Connect Google Drive) ─────────────────────────────────────────

    /**
     * GET /api/sync/oauth/url?origin=… — consent URL for the Settings page to
     * redirect to. {@code origin} = the frontend's window.location.origin; the
     * matching redirect URI (origin + /api/sync/oauth/callback) must be registered
     * on the OAuth client in Google Cloud Console.
     */
    @GetMapping("/oauth/url")
    public ResponseEntity<Map<String, Object>> oauthUrl(@RequestParam String origin) {
        try {
            return ResponseEntity.ok(Map.of("url", oauthService.buildAuthUrl(origin)));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/sync/oauth/callback — Google redirects the browser here after consent.
     * Always answers with a redirect back to the Settings page (the SPA reads the
     * {@code drive=} query param and shows the result).
     */
    @GetMapping("/oauth/callback")
    public ResponseEntity<Void> oauthCallback(@RequestParam(required = false) String code,
                                              @RequestParam(required = false) String state,
                                              @RequestParam(required = false) String error) {
        String target;
        if (error != null || code == null || state == null) {
            log.warn("[SyncController] OAuth consent denied/failed: {}", error);
            target = "/settings?drive=error&reason=" + enc(error == null ? "missing code" : error);
        } else {
            try {
                oauthService.handleCallback(code, state);
                target = "/settings?drive=connected";
            } catch (Exception e) {
                log.error("[SyncController] OAuth exchange failed: {}", e.getMessage());
                target = "/settings?drive=error&reason=" + enc(e.getMessage());
            }
        }
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, target)
            .build();
    }

    /** POST /api/sync/disconnect — revoke + forget the Google connection. */
    @PostMapping("/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect() {
        oauthService.disconnect();
        return getStatus();
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }
}
