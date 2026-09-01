package com.obsidian.obsidian.common;

import java.util.List;

/**
 * A pollable work list: claim a batch of candidates, report what happened to each.
 * {@link PollingQueueWorker} drives one of these; today's three implementations
 * (Group A pollers) leave the mark* hooks as no-ops since the actual DB write
 * happens inside the item processor (it needs data — e.g. a freshly computed
 * embedding vector — that isn't part of the claimed item). The hooks exist so a
 * later phase can add real capped/backoff/dead-letter handling (see
 * {@link RetryPolicy}) without reshaping this interface.
 */
public interface WorkQueue<T> {

    /** Claim up to {@code limit} candidate items. */
    List<T> claimBatch(int limit);

    /** Item was processed successfully. */
    void markDone(T item);

    /** Item processing threw. */
    void markFailed(T item, Exception error);

    /** Item processing completed without error but made no progress (e.g. a
     *  downstream dependency was unavailable) — left for the next poll. */
    void markDeferred(T item);
}
