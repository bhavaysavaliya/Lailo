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

    /** Not instantiable: constants and event types only. */
    private AuthEvents() {
    }

    /**
     * A new account was created.
     *
     * @param eventId unique event id, used for idempotency
     * @param userId the new user's id
     * @param email verified email, or {@code null}
     * @param phone verified phone, or {@code null}
     * @param occurredAt creation time
     */
    public record UserRegistered(String eventId, String userId, String email, String phone, Instant occurredAt) {
    }

    /**
     * A user signed in.
     *
     * @param eventId unique event id
     * @param userId the user's id
     * @param sessionId the session created by this sign-in
     * @param identifierType {@code EMAIL} or {@code PHONE}
     * @param email user's email, or {@code null}
     * @param phone user's phone, or {@code null}
     * @param occurredAt sign-in time
     */
    public record UserLoggedIn(String eventId, String userId, String sessionId, String identifierType,
                               String email, String phone, Instant occurredAt) {
    }

    /**
     * A user signed out of one device.
     *
     * @param eventId unique event id
     * @param userId the user's id
     * @param sessionId the session that was presented
     * @param occurredAt sign-out time
     */
    public record UserLoggedOut(String eventId, String userId, String sessionId, Instant occurredAt) {
    }

    /**
     * A rotated refresh token was replayed; the family was revoked. Consumers can alert the user or security team.
     *
     * @param eventId unique event id
     * @param userId the affected user
     * @param familyId the revoked session family
     * @param ipAddress IP that replayed the token
     * @param occurredAt detection time
     */
    public record SessionReuseDetected(String eventId, String userId, String familyId, String ipAddress,
                                       Instant occurredAt) {
    }
}
