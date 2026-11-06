package com.lailo.application.features.auth.application.query;

import java.time.Instant;

public record UserProfileView(String id, String email, String phone, Instant registeredAt, Instant lastLoginAt) {
}
