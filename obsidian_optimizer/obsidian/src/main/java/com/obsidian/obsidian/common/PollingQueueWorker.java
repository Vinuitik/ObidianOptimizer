package com.obsidian.obsidian.common;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * The Group A shape (see QUEUE_UNIFICATION_PLAN.md), extracted once: claim a batch
 * from a {@link WorkQueue}, process each item, optionally keep draining while a
 * full batch came back AND progress was made. {@code NoteEmbeddingWorker},
 * {@code ChunkEmbeddingReconciler} and {@code ResourceScanService} are thin
 * configurations of this class — each keeps its own {@code @Scheduled} method
 * (Spring needs a literal property key per annotation, so that can't be
 * generalized) and just delegates the batch loop here.
 *
 * <p>{@code enabled}/{@code batchLimit} are suppliers, not captured values —
 * several of these workers expose their {@code enabled}/{@code batchSize} fields
 * to tests via {@code ReflectionTestUtils.setField} on an already-constructed
 * instance (bypassing Spring's {@code @Value} injection and any lifecycle
 * callback), so this must read them live on every call rather than once at
 * construction time.
 */
public final class PollingQueueWorker<T> {

    @FunctionalInterface
    public interface ItemProcessor<T> {
        boolean process(T item) throws Exception;
    }

    /** Per-batch logging hook — each configuration reproduces its own exact
     *  historical log lines here so extracting this class doesn't change them. */
    public interface BatchListener<T> {
        default void onBatchClaimed(List<T> batch) {}
        default void onBatchFinished(List<T> batch, int okCount) {}
    }

    private static final BatchListener<Object> NO_OP_LISTENER = new BatchListener<>() {};

    private final WorkQueue<T> queue;
    private final ItemProcessor<T> processor;
    private final IntSupplier batchLimit;
    private final BooleanSupplier enabled;
    private final boolean continuousDrain;
    private final WorkerLane lane;             // nullable: null => run inline, no lane
    private final BatchListener<T> listener;

    @SuppressWarnings("unchecked")
    public PollingQueueWorker(WorkQueue<T> queue,
                               ItemProcessor<T> processor,
                               IntSupplier batchLimit,
                               BooleanSupplier enabled,
                               boolean continuousDrain,
                               WorkerLane lane,
                               BatchListener<T> listener) {
        this.queue = queue;
        this.processor = processor;
        this.batchLimit = batchLimit;
        this.enabled = enabled;
        this.continuousDrain = continuousDrain;
        this.lane = lane;
        this.listener = listener != null ? listener : (BatchListener<T>) NO_OP_LISTENER;
    }

    /** Tick entry point for the {@code @Scheduled} method: hands the drain to the
     *  lane (if configured) and returns immediately, or runs it inline otherwise. */
    public void tick() {
        if (!enabled.getAsBoolean()) return;
        if (lane != null) {
            lane.trigger(this::drain);
        } else {
            drain();
        }
    }

    /** Runs the batch loop synchronously on the calling thread. With
     *  {@code continuousDrain}, keeps going while a batch came back full AND at
     *  least one item succeeded; otherwise (e.g. a paced retry worker) does at
     *  most one batch per call regardless of how much backlog remains. */
    public void drain() {
        do {
            if (!enabled.getAsBoolean()) return;
            int limit = batchLimit.getAsInt();
            List<T> batch = queue.claimBatch(limit);
            if (batch.isEmpty()) return;

            listener.onBatchClaimed(batch);
            int ok = 0;
            for (T item : batch) {
                boolean success;
                try {
                    success = processor.process(item);
                } catch (Exception e) {
                    queue.markFailed(item, e);
                    continue;
                }
                if (success) {
                    queue.markDone(item);
                    ok++;
                } else {
                    queue.markDeferred(item);
                }
            }
            listener.onBatchFinished(batch, ok);

            if (batch.size() < limit || ok == 0) return;
        } while (continuousDrain);
    }
}
