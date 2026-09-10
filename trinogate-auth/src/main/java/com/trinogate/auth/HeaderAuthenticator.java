package com.trinogate.auth;

import com.trinogate.config.AuthConfig;

import java.util.Map;
import java.util.Optional;

/**
 * Trusts an identity header set by an upstream reverse proxy / SSO gateway
 * (e.g. X-Auth-User). Suitable when TLS terminates and the network is trusted.
 */
public class HeaderAuthenticator implements Authenticator {

    private final String headerName;

    public HeaderAuthenticator(AuthConfig config) {
        this.headerName = config.getHeaderName();
    }

    @Override
    public String type() {
        return "header";
    }

    @Override
    public Optional<IdentityContext> authenticate(Map<String, String> headers, String remoteAddr) {
        String value = headers.get(headerName);
        if (value == null) {
            value = headers.get(headerName.toLowerCase());
        }
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(IdentityContext.of(value.trim(), type()));
    }
}
