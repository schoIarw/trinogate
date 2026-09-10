package com.trinogate.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authenticated identity used for rate limiting, rule evaluation, auditing and
 * identity propagation to the backend (design doc M2).
 */
public class IdentityContext {

    private final String user;
    private final String originalUser;
    private final String authType;
    private final Map<String, String> attributes;

    public IdentityContext(String user, String originalUser, String authType, Map<String, String> attributes) {
        this.user = user;
        this.originalUser = originalUser == null ? user : originalUser;
        this.authType = authType;
        this.attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    public static IdentityContext of(String user, String authType) {
        return new IdentityContext(user, user, authType, Map.of());
    }

    public String getUser() { return user; }
    public String getOriginalUser() { return originalUser; }
    public String getAuthType() { return authType; }
    public Map<String, String> getAttributes() { return attributes; }

    @Override
    public String toString() {
        return "IdentityContext{user='" + user + "', authType='" + authType + "'}";
    }
}
