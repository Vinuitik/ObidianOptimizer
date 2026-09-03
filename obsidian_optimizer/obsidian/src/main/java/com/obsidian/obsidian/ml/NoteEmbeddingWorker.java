package com.obsidian.obsidian.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsidian.obsidian.common.PollingQueueWorker;
import com.obsidian.obsidian.common.WorkQueue;
import com.obsidian.obsidian.common.WorkerLane;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Embeds note TEXT into note_chunks (source='text'). Same philosophy as the
 * cards worker: the notes.content_hash ↔ notes.embedded_hash diff IS the work
 * list — covers note creation, app edits, sync downloads, chrono rewrites,
 * external Obsidian edits (hash loop), and first-boot backfill with zero
 * call-site hooks. Image chunks are owned by ImageProcessingWorker.
 */
@Component
public class NoteEmbeddingWorker {

    private static final Logger log = LoggerFactory.getLogger(NoteEmbeddingWorker.class);

    // Quiet mode: false → no note embedding runs (boot the app light for testing).
    @Value("${embedding.enabled:true}")
    private boolean enabled;

    // Gentle defaults (was hardcoded 20 / 30s): tunable without a rebuild so the
    // backlog can trickle instead of hammering a memory-tight host.
    @Value("${embedding.batch-limit:8}")
    private int batchSize;

    private final NoteIndexRepository noteIndexRepo;
    private final EmbeddingService embeddingService;
    private final NoteChunkRepository chunkRepo;

    // Own lane: the drain runs here, never on the scheduler thread, so a slow
    // embedder call can't starve the image / card / chrono workers (and vice versa).
    private final WorkerLane lane = new WorkerLane("embed");

    private record EmbedCandidate(String path, String hash) {}

    private final PollingQueueWorker<EmbedCandidate> pollingWorker;
    private final ObjectMapper mapper = new ObjectMapper();

    public NoteEmbeddingWorker(NoteIndexRepository noteIndexRepo,
                               EmbeddingService embeddingService,
                               NoteChunkRepository chunkRepo) {
        this.noteIndexRepo    = noteIndexRepo;
        this.embeddingService = embeddingService;
        this.chunkRepo        = chunkRepo;

        WorkQueue<EmbedCandidate> queue = new WorkQueue<>() {
            @Override
            public List<EmbedCandidate> claimBatch(int limit) {
                Map<String, String> pending = noteIndexRepo.findNotesNeedingEmbedding(limit);
                List<EmbedCandidate> out = new ArrayList<>(pending.size());
                for (Map.Entry<String, String> e : pending.entrySet()) {
                    out.add(new EmbedCandidate(e.getKey(), e.getValue()));
                }
                return out;
            }

            // Persistence happens inside the processor below (markEmbedded must run
            // only after a successful indexNote, in the same try/catch) — these are
            // no-ops today, kept for a later phase's RetryPolicy.
            @Override public void markDone(EmbedCandidate item) {}
            @Override public void markFailed(EmbedCandidate item, Exception error) {}
            @Override public void markDeferred(EmbedCandidate item) {}
        };

        PollingQueueWorker.ItemProcessor<EmbedCandidate> processor =
            candidate -> embedOne(candidate.path(), candidate.hash());

        PollingQueueWorker.BatchListener<EmbedCandidate> listener = new PollingQueueWorker.BatchListener<>() {
            @Override
            public void onBatchClaimed(List<EmbedCandidate> batch) {
                log.info("[NoteEmbeddingWorker] embedding {} note(s)", batch.size());
            }

            @Override
            public void onBatchFinished(List<EmbedCandidate> batch, int okCount) {
                log.debug("[NoteEmbeddingWorker] embedded {}/{} note(s)", okCount, batch.size());
            }
        };

        this.pollingWorker = new PollingQueueWorker<>(queue, processor,
            () -> batchSize, () -> enabled, true, lane, listener);
    }

    @PreDestroy
    void stopLane() { lane.shutdown(); }

    /** Tick: hand the drain to the lane and return immediately. Now the SAFETY NET
     *  (default 1h, was 60s) — the outbox+RabbitMQ path from ImageScanService's
     *  chokepoint is the primary trigger, delivering within seconds instead of
     *  waiting for this tick. See QUEUE_UNIFICATION_PLAN.md Phase 3. */
    @Scheduled(fixedDelayString = "${embedding.scan.delay-ms:3600000}",
               initialDelayString = "${embedding.scan.initial-delay-ms:30000}")
    public void embedPendingNotes() {
        pollingWorker.tick();
    }

