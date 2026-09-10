package com.trinogate.config;

import java.util.List;
import java.util.Map;

/**
 * Root configuration of the Trino gateway.
 * Loaded from YAML with {@link ConfigLoader}; missing fields keep their defaults.
 * Maps to design doc sections M0/M7 (gateway-config).
 */
public class GatewayConfig {

    private ServerConfig server = new ServerConfig();
    private AuthConfig auth = new AuthConfig();
    private ValidationConfig validation = new ValidationConfig();
    private FlowConfig flow = new FlowConfig();
    private RoutingConfig routing = new RoutingConfig();
    private ProxyConfig proxy = new ProxyConfig();

    public ServerConfig getServer() { return server; }
    public void setServer(ServerConfig server) { this.server = server; }
    public AuthConfig getAuth() { return auth; }
    public void setAuth(AuthConfig auth) { this.auth = auth; }
    public ValidationConfig getValidation() { return validation; }
    public void setValidation(ValidationConfig validation) { this.validation = validation; }
    public FlowConfig getFlow() { return flow; }
    public void setFlow(FlowConfig flow) { this.flow = flow; }
    public RoutingConfig getRouting() { return routing; }
    public void setRouting(RoutingConfig routing) { this.routing = routing; }
    public ProxyConfig getProxy() { return proxy; }
    public void setProxy(ProxyConfig proxy) { this.proxy = proxy; }
}
