package com.obsidian.obsidian.ml;

import com.obsidian.obsidian.common.WorkerLane;
import com.obsidian.obsidian.ml.NoteChunkRepository.PendingChunk;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The embed queue: any {@code note_chunks} row with a NULL vector is work to do,
 * regardless of source. This decouples "produce the text" from "embed the text" —
 * the image caption stage writes text with a NULL vector (so an expensive VLM
 * caption is never lost to a cheap embed failure), and this reconciler fills the
 * vector. Self-healing like {@link NoteEmbeddingWorker}: a chunk's vector is set
 * only on a successful embed, so a transient embedder failure just leaves it NULL
 * for the next cycle. See ml/FLOWS_orchestration.md.
 *
 * One consumer today (its own lane); the claim query can move to FOR UPDATE SKIP
 * LOCKED if concurrent embed consumers are ever added.
 */
@Component
public class ChunkEmbeddingReconciler {

    private static final Logger log = LoggerFactory.getLogger(ChunkEmbeddingReconciler.class);

    // Shares the embedding master switch — no embedding stage runs in quiet mode.
    @Value("${embedding.enabled:true}")
    private boolean enabled;

    @Value("${embedding.reconcile.batch-limit:100}")
    private int batchSize;

    private final NoteChunkRepository chunkRepo;
    private final EmbeddingService embeddingService;
    private final WorkerLane lane = new WorkerLane("embed-reconcile");

    public ChunkEmbeddingReconciler(NoteChunkRepository chunkRepo, EmbeddingService embeddingService) {
        this.chunkRepo = chunkRepo;
        this.embeddingService = embeddingService;
    }

    @PreDestroy
    void stopLane() { lane.shutdown(); }

    @Scheduled(fixedDelayString = "${embedding.reconcile.delay-ms:15000}",
               initialDelayString = "${embedding.reconcile.initial-delay-ms:45000}")
    public void reconcilePendingChunks() {
        if (!enabled) return;
        lane.trigger(this::drain);
    }

    /** Runs on the embed-reconcile lane. Drains NULL-vector chunks in batches,
     *  continuing while a batch comes back full AND progress is made; stops on a
     *  partial batch (drained) or zero progress (embedder down → next tick retries). */
    void drain() {
        while (enabled) {
            List<PendingChunk> pending = chunkRepo.findChunksNeedingEmbedding(batchSize);
            if (pending.isEmpty()) return;

            log.info("[ChunkEmbeddingReconciler] embedding {} pending chunk(s)", pending.size());

            int ok = 0;
            for (PendingChunk c : pending) {
                try {
                    float[] embedding = embeddingService.embed(c.text());
                    if (embedding != null
                        && chunkRepo.setChunkEmbedding(c.notePath(), c.source(), c.chunkIndex(), embedding)) {
                        ok++;
                    }
                    // embedding null → embedder unreachable; leave NULL, retry next cycle
                } catch (Exception e) {
                    log.warn("[ChunkEmbeddingReconciler] failed {}#{} ({}): {}",
                        c.notePath(), c.chunkIndex(), c.source(), e.getMessage());
                }
            }
            log.debug("[ChunkEmbeddingReconciler] embedded {}/{} chunk(s)", ok, pending.size());

            if (pending.size() < batchSize || ok == 0) return;
        }
    }
}
