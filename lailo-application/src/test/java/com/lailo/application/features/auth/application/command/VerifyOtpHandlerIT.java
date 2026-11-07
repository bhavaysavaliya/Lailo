package com.lailo.application.features.auth.application.command;

import com.lailo.application.common.web.ApiException;
import com.lailo.application.common.web.ClientInfo;
import com.lailo.application.features.auth.application.AuthResult;
import com.lailo.application.features.auth.config.AuthProperties;
import com.lailo.application.features.auth.domain.Identifier;
import com.lailo.application.features.auth.domain.User;
import com.lailo.application.features.auth.domain.UserSession;
import com.lailo.application.features.auth.domain.UserStatus;
import com.lailo.application.features.auth.infrastructure.otp.OtpHasher;
import com.lailo.application.features.auth.infrastructure.persistence.UserRepository;
import com.lailo.application.features.auth.infrastructure.persistence.UserSessionRepository;
import com.lailo.application.features.auth.infrastructure.redis.OtpChallenge;
import com.lailo.application.features.auth.infrastructure.redis.RedisOtpStore;
import com.lailo.application.features.auth.infrastructure.token.RefreshTokenCodec;
import com.lailo.application.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** VerifyOtpHandler against real Redis (OTP) and PostgreSQL (users, sessions, outbox). */
class VerifyOtpHandlerIT extends IntegrationTest {

    @Autowired VerifyOtpHandler handler;
    @Autowired RedisOtpStore otpStore;
    @Autowired OtpHasher hasher;
    @Autowired AuthProperties props;
    @Autowired UserRepository users;
    @Autowired UserSessionRepository sessions;
    @Autowired RefreshTokenCodec codec;
    @Autowired DatabaseClient db;

    /** A pending OTP in Redis, exactly as SendOtpHandler leaves it. */
    private String pendingOtp(String rawIdentifier, int attempts) {
        String requestId = UUID.randomUUID().toString();
        Identifier id = Identifier.parse(rawIdentifier, "91");
        otpStore.save(new OtpChallenge(requestId, id, hasher.hash(requestId, "123456"), attempts), props.otp().ttl()).block();
        return requestId;
    }

    /**
     * Lists the outbox event types recorded for a user, oldest first.
     *
     * @param userId aggregate id
     * @return event type names
     */
    private List<String> outboxEventTypes(UUID userId) {
        return db.sql("SELECT event_type FROM outbox_messages WHERE aggregate_id = :id ORDER BY created_at")
                .bind("id", userId.toString()).map(r -> r.get(0, String.class)).all().collectList().block();
    }

    /**
     * Extracts the error code from an {@code ApiException}.
     *
     * @param e the error signal
     * @return its code
     */
    private static String code(Throwable e) {
        return ((ApiException) e).getCode();
    }

    /**
     * A first sign-in creates the user, the session and both events together.
     * <ul>
     *   <li><b>Given</b> a pending OTP for a new phone number</li>
     *   <li><b>When</b> the correct code is verified</li>
     *   <li><b>Then</b> {@code newUser} is true; the user row is ACTIVE with the phone verified; the session row matches the refresh token's hash, holds the client IP and expires in about 30 days; the outbox has UserRegistered then UserLoggedIn</li>
     * </ul>
     */
    @Test
    void firstSignInCreatesUserSessionAndEventsInOneTransaction() {
        String phone = uniquePhone();
        ClientInfo client = uniqueClient();

        AuthResult result = handler.handle(new VerifyOtpCommand(pendingOtp(phone, 0), "123456", client)).block();

        assertThat(result.newUser()).isTrue();
        User user = users.findById(result.userId()).block();
        assertThat(user.getPhone()).isEqualTo("+91" + phone);
        assertThat(user.isPhoneVerified()).isTrue();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getVersion()).isNotNull();

