package com.lailo.application.features.auth.api.dto;

import java.time.Instant;

/** Tokens are deliberately absent: they travel only in HttpOnly cookies, out of reach of page JavaScript. */
public record SignInResponse(String userId, boolean newUser, Instant accessTokenExpiresAt) {
}
