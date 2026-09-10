package com.trinogate.proxy;

import com.trinogate.config.RoutingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Backend cluster registry with round-robin selection among usable clusters.
 * Query affinity is enforced by the pipeline via the registered cluster name.
 */
public class ClusterRegistry {

    private static final Logger log = LoggerFactory.getLogger(ClusterRegistry.class);

    private final Map<String, BackendCluster> clusters = new ConcurrentHashMap<>();
    private final AtomicInteger rrCounter = new AtomicInteger();

    public ClusterRegistry(RoutingConfig config) {
        for (RoutingConfig.ClusterConfig c : config.getClusters()) {
            BackendCluster cluster = BackendCluster.from(c);
            clusters.put(cluster.getName(), cluster);
            log.info("Registered backend cluster {} -> {}", cluster.getName(), cluster.getUrl());
        }
        if (clusters.isEmpty()) {
            log.warn("No backend clusters configured; queries will be rejected");
        }
    }

    public Optional<BackendCluster> pick() {
        List<BackendCluster> usable = new ArrayList<>();
        for (BackendCluster c : clusters.values()) {
            if (c.isUsable()) {
                usable.add(c);
            }
        }
        if (usable.isEmpty()) {
            return Optional.empty();
        }
        int idx = Math.floorMod(rrCounter.getAndIncrement(), usable.size());
        return Optional.of(usable.get(idx));
    }

    public Optional<BackendCluster> byName(String name) {
        return Optional.ofNullable(clusters.get(name));
    }

    public List<BackendCluster> all() {
        return List.copyOf(clusters.values());
    }

    public void setActive(String name, boolean active) {
        BackendCluster c = clusters.get(name);
        if (c != null) {
            c.setActive(active);
            log.info("Cluster {} active={}", name, active);
        }
    }

    public Map<String, Object> status() {
        return all().stream().collect(java.util.stream.Collectors.toMap(
                BackendCluster::getName,
                c -> Map.of(
                        "url", c.getUrl().toString(),
                        "active", c.isActive(),
                        "healthy", c.isHealthy())));
    }
}
