package com.obsidian.obsidian.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A single-threaded "lane" for one background worker's heavy work.
 *
 * <p>Why this exists: every {@code @Scheduled} worker used to run its heavy drain
 * (embedding, image captioning, card generation, the nightly chrono pass) directly
 * on Spring's shared scheduler thread. A worker that BLOCKS — e.g. the image worker
 * waiting on rate-limited vision APIs — therefore starved every other worker
 * (head-of-line blocking). See ml/FLOWS_orchestration.md.
 *
 * <p>The fix: each worker owns a {@code WorkerLane}. Its {@code @Scheduled} tick
 * becomes a non-blocking {@link #trigger(Runnable)} — it hands the drain to this
 * lane's own thread and returns instantly, so the scheduler thread is never held.
 * Lanes run concurrently, which is safe because the one truly contended resource
 * (the single-occupant GPU) is arbitrated downstream in the embedder's gpu_slot,
 * not here.
 *
 * <p>The {@link AtomicBoolean} guard makes {@code trigger} idempotent while a drain
 * is in flight: a tick that fires mid-drain is a no-op rather than stacking a second
 * run. One lane therefore runs at most one drain at a time, back-to-back at most.
 */
public final class WorkerLane {

    private static final Logger log = LoggerFactory.getLogger(WorkerLane.class);

    private final String name;
    private final ExecutorService exec;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public WorkerLane(String name) {
        this.name = name;
        this.exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "lane-" + name);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Submit {@code drain} to run on this lane's thread, unless a drain is already
     * in flight. Returns immediately (never blocks the caller / scheduler thread).
     *
     * @return {@code true} if the drain was submitted; {@code false} if one was
     *         already running (this call was skipped).
     */
    public boolean trigger(Runnable drain) {
        if (!running.compareAndSet(false, true)) {
            return false;   // a drain is already in flight — don't stack another
        }
        try {
            exec.submit(() -> {
                try {
                    drain.run();
                } catch (Throwable t) {   // a drain must never kill the lane thread
                    log.warn("[lane-{}] drain threw: {}", name, t.toString());
                } finally {
                    running.set(false);
                }
            });
            return true;
        } catch (RuntimeException e) {   // e.g. RejectedExecutionException after shutdown
            running.set(false);
            log.warn("[lane-{}] could not submit drain: {}", name, e.toString());
            return false;
        }
    }

    /** True while a drain is executing (or queued) on this lane. */
    public boolean isRunning() {
        return running.get();
    }

    /** Stop the lane thread. Call from the owning worker's {@code @PreDestroy};
     *  the thread is a daemon, so this is graceful cleanup rather than required. */
    public void shutdown() {
        exec.shutdownNow();
    }
}
