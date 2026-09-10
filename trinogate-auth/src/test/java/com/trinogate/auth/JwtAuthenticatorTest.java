package com.trinogate.auth;

import com.trinogate.config.AuthConfig;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtAuthenticatorTest {

    private static final String SECRET = "ci-test-secret-key-0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "trinogate";
    private static final String AUDIENCE = "trino-clients";

    private static JwtAuthenticator authenticator() {
        AuthConfig config = new AuthConfig();
        config.setJwtSecret(SECRET);
        config.setJwtIssuer(ISSUER);
        config.setJwtAudience(AUDIENCE);
        return new JwtAuthenticator(config);
    }

    private static String token(String subject, String issuer, String audience, long ttlMillis, String secret) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        return Jwts.builder()
                .subject(subject)
                .issuer(issuer)
                .audience().add(audience).and()
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .signWith(key)
                .compact();
    }

    @Test
    void acceptsValidToken() {
        JwtAuthenticator auth = authenticator();
        String token = token("alice", ISSUER, AUDIENCE, 60_000, SECRET);
        Optional<IdentityContext> identity = auth.authenticate(Map.of("Authorization", "Bearer " + token), "127.0.0.1");
        assertTrue(identity.isPresent());
        assertEquals("alice", identity.get().getUser());
        assertEquals("jwt", identity.get().getAuthType());
    }

    @Test
    void rejectsWrongSignature() {
        JwtAuthenticator auth = authenticator();
        String token = token("alice", ISSUER, AUDIENCE, 60_000, "other-secret-key-0123456789abcdef0123456789abc");
        assertFalse(auth.authenticate(Map.of("Authorization", "Bearer " + token), "127.0.0.1").isPresent());
    }

    @Test
    void rejectsExpiredToken() {
        JwtAuthenticator auth = authenticator();
        String token = token("alice", ISSUER, AUDIENCE, -60_000, SECRET);
        assertFalse(auth.authenticate(Map.of("Authorization", "Bearer " + token), "127.0.0.1").isPresent());
    }

    @Test
    void rejectsWrongIssuerOrAudience() {
        JwtAuthenticator auth = authenticator();
        String wrongIssuer = token("alice", "evil", AUDIENCE, 60_000, SECRET);
        String wrongAudience = token("alice", ISSUER, "other-app", 60_000, SECRET);
        assertFalse(auth.authenticate(Map.of("Authorization", "Bearer " + wrongIssuer), "127.0.0.1").isPresent());
        assertFalse(auth.authenticate(Map.of("Authorization", "Bearer " + wrongAudience), "127.0.0.1").isPresent());
    }

    @Test
    void rejectsMissingOrMalformedHeader() {
        JwtAuthenticator auth = authenticator();
        assertFalse(auth.authenticate(Map.of(), "127.0.0.1").isPresent());
        assertFalse(auth.authenticate(Map.of("Authorization", "Basic xyz"), "127.0.0.1").isPresent());
        assertFalse(auth.authenticate(Map.of("Authorization", "Bearer not-a-jwt"), "127.0.0.1").isPresent());
    }
}
