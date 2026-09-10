package com.trinogate.flow;

import com.trinogate.config.FlowConfig.RateLimitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

/**
 * Distributed fixed-window rate limiter over Redis (atomic Lua INCR+EXPIRE).
 * Enabled when flow.rateLimit.distributed.enabled=true, so multiple gateway
 * instances share the same per-user quota.
 */
public class RedisRateLimiter implements RateLimiter, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final String LUA = "local c = redis.call('INCR', KEYS[1])"
            + " if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end"
            + " return c";

    private final JedisPool pool;
    private final int defaultLimit;
    private final Map<String, Integer> perUser;

    public RedisRateLimiter(RateLimitConfig config) {
        this.defaultLimit = config.getDefaultPerMinute();
        this.perUser = config.getPerUser() == null ? Map.of() : config.getPerUser();
        URI uri;
        try {
            uri = new URI(config.getDistributed().getRedisUri());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid redisUri: " + config.getDistributed().getRedisUri(), e);
        }
        this.pool = new JedisPool(uri.getHost(), uri.getPort());
        log.info("Redis rate limiter enabled at {}", config.getDistributed().getRedisUri());
    }

    @Override
    public boolean tryAcquire(String user) {
        int limit = limitOf(user);
        if (limit <= 0) {
            return true;
        }
        String key = "trinogate:ratelimit:" + user + ":" + (System.currentTimeMillis() / 60_000);
        try (Jedis jedis = pool.getResource()) {
            Long count = (Long) jedis.eval(LUA, List.of(key), List.of("120"));
            return count <= limit;
        } catch (Exception e) {
            // fail-open: if Redis is unavailable, fall back to local admission
            log.warn("Redis rate limiter error for user {}, failing open: {}", user, e.getMessage());
            return true;
        }
    }

    @Override
    public int retryAfterSeconds(String user) {
        long remaining = 60_000 - (System.currentTimeMillis() % 60_000);
        return (int) Math.max(1, Math.ceil(remaining / 1000.0));
    }

    public int limitOf(String user) {
        Integer explicit = perUser.get(user);
        return explicit != null ? explicit : defaultLimit;
    }

    @Override
    public void close() {
        pool.close();
    }
}
