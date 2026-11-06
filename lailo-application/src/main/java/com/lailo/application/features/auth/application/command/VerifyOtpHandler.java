package com.lailo.application.features.auth.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.outbox.OutboxPublisher;
import com.lailo.application.features.auth.application.AuthErrors;
import com.lailo.application.features.auth.application.AuthResult;
import com.lailo.application.features.auth.application.SessionFactory;
import com.lailo.application.features.auth.config.AuthProperties;
import com.lailo.application.features.auth.domain.Identifier;
import com.lailo.application.features.auth.domain.IdentifierType;
import com.lailo.application.features.auth.domain.User;
import com.lailo.application.features.auth.domain.event.AuthEvents;
import com.lailo.application.features.auth.infrastructure.otp.OtpHasher;
import com.lailo.application.features.auth.infrastructure.persistence.UserRepository;
import com.lailo.application.features.auth.infrastructure.persistence.UserSessionRepository;
import com.lailo.application.features.auth.infrastructure.redis.OtpChallenge;
import com.lailo.application.features.auth.infrastructure.redis.RedisOtpStore;
import com.lailo.application.features.auth.infrastructure.token.JwtTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Step 2: verify the OTP, then sign in - creating the account on first use (unified sign up / login,
 * as on Amazon and Flipkart). User upsert, session and outbox event commit in one transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerifyOtpHandler implements CommandHandler<VerifyOtpCommand, AuthResult> {

    private final AuthProperties props;
    private final RedisOtpStore otpStore;
    private final OtpHasher hasher;
    private final UserRepository users;
    private final UserSessionRepository sessions;
    private final SessionFactory sessionFactory;
    private final JwtTokenService jwt;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    @Override
    public Mono<AuthResult> handle(VerifyOtpCommand cmd) {
        return otpStore.find(cmd.requestId())
                .switchIfEmpty(Mono.error(AuthErrors.otpExpired()))
                .flatMap(challenge -> checkCode(challenge, cmd.code()))
                .flatMap(challenge -> signIn(challenge.identifier(), cmd)
                        // two concurrent first-time sign-ins for the same identifier: unique index wins, retry as login
                        .onErrorResume(DataIntegrityViolationException.class, ex -> signIn(challenge.identifier(), cmd)));
    }

    private Mono<OtpChallenge> checkCode(OtpChallenge challenge, String code) {
        int max = props.otp().maxVerifyAttempts();
        if (challenge.attempts() >= max) {
            return otpStore.consume(challenge.requestId()).then(Mono.error(AuthErrors.otpLocked()));
        }
        if (code != null && hasher.matches(challenge.requestId(), code.trim(), challenge.codeHash())) {
            return otpStore.consume(challenge.requestId())
                    .flatMap(won -> won ? Mono.just(challenge) : Mono.error(AuthErrors.otpExpired()));
        }
        return otpStore.incrementAttempts(challenge.requestId())
                .flatMap(attempts -> {
                    int left = (int) (max - attempts);
                    if (left <= 0) {
                        return otpStore.consume(challenge.requestId()).then(Mono.error(AuthErrors.otpLocked()));
                    }
                    return Mono.error(AuthErrors.otpInvalid(left));
                });
    }

    private Mono<AuthResult> signIn(Identifier identifier, VerifyOtpCommand cmd) {
        Instant now = Instant.now(clock);
        Mono<User> lookup = identifier.type() == IdentifierType.EMAIL
                ? users.findByEmail(identifier.value())
                : users.findByPhone(identifier.value());

        return lookup
                .map(existing -> new Resolved(existing, false))
                .defaultIfEmpty(new Resolved(null, true))
                .flatMap(r -> {
                    if (!r.isNew() && r.user().isBlocked()) {
                        return Mono.error(AuthErrors.accountBlocked());
                    }
                    User toSave = r.isNew()
                            ? User.register(UUID.randomUUID(), identifier, now)
                            : r.user().recordLogin(identifier, now);
                    return users.save(toSave).map(saved -> new Resolved(saved, r.isNew()));
                })
                .flatMap(r -> {
                    SessionFactory.NewSession ns = sessionFactory.create(r.user(), UUID.randomUUID(), cmd.client());
                    return sessions.save(ns.session())
                            .then(appendEvents(r, identifier, ns.session().getId(), now))
                            .thenReturn(new AuthResult(r.user().getId(), r.isNew(),
                                    jwt.issue(r.user(), ns.session().getId()),
                                    ns.rawToken(), ns.session().getExpiresAt()));
                })
                .as(tx::transactional);
    }

    private Mono<Void> appendEvents(Resolved r, Identifier identifier, UUID sessionId, Instant now) {
        User u = r.user();
        String userId = u.getId().toString();
        Mono<?> registered = r.isNew()
                ? outbox.append(AuthEvents.TOPIC, AuthEvents.AGGREGATE, userId, AuthEvents.USER_REGISTERED,
                new AuthEvents.UserRegistered(UUID.randomUUID().toString(), userId, u.getEmail(), u.getPhone(), now))
                : Mono.empty();
        return registered.then(outbox.append(AuthEvents.TOPIC, AuthEvents.AGGREGATE, userId, AuthEvents.USER_LOGGED_IN,
                new AuthEvents.UserLoggedIn(UUID.randomUUID().toString(), userId, sessionId.toString(),
                        identifier.type().name(), u.getEmail(), u.getPhone(), now))).then();
    }

    private record Resolved(User user, boolean isNew) {
    }

    @Override
    public Class<VerifyOtpCommand> commandType() {
        return VerifyOtpCommand.class;
    }
}
