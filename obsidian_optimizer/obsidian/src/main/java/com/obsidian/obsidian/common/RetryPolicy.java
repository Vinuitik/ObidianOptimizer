package com.obsidian.obsidian.common;

/**
 * How a {@link WorkQueue} should treat a failed/deferred item. Today every Group A
 * poller (see QUEUE_UNIFICATION_PLAN.md) uses {@link #unbounded()} — matching their
 * current behavior of "leave it in the WHERE-clause predicate, retry forever, no
 * backoff." {@link #capped} is the shape a later phase extends (e.g. with a
 * {@code .backoff(Duration...)} builder step) to add dead-lettering without
 * changing {@link WorkQueue} or {@link PollingQueueWorker}.
 */
public final class RetryPolicy {

    private final boolean unbounded;
    private final int maxAttempts;

    private RetryPolicy(boolean unbounded, int maxAttempts) {
        this.unbounded = unbounded;
        this.maxAttempts = maxAttempts;
    }

    public static RetryPolicy unbounded() {
        return new RetryPolicy(true, -1);
    }

    public static Builder capped(int maxAttempts) {
        return new Builder(maxAttempts);
    }

    public boolean isUnbounded() {
        return unbounded;
    }

    /** -1 when {@link #isUnbounded()}. */
    public int maxAttempts() {
        return maxAttempts;
    }

    public static final class Builder {
        private final int maxAttempts;

        private Builder(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        // Future phases add .backoff(...), .deadLetter(...) etc. here.
        public RetryPolicy build() {
            return new RetryPolicy(false, maxAttempts);
        }
    }
}
