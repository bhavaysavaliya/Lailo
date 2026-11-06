package com.lailo.application.features.auth.application.command;

import com.lailo.application.common.web.ApiException;
import com.lailo.application.common.web.ClientInfo;
import com.lailo.application.features.auth.application.AuthResult;
import com.lailo.application.features.auth.config.AuthProperties;
import com.lailo.application.features.auth.domain.Identifier;
import com.lailo.application.features.auth.domain.UserSession;
import com.lailo.application.features.auth.infrastructure.otp.OtpHasher;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Refresh-token rotation and reuse detection against real PostgreSQL. */
class RefreshSessionHandlerIT extends IntegrationTest {

    @Autowired RefreshSessionHandler handler;
    @Autowired VerifyOtpHandler verifyOtp;
    @Autowired RedisOtpStore otpStore;
    @Autowired OtpHasher hasher;
    @Autowired AuthProperties props;
    @Autowired UserSessionRepository sessions;
    @Autowired RefreshTokenCodec codec;
    @Autowired DatabaseClient db;

    /** Signs a fresh user in through the real handler and returns their session. */
    private AuthResult signIn() {
        String requestId = UUID.randomUUID().toString();
        otpStore.save(new OtpChallenge(requestId, Identifier.parse(uniqueEmail(), "91"),
                hasher.hash(requestId, "123456"), 0), props.otp().ttl()).block();
        return verifyOtp.handle(new VerifyOtpCommand(requestId, "123456", uniqueClient())).block();
    }

    private UserSession session(String rawToken) {
        return sessions.findByTokenHash(codec.hash(rawToken)).block();
    }

    private static String code(Throwable e) {
        return ((ApiException) e).getCode();
    }

    @Test
    void rotatesTokenAndChainsSessionsInTheSameFamily() {
        AuthResult login = signIn();
        ClientInfo client = uniqueClient();

        AuthResult refreshed = handler.handle(new RefreshSessionCommand(login.refreshToken(), client)).block();

        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThat(refreshed.userId()).isEqualTo(login.userId());
        UserSession retired = session(login.refreshToken());
        UserSession current = session(refreshed.refreshToken());
        assertThat(retired.getRevokedReason()).isEqualTo("ROTATED");
        assertThat(retired.getReplacedBy()).isEqualTo(current.getId());
        assertThat(current.getFamilyId()).isEqualTo(retired.getFamilyId());
        assertThat(current.getRevokedAt()).isNull();
        assertThat(current.getIpAddress()).isEqualTo(client.ipAddress());
    }

    @Test
    void replayingARotatedTokenRevokesTheWholeFamilyAndRecordsAnEvent() {
        AuthResult login = signIn();
        AuthResult refreshed = handler.handle(new RefreshSessionCommand(login.refreshToken(), uniqueClient())).block();

        StepVerifier.create(handler.handle(new RefreshSessionCommand(login.refreshToken(), uniqueClient())))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("SESSION_INVALID"))
                .verify();

        assertThat(session(refreshed.refreshToken()).getRevokedReason()).isEqualTo("REUSE_DETECTED");
        StepVerifier.create(handler.handle(new RefreshSessionCommand(refreshed.refreshToken(), uniqueClient())))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("SESSION_INVALID"))
                .verify();
        Long events = db.sql("SELECT count(*) FROM outbox_messages WHERE event_type = 'SessionReuseDetected' AND aggregate_id = :id")
                .bind("id", login.userId().toString()).map(r -> r.get(0, Long.class)).one().block();
        assertThat(events).isEqualTo(1L);
    }

    @Test
    void otherDevicesSurviveReuseOnOneDevice() {
        AuthResult phone = signIn();
        // second, independent login for the same user = a different session family
        String requestId = UUID.randomUUID().toString();
        String email = db.sql("SELECT email FROM users WHERE id = :id").bind("id", phone.userId())
                .map(r -> r.get(0, String.class)).one().block();
        otpStore.save(new OtpChallenge(requestId, Identifier.parse(email, "91"), hasher.hash(requestId, "123456"), 0),
                props.otp().ttl()).block();
        AuthResult laptopLogin = verifyOtp.handle(new VerifyOtpCommand(requestId, "123456", uniqueClient())).block();

        handler.handle(new RefreshSessionCommand(phone.refreshToken(), uniqueClient())).block();
        handler.handle(new RefreshSessionCommand(phone.refreshToken(), uniqueClient())).onErrorResume(e -> Mono.empty()).block();

        StepVerifier.create(handler.handle(new RefreshSessionCommand(laptopLogin.refreshToken(), uniqueClient())))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void expiredSessionIsRejected() {
        AuthResult login = signIn();
        db.sql("UPDATE user_sessions SET expires_at = now() - interval '1 minute' WHERE token_hash = :h")
                .bind("h", codec.hash(login.refreshToken())).fetch().rowsUpdated().block();

        StepVerifier.create(handler.handle(new RefreshSessionCommand(login.refreshToken(), uniqueClient())))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("SESSION_INVALID"))
                .verify();
        assertThat(session(login.refreshToken()).getRevokedAt()).isNull();
    }

    @Test
    void blockedUserCannotRefreshAndLosesAllSessionsInFamily() {
        AuthResult login = signIn();
        db.sql("UPDATE users SET status = 'BLOCKED' WHERE id = :id").bind("id", login.userId()).fetch().rowsUpdated().block();

        StepVerifier.create(handler.handle(new RefreshSessionCommand(login.refreshToken(), uniqueClient())))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("ACCOUNT_BLOCKED"))
                .verify();
        assertThat(session(login.refreshToken()).getRevokedReason()).isEqualTo("ACCOUNT_BLOCKED");
    }

    @Test
    void unknownOrMissingTokenIsRejected() {
        StepVerifier.create(handler.handle(new RefreshSessionCommand("not-a-real-token", uniqueClient())))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("SESSION_INVALID"))
                .verify();
        StepVerifier.create(handler.handle(new RefreshSessionCommand(null, uniqueClient())))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("SESSION_INVALID"))
                .verify();
    }

    @Test
    void concurrentRefreshesWithOneTokenYieldAtMostOneNewSession() {
        AuthResult login = signIn();

        List<Boolean> outcomes = Flux.range(0, 4)
                .flatMap(i -> handler.handle(new RefreshSessionCommand(login.refreshToken(), uniqueClient()))
                        .map(r -> true)
                        .onErrorResume(e -> Mono.just(false))
                        .subscribeOn(Schedulers.parallel()))
                .collectList().block();

        assertThat(outcomes.stream().filter(Boolean::booleanValue).count()).isLessThanOrEqualTo(1);
        Long active = db.sql("SELECT count(*) FROM user_sessions WHERE family_id = :f AND revoked_at IS NULL")
                .bind("f", session(login.refreshToken()).getFamilyId()).map(r -> r.get(0, Long.class)).one().block();
        assertThat(active).isLessThanOrEqualTo(1L);
    }
}
