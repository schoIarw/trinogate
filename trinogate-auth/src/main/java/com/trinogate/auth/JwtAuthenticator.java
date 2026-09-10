package com.trinogate.auth;

import com.trinogate.config.AuthConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * JWT bearer-token authenticator (HS256, HMAC shared secret).
 * Validates signature, issuer, audience and expiry; the subject becomes the session user.
 * JWKS / RS256 can be added behind the same {@link Authenticator} interface.
 */
public class JwtAuthenticator implements Authenticator {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticator.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final SecretKey key;
    private final String issuer;
    private final String audience;

    public JwtAuthenticator(AuthConfig config) {
        if (config.getJwtSecret() == null || config.getJwtSecret().isBlank()) {
            throw new IllegalArgumentException("auth.type=jwt requires auth.jwtSecret (HMAC secret)");
        }
        this.key = Keys.hmacShaKeyFor(config.getJwtSecret().getBytes(StandardCharsets.UTF_8));
        this.issuer = config.getJwtIssuer();
        this.audience = config.getJwtAudience();
    }

    @Override
    public String type() {
        return "jwt";
    }

    @Override
    public Optional<IdentityContext> authenticate(Map<String, String> headers, String remoteAddr) {
        String authz = headers.get("Authorization");
        if (authz == null) {
            authz = headers.get("authorization");
        }
        if (authz == null || !authz.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        String token = authz.substring(BEARER_PREFIX.length()).trim();
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (issuer != null && !issuer.isBlank() && !issuer.equals(claims.getIssuer())) {
                return Optional.empty();
            }
            if (audience != null && !audience.isBlank() && !claims.getAudience().contains(audience)) {
                return Optional.empty();
            }
            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(IdentityContext.of(subject, type()));
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
