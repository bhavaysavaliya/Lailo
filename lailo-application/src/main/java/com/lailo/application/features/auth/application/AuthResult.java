package com.lailo.application.features.auth.application;

import com.lailo.application.features.auth.infrastructure.token.AccessToken;

import java.time.Instant;
import java.util.UUID;

/** Outcome of a successful sign-in or refresh. Tokens go to HttpOnly cookies, never the JSON body. */
public record AuthResult(UUID userId, boolean newUser, AccessToken accessToken,
                         String refreshToken, Instant refreshExpiresAt) {
    @Override
    public String toString() {
        return "AuthResult[userId=" + userId + ", newUser=" + newUser + "]";
    }
}
