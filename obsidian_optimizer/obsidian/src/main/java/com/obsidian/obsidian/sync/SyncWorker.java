package com.obsidian.obsidian.sync;

import com.obsidian.obsidian.common.WorkerLane;
import com.obsidian.obsidian.settings.SettingsRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SyncWorker {

    private static final Logger log = LoggerFactory.getLogger(SyncWorker.class);

    private final SyncService syncService;
    private final DbBackupService dbBackupService;
    private final SettingsRepository settingsRepo;

    // Own lane: a Drive upload is network-bound and can run for a while; keep it
    // off the shared scheduler thread like every other background worker. DB
    // backup/restore share this lane too, so they never race a file drain.
    private final WorkerLane lane = new WorkerLane("sync");

    public SyncWorker(SyncService syncService, DbBackupService dbBackupService,
                      SettingsRepository settingsRepo) {
        this.syncService = syncService;
        this.dbBackupService = dbBackupService;
        this.settingsRepo = settingsRepo;
    }

    @PreDestroy
    void stopLane() { lane.shutdown(); }

    /**
     * Manual upload (POST /api/sync/upload). Runs the drain on the sync lane, off the
     * HTTP request thread, so the endpoint returns immediately. Single-flight: if a
     * drain is already in progress this is a no-op.
     *
     * @return true if a fresh drain was started; false if one was already running.
     */
    public boolean triggerManualUpload() {
        return lane.trigger(() -> {
            log.info("[SyncWorker] manual upload triggered");
            syncService.uploadPending();
        });
    }

    /** True while an upload/janitor drain is running on the sync lane. */
    public boolean isBusy() { return lane.isRunning(); }

    // ── DB backup / restore (same lane — never races a file drain) ───────────

    /** Nightly DB dump → Drive (gated on syncEnabled, like scheduled upload). */
    @Scheduled(cron = "${sync.dbbackup.cron:0 0 3 * * *}")
    public void scheduledDbBackup() {
        if (!settingsRepo.isSyncEnabled()) {
            log.debug("[SyncWorker] sync disabled — skipping scheduled DB backup");
            return;
        }
        lane.trigger(() -> {
            log.info("[SyncWorker] scheduled DB backup triggered");
            dbBackupService.backupNow();
        });
    }

    /** Manual "Back up DB now". @return true if started, false if the lane was busy. */
    public boolean triggerDbBackup() {
        return lane.trigger(() -> {
            log.info("[SyncWorker] manual DB backup triggered");
            dbBackupService.backupNow();
        });
    }

    /** Manual "Full Backup" — push vault files to Drive, THEN dump the DB (so the dump's
     *  sync_queue matches what's on Drive). One lane task. @return true if started. */
    public boolean triggerFullBackup() {
        return lane.trigger(() -> {
            log.info("[SyncWorker] full backup triggered (files + DB)");
            syncService.uploadPending();
            dbBackupService.backupNow();
        });
    }

    /** Manual "Restore from Drive". @return true if started, false if the lane was busy. */
    public boolean triggerDbRestore(boolean force) {
        return lane.trigger(() -> {
            log.info("[SyncWorker] DB restore triggered (force={})", force);
            dbBackupService.restore(force);
        });
    }

    // The Settings toggle gates only this automatic cron; the manual
    // POST /api/sync/upload button works regardless (explicit user action).
    @Scheduled(cron = "${sync.upload.cron:0 0 */6 * * *}")
    public void scheduledUpload() {
        if (!settingsRepo.isSyncEnabled()) {
            log.debug("[SyncWorker] sync disabled in settings — skipping scheduled upload");
            return;
        }
        lane.trigger(() -> {
            log.info("[SyncWorker] scheduled upload triggered");
            syncService.uploadPending();
        });
    }

    // Weekly orphan sweep (Drive files whose local twin is gone → Drive trash).
    // Full Drive BFS, so it gets its own slow cadence — never piggyback on the upload cron.
    @Scheduled(cron = "${sync.janitor.cron:0 0 4 * * SUN}")
    public void scheduledJanitor() {
        if (!settingsRepo.isSyncEnabled()) {
            log.debug("[SyncWorker] sync disabled in settings — skipping janitor");
            return;
        }
        lane.trigger(() -> {
            log.info("[SyncWorker] scheduled janitor triggered");
            try {
                syncService.janitor(false);
            } catch (Exception e) {
                log.error("[SyncWorker] janitor failed: {}", e.getMessage());
            }
        });
    }
}
