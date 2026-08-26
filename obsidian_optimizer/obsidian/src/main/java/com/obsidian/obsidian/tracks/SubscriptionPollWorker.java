package com.obsidian.obsidian.tracks;

import com.obsidian.obsidian.capture.CaptureController;
import com.obsidian.obsidian.capture.CaptureIngestWorker;
import com.obsidian.obsidian.capture.CaptureRepository;
import com.obsidian.obsidian.common.WorkerLane;
import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.tracks.TrackAgentClient.Candidate;
import com.obsidian.obsidian.tracks.TrackAgentClient.DiscoverResult;
import com.obsidian.obsidian.tracks.TrackRepository.Track;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Checks each active subscription track (YouTube channel / RSS feed) for new items and
 * enqueues them through the existing durable capture queue — same pipeline the extension
 * and PWA already use ({@code CaptureRepository.enqueue} → {@link CaptureIngestWorker}'s
 * own drain). This worker only discovers and enqueues; it never talks to the embedder's
 * ingest endpoints directly. Mirrors {@link CaptureIngestWorker}'s own shape: cheap
 * {@code @Scheduled} tick → {@link WorkerLane} → claim-and-process drain.
 */
@Component
public class SubscriptionPollWorker {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionPollWorker.class);

    private final TrackRepository trackRepo;
    private final CaptureRepository captureRepo;
    private final CaptureIngestWorker captureIngestWorker;
    private final SettingsRepository settingsRepo;
    private final TrackAgentClient trackAgentClient;
    private final WorkerLane lane = new WorkerLane("subscription-poll");

    public SubscriptionPollWorker(TrackRepository trackRepo, CaptureRepository captureRepo,
                                  CaptureIngestWorker captureIngestWorker, SettingsRepository settingsRepo,
                                  TrackAgentClient trackAgentClient) {
        this.trackRepo = trackRepo;
        this.captureRepo = captureRepo;
        this.captureIngestWorker = captureIngestWorker;
        this.settingsRepo = settingsRepo;
        this.trackAgentClient = trackAgentClient;
    }

    @Scheduled(fixedDelayString = "${subscriptions.poll.tick-ms:300000}",
               initialDelayString = "${subscriptions.poll.initial-delay-ms:30000}")
    public void tick() {
        lane.trigger(this::drain);
    }

    /** User-triggered "check now" from the track UI — bypasses the due-interval check
     *  (the poll interval only throttles the automated tick). */
    public boolean pollNow(long trackId) {
        Track t = trackRepo.get(trackId);
        if (t == null || !"subscription".equals(t.type())) return false;
        pollTrack(t);
        return true;
    }

    void drain() {
        long intervalMs = settingsRepo.getSubscriptionPollIntervalMs();
        for (Track t : trackRepo.listActive()) {
            if (!"subscription".equals(t.type()) || t.sourceUrl() == null) continue;
            boolean due = t.lastCheckedAt() == null
                || Instant.now().isAfter(t.lastCheckedAt().plusMillis(intervalMs));
            if (due) pollTrack(t);
        }
    }

    private void pollTrack(Track t) {
        DiscoverResult res = trackAgentClient.discover(t.sourceUrl(), t.sourceType());
        if (!res.ok()) return;   // lastCheckedAt untouched, retried next tick

        int enqueued = 0;
        for (Candidate c : res.candidates()) {
            if (c.itemUrl() == null || c.itemUrl().isBlank()) continue;
            if (captureRepo.existsForSource(c.itemUrl())) continue;   // already seen this item, ever
            String captureId = UUID.randomUUID().toString().substring(0, 12);
            String title = (c.title() == null || c.title().isBlank()) ? c.itemUrl() : c.title();
            captureRepo.enqueue(captureId, CaptureController.classifyUrl(c.itemUrl()), c.itemUrl(), null, title);
            captureRepo.setTrackId(captureId, t.id());
            enqueued++;
        }
        if (enqueued > 0) {
            log.info("[SubscriptionPollWorker] track {} ({}): {} new item(s) enqueued",
                t.id(), t.sourceUrl(), enqueued);
            captureIngestWorker.nudge();
        }
        trackRepo.markChecked(t.id(), Instant.now());
    }
}
