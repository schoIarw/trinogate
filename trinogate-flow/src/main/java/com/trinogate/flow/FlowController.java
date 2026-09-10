package com.trinogate.flow;

import com.trinogate.config.FlowConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Admission control pipeline: rate limit → bounded waiting room (queue) → run permit.
 * <p>
 * The query is admitted in the POST /v1/statement thread; a bounded wait with a
 * configurable timeout implements the gateway-side queue. Rejections surface as
 * HTTP 429 + Retry-After (protocol-compatible), so JDBC clients retry.
 */
public class FlowController implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FlowController.class);

    private final RateLimiter rateLimiter;
    private final QueryQueue queue;
    private final ConcurrencyController concurrency;
    private final long queueTimeoutNanos;
    private final FlowConfig config;

    public FlowController(FlowConfig config) {
        this.config = config;
        FlowConfig.RateLimitConfig rl = config.getRateLimit();
        this.rateLimiter = rl.getDistributed().isEnabled()
                ? new RedisRateLimiter(rl)
                : new WindowRateLimiter(rl);
        this.queue = new QueryQueue(
                config.getQueue().getMaxGlobalPending(),
                config.getQueue().getMaxPerUserPending());
        this.concurrency = new ConcurrencyController(
                config.getConcurrency().getMaxRunningGlobal(),
                config.getConcurrency().getMaxRunningPerUser());
        this.queueTimeoutNanos = TimeUnit.SECONDS.toNanos(config.getQueue().getTimeoutSeconds());
        log.info("FlowController: rate={}/min default, queue={} global/{} per user, timeout={}s, "
                        + "concurrency={} global/{} per user",
                rl.getDefaultPerMinute(),
                config.getQueue().getMaxGlobalPending(),
                config.getQueue().getMaxPerUserPending(),
                config.getQueue().getTimeoutSeconds(),
                config.getConcurrency().getMaxRunningGlobal(),
                config.getConcurrency().getMaxRunningPerUser());
    }

    /**
     * @return ALLOWED with a run permit, or a rejection the caller maps to 429.
     */
    public Admission admit(String user) {
        if (!rateLimiter.tryAcquire(user)) {
            return Admission.rateLimited(rateLimiter.retryAfterSeconds(user));
        }
        if (!queue.enter(user)) {
            log.debug("Queue full for user {}", user);
            return Admission.queueFull();
        }
        long deadline = System.nanoTime() + queueTimeoutNanos;
        try {
            while (true) {
                ConcurrencyController.Permit permit = concurrency.tryAcquire(user);
                if (permit != null) {
                    return Admission.allowed(permit);
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    log.debug("Queue timeout for user {}", user);
                    return Admission.queueTimeout();
                }
                LockSupport.parkNanos(Math.min(remaining, 100_000_000L));
            }
        } finally {
            queue.leave(user);
        }
    }

    public Map<String, Object> stats(String user) {
        return Map.of(
                "rateLimitMode", config.getRateLimit().getMode(),
                "defaultPerMinute", config.getRateLimit().getDefaultPerMinute(),
                "queue", queue.stats(user),
                "runningGlobal", concurrency.runningGlobal(),
                "runningPerUser", concurrency.runningPerUser(user),
                "maxRunningGlobal", config.getConcurrency().getMaxRunningGlobal(),
                "maxRunningPerUser", config.getConcurrency().getMaxRunningPerUser());
    }

    @Override
    public void close() {
        if (rateLimiter instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
