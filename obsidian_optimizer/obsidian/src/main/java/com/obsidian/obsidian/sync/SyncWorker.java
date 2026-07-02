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
    private final SettingsRepository settingsRepo;

    // Own lane: a Drive upload is network-bound and can run for a while; keep it
    // off the shared scheduler thread like every other background worker.
    private final WorkerLane lane = new WorkerLane("sync");

    public SyncWorker(SyncService syncService, SettingsRepository settingsRepo) {
        this.syncService = syncService;
        this.settingsRepo = settingsRepo;
    }

    @PreDestroy
    void stopLane() { lane.shutdown(); }

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
}
