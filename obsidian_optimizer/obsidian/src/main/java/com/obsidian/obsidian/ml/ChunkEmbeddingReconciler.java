package com.obsidian.obsidian.ml;

import com.obsidian.obsidian.common.PollingQueueWorker;
import com.obsidian.obsidian.common.WorkQueue;
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

    private final PollingQueueWorker<PendingChunk> pollingWorker;

    public ChunkEmbeddingReconciler(NoteChunkRepository chunkRepo, EmbeddingService embeddingService) {
        this.chunkRepo = chunkRepo;
        this.embeddingService = embeddingService;

        WorkQueue<PendingChunk> queue = new WorkQueue<>() {
            @Override
            public List<PendingChunk> claimBatch(int limit) {
                return chunkRepo.findChunksNeedingEmbedding(limit);
            }

            // The vector is only known once the processor computes it, so the
            // actual setChunkEmbedding write lives there — these are no-ops today,
            // kept for a later phase's RetryPolicy.
            @Override public void markDone(PendingChunk item) {}
            @Override public void markFailed(PendingChunk item, Exception error) {}
            @Override public void markDeferred(PendingChunk item) {}
        };

        PollingQueueWorker.ItemProcessor<PendingChunk> processor = c -> {
            try {
                float[] embedding = embeddingService.embed(c.text());
                if (embedding != null
                    && chunkRepo.setChunkEmbedding(c.notePath(), c.source(), c.chunkIndex(), embedding)) {
                    return true;
                }
                // embedding null → embedder unreachable; leave NULL, retry next cycle
                return false;
            } catch (Exception e) {
                log.warn("[ChunkEmbeddingReconciler] failed {}#{} ({}): {}",
                    c.notePath(), c.chunkIndex(), c.source(), e.getMessage());
                return false;
            }
        };

        PollingQueueWorker.BatchListener<PendingChunk> listener = new PollingQueueWorker.BatchListener<>() {
            @Override
            public void onBatchClaimed(List<PendingChunk> batch) {
                log.info("[ChunkEmbeddingReconciler] embedding {} pending chunk(s)", batch.size());
            }

            @Override
            public void onBatchFinished(List<PendingChunk> batch, int okCount) {
                log.debug("[ChunkEmbeddingReconciler] embedded {}/{} chunk(s)", okCount, batch.size());
            }
        };

        this.pollingWorker = new PollingQueueWorker<>(queue, processor,
            () -> batchSize, () -> enabled, true, lane, listener);
    }

    @PreDestroy
    void stopLane() { lane.shutdown(); }

    @Scheduled(fixedDelayString = "${embedding.reconcile.delay-ms:15000}",
               initialDelayString = "${embedding.reconcile.initial-delay-ms:45000}")
    public void reconcilePendingChunks() {
        pollingWorker.tick();
    }

    /** Runs on the embed-reconcile lane. Drains NULL-vector chunks in batches,
     *  continuing while a batch comes back full AND progress is made; stops on a
     *  partial batch (drained) or zero progress (embedder down → next tick retries). */
    void drain() {
        pollingWorker.drain();
    }
}
