package com.trinogate.auth;

import java.util.Map;
import java.util.Optional;

/**
 * Authenticates a client request. Headers are passed with their original casing
 * (HTTP headers are case-insensitive; implementations should look up both forms).
 */
public interface Authenticator {

    /** Unique authenticator type name, e.g. header | basic | jwt. */
    String type();

    /**
     * @param headers    request headers (original casing)
     * @param remoteAddr client address, used by trusted-proxy style checks
     * @return authenticated identity, or {@link Optional#empty()} when credentials are missing/invalid
     */
    Optional<IdentityContext> authenticate(Map<String, String> headers, String remoteAddr);
}
