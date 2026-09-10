package com.trinogate.flow;

import com.trinogate.config.FlowConfig.RateLimitConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single-instance fixed-window rate limiter (1-minute windows) keyed by user.
 * Quota resolution: explicit perUser map > defaultPerMinute (0 = unlimited).
 */
public class WindowRateLimiter implements RateLimiter {

    static final long WINDOW_MILLIS = 60_000;

    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;
    private final int defaultLimit;
    private final Map<String, Integer> perUser;

    public WindowRateLimiter(RateLimitConfig config) {
        this.defaultLimit = config.getDefaultPerMinute();
        this.perUser = config.getPerUser() == null ? Map.of() : config.getPerUser();
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limiter-cleaner");
            t.setDaemon(true);
            return t;
        });
        this.cleaner.scheduleAtFixedRate(this::purge, 2, 2, TimeUnit.MINUTES);
    }

    @Override
    public boolean tryAcquire(String user) {
        int limit = limitOf(user);
        if (limit <= 0) {
            return true;
        }
        long window = System.currentTimeMillis() / WINDOW_MILLIS;
        Counter counter = counters.compute(user, (k, old) ->
                old == null || old.window != window ? new Counter(window) : old);
        return counter.count.incrementAndGet() <= limit;
    }

    @Override
    public int retryAfterSeconds(String user) {
        long remaining = WINDOW_MILLIS - (System.currentTimeMillis() % WINDOW_MILLIS);
        return (int) Math.max(1, Math.ceil(remaining / 1000.0));
    }

    public int limitOf(String user) {
        Integer explicit = perUser.get(user);
        return explicit != null ? explicit : defaultLimit;
    }

    /** Current window usage for a user (0 when unlimited). */
    public int currentUsage(String user) {
        Counter c = counters.get(user);
        return c == null ? 0 : c.count.get();
    }

    private void purge() {
        long window = System.currentTimeMillis() / WINDOW_MILLIS;
        counters.entrySet().removeIf(e -> e.getValue().window != window);
    }

    public void close() {
        cleaner.shutdownNow();
    }

    private static final class Counter {
        final long window;
        final AtomicInteger count = new AtomicInteger();

        Counter(long window) {
            this.window = window;
        }
    }
}
