package com.trinogate.protocol;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A query tracked by the gateway. Holds the opaque backend handle {@code T}
 * (wired by the proxy module) and an expiry callback invoked when the gateway
 * stops tracking the query (client stopped polling / query finished / cancelled).
 */
public class RegisteredQuery<T> {

    private final String gatewayId;
    private final String backendQueryId;
    private final String user;
    private final String source;
    private final String cluster;
    private final long createdAtMillis;
    private volatile long lastAccessNanos;
    private final AtomicLong tokenCounter = new AtomicLong();
    private final T handle;
    private final Runnable onExpire;

    public RegisteredQuery(String gatewayId, String backendQueryId, String user, String source,
                           String cluster, T handle, Runnable onExpire) {
        this.gatewayId = gatewayId;
        this.backendQueryId = backendQueryId;
        this.user = user;
        this.source = source;
        this.cluster = cluster;
        this.createdAtMillis = System.currentTimeMillis();
        this.lastAccessNanos = System.nanoTime();
        this.handle = handle;
        this.onExpire = onExpire;
    }

    public void touch() {
        lastAccessNanos = System.nanoTime();
    }

    public long idleMillis() {
        return (System.nanoTime() - lastAccessNanos) / 1_000_000L;
    }

    /** Next opaque token for a rewritten nextUri: /v1/statement/{gatewayId}/{token}. */
    public long nextToken() {
        return tokenCounter.incrementAndGet();
    }

    public String getGatewayId() { return gatewayId; }
    public String getBackendQueryId() { return backendQueryId; }
    public String getUser() { return user; }
    public String getSource() { return source; }
    public String getCluster() { return cluster; }
    public long getCreatedAtMillis() { return createdAtMillis; }
    public T getHandle() { return handle; }
    public Runnable getOnExpire() { return onExpire; }
}
