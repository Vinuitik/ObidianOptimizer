package com.obsidian.obsidian.sync;

import com.obsidian.obsidian.common.ContentHashing;
import com.obsidian.obsidian.ml.ImageScanService;
import com.obsidian.obsidian.notes.FrontmatterParser;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import com.obsidian.obsidian.notes.NoteLinkRepository;
import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.sync.DriveService.DriveFileInfo;
import com.obsidian.obsidian.sync.SyncQueueRepository.SyncEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    /** Janitor won't touch Drive files uploaded within this many days (rename race guard). */
    static final int GRACE_DAYS = 30;

    private final SyncQueueRepository   syncQueueRepo;
    private final VaultEncryptionService encryptionService;
    private final DriveService           driveService;
    private final DeviceIdentityService  deviceIdentityService;
    private final SettingsRepository     settingsRepo;
    private final NoteIndexRepository    noteIndex;
    private final NoteLinkRepository     noteLinkRepo;
    private final ImageScanService       imageScanService;

    public SyncService(SyncQueueRepository syncQueueRepo,
                       VaultEncryptionService encryptionService,
                       DriveService driveService,
                       DeviceIdentityService deviceIdentityService,
                       SettingsRepository settingsRepo,
                       NoteIndexRepository noteIndex,
                       NoteLinkRepository noteLinkRepo,
                       ImageScanService imageScanService) {
        this.syncQueueRepo        = syncQueueRepo;
        this.encryptionService    = encryptionService;
        this.driveService         = driveService;
        this.deviceIdentityService = deviceIdentityService;
        this.settingsRepo         = settingsRepo;
        this.noteIndex            = noteIndex;
        this.noteLinkRepo         = noteLinkRepo;
        this.imageScanService     = imageScanService;
    }

    // ── Startup ───────────────────────────────────────────────────────────────

    /** Runs after all @PostConstruct beans are ready — populates sync_queue for existing vault files. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        CompletableFuture.runAsync(this::initialScan);
    }

    /**
     * Compares every vault file against sync_queue.
     * Any file missing or with a changed hash is marked PENDING.
     */
    public void initialScan() {
        String vaultRoot = settingsRepo.getVaultPath();
        log.info("[SyncService.initialScan] starting for vault={}", vaultRoot);
        int queued = 0;

        // .md files — content_hash is already maintained by ImageScanService in notes table
        List<Map<String, Object>> notes = noteIndex.getAllPathsWithHash();
        for (Map<String, Object> row : notes) {
            String absPath = (String) row.get("path");
            String hash    = (String) row.get("content_hash");
            if (absPath == null || hash == null) continue;
            String rel = toRelative(vaultRoot, absPath);
            SyncEntry existing = syncQueueRepo.findByPath(rel);
            if (existing == null || !hash.equals(existing.contentHash()) || !"DONE".equals(existing.status())) {
                syncQueueRepo.markPending(rel, hash);
                queued++;
            }
        }

        // Resource files — compute hash from bytes
        Path resourcesDir = Paths.get(vaultRoot, "resources");
        if (Files.exists(resourcesDir)) {
            try {
                Files.walk(resourcesDir)
                    .filter(Files::isRegularFile)
                    .forEach(p -> {
                        try {
                            byte[] bytes = Files.readAllBytes(p);
                            String hash  = ContentHashing.sha256(bytes);
                            String rel   = toRelative(vaultRoot, p.toString());
                            SyncEntry existing = syncQueueRepo.findByPath(rel);
                            if (existing == null || !hash.equals(existing.contentHash()) || !"DONE".equals(existing.status())) {
                                syncQueueRepo.markPending(rel, hash);
                            }
                        } catch (IOException e) {
                            log.warn("[SyncService.initialScan] skip {}: {}", p, e.getMessage());
                        }
                    });
            } catch (IOException e) {
                log.warn("[SyncService.initialScan] cannot walk resources: {}", e.getMessage());
            }
        }

        log.info("[SyncService.initialScan] complete — {} new/changed files queued", queued);
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    public void uploadPending() {
        if (!encryptionService.isConfigured()) {
            log.warn("[SyncService.uploadPending] encryption not configured — skipping");
            return;
        }
        if (!driveService.isConfigured()) {
            log.warn("[SyncService.uploadPending] Drive not configured — skipping");
            return;
        }

        String vaultRoot = settingsRepo.getVaultPath();
        String deviceId  = deviceIdentityService.getDeviceId();
        List<SyncEntry> pending = syncQueueRepo.findByStatus("PENDING");

        if (pending.isEmpty()) {
            log.debug("[SyncService.uploadPending] nothing to upload");
            processTombstones();
            return;
        }

        processTombstones();

        int uploaded = 0, failed = 0;
        for (SyncEntry entry : pending) {
            try {
                String absPath = Paths.get(vaultRoot, entry.path()).toString();
                byte[] plaintext = readFile(absPath, entry.path());
                byte[] encrypted = encryptionService.encrypt(plaintext);

                // Hash what was actually read — Drive metadata must describe the
                // uploaded bytes, not the (possibly stale) hash from queue time.
                String actualHash = ContentHashing.sha256(plaintext);
                String driveFileId = driveService.uploadFile(
                    entry.path(), encrypted, actualHash, deviceId, entry.driveFileId());

                // Conditional on the queue-time hash: if an edit re-marked the row
                // PENDING while we were uploading, it stays PENDING and the newer
                // content goes out on the next pass.
                boolean done = syncQueueRepo.markDoneIfHashMatches(
                    entry.path(), driveFileId, entry.contentHash());
                if (!done) {
                    log.info("[SyncService.uploadPending] {} changed during upload — stays PENDING", entry.path());
                }
                uploaded++;
            } catch (Exception e) {
                log.error("[SyncService.uploadPending] failed for {}: {}", entry.path(), e.getMessage());
                syncQueueRepo.markFailed(entry.path());
                failed++;
            }
        }
        log.info("[SyncService.uploadPending] uploaded={}, failed={}", uploaded, failed);
    }

    // ── Download ──────────────────────────────────────────────────────────────

    public void downloadAll() throws IOException {
        if (!encryptionService.isConfigured()) {
            log.warn("[SyncService.downloadAll] encryption not configured — skipping");
            return;
        }
        if (!driveService.isConfigured()) {
            log.warn("[SyncService.downloadAll] Drive not configured — skipping");
            return;
        }

        String vaultRoot = settingsRepo.getVaultPath();
        Path vaultRootPath = Paths.get(vaultRoot).toAbsolutePath().normalize();
        List<DriveFileInfo> driveFiles = driveService.listAllFiles();
        int downloaded = 0, skipped = 0, kept = 0;

        for (DriveFileInfo df : driveFiles) {
            try {
                // vault_path comes from Drive metadata — never trust it blindly.
                Path target = vaultRootPath.resolve(df.vaultPath()).normalize();
                if (!target.startsWith(vaultRootPath)) {
                    log.error("[SyncService.downloadAll] rejected Drive path escaping vault: {}", df.vaultPath());
                    continue;
                }
                String absPath  = target.toString();
                String localHash = computeLocalHash(absPath, df.vaultPath());

                if (df.contentHash().equals(localHash)) {
                    skipped++;
                    continue;
                }

                // A PENDING local edit hasn't reached Drive yet — overwriting it
                // here would destroy it (and markSynced would cancel its upload).
                // Local wins until uploadPending has run. DELETE_PENDING likewise:
                // the file was deleted locally and its Drive copy is doomed —
                // downloading would resurrect it AND cancel the tombstone.
                SyncEntry existing = syncQueueRepo.findByPath(df.vaultPath());
                if (existing != null
                        && ("PENDING".equals(existing.status()) || "DELETE_PENDING".equals(existing.status()))) {
                    log.info("[SyncService.downloadAll] {} has a pending local {} — skipping download",
                        df.vaultPath(), "PENDING".equals(existing.status()) ? "edit" : "delete");
                    kept++;
                    continue;
                }

                byte[] encrypted = driveService.downloadFile(df.fileId());
                byte[] plaintext = encryptionService.decrypt(encrypted);
                writeDownloaded(absPath, df.vaultPath(), plaintext);
                syncQueueRepo.markSynced(df.vaultPath(), df.contentHash(), df.fileId());
                downloaded++;

            } catch (Exception e) {
                log.error("[SyncService.downloadAll] failed for {}: {}", df.vaultPath(), e.getMessage());
            }
        }
        log.info("[SyncService.downloadAll] downloaded={}, skipped={}, keptLocal={}", downloaded, skipped, kept);
    }

    // ── Delete propagation (tombstones → Drive trash) ────────────────────────

    /** Drain DELETE_PENDING rows: move the Drive copy to trash, drop the row. */
    private void processTombstones() {
        List<SyncEntry> doomed = syncQueueRepo.findByStatus("DELETE_PENDING");
        for (SyncEntry entry : doomed) {
            try {
                if (entry.driveFileId() != null) {
                    driveService.trashFile(entry.driveFileId());
                }
                syncQueueRepo.delete(entry.path());
            } catch (Exception e) {
                log.error("[SyncService.processTombstones] failed for {}: {}", entry.path(), e.getMessage());
                // row stays DELETE_PENDING — retried next pass
            }
        }
        if (!doomed.isEmpty()) {
            log.info("[SyncService.processTombstones] processed {} tombstone(s)", doomed.size());
        }
    }

    // ── Janitor (weekly orphan sweep) ─────────────────────────────────────────

    public record JanitorResult(boolean dryRun, int scanned, int orphans, long freedBytes,
                                List<String> deletedPaths) {}

    /**
     * Remove Drive files whose local counterpart no longer exists ("orphans" left by
     * pre-tombstone renames/deletes, or edits made outside the app). Safety rails:
     * appProperties-matched files only (listAllFiles guarantees that), a grace period
     * so we never race an in-flight rename, deletion goes to Drive TRASH (30-day
     * recovery), and dryRun mode reports without touching anything.
     */
    public JanitorResult janitor(boolean dryRun) throws IOException {
        if (!driveService.isConfigured()) {
            throw new IOException("Drive not configured");
        }
        Path vaultRoot = Paths.get(settingsRepo.getVaultPath()).toAbsolutePath().normalize();
        long graceCutoff = System.currentTimeMillis() - GRACE_DAYS * 24L * 3600 * 1000;

        List<DriveFileInfo> driveFiles = driveService.listAllFiles();
        List<String> deleted = new java.util.ArrayList<>();
        long freed = 0;

        for (DriveFileInfo df : driveFiles) {
            Path target = vaultRoot.resolve(df.vaultPath()).normalize();
            if (!target.startsWith(vaultRoot)) continue;          // hostile metadata — not ours to judge
            if (Files.exists(target)) continue;                    // local twin alive

            SyncEntry row = syncQueueRepo.findByPath(df.vaultPath());
            if (row != null && "PENDING".equals(row.status())) continue; // about to (re)upload
            if (df.uploadedAt() > graceCutoff) continue;            // too fresh — could be mid-rename

            if (!dryRun) {
                try {
                    driveService.trashFile(df.fileId());
                    syncQueueRepo.delete(df.vaultPath());
                } catch (Exception e) {
                    log.error("[SyncService.janitor] failed for {}: {}", df.vaultPath(), e.getMessage());
                    continue;
                }
            }
            deleted.add(df.vaultPath());
            freed += df.sizeBytes();
        }

        JanitorResult result = new JanitorResult(dryRun, driveFiles.size(), deleted.size(), freed, deleted);
        log.info("[SyncService.janitor] dryRun={} scanned={} orphans={} freedBytes={}",
            dryRun, result.scanned(), result.orphans(), result.freedBytes());
        if (!dryRun) {
            settingsRepo.set("sync.janitor_last",
                System.currentTimeMillis() + ":" + result.orphans() + ":" + result.freedBytes());
        }
        return result;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private byte[] readFile(String absPath, String relativePath) throws IOException {
        if (relativePath.endsWith(".md")) {
            return Files.readString(Paths.get(absPath)).getBytes(StandardCharsets.UTF_8);
        }
        return Files.readAllBytes(Paths.get(absPath));
    }

    private String computeLocalHash(String absPath, String relativePath) {
        try {
            if (relativePath.endsWith(".md")) {
                return ContentHashing.sha256(Files.readString(Paths.get(absPath)));
            }
            return ContentHashing.sha256(Files.readAllBytes(Paths.get(absPath)));
        } catch (IOException e) {
            return ""; // file doesn't exist locally yet
        }
    }

    /** Write a downloaded file to disk. For .md files, also updates the notes index and image queue. */
    private void writeDownloaded(String absPath, String relativePath, byte[] plaintext) throws IOException {
        Files.createDirectories(Paths.get(absPath).getParent());

        if (relativePath.endsWith(".md")) {
            String content = new String(plaintext, StandardCharsets.UTF_8);
            Files.writeString(Paths.get(absPath), content);
            File f = new File(absPath);
            String title = f.getName().replace(".md", "");
            FrontmatterParser.NoteMetadata meta = FrontmatterParser.parse(content);
            noteIndex.upsert(absPath, title, meta, f.lastModified());
            noteLinkRepo.updateLinks(absPath, NoteLinkRepository.extractTargets(content));
            imageScanService.registerImages(absPath, content);
            // No markPending here — markDone is called by the caller immediately after
        } else {
            Files.write(Paths.get(absPath), plaintext);
        }
    }

    static String toRelative(String vaultRoot, String absPath) {
        return Paths.get(vaultRoot).relativize(Paths.get(absPath))
            .toString().replace('\\', '/');
    }
}