    /**
     * Fast path: consumes the "embed" outbox queue, one note per message. Reuses the
     * exact same per-note logic as the polling fallback ({@link #embedOne}) — the only
     * difference is where the content_hash comes from (a poll batch snapshots it at
     * claim time; here the message carries no hash, so the CURRENT hash is read fresh,
     * which doubles as the idempotency check: if the note changed again since this
     * message was published, embedOne's markEmbedded WHERE-guard against the stale
     * hash it captured will still be correct because we read fresh, not stale).
     */
    @RabbitListener(queues = com.obsidian.obsidian.common.RabbitQueueConfig.EMBED_QUEUE)
    public void onEmbedMessage(String payloadJson) {
        String path;
        try {
            JsonNode node = mapper.readTree(payloadJson);
            path = node.path("notePath").asText(null);
        } catch (Exception e) {
            log.warn("[NoteEmbeddingWorker] malformed embed message, dropping: {}", e.getMessage());
            return;
        }
        if (path == null) return;
        String hash = noteIndexRepo.getContentHash(path);
        if (hash == null) return; // note deleted or never hashed — nothing to do
        embedOne(path, hash);
    }

    /** Shared by the polling fallback and the RabbitMQ fast path: embed one note and
     *  record success against the given hash (must be the hash currently in
     *  {@code notes.content_hash} for markEmbedded's guard to actually take). */
    boolean embedOne(String path, String hash) {
        try {
            if (embeddingService.indexNote(path)) {
                noteIndexRepo.markEmbedded(path, hash);
                return true;
            }
            // false → embedder unreachable / partial failure: stays in the
            // diff and is retried next cycle
            return false;
        } catch (Exception e) {
            log.warn("[NoteEmbeddingWorker] failed {}: {}", path, e.getMessage());
            return false;
        }
    }

    /** Runs on the lane thread. Drains the backlog in batches, continuing while a
     *  batch comes back full AND progress is being made — so a big first-boot
     *  backlog clears in minutes without waiting a tick per batch, but a persistent
     *  failure (embedder down → zero progress) stops and waits for the next tick. */
    void drain() {
        pollingWorker.drain();
    }

    /**
     * One-off backfill: walk every note and add its title chunk (source='title')
     * if missing. Safe to call repeatedly — {@link EmbeddingService#indexNote}
     * content-hash-gates every chunk (title included), so notes that already have
     * an up-to-date title chunk cost one hash compare and no embed call. Runs on
     * the same lane as the scheduled drain, so it never overlaps a normal embed
     * pass; returns false (no-op) if one is already in flight.
     */
    public boolean backfillTitleChunks() {
        return lane.trigger(() -> {
            List<String> paths = noteIndexRepo.getAllPaths();
            log.info("[NoteEmbeddingWorker] title backfill: scanning {} note(s)", paths.size());
            int ok = 0;
            for (String path : paths) {
                try {
                    if (embeddingService.indexNote(path)) ok++;
                } catch (Exception e) {
                    log.warn("[NoteEmbeddingWorker] title backfill failed for {}: {}", path, e.getMessage());
                }
            }
            log.info("[NoteEmbeddingWorker] title backfill done: {}/{} note(s) ok", ok, paths.size());
        });
    }

    /** Best-effort: true while a drain (scheduled or backfill) is on the lane.
     *  Lost on restart like everything else in-memory — combine with a DB-truth
     *  count (see {@link NoteChunkRepository#countDistinctNotesForSource}) for a
     *  signal that actually survives a redeploy. */
    public boolean isBackfillRunning() {
        return lane.isRunning();
    }

    /** Daily: drop chunks belonging to deleted/renamed notes (text AND image). */
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 7_200_000)
    public void purgeOrphanChunks() {
        try {
            int purged = chunkRepo.deleteOrphanChunks();
            if (purged > 0) {
                log.info("[NoteEmbeddingWorker] purged {} orphan chunk(s) of deleted/renamed notes", purged);
            }
        } catch (Exception e) {
            log.warn("[NoteEmbeddingWorker] orphan purge failed: {}", e.getMessage());
        }
    }
}
