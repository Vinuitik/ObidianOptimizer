package com.obsidian.obsidian.common;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerLaneTest {

    /** Spin-wait until the lane goes idle (or time out). Avoids an extra test dep. */
    private static void awaitIdle(WorkerLane lane) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (lane.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(lane.isRunning()).as("lane should be idle").isFalse();
    }

    @Test
    void triggerRunsTheDrain() throws InterruptedException {
        WorkerLane lane = new WorkerLane("test-run");
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();

        boolean submitted = lane.trigger(() -> { ran.incrementAndGet(); done.countDown(); });

        assertThat(submitted).isTrue();
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(ran.get()).isEqualTo(1);
        lane.shutdown();
    }

    @Test
    void triggerDoesNotStackWhileRunning() throws InterruptedException {
        WorkerLane lane = new WorkerLane("test-nostack");
        CountDownLatch block = new CountDownLatch(1);   // first drain waits on this
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();

        // First drain starts and blocks, holding the lane.
        boolean first = lane.trigger(() -> {
            runs.incrementAndGet();
            started.countDown();
            try { block.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        });
        assertThat(first).isTrue();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        // Second trigger WHILE the first is running must be rejected (no stacking).
        boolean second = lane.trigger(runs::incrementAndGet);
        assertThat(second).isFalse();

        block.countDown();                       // let the first drain finish
        awaitIdle(lane);

        // Once idle, a new trigger is accepted again.
        CountDownLatch done = new CountDownLatch(1);
        boolean third = lane.trigger(() -> { runs.incrementAndGet(); done.countDown(); });
        assertThat(third).isTrue();
        assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(runs.get()).isEqualTo(2);     // first + third ran; second never did
        lane.shutdown();
    }

    @Test
    void triggerReturnsImmediatelyEvenWhenDrainBlocks() {
        WorkerLane lane = new WorkerLane("test-fast");
        CountDownLatch block = new CountDownLatch(1);

        long start = System.nanoTime();
        lane.trigger(() -> { try { block.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } });
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isLessThan(200);   // caller/scheduler thread not held
        block.countDown();
        lane.shutdown();
    }

    @Test
    void triggerAfterShutdownIsRejectedNotThrown() {
        WorkerLane lane = new WorkerLane("test-shutdown");
        lane.shutdown();
        assertThat(lane.trigger(() -> { })).isFalse();   // no exception escapes
    }
}
