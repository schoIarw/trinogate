package com.trinogate.server;

import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/**
 * Lightweight Prometheus metrics (design doc M8). Exposed at GET /metrics.
 */
public class GatewayMetrics {

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    private final Counter submitted;
    private final Counter forwarded;
    private final Counter validationRejected;
    private final Counter flowRejected;
    private final Counter cancelled;
    private final Counter error;

    public GatewayMetrics() {
        this.submitted = counter("gateway_queries_total", "submitted");
        this.forwarded = counter("gateway_queries_total", "forwarded");
        this.validationRejected = counter("gateway_queries_rejected_total", "validation");
        this.flowRejected = counter("gateway_queries_rejected_total", "flow");
        this.cancelled = counter("gateway_queries_total", "cancelled");
        this.error = counter("gateway_queries_total", "error");
    }

    private Counter counter(String name, String status) {
        return Counter.builder(name)
                .description("Trino gateway query counters")
                .tag("status", status)
                .register(registry);
    }

    public void submitted() { submitted.increment(); }
    public void forwarded() { forwarded.increment(); }
    public void validationRejected() { validationRejected.increment(); }
    public void flowRejected() { flowRejected.increment(); }
    public void cancelled() { cancelled.increment(); }
    public void error() { error.increment(); }

    public String scrape() {
        return registry.scrape();
    }
}
