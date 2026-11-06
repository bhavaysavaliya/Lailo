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

    private List<String> outboxEventTypes(UUID userId) {
        return db.sql("SELECT event_type FROM outbox_messages WHERE aggregate_id = :id ORDER BY created_at")
                .bind("id", userId.toString()).map(r -> r.get(0, String.class)).all().collectList().block();
    }

    private static String code(Throwable e) {
        return ((ApiException) e).getCode();
    }

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

    @Test
    void fifthWrongCodeBurnsTheChallenge() {
        String requestId = pendingOtp(uniquePhone(), 4);

        StepVerifier.create(handler.handle(new VerifyOtpCommand(requestId, "000000", uniqueClient())))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("OTP_ATTEMPTS_EXCEEDED"))
                .verify();

        assertThat(otpStore.find(requestId).blockOptional()).isEmpty();
    }

    @Test
    void lockedChallengeRejectsEvenTheCorrectCode() {
        String phone = uniquePhone();
        String requestId = pendingOtp(phone, 5);

        StepVerifier.create(handler.handle(new VerifyOtpCommand(requestId, "123456", uniqueClient())))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("OTP_ATTEMPTS_EXCEEDED"))
                .verify();

        assertThat(users.findByPhone("+91" + phone).blockOptional()).isEmpty();
    }

    @Test
    void unknownRequestIsExpired() {
        StepVerifier.create(handler.handle(new VerifyOtpCommand(UUID.randomUUID().toString(), "123456", uniqueClient())))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("OTP_EXPIRED"))
                .verify();
    }

    @Test
    void codeIsSingleUse() {
        String requestId = pendingOtp(uniquePhone(), 0);
        handler.handle(new VerifyOtpCommand(requestId, "123456", uniqueClient())).block();

        StepVerifier.create(handler.handle(new VerifyOtpCommand(requestId, "123456", uniqueClient())))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("OTP_EXPIRED"))
                .verify();
    }

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
