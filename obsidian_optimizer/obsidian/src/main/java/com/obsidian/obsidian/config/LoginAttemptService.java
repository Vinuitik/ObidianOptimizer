package com.obsidian.obsidian.config;


import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory per-IP failed-login counter: MAX_FAILURES failures → locked for LOCK_MS. Resets on restart. */
public class LoginAttemptService {

    static final int  MAX_FAILURES = 5;
    static final long LOCK_MS      = 15 * 60 * 1000L;

    private record Entry(int failures, long lockedUntil) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttemptService() { this(Clock.systemUTC()); }

    LoginAttemptService(Clock clock) { this.clock = clock; }

    /** Seconds left on the lock, or 0 when the IP may try. */
    public long retryAfterSeconds(String ip) {
        Entry e = entries.get(ip);
        if (e == null || e.lockedUntil() == 0) return 0;
        long left = e.lockedUntil() - clock.millis();
        if (left <= 0) { entries.remove(ip); return 0; }
        return (left + 999) / 1000;
    }

    public void recordFailure(String ip) {
        long now = clock.millis();
        entries.merge(ip, new Entry(1, 0), (old, ignored) -> {
            if (old.lockedUntil() != 0 && old.lockedUntil() <= now) return new Entry(1, 0);
            int n = old.failures() + 1;
            return new Entry(n, n >= MAX_FAILURES ? now + LOCK_MS : old.lockedUntil());
        });
        if (entries.size() > 10_000) {
            entries.entrySet().removeIf(en -> en.getValue().lockedUntil() != 0 && en.getValue().lockedUntil() <= now);
        }
    }

    public void recordSuccess(String ip) { entries.remove(ip); }
}
