package com.lailo.application.features.auth.infrastructure.token;

import java.time.Instant;

public record AccessToken(String value, Instant expiresAt) {
    @Override
    public String toString() {
        return "AccessToken[expiresAt=" + expiresAt + "]";
    }
}
