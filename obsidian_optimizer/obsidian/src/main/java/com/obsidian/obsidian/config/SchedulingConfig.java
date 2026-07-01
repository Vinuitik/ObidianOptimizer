package com.obsidian.obsidian.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Multi-threaded scheduler for all {@code @Scheduled} triggers.
 *
 * <p>Spring's default scheduler is single-threaded, so every {@code @Scheduled}
 * method shares ONE thread — a slow/blocking tick starves all the others. Each
 * heavy worker now hands its drain to its own {@link com.obsidian.obsidian.common.WorkerLane}
 * so the ticks themselves are cheap, but we still give the scheduler a small pool
 * as belt-and-suspenders: even the trivial triggers plus the nightly chrono cron
 * never serialise behind one another.
 *
 * <p>Pool size 3 comfortably covers the handful of periodic triggers; the actual
 * work runs off on the per-worker lanes, not here.
 */
@Configuration
public class SchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("sched-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }
}