        UserSession session = sessions.findByTokenHash(codec.hash(result.refreshToken())).block();
        assertThat(session.getUserId()).isEqualTo(user.getId());
        assertThat(session.getIpAddress()).isEqualTo(client.ipAddress());
        assertThat(session.getRevokedAt()).isNull();
        assertThat(session.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofDays(29)));

        assertThat(outboxEventTypes(user.getId())).containsExactly("UserRegistered", "UserLoggedIn");
    }

    /**
     * A returning user signs in without a second account or registration event.
     * <ul>
     *   <li><b>Given</b> an account created by a previous sign-in with an email</li>
     *   <li><b>When</b> a new OTP for the same email in upper case is verified</li>
     *   <li><b>Then</b> {@code newUser} is false, the same user id is returned, there is one account for the email and the outbox has one UserRegistered and two UserLoggedIn</li>
     * </ul>
     */
    @Test
    void existingUserSignsInWithoutRegistrationEvent() {
        String email = uniqueEmail();
        AuthResult first = handler.handle(new VerifyOtpCommand(pendingOtp(email, 0), "123456", uniqueClient())).block();

        AuthResult second = handler.handle(new VerifyOtpCommand(pendingOtp(email.toUpperCase(), 0), "123456", uniqueClient())).block();

        assertThat(second.newUser()).isFalse();
        assertThat(second.userId()).isEqualTo(first.userId());
        assertThat(outboxEventTypes(first.userId())).containsExactly("UserRegistered", "UserLoggedIn", "UserLoggedIn");
        Long accounts = db.sql("SELECT count(*) FROM users WHERE email = :e").bind("e", email.toLowerCase())
                .map(r -> r.get(0, Long.class)).one().block();
        assertThat(accounts).isEqualTo(1L);
    }

    /**
     * A wrong code counts an attempt.
     * <ul>
     *   <li><b>Given</b> a pending OTP with no attempts</li>
     *   <li><b>When</b> a wrong code is verified</li>
     *   <li><b>Then</b> it fails with {@code OTP_INVALID} and 4 attempts left, and Redis shows 1 attempt</li>
     * </ul>
     */
    @Test
    void wrongCodeIncrementsAttemptsInRedis() {
        String requestId = pendingOtp(uniquePhone(), 0);

        StepVerifier.create(handler.handle(new VerifyOtpCommand(requestId, "000000", uniqueClient())))
                .expectErrorSatisfies(e -> {
                    assertThat(code(e)).isEqualTo("OTP_INVALID");
                    assertThat(((ApiException) e).getDetails()).containsEntry("attemptsLeft", 4);
                })
                .verify();

        assertThat(otpStore.find(requestId).block().attempts()).isEqualTo(1);
    }

    /**
     * The fifth wrong code burns the OTP.
     * <ul>
     *   <li><b>Given</b> a pending OTP with 4 attempts</li>
     *   <li><b>When</b> a wrong code is verified</li>
     *   <li><b>Then</b> it fails with {@code OTP_ATTEMPTS_EXCEEDED} and the challenge is deleted</li>
     * </ul>
     */
    @Test
    void fifthWrongCodeBurnsTheChallenge() {
        String requestId = pendingOtp(uniquePhone(), 4);

        StepVerifier.create(handler.handle(new VerifyOtpCommand(requestId, "000000", uniqueClient())))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("OTP_ATTEMPTS_EXCEEDED"))
                .verify();

        assertThat(otpStore.find(requestId).blockOptional()).isEmpty();
    }

    /**
     * A locked OTP rejects even the correct code.
     * <ul>
     *   <li><b>Given</b> a pending OTP that already has 5 attempts</li>
     *   <li><b>When</b> the correct code is verified</li>
     *   <li><b>Then</b> it fails with {@code OTP_ATTEMPTS_EXCEEDED} and no user is created</li>
     * </ul>
     */
    @Test
    void lockedChallengeRejectsEvenTheCorrectCode() {
        String phone = uniquePhone();
        String requestId = pendingOtp(phone, 5);

        StepVerifier.create(handler.handle(new VerifyOtpCommand(requestId, "123456", uniqueClient())))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("OTP_ATTEMPTS_EXCEEDED"))
                .verify();

        assertThat(users.findByPhone("+91" + phone).blockOptional()).isEmpty();
    }

    /**
     * An unknown OTP request is treated as expired.
     * <ul>
     *   <li><b>Given</b> a random request id with nothing in Redis</li>
     *   <li><b>When</b> a code is verified</li>
     *   <li><b>Then</b> it fails with {@code OTP_EXPIRED}</li>
     * </ul>
     */
    @Test
    void unknownRequestIsExpired() {
        StepVerifier.create(handler.handle(new VerifyOtpCommand(UUID.randomUUID().toString(), "123456", uniqueClient())))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("OTP_EXPIRED"))
                .verify();
    }

    /**
     * A code can be used only once.
     * <ul>
     *   <li><b>Given</b> an OTP that was just verified successfully</li>
     *   <li><b>When</b> the same request id and code are verified again</li>
     *   <li><b>Then</b> it fails with {@code OTP_EXPIRED}</li>
     * </ul>
     */
    @Test
    void codeIsSingleUse() {
        String requestId = pendingOtp(uniquePhone(), 0);
        handler.handle(new VerifyOtpCommand(requestId, "123456", uniqueClient())).block();

        StepVerifier.create(handler.handle(new VerifyOtpCommand(requestId, "123456", uniqueClient())))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("OTP_EXPIRED"))
                .verify();
    }

    /**
     * Parallel use of one code produces exactly one session.
     * <ul>
     *   <li><b>Given</b> a pending OTP for a new phone number</li>
     *   <li><b>When</b> five verifications with the correct code run in parallel</li>
     *   <li><b>Then</b> exactly one succeeds and the user has exactly one session</li>
     * </ul>
     */
    @Test
    void concurrentVerificationsOfOneCodeProduceExactlyOneSession() {
        String phone = uniquePhone();
        String requestId = pendingOtp(phone, 0);

        List<Boolean> outcomes = Flux.range(0, 5)
                .flatMap(i -> handler.handle(new VerifyOtpCommand(requestId, "123456", uniqueClient()))
                        .map(r -> true)
                        .onErrorResume(e -> Mono.just(false))
                        .subscribeOn(Schedulers.parallel()))
                .collectList().block();

        assertThat(outcomes).containsOnlyOnce(true);
        UUID userId = users.findByPhone("+91" + phone).block().getId();
        Long sessionCount = db.sql("SELECT count(*) FROM user_sessions WHERE user_id = :id").bind("id", userId)
                .map(r -> r.get(0, Long.class)).one().block();
        assertThat(sessionCount).isEqualTo(1L);
    }

    /**
     * A blocked account cannot sign in.
     * <ul>
     *   <li><b>Given</b> an existing account set to BLOCKED in Postgres</li>
     *   <li><b>When</b> a new OTP for it is verified</li>
     *   <li><b>Then</b> it fails with {@code ACCOUNT_BLOCKED} and no new session is created</li>
     * </ul>
     */
    @Test
    void blockedAccountCannotSignIn() {
        String email = uniqueEmail();
        UUID userId = handler.handle(new VerifyOtpCommand(pendingOtp(email, 0), "123456", uniqueClient())).block().userId();
        db.sql("UPDATE users SET status = 'BLOCKED' WHERE id = :id").bind("id", userId).fetch().rowsUpdated().block();

        StepVerifier.create(handler.handle(new VerifyOtpCommand(pendingOtp(email, 0), "123456", uniqueClient())))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("ACCOUNT_BLOCKED"))
                .verify();

        Long sessionCount = db.sql("SELECT count(*) FROM user_sessions WHERE user_id = :id").bind("id", userId)
                .map(r -> r.get(0, Long.class)).one().block();
        assertThat(sessionCount).isEqualTo(1L); // only the session from before the block
    }
}
