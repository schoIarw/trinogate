package com.trinogate.flow;

/** Rate limiter: per-user quota of queries per rolling minute (design doc M4). */
public interface RateLimiter {

    /**
     * @return true when the query is within the user's per-minute quota
     */
    boolean tryAcquire(String user);

    /** Seconds the client should wait before retrying after a 429 rejection. */
    int retryAfterSeconds(String user);
}
