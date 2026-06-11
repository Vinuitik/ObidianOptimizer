package com.obsidian.obsidian.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private static final Logger log = LoggerFactory.getLogger(SyncController.class);

    private final SyncService            syncService;
    private final SyncQueueRepository    syncQueueRepo;
    private final DeviceIdentityService  deviceIdentityService;
    private final VaultEncryptionService encryptionService;
    private final DriveService           driveService;

    public SyncController(SyncService syncService,
                          SyncQueueRepository syncQueueRepo,
                          DeviceIdentityService deviceIdentityService,
                          VaultEncryptionService encryptionService,
                          DriveService driveService) {
        this.syncService           = syncService;
        this.syncQueueRepo         = syncQueueRepo;
        this.deviceIdentityService = deviceIdentityService;
        this.encryptionService     = encryptionService;
        this.driveService          = driveService;
    }

    /** GET /api/sync/status — queue counts + configuration state */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> body = new LinkedHashMap<>(syncQueueRepo.getStatusSummary());
        body.put("deviceId",             deviceIdentityService.getDeviceId());
        body.put("encryptionConfigured", encryptionService.isConfigured());
        body.put("driveConfigured",      driveService.isConfigured());
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
}
