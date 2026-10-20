package com.obsidian.obsidian.vaults;

import com.obsidian.obsidian.common.PollingQueueWorker;
import com.obsidian.obsidian.common.WorkQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drains {@code vault_deletion_jobs}: backup (once) -> purge DB rows -> drop registration.
 * Runs only for jobs the user enqueued; there is no time-based/automatic trigger. Never touches
 * files on disk. A crash leaves the job RUNNING, so the next tick resumes it.
 */
@Component
public class VaultDeletionWorker {

    private static final Logger log = LoggerFactory.getLogger(VaultDeletionWorker.class);

    @Value("${vaults.deletion.enabled:true}")
    private boolean enabled;

    private final VaultDeletionRepository repo;
    private final VaultBackup backup;
    private final PollingQueueWorker<Long> worker;

    public VaultDeletionWorker(VaultDeletionRepository repo, VaultBackup backup) {
        this.repo = repo;
        this.backup = backup;
        WorkQueue<Long> queue = new WorkQueue<>() {
            @Override public List<Long> claimBatch(int limit) { return repo.claimPending(limit); }
            @Override public void markDone(Long id) { repo.markDone(id); }
            @Override public void markFailed(Long id, Exception e) {
                log.error("[VaultDeletion] vault {} failed", id, e);
                repo.markFailed(id, String.valueOf(e.getMessage()));
            }
            @Override public void markDeferred(Long id) {}
        };
        // continuousDrain=false: one batch per tick (a FAILED job is not re-claimed anyway).
        this.worker = new PollingQueueWorker<>(queue, this::process, () -> 5, () -> enabled, false, null, null);
    }

    boolean process(long id) throws Exception {
        repo.markRunning(id);
        if (!repo.backupDone(id)) {
            backup.backup(id);
            repo.markBackupDone(id);
        }
        repo.purge(id);
        return true;
    }

    @Scheduled(fixedDelayString = "${vaults.deletion.poll-ms:5000}", initialDelayString = "${vaults.deletion.initial-delay-ms:10000}")
    public void tick() { worker.tick(); }

    /** For tests: run one batch synchronously. */
    void drainOnce() { worker.drain(); }
}
