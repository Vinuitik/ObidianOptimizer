package com.obsidian.obsidian.capture;

import com.obsidian.obsidian.common.IngestClient;
import com.obsidian.obsidian.common.WorkerLane;
import com.obsidian.obsidian.settings.SettingsRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Continuously drains the durable capture queue into the ingest pipeline. The user
 * drops resources (shared links, pasted text) from time to time via
 * {@link CaptureController}; each lands as a {@code queued} row. This worker keeps
 * pulling {@code queued} resources and submitting them through the one
 * {@link IngestClient} gate until the queue is empty — then idles until the next
 * arrival (a {@link #nudge()} from the controller, or the scheduled tick).
 *
 * <p>Why a durable queue and not fire-on-capture: the embedder's own job queue is
 * in-memory (a restart loses anything not yet drained), and the embedder can be down
 * when a resource arrives. Persisting to the {@code capture} table and draining from
 * it means nothing is dropped — a resource captured while the embedder is offline is
 * simply submitted once it returns. Mirrors {@code ImageProcessingWorker}: cheap
 * {@code @Scheduled} tick → {@link WorkerLane} → claim-and-submit drain.
 *
 * <p>Pacing is intentionally light: submitting is a fast POST (the embedder does the
 * minutes-long work off its own worker thread), so a claimed resource flips to
 * {@code processing} in milliseconds; a transient submit failure releases it back to
 * {@code queued} for the next tick.
 */
@Component
public class CaptureIngestWorker {

    private static final Logger log = LoggerFactory.getLogger(CaptureIngestWorker.class);

    private final CaptureRepository captureRepo;
    private final IngestClient ingestClient;
    private final SettingsRepository settingsRepo;
    private final WorkerLane lane = new WorkerLane("capture-ingest");

    // Shared master switch with ResourceScanService: off → the whole ingest pipeline
    // is disabled, so leave queued resources untouched (they drain when re-enabled).
    @Value("${ingest.enabled:true}")
    private boolean ingestEnabled;

    // Resources submitted per drain. Submitting is cheap; this just bounds one tick.
    @Value("${ingest.capture.batch-limit:25}")
    private int batchLimit;

    // The embedder publishes synthesized notes BACK to this backend; during boot Tomcat
    // isn't bound yet, so hold submission until the app is ready (same reasoning as
    // ResourceScanService). The scheduled tick drains the backlog once ready.
    private volatile boolean appReady = false;

    public CaptureIngestWorker(CaptureRepository captureRepo, IngestClient ingestClient,
                               SettingsRepository settingsRepo) {
        this.captureRepo = captureRepo;
        this.ingestClient = ingestClient;
        this.settingsRepo = settingsRepo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onAppReady() {
        appReady = true;
        nudge();   // drain anything captured (or left over) before the first tick
    }

    @PreDestroy
    void shutdown() {
        lane.shutdown();
    }

    /** Scheduled heartbeat — guarantees the queue drains even with no nudges (restart
     *  backlog, released retries, resources enqueued by other paths). */
    @Scheduled(fixedDelayString = "${ingest.capture.delay-ms:15000}",
               initialDelayString = "${ingest.capture.initial-delay-ms:20000}")
    public void tick() {
        if (!ingestEnabled) return;
        lane.trigger(this::drain);
    }

    /** Kick the drain immediately (called by the controller right after enqueue) so a
     *  freshly captured resource ingests without waiting for the next tick. No-op if a
     *  drain is already in flight — the running drain will pick the new row up. */
    public void nudge() {
        if (!ingestEnabled) return;
        lane.trigger(this::drain);
    }

    /** Runs on the capture lane. Claim-and-submit each queued resource; a transient
     *  submit failure releases the row back to {@code queued} for the next tick, so a
     *  down embedder never loses a resource. */
    void drain() {
        if (!appReady) return;
        List<CaptureRepository.Capture> batch = captureRepo.findQueued(batchLimit);
        if (batch.isEmpty()) return;

        int submitted = 0;
        for (CaptureRepository.Capture c : batch) {
            if (!captureRepo.claim(c.id())) continue;   // a concurrent drain won it
            try {
                IngestClient.Result res = submit(c);
                if (res.ok()) {
                    submitted++;
                } else if (res.status() >= 400 && res.status() < 500) {
                    // the embedder rejected the request itself (bad ref/route) — retrying
                    // won't help, so fail it rather than loop forever.
                    captureRepo.updateStatus(c.id(), "failed");
                    log.warn("[CaptureIngestWorker] {} rejected ({}) — marked failed",
                        c.id(), res.status());
                } else {
                    // transport / 5xx — embedder down or busy; release for the next tick.
                    captureRepo.updateStatus(c.id(), "queued");
                }
            } catch (Exception e) {   // reconstruction/read error → don't strand as processing
                captureRepo.updateStatus(c.id(), "queued");
                log.warn("[CaptureIngestWorker] {} submit errored, requeued: {}", c.id(), e.toString());
            }
        }
        if (submitted > 0) {
            log.info("[CaptureIngestWorker] submitted {} of {} queued resource(s)",
                submitted, batch.size());
        }
    }

    /** Rebuild the embedder payload from the persisted capture row. Text captures kept
     *  their prose on disk (CaptureController.storeTextResource); read it back so the
     *  embedder's text route gets the content, not just a path. */
    private IngestClient.Result submit(CaptureRepository.Capture c) throws Exception {
        if ("text".equals(c.sourceType())) {
            String text = readTextResource(c.sourcePath());
            if (text == null || text.isBlank()) {
                return new IngestClient.Result(false, 422, null, "text resource missing");
            }
            return ingestClient.submitText(c.id(), text, c.title());
        }
        return ingestClient.submitStandalone(c.id(), c.sourceRef(), c.sourceType());
    }

    private String readTextResource(String vaultRelPath) throws Exception {
        if (vaultRelPath == null) return null;
        Path file = Paths.get(settingsRepo.getVaultPath()).resolve(vaultRelPath).normalize();
        if (!Files.isRegularFile(file)) return null;
        return Files.readString(file);
    }
}
