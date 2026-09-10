package com.trinogate.flow;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Limits concurrently forwarded queries (global and per-user). Permits must be
 * acquired in the same order (global then user) to avoid deadlocks, and released
 * via {@link Permit#close()}.
 */
public class ConcurrencyController {

    private final Semaphore globalRunning;
    private final int maxPerUser;
    private final ConcurrentHashMap<String, Semaphore> perUserRunning = new ConcurrentHashMap<>();

    public ConcurrencyController(int maxRunningGlobal, int maxRunningPerUser) {
        this.globalRunning = new Semaphore(maxRunningGlobal);
        this.maxPerUser = maxRunningPerUser;
    }

    /** Non-blocking acquisition. */
    public Permit tryAcquire(String user) {
        if (!globalRunning.tryAcquire()) {
            return null;
        }
        Semaphore userSem = perUserRunning.computeIfAbsent(user, k -> new Semaphore(maxPerUser));
        if (!userSem.tryAcquire()) {
            globalRunning.release();
            return null;
        }
        return new Permit(globalRunning, userSem);
    }

    public int runningGlobal() {
        return globalRunning.getQueueLength();
    }

    public int runningPerUser(String user) {
        Semaphore s = perUserRunning.get(user);
        return s == null ? 0 : s.getQueueLength();
    }

    public static final class Permit implements AutoCloseable {
        private final Semaphore global;
        private final Semaphore user;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(Semaphore global, Semaphore user) {
            this.global = global;
            this.user = user;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                user.release();
                global.release();
            }
        }
    }
}
