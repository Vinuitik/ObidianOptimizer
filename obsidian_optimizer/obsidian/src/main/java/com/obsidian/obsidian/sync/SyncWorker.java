package com.obsidian.obsidian.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SyncWorker {

    private static final Logger log = LoggerFactory.getLogger(SyncWorker.class);

    private final SyncService syncService;

    public SyncWorker(SyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(cron = "${sync.upload.cron:0 0 */6 * * *}")
    public void scheduledUpload() {
        log.info("[SyncWorker] scheduled upload triggered");
        syncService.uploadPending();
    }
}
