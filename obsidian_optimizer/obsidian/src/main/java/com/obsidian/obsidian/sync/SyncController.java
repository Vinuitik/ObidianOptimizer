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
    private final SyncWorker             syncWorker;
    private final DbBackupService        dbBackupService;

    public SyncController(SyncService syncService,
                          SyncQueueRepository syncQueueRepo,
                          DeviceIdentityService deviceIdentityService,
                          VaultEncryptionService encryptionService,
                          DriveService driveService,
                          SyncOAuthService oauthService,
                          SettingsRepository settingsRepo,
                          SyncWorker syncWorker,
                          DbBackupService dbBackupService) {
        this.syncService           = syncService;
        this.syncQueueRepo         = syncQueueRepo;
        this.deviceIdentityService = deviceIdentityService;
        this.encryptionService     = encryptionService;
        this.driveService          = driveService;
        this.oauthService          = oauthService;
        this.settingsRepo          = settingsRepo;
        this.syncWorker            = syncWorker;
        this.dbBackupService       = dbBackupService;
    }

    /** GET /api/sync/status — queue counts + configuration/connection state + quota */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> body = new LinkedHashMap<>(syncQueueRepo.getStatusSummary());
        body.put("deviceId",             deviceIdentityService.getDeviceId());
        body.put("enabled",              settingsRepo.isSyncEnabled());
        body.put("encryptionConfigured", encryptionService.isConfigured());
        body.put("driveConfigured",      driveService.isConfigured());
        body.putAll(syncService.uploadProgress());   // uploading / uploadDone / uploadTotal
        body.putAll(dbBackupService.statusFragment()); // dbBackup / dbEmpty / dbRestoring
        body.putAll(oauthService.statusFragment());
        // Quota is one Drive API call; the panel fetches status on mount, not on a poll.
        if (driveService.isConfigured() && oauthService.isConnected()) {
            try {
                body.put("quota", driveService.fetchQuota());
            } catch (Exception e) {
                log.warn("[SyncController] quota fetch failed: {}", e.getMessage());
            }
        }
        return ResponseEntity.ok(body);
    }

    /**
     * POST /api/sync/janitor?dryRun=true — sweep Drive orphans (files whose local twin
     * is gone). dryRun (default) only reports; dryRun=false trashes them (30-day
     * recovery in Drive trash). Also runs weekly via SyncWorker.
     */
    @PostMapping("/janitor")
    public ResponseEntity<Map<String, Object>> janitor(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        try {
            SyncService.JanitorResult r = syncService.janitor(dryRun);
            return ResponseEntity.ok(Map.of(
                "dryRun",       r.dryRun(),
                "scanned",      r.scanned(),
                "orphans",      r.orphans(),
                "freedBytes",   r.freedBytes(),
                "deletedPaths", r.deletedPaths()
            ));
        } catch (IOException e) {
            log.error("[SyncController] janitor failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/sync/upload — drain the queue on the sync worker lane (off the request
     * thread) and return immediately. The Settings panel then polls /sync/status for
     * live progress. Single-flight: a second press while a drain runs is a no-op.
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> triggerUpload() {
        boolean started = syncWorker.triggerManualUpload();
        log.info("[SyncController] manual upload {}", started ? "started" : "already running");
        Map<String, Object> body = new LinkedHashMap<>(syncQueueRepo.getStatusSummary());
        body.put("started", started);
        body.putAll(syncService.uploadProgress());
        return ResponseEntity.accepted().body(body);
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

    // ── DB backup / restore ──────────────────────────────────────────────────

    /** POST /api/sync/db/backup — pg_dump → encrypt → Drive _db/, on the sync lane (202). */
    @PostMapping("/db/backup")
    public ResponseEntity<Map<String, Object>> dbBackup() {
        boolean started = syncWorker.triggerDbBackup();
        log.info("[SyncController] DB backup {}", started ? "started" : "already running");
        return ResponseEntity.accepted().body(Map.of("started", started));
    }

    /** POST /api/sync/backup — Full Backup: upload pending files THEN dump the DB (202). */
    @PostMapping("/backup")
    public ResponseEntity<Map<String, Object>> fullBackup() {
        boolean started = syncWorker.triggerFullBackup();
        log.info("[SyncController] full backup {}", started ? "started" : "already running");
        return ResponseEntity.accepted().body(Map.of("started", started));
    }

    /**
     * POST /api/sync/db/restore?force= — restore latest Drive dump then materialize vault
     * files. Synchronous precheck returns 400 with a reason; the heavy work runs on the
     * lane (202). Destructive (pg_restore --clean) — the UI confirms before calling.
     */
    @PostMapping("/db/restore")
    public ResponseEntity<Map<String, Object>> dbRestore(
            @RequestParam(defaultValue = "false") boolean force) {
        String blocked = dbBackupService.restoreBlockedReason(force);
        if (blocked != null) {
            log.warn("[SyncController] DB restore refused: {}", blocked);
            return ResponseEntity.badRequest().body(Map.of("error", blocked));
        }
        boolean started = syncWorker.triggerDbRestore(force);
        log.info("[SyncController] DB restore {}", started ? "started" : "already running");
        return ResponseEntity.accepted().body(Map.of("started", started));
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
