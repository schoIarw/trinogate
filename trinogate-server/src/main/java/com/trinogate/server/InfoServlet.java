package com.trinogate.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trinogate.proxy.BackendCluster;
import com.trinogate.proxy.ClusterRegistry;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;

/**
 * Metadata endpoints (design doc M6): /v1/info, /v1/status, /v1/node are proxied
 * to a healthy backend (or a synthetic view when none is reachable); /metrics
 * exposes gateway Prometheus metrics.
 */
public class InfoServlet extends HttpServlet {

    private final ClusterRegistry clusters;
    private final OkHttpClient http;
    private final GatewayMetrics metrics;
    private final ObjectMapper mapper = new ObjectMapper();

    public InfoServlet(ClusterRegistry clusters, OkHttpClient http, GatewayMetrics metrics) {
        this.clusters = clusters;
        this.http = http;
        this.metrics = metrics;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getRequestURI();
        switch (path) {
            case "/metrics" -> {
                resp.setContentType("text/plain");
                resp.getWriter().write(metrics.scrape());
            }
            case "/v1/info" -> proxyOr(resp, "/v1/info",
                    "{\"nodeVersion\":{\"version\":\"trino-gateway\"},\"environment\":\"trinogate\",\"starting\":false}");
            case "/v1/status" -> proxyOr(resp, "/v1/status",
                    "{\"nodeId\":\"trinogate\",\"version\":\"0.1.0\",\"uptime\":\"0s\"}");
            case "/v1/node" -> proxyOr(resp, "/v1/node", "[]");
            default -> resp.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    private void proxyOr(HttpServletResponse resp, String backendPath, String fallback) throws IOException {
        Optional<BackendCluster> cluster = clusters.pick();
        if (cluster.isEmpty()) {
            writeJson(resp, fallback);
            return;
        }
        Request request = new Request.Builder()
                .url(cluster.get().getUrl().toString() + backendPath)
                .header("Accept", "application/json")
                .build();
        try (Response response = http.newCall(request).execute()) {
            String body = response.body() == null ? fallback : response.body().string();
            resp.setContentType("application/json");
            resp.getWriter().write(body);
        } catch (Exception e) {
            writeJson(resp, fallback);
        }
    }

    private void writeJson(HttpServletResponse resp, String body) throws IOException {
        resp.setContentType("application/json");
        resp.getWriter().write(body);
    }
}
