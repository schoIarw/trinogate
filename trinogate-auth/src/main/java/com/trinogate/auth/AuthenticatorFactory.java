package com.trinogate.auth;

import com.trinogate.config.AuthConfig;

/**
 * Builds the configured {@link Authenticator}. Add new authenticator types here
 * (Kerberos/SPNEGO, mTLS certificate, OAuth2/OIDC with JWKS) behind the same interface.
 */
public final class AuthenticatorFactory {

    private AuthenticatorFactory() {}

    public static Authenticator create(AuthConfig config) {
        return switch (config.getType().toLowerCase()) {
            case "header" -> new HeaderAuthenticator(config);
            case "basic" -> new BasicAuthenticator(new BasicAuthenticator.FileUserStore(config.getPasswordFile()));
            case "jwt" -> new JwtAuthenticator(config);
            default -> throw new IllegalArgumentException(
                    "Unsupported auth.type '" + config.getType() + "' (supported: header, basic, jwt)");
        };
    }
}
