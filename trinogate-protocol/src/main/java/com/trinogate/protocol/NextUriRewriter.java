package com.trinogate.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * Rewrites backend {@code nextUri} links so that clients always poll the gateway
 * instead of the backend coordinator:
 * {@code {gatewayBaseUri}/v1/statement/{gatewayId}/{token}}
 */
public final class NextUriRewriter {

    private static final Logger log = LoggerFactory.getLogger(NextUriRewriter.class);

    private NextUriRewriter() {}

    public static String build(String gatewayBaseUri, String gatewayId, long token) {
        String base = gatewayBaseUri;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/v1/statement/" + gatewayId + "/" + token;
    }

    /**
     * Parses {@code /v1/statement/{gatewayId}/{token}} into [gatewayId, token]; returns null
     * when the path does not match (e.g. the client hit the base POST path).
     */
    public static String[] parse(String path) {
        if (path == null) {
            return null;
        }
        String p = path;
        int idx = p.indexOf("/v1/statement/");
        if (idx < 0) {
            return null;
        }
        String rest = p.substring(idx + "/v1/statement/".length());
        if (rest.isEmpty() || rest.indexOf('/') < 0) {
            return null;
        }
        String[] parts = rest.split("/", 2);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return null;
        }
        return parts;
    }

    public static URI parseUri(String raw) {
        try {
            return URI.create(raw);
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring malformed URI {}", raw);
            return null;
        }
    }
}
