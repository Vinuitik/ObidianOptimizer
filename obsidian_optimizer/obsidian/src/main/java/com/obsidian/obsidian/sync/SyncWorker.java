package com.obsidian.obsidian.sync;

import com.obsidian.obsidian.common.WorkerLane;
import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.vaults.ActiveVault;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.function.LongConsumer;

@Component
public class SyncWorker {

    private static final Logger log = LoggerFactory.getLogger(SyncWorker.class);

    private final SyncService syncService;
    private final DbBackupService dbBackupService;
    private final SettingsRepository settingsRepo;
    private final ActiveVault activeVault;

    // Own lane: a Drive upload is network-bound and can run for a while; keep it
    // off the shared scheduler thread like every other background worker. DB
    // backup/restore share this lane too, so they never race a file drain.
    private final WorkerLane lane = new WorkerLane("sync");

    public SyncWorker(SyncService syncService, DbBackupService dbBackupService,
                      SettingsRepository settingsRepo, ActiveVault activeVault) {
        this.activeVault = activeVault;
        this.syncService = syncService;
        this.dbBackupService = dbBackupService;
        this.settingsRepo = settingsRepo;
    }

    @PreDestroy
    void stopLane() { lane.shutdown(); }

    /**
     * Runs {@code task} once per vault whose syncEnabled is on (active or not), each call
     * strictly for that vault: its own folder, token, passphrase and vault root. One vault's
     * failure never stops the others. Called on the sync lane.
     */
    void forEachSyncEnabledVault(LongConsumer task) {
        for (long vaultId : settingsRepo.syncEnabledVaultIds()) {
            try {
                task.accept(vaultId);
            } catch (Exception e) {
                log.error("[SyncWorker] vault {} failed: {}", vaultId, e.getMessage());
            }
        }
    }

    /**
     * Manual upload (POST /api/sync/upload). Runs the drain on the sync lane, off the
     * HTTP request thread, so the endpoint returns immediately. Single-flight: if a
     * drain is already in progress this is a no-op.
     *
     * @return true if a fresh drain was started; false if one was already running.
     */
    public boolean triggerManualUpload() {
        final long vaultId = activeVault.id();   // manual actions target the active vault
        return lane.trigger(() -> {
            log.info("[SyncWorker] manual upload triggered (vault {})", vaultId);
            syncService.uploadPending(vaultId);
        });
    }

    /** True while an upload/janitor drain is running on the sync lane. */
    public boolean isBusy() { return lane.isRunning(); }

    // ── DB backup / restore (same lane — never races a file drain) ───────────

    /** Nightly DB dump → Drive (gated on syncEnabled, like scheduled upload). */
    @Scheduled(cron = "${sync.dbbackup.cron:0 0 3 * * *}")
    public void scheduledDbBackup() {
        lane.trigger(() -> {
            log.info("[SyncWorker] scheduled DB backup triggered");
            forEachSyncEnabledVault(v -> syncService.runExclusiveOfUploads(() -> dbBackupService.backupNow(v)));
        });
    }

    /** Manual "Back up DB now". @return true if started, false if the lane was busy. */
    public boolean triggerDbBackup() {
        final long vaultId = activeVault.id();
        return lane.trigger(() -> {
            log.info("[SyncWorker] manual DB backup triggered (vault {})", vaultId);
            syncService.runExclusiveOfUploads(() -> dbBackupService.backupNow(vaultId));
        });
    }

    /** Manual "Full Backup" — push vault files to Drive, THEN dump the DB (so the dump's
     *  sync_queue matches what's on Drive). One lane task. @return true if started. */
    public boolean triggerFullBackup() {
        final long vaultId = activeVault.id();
        return lane.trigger(() -> {
            log.info("[SyncWorker] full backup triggered (files + DB, vault {})", vaultId);
            syncService.uploadPending(vaultId);
            syncService.runExclusiveOfUploads(() -> dbBackupService.backupNow(vaultId));
        });
    }

    /** Manual "Restore from Drive". @return true if started, false if the lane was busy. */
    public boolean triggerDbRestore(boolean force) {
        final long vaultId = activeVault.id();
        return lane.trigger(() -> {
            log.info("[SyncWorker] DB restore triggered (force={}, vault {})", force, vaultId);
            syncService.runExclusiveOfUploads(() -> dbBackupService.restore(vaultId, force));
        });
    }

    // Now the SAFETY NET only (QUEUE_UNIFICATION_PLAN.md Phase 6): markPending's outbox
    // fast path delivers within seconds via the "sync-upload" RabbitMQ listener, so this
    // tick only ever finds work when a publish was missed (crash between the DB write
    // and the publish attempt — OutboxRelay's own sweep already retries that within
    // seconds, so even this is a second-order net) or the retry ladder's own dead cap
    // reset a row. Cadence deliberately left at 6h, UNCHANGED — unlike Group A's
    // original 15-60s pollers, sync's default was already coarse (file-sync latency was
    // never sub-minute-sensitive), and demoting it further (e.g. to 24h, matching the
    // "add a zero" pattern used elsewhere) would leave a missed-publish file stranded
    // on only one device for up to a day for no real benefit — an idle findUploadable()
    // scan costs one cheap query. The Settings toggle gates only this automatic cron;
    // the manual POST /api/sync/upload button works regardless (explicit user action).
    @Scheduled(cron = "${sync.upload.cron:0 0 */6 * * *}")
    public void scheduledUpload() {
        lane.trigger(() -> {
            log.info("[SyncWorker] scheduled upload triggered");
            forEachSyncEnabledVault(syncService::uploadPending);
        });
    }

    // Weekly orphan sweep (Drive files whose local twin is gone → Drive trash).
    // Full Drive BFS, so it gets its own slow cadence — never piggyback on the upload cron.
    @Scheduled(cron = "${sync.janitor.cron:0 0 4 * * SUN}")
    public void scheduledJanitor() {
        lane.trigger(() -> {
            log.info("[SyncWorker] scheduled janitor triggered");
            forEachSyncEnabledVault(v -> {
                try {
                    syncService.janitor(v, false);
                } catch (Exception e) {
                    log.error("[SyncWorker] janitor failed for vault {}: {}", v, e.getMessage());
                }
            });
        });
    }
}
