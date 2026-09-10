package com.trinogate.flow;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded admission gate: limits how many queries may wait for a run permit
 * (global and per-user), i.e. the "SQL 排队数" limits (design doc M4).
 * Thread-safe via synchronized enter/leave (admission is not a hot path).
 */
public class QueryQueue {

    private final int maxGlobal;
    private final int maxPerUser;
    private final AtomicInteger globalQueued = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicInteger> perUserQueued = new ConcurrentHashMap<>();

    public QueryQueue(int maxGlobal, int maxPerUser) {
        this.maxGlobal = maxGlobal;
        this.maxPerUser = maxPerUser;
    }

    /** @return true when the user acquired a waiting slot within both caps */
    public synchronized boolean enter(String user) {
        if (maxPerUser > 0) {
            AtomicInteger u = perUserQueued.computeIfAbsent(user, k -> new AtomicInteger());
            if (u.get() >= maxPerUser) {
                return false;
            }
        }
        if (maxGlobal > 0 && globalQueued.get() >= maxGlobal) {
            return false;
        }
        globalQueued.incrementAndGet();
        perUserQueued.computeIfAbsent(user, k -> new AtomicInteger()).incrementAndGet();
        return true;
    }

    public synchronized void leave(String user) {
        globalQueued.decrementAndGet();
        AtomicInteger u = perUserQueued.get(user);
        if (u != null) {
            u.decrementAndGet();
        }
    }

    public int globalDepth() {
        return globalQueued.get();
    }

    public int perUserDepth(String user) {
        AtomicInteger u = perUserQueued.get(user);
        return u == null ? 0 : u.get();
    }

    public Map<String, Object> stats(String user) {
        return Map.of(
                "globalQueued", globalQueued.get(),
                "userQueued", perUserDepth(user),
                "maxGlobalQueued", maxGlobal,
                "maxPerUserQueued", maxPerUser);
    }
}
