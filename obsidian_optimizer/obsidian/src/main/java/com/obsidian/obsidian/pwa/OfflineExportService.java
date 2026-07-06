package com.obsidian.obsidian.pwa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsidian.obsidian.notes.FileRepository;
import com.obsidian.obsidian.sync.DriveService;
import com.obsidian.obsidian.sync.VaultEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server → phone export for the offline PWA (DRIVE_OFFLINE_SYNC_ARCH §8 B1). Builds the
 * due-review bundle (same data as {@code /api/review/bundle}), encrypts it with the vault
 * passphrase, and writes it to Drive {@code _offline/review-bundle.json.enc}. The phone then
 * pulls that ONE file and works offline — the laptop never has to be reachable at review time.
 *
 * Trigger cadence is belt-and-suspenders because the laptop is often OFF:
 *  - on startup (ApplicationReadyEvent) — refresh whenever the laptop comes online,
 *  - nightly cron — top-up while it happens to be up,
 *  - manual (POST /api/pwa/export) — "prep offline set before I leave".
 * All three are idempotent (the export is a singleton file, overwritten each time).
 */
@Service
public class OfflineExportService {

    private static final Logger log = LoggerFactory.getLogger(OfflineExportService.class);
    static final String REVIEW_BUNDLE = "review-bundle.json.enc";
    private static final int DEFAULT_LIMIT = 200;

    private final FileRepository repository;
    private final VaultEncryptionService encryption;
    private final DriveService drive;
    private final ObjectMapper mapper = new ObjectMapper();

    public OfflineExportService(FileRepository repository,
                                VaultEncryptionService encryption,
                                DriveService drive) {
        this.repository = repository;
        this.encryption = encryption;
        this.drive = drive;
    }

    /** Build + encrypt + upload the due-notes bundle. Returns the note count. */
    public int exportReviewBundle(int limit) throws Exception {
        if (!drive.isConfigured())      throw new IllegalStateException("Drive not connected");
        if (!encryption.isConfigured()) throw new IllegalStateException("Sync passphrase not set");

        FileRepository.ReviewPage page = repository.getReviewNotesPaged(0, Math.min(limit, 500));
        List<Map<String, Object>> notes = new ArrayList<>();
        for (String path : page.notes()) {
            String content = repository.getText(path);
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("path", path);
            rec.put("shortName", shortName(path));
            rec.put("content", content == null ? "" : content);
            notes.add(rec);
        }
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("generatedAt", System.currentTimeMillis());
        bundle.put("notes", notes);

        byte[] encrypted = encryption.encrypt(mapper.writeValueAsBytes(bundle));
        drive.uploadOffline(REVIEW_BUNDLE, encrypted);
        log.info("[OfflineExport] exported {} due notes → _offline/{}", notes.size(), REVIEW_BUNDLE);
        return notes.size();
    }

    @Scheduled(cron = "${offline.export.cron:0 30 3 * * *}")
    public void scheduledExport() {
        tryExport("scheduled");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void exportOnStartup() {
        // Off the boot thread — building the bundle reads note text + hits Drive.
        Thread t = new Thread(() -> tryExport("startup"), "offline-export-startup");
        t.setDaemon(true);
        t.start();
    }

    private void tryExport(String why) {
        if (!drive.isConfigured() || !encryption.isConfigured()) return;
        try {
            exportReviewBundle(DEFAULT_LIMIT);
        } catch (Exception e) {
            log.warn("[OfflineExport] {} export failed: {}", why, e.getMessage());
        }
    }

    private static String shortName(String path) {
        return path.replaceAll(".*[/\\\\]", "").replaceAll("\\.md$", "");
    }
}
