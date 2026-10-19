package com.obsidian.obsidian.feed;

import com.obsidian.obsidian.capture.CaptureController;
import com.obsidian.obsidian.capture.CaptureIngestWorker;
import com.obsidian.obsidian.capture.CaptureRepository;
import com.obsidian.obsidian.feed.FeedRepository.FeedItem;
import com.obsidian.obsidian.feed.SourceRepository.Source;
import com.obsidian.obsidian.settings.SettingsRepository;
import com.obsidian.obsidian.tracks.TrackRepository;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * accept / restore decisions, shared by {@link FeedController} (user swipe) and
 * {@link SourcePollWorker} (auto-accept). Lives apart from the controller so the worker and
 * controller don't depend on each other. See feed/FLOWS.md.
 */
@Service
public class FeedService {

    private final com.obsidian.obsidian.vaults.ActiveVault activeVault;

    /** Thrown by {@link #restore}: capture already left QUEUED (maps to HTTP 409). */
    public static class CaptureStartedException extends RuntimeException {
        public CaptureStartedException(String m) { super(m); }
    }

    private final FeedRepository feedRepo;
    private final SourceRepository sourceRepo;
    private final CaptureRepository captureRepo;
    private final CaptureIngestWorker ingestWorker;
    private final TrackRepository trackRepo;
    private final SettingsRepository settingsRepo;

    public FeedService(FeedRepository feedRepo, SourceRepository sourceRepo, CaptureRepository captureRepo,
                       CaptureIngestWorker ingestWorker, TrackRepository trackRepo,
                       SettingsRepository settingsRepo,
            com.obsidian.obsidian.vaults.ActiveVault activeVault) {
        this.activeVault = activeVault;
        this.feedRepo = feedRepo;
        this.sourceRepo = sourceRepo;
        this.captureRepo = captureRepo;
        this.ingestWorker = ingestWorker;
        this.trackRepo = trackRepo;
        this.settingsRepo = settingsRepo;
    }

    /** pending/binned → accepted + exactly one capture tagged with the target track.
     *  Idempotent: the atomic claim means a 2nd/concurrent call returns the same captureId.
     *  Returns null if the item doesn't exist. */
    public String accept(long vaultId, long itemId) {
        FeedItem item = feedRepo.get(vaultId, itemId);
        if (item == null) return null;
        String captureId = UUID.randomUUID().toString().substring(0, 12);
        if (!feedRepo.claimAccepted(itemId, captureId)) {
            return feedRepo.get(vaultId, itemId).captureId();   // already accepted → same id
        }
        try {
            Source src = sourceRepo.get(vaultId, item.sourceId());
            long trackId = (src != null && src.targetTrackId() != null)
                ? src.targetTrackId() : trackRepo.getOrCreatePassive(vaultId).id();
            String title = (item.title() == null || item.title().isBlank()) ? item.itemUrl() : item.title();
            if (item.body() != null && !item.body().isBlank()) {
                // youtube_posts: the full text is already in hand (see feed/FLOWS.md) — a
                // text capture (body kept under resources/files/<id>.md, which the ingest
                // worker reads back for source_type 'text'), not a URL fetch.
                captureRepo.enqueue(vaultId, captureId, "text", title, storePostText(vaultId, captureId, title, item.body()), title);
            } else {
                captureRepo.enqueue(vaultId, captureId, CaptureController.classifyUrl(item.itemUrl()),
                    item.itemUrl(), null, title);
            }
            captureRepo.setTrackId(captureId, trackId);
        } catch (RuntimeException e) {
            feedRepo.markPending(itemId);   // don't strand an 'accepted' row with no capture
            throw e;
        }
        ingestWorker.nudge();
        return captureId;
    }

    public void bin(long vaultId, long itemId) {
        if (feedRepo.get(vaultId, itemId) == null) return;
        feedRepo.bin(itemId);
    }

    /** → pending. An accepted item is only restorable while its capture is still QUEUED
     *  (cancelled atomically); otherwise {@link CaptureStartedException}. */
    public void restore(long vaultId, long itemId) {
        FeedItem item = feedRepo.get(vaultId, itemId);
        if (item == null) return;
        if ("accepted".equals(item.status()) && item.captureId() != null
                && captureRepo.get(item.captureId()) != null
                && !captureRepo.cancelQueued(item.captureId())) {
            throw new CaptureStartedException("capture already started");
        }
        feedRepo.markPending(itemId);
    }

    private String storePostText(long vaultId, String captureId, String title, String text) {
        try {
            Path dir = activeVault.rootFor(vaultId).resolve("resources").resolve("files");
            Files.createDirectories(dir);
            String name = captureId + ".md";
            Files.writeString(dir.resolve(name), "# " + title.replace("\n", " ").trim() + "\n\n" + text.trim() + "\n");
            return "resources/files/" + name;
        } catch (java.io.IOException e) {
            throw new RuntimeException("could not store post text for capture " + captureId, e);
        }
    }
}
