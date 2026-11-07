package com.lailo.application.features.auth.application.query;

import java.time.Instant;

/**
 * Profile returned by {@code GET /api/v1/users/me}.
 *
 * @param id user id
 * @param email verified email, or {@code null}
 * @param phone verified mobile in E.164, or {@code null}
 * @param registeredAt account creation time
 * @param lastLoginAt most recent sign-in time
 */
public record UserProfileView(String id, String email, String phone, Instant registeredAt, Instant lastLoginAt) {
}
