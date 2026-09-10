package com.trinogate.config;

/** HTTP server and gateway identity settings. */
public class ServerConfig {

    /** Port the gateway listens on. */
    private int httpPort = 8080;

    /**
     * Public base URI advertised in rewritten {@code nextUri} links, e.g. http://gw.example.com:8080.
     * Defaults to http://localhost:{httpPort} when empty.
     */
    private String gatewayBaseUri = "";

    private boolean processForwarded = true;

    public int getHttpPort() { return httpPort; }
    public void setHttpPort(int httpPort) { this.httpPort = httpPort; }
    public String getGatewayBaseUri() { return gatewayBaseUri; }
    public void setGatewayBaseUri(String gatewayBaseUri) { this.gatewayBaseUri = gatewayBaseUri; }
    public boolean isProcessForwarded() { return processForwarded; }
    public void setProcessForwarded(boolean processForwarded) { this.processForwarded = processForwarded; }
}
