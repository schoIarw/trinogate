package com.trinogate.config;

/** Backend forwarding and protection settings (design doc M6). */
public class ProxyConfig {

    /** Connect timeout to backend (ms). */
    private int connectTimeoutMs = 5000;
    /** Read timeout per backend HTTP call (ms). */
    private int readTimeoutMs = 60_000;
    /** Idle timeout for client polling; the backend query is cancelled after this (s). */
    private int idleTimeoutSeconds = 60;
    /** Max response size accepted from the backend (bytes). */
    private long maxResponseBytes = 32L * 1024 * 1024;

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public int getIdleTimeoutSeconds() { return idleTimeoutSeconds; }
    public void setIdleTimeoutSeconds(int idleTimeoutSeconds) { this.idleTimeoutSeconds = idleTimeoutSeconds; }
    public long getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(long maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
}
