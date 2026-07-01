package com.obsidian.obsidian.sync;

import com.obsidian.obsidian.common.WorkerLane;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SyncWorker {

    private static final Logger log = LoggerFactory.getLogger(SyncWorker.class);

    private final SyncService syncService;

    // Own lane: a Drive upload is network-bound and can run for a while; keep it
    // off the shared scheduler thread like every other background worker.
    private final WorkerLane lane = new WorkerLane("sync");

    public SyncWorker(SyncService syncService) {
        this.syncService = syncService;
    }

    @PreDestroy
    void stopLane() { lane.shutdown(); }

    @Scheduled(cron = "${sync.upload.cron:0 0 */6 * * *}")
    public void scheduledUpload() {
        lane.trigger(() -> {
            log.info("[SyncWorker] scheduled upload triggered");
            syncService.uploadPending();
        });
    }
}
