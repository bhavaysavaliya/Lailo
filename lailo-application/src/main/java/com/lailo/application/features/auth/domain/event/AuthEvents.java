package com.lailo.application.features.auth.domain.event;

import java.time.Instant;

/** Integration events published to Kafka via the outbox. Payloads are versioned by {@code eventType}. */
public final class AuthEvents {

    public static final String TOPIC = "lailo.auth.user-events.v1";
    public static final String AGGREGATE = "User";

    public static final String USER_REGISTERED = "UserRegistered";
    public static final String USER_LOGGED_IN = "UserLoggedIn";
    public static final String USER_LOGGED_OUT = "UserLoggedOut";
    public static final String SESSION_REUSE_DETECTED = "SessionReuseDetected";

    private AuthEvents() {
    }

    public record UserRegistered(String eventId, String userId, String email, String phone, Instant occurredAt) {
    }

    public record UserLoggedIn(String eventId, String userId, String sessionId, String identifierType,
                               String email, String phone, Instant occurredAt) {
    }

    public record UserLoggedOut(String eventId, String userId, String sessionId, Instant occurredAt) {
    }

    public record SessionReuseDetected(String eventId, String userId, String familyId, String ipAddress,
                                       Instant occurredAt) {
    }
}
