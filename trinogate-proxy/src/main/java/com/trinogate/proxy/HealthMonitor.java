package com.trinogate.proxy;

import com.trinogate.config.RoutingConfig.HealthCheckConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically probes each cluster's {@code GET /v1/info} endpoint (INFO_API mode).
 * A cluster is healthy when the coordinator answers 200 with {@code "starting": false}.
 */
public class HealthMonitor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HealthMonitor.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ClusterRegistry registry;
    private final OkHttpClient http;
    private final HealthCheckConfig config;
    private final ScheduledExecutorService scheduler;

    public HealthMonitor(ClusterRegistry registry, HealthCheckConfig config, OkHttpClient http) {
        this.registry = registry;
        this.config = config;
        this.http = http;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "health-monitor");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (!config.isEnabled()) {
            log.info("Health monitor disabled");
            return;
        }
        checkAll();
        scheduler.scheduleAtFixedRate(this::checkAll, config.getIntervalSeconds(), config.getIntervalSeconds(), TimeUnit.SECONDS);
        log.info("Health monitor started: every {}s ({} mode)", config.getIntervalSeconds(), config.getType());
    }

    void checkAll() {
        for (BackendCluster cluster : registry.all()) {
            check(cluster);
        }
    }

    void check(BackendCluster cluster) {
        boolean healthy = probe(cluster);
        cluster.setHealthy(healthy);
        if (!healthy) {
            log.warn("Cluster {} is UNHEALTHY, routed away", cluster.getName());
        }
    }

    private boolean probe(BackendCluster cluster) {
        Request request = new Request.Builder()
                .url(cluster.getUrl().toString() + "/v1/info")
                .header("Accept", "application/json")
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (response.code() != 200 || response.body() == null) {
                return false;
            }
            JsonNode node = JSON.readTree(response.body().string());
            return !node.path("starting").asBoolean(true);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    // helper for default timeout handling
    static OkHttpClient buildDefaultHttp(Duration connectTimeout, Duration readTimeout) {
        return new OkHttpClient.Builder()
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .build();
    }
}
