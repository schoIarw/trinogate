package com.trinogate.server;

import com.trinogate.auth.Authenticator;
import com.trinogate.auth.AuthenticatorFactory;
import com.trinogate.config.GatewayConfig;
import com.trinogate.config.ProxyConfig;
import com.trinogate.flow.FlowController;
import com.trinogate.protocol.QueryRegistry;
import com.trinogate.proxy.BackendClient;
import com.trinogate.proxy.BackendQueryHandle;
import com.trinogate.proxy.ClusterRegistry;
import com.trinogate.proxy.HealthMonitor;
import com.trinogate.validation.SqlValidationService;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.DispatcherType;
import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

/**
 * Composition root: wires all gateway modules and boots the Jetty server.
 * Used by GatewayMain and by integration tests.
 */
public class GatewayApplication implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GatewayApplication.class);

    private final GatewayConfig config;
    private final Server jetty;
    private final SqlValidationService validation;
    private final FlowController flow;
    private final ClusterRegistry clusters;
    private final HealthMonitor healthMonitor;
    private final BackendClient backend;
    private final QueryRegistry<BackendQueryHandle> registry;
    private final QueryPipeline pipeline;
    private final GatewayMetrics metrics;

    private GatewayApplication(GatewayConfig config) {
        this.config = config;
        this.metrics = new GatewayMetrics();

        this.validation = new SqlValidationService(config.getValidation());
        this.flow = new FlowController(config.getFlow());
        this.clusters = new ClusterRegistry(config.getRouting());
        this.backend = new BackendClient(config.getProxy());
        this.registry = new QueryRegistry<>(TimeUnit.SECONDS.toMillis(config.getProxy().getIdleTimeoutSeconds()));

        this.pipeline = new QueryPipeline(
                validation, flow, clusters, backend, registry,
                config.getServer().getGatewayBaseUri(), metrics);

        this.healthMonitor = new HealthMonitor(clusters, config.getRouting().getHealthCheck(), backend.http());

        this.jetty = buildServer();
    }

    public static GatewayApplication start(GatewayConfig config) throws Exception {
        GatewayApplication app = new GatewayApplication(config);
        app.start();
        return app;
    }

    private void start() throws Exception {
        validation.start();
        healthMonitor.start();
        jetty.start();
        int port = actualPort();
        log.info("Trino gateway started, listening on http://localhost:{} (advertised base: {})",
                port, config.getServer().getGatewayBaseUri());
    }

    private Server buildServer() {
        Server server = new Server();
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSendServerVersion(false);
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        connector.setPort(config.getServer().getHttpPort());
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new StatementServlet(pipeline)), "/v1/statement/*");
        context.addServlet(new ServletHolder(new InfoServlet(clusters, backend.http(), metrics)), "/v1/info");
        context.addServlet(new ServletHolder(new InfoServlet(clusters, backend.http(), metrics)), "/v1/status");
        context.addServlet(new ServletHolder(new InfoServlet(clusters, backend.http(), metrics)), "/v1/node");
        context.addServlet(new ServletHolder(new InfoServlet(clusters, backend.http(), metrics)), "/metrics");
        context.addServlet(new ServletHolder(new AdminServlet(validation, flow, clusters, pipeline, config.getFlow())), "/admin/*");

        Authenticator authenticator = AuthenticatorFactory.create(config.getAuth());
        context.addFilter(new FilterHolder(new AuthFilter(authenticator)), "/*", EnumSet.of(DispatcherType.REQUEST));
        server.setHandler(context);
        return server;
    }

    /** Actual bound port (useful when configured with port 0). */
    public int actualPort() {
        return ((ServerConnector) jetty.getConnectors()[0]).getLocalPort();
    }

    public GatewayMetrics metrics() {
        return metrics;
    }

    public QueryPipeline pipeline() {
        return pipeline;
    }

    public GatewayConfig config() {
        return config;
    }

    @Override
    public void close() {
        try {
            jetty.stop();
        } catch (Exception e) {
            log.warn("Jetty stop failed: {}", e.toString());
        }
        registry.close();
        healthMonitor.close();
        validation.close();
        flow.close();
        backend.close();
        log.info("Trino gateway stopped");
    }
}
