package com.trinogate.auth;

import com.trinogate.config.AuthConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeaderAuthenticatorTest {

    private static HeaderAuthenticator authenticator(String headerName) {
        AuthConfig config = new AuthConfig();
        config.setHeaderName(headerName);
        return new HeaderAuthenticator(config);
    }

    @Test
    void acceptsConfiguredHeader() {
        HeaderAuthenticator auth = authenticator("X-Auth-User");
        var identity = auth.authenticate(Map.of("X-Auth-User", " alice "), "10.0.0.1");
        assertTrue(identity.isPresent());
        assertEquals("alice", identity.get().getUser());
        assertEquals("header", identity.get().getAuthType());
    }

    @Test
    void missingOrBlankHeaderIsRejected() {
        HeaderAuthenticator auth = authenticator("X-Auth-User");
        assertFalse(auth.authenticate(Map.of(), "10.0.0.1").isPresent());
        assertFalse(auth.authenticate(Map.of("X-Auth-User", "   "), "10.0.0.1").isPresent());
        assertFalse(auth.authenticate(Map.of("X-Auth-User", ""), "10.0.0.1").isPresent());
    }

    @Test
    void wrongHeaderNameIsRejected() {
        HeaderAuthenticator auth = authenticator("X-Auth-User");
        assertFalse(auth.authenticate(Map.of("X-Other-User", "alice"), "10.0.0.1").isPresent());
    }
}
