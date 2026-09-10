package com.trinogate.proxy;

import com.trinogate.config.RoutingConfig.ClusterConfig;

import java.net.URI;

/** A backend Trino coordinator known to the gateway. */
public class BackendCluster {

    private final String name;
    private final URI url;
    private volatile boolean active;
    private volatile boolean healthy = true;

    public BackendCluster(String name, URI url, boolean active) {
        this.name = name;
        this.url = url;
        this.active = active;
    }

    public static BackendCluster from(ClusterConfig config) {
        return new BackendCluster(config.getName(), URI.create(config.getUrl()), config.isActive());
    }

    public String getName() { return name; }
    public URI getUrl() { return url; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public boolean isHealthy() { return healthy; }
    public void setHealthy(boolean healthy) { this.healthy = healthy; }

    /** Routeable = manually active AND passing health checks. */
    public boolean isUsable() {
        return active && healthy;
    }

    @Override
    public String toString() {
        return "BackendCluster{" + name + ", " + url + ", active=" + active + ", healthy=" + healthy + '}';
    }
}
