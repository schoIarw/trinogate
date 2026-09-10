package com.trinogate.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** Traffic governance settings: rate limit, queuing, concurrency (design doc M4). */
public class FlowConfig {

    private RateLimitConfig rateLimit = new RateLimitConfig();
    private QueueConfig queue = new QueueConfig();
    private ConcurrencyConfig concurrency = new ConcurrencyConfig();

    public RateLimitConfig getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimitConfig rateLimit) { this.rateLimit = rateLimit; }
    public QueueConfig getQueue() { return queue; }
    public void setQueue(QueueConfig queue) { this.queue = queue; }
    public ConcurrencyConfig getConcurrency() { return concurrency; }
    public void setConcurrency(ConcurrencyConfig concurrency) { this.concurrency = concurrency; }

    public static class RateLimitConfig {
        /** fixedWindow | slidingWindow | tokenBucket (fixedWindow implemented; others map to fixedWindow in v1). */
        private String mode = "fixedWindow";
        /** Per-minute SQL quota applied to users without an explicit entry. 0 = unlimited. */
        private int defaultPerMinute = 60;
        /** Per-user overrides: key=username, value=queries per minute (0 = unlimited). */
        private Map<String, Integer> perUser = new LinkedHashMap<>();
        private DistributedRateLimitConfig distributed = new DistributedRateLimitConfig();

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public int getDefaultPerMinute() { return defaultPerMinute; }
        public void setDefaultPerMinute(int defaultPerMinute) { this.defaultPerMinute = defaultPerMinute; }
        public Map<String, Integer> getPerUser() { return perUser; }
        public void setPerUser(Map<String, Integer> perUser) { this.perUser = perUser; }
        public DistributedRateLimitConfig getDistributed() { return distributed; }
        public void setDistributed(DistributedRateLimitConfig distributed) { this.distributed = distributed; }
    }

    public static class DistributedRateLimitConfig {
        private boolean enabled = false;
        private String redisUri = "redis://localhost:6379";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getRedisUri() { return redisUri; }
        public void setRedisUri(String redisUri) { this.redisUri = redisUri; }
    }

    public static class QueueConfig {
        /** Max queries waiting for a run permit across all users. */
        private int maxGlobalPending = 200;
        /** Max queries waiting per user. */
        private int maxPerUserPending = 20;
        /** Max time (seconds) a query may wait for admission. */
        private int timeoutSeconds = 30;

        public int getMaxGlobalPending() { return maxGlobalPending; }
        public void setMaxGlobalPending(int maxGlobalPending) { this.maxGlobalPending = maxGlobalPending; }
        public int getMaxPerUserPending() { return maxPerUserPending; }
        public void setMaxPerUserPending(int maxPerUserPending) { this.maxPerUserPending = maxPerUserPending; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    public static class ConcurrencyConfig {
        /** Max concurrently running (forwarded) queries per user. */
        private int maxRunningPerUser = 10;
        /** Max concurrently running (forwarded) queries globally. */
        private int maxRunningGlobal = 100;

        public int getMaxRunningPerUser() { return maxRunningPerUser; }
        public void setMaxRunningPerUser(int maxRunningPerUser) { this.maxRunningPerUser = maxRunningPerUser; }
        public int getMaxRunningGlobal() { return maxRunningGlobal; }
        public void setMaxRunningGlobal(int maxRunningGlobal) { this.maxRunningGlobal = maxRunningGlobal; }
    }
}
