package com.lailo.application.features.auth.infrastructure.token;

import java.time.Instant;

/**
 * A signed access token and its expiry.
 *
 * @param value the compact JWT
 * @param expiresAt expiry, also used for the cookie lifetime
 */
public record AccessToken(String value, Instant expiresAt) {
    /**
     * Omits the token value so it never reaches logs.
     *
     * @return a log-safe representation
     */
    @Override
    public String toString() {
        return "AccessToken[expiresAt=" + expiresAt + "]";
    }
}
