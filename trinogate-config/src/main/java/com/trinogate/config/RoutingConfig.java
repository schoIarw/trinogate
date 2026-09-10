package com.trinogate.config;

import java.util.ArrayList;
import java.util.List;

/** Backend cluster registry and routing settings (design doc M5). */
public class RoutingConfig {

    private List<ClusterConfig> clusters = new ArrayList<>();
    /** roundRobin | queryCount (queryCount needs health check metrics; v1 implements roundRobin) */
    private String strategy = "roundRobin";
    private HealthCheckConfig healthCheck = new HealthCheckConfig();

    public List<ClusterConfig> getClusters() { return clusters; }
    public void setClusters(List<ClusterConfig> clusters) { this.clusters = clusters; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public HealthCheckConfig getHealthCheck() { return healthCheck; }
    public void setHealthCheck(HealthCheckConfig healthCheck) { this.healthCheck = healthCheck; }

    public static class ClusterConfig {
        private String name;
        /** Base URL of the Trino coordinator, e.g. http://coordinator:8080 */
        private String url;
        private boolean active = true;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
    }

    public static class HealthCheckConfig {
        /** INFO_API | METRICS (INFO_API implemented in v1). */
        private String type = "INFO_API";
        private boolean enabled = true;
        private int intervalSeconds = 10;
        private int timeoutMs = 5000;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getIntervalSeconds() { return intervalSeconds; }
        public void setIntervalSeconds(int intervalSeconds) { this.intervalSeconds = intervalSeconds; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    }
}
