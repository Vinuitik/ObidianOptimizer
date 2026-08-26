package com.obsidian.obsidian.ml;

import com.obsidian.obsidian.common.WorkerLane;
import com.obsidian.obsidian.notes.NoteIndexRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    public NoteEmbeddingWorker(NoteIndexRepository noteIndexRepo,
                               EmbeddingService embeddingService,
                               NoteChunkRepository chunkRepo) {
        this.noteIndexRepo    = noteIndexRepo;
        this.embeddingService = embeddingService;
        this.chunkRepo        = chunkRepo;
    }

    @PreDestroy
    void stopLane() { lane.shutdown(); }

    /** Tick: hand the drain to the lane and return immediately. */
    @Scheduled(fixedDelayString = "${embedding.scan.delay-ms:60000}",
               initialDelayString = "${embedding.scan.initial-delay-ms:30000}")
    public void embedPendingNotes() {
        if (!enabled) return;
        lane.trigger(this::drain);
    }

    /** Runs on the lane thread. Drains the backlog in batches, continuing while a
     *  batch comes back full AND progress is being made — so a big first-boot
     *  backlog clears in minutes without waiting a tick per batch, but a persistent
     *  failure (embedder down → zero progress) stops and waits for the next tick. */
    void drain() {
        while (enabled) {
            Map<String, String> pending = noteIndexRepo.findNotesNeedingEmbedding(batchSize);
            if (pending.isEmpty()) return;

            log.info("[NoteEmbeddingWorker] embedding {} note(s)", pending.size());

            int ok = 0;
            for (Map.Entry<String, String> entry : pending.entrySet()) {
                try {
                    if (embeddingService.indexNote(entry.getKey())) {
                        noteIndexRepo.markEmbedded(entry.getKey(), entry.getValue());
                        ok++;
                    }
                    // false → embedder unreachable / partial failure: stays in the
                    // diff and is retried next cycle
                } catch (Exception e) {
                    log.warn("[NoteEmbeddingWorker] failed {}: {}", entry.getKey(), e.getMessage());
                }
            }
            log.debug("[NoteEmbeddingWorker] embedded {}/{} note(s)", ok, pending.size());

            // Stop when the backlog is drained (partial batch) or nothing progressed
            // (embedder down / all failing) — the next tick will retry either way.
            if (pending.size() < batchSize || ok == 0) return;
        }
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
