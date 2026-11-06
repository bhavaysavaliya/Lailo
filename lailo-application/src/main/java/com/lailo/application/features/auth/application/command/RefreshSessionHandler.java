package com.lailo.application.features.auth.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.outbox.OutboxPublisher;
import com.lailo.application.features.auth.application.AuthErrors;
import com.lailo.application.features.auth.application.AuthResult;
import com.lailo.application.features.auth.application.SessionFactory;
import com.lailo.application.features.auth.domain.UserSession;
import com.lailo.application.features.auth.domain.event.AuthEvents;
import com.lailo.application.features.auth.infrastructure.persistence.UserRepository;
import com.lailo.application.features.auth.infrastructure.persistence.UserSessionRepository;
import com.lailo.application.features.auth.infrastructure.token.JwtTokenService;
import com.lailo.application.features.auth.infrastructure.token.RefreshTokenCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Refresh-token rotation with reuse detection: every refresh retires the presented token and issues
 * a new one in the same family. If a retired token is ever presented again, someone copied it -
 * the entire family is revoked and the user must sign in again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshSessionHandler implements CommandHandler<RefreshSessionCommand, AuthResult> {

    static final String REASON_ROTATED = "ROTATED";
    static final String REASON_REUSE = "REUSE_DETECTED";
    static final String REASON_BLOCKED = "ACCOUNT_BLOCKED";

    private final UserSessionRepository sessions;
    private final UserRepository users;
    private final RefreshTokenCodec codec;
    private final SessionFactory sessionFactory;
    private final JwtTokenService jwt;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    @Override
    public Mono<AuthResult> handle(RefreshSessionCommand cmd) {
        if (cmd.refreshToken() == null || cmd.refreshToken().isBlank()) {
            return Mono.error(AuthErrors.sessionInvalid());
        }
        Instant now = Instant.now(clock);
        return sessions.findByTokenHash(codec.hash(cmd.refreshToken()))
                .switchIfEmpty(Mono.error(AuthErrors.sessionInvalid()))
                .<AuthResult>flatMap(current -> {
                    if (current.isRevoked()) {
                        return REASON_ROTATED.equals(current.getRevokedReason())
                                ? onReuse(current, cmd, now)
                                : Mono.<AuthResult>error(AuthErrors.sessionInvalid());
                    }
                    if (current.isExpired(now)) {
                        return Mono.<AuthResult>error(AuthErrors.sessionInvalid());
                    }
                    return rotate(current, cmd, now);
                })
                .onErrorMap(OptimisticLockingFailureException.class, ex -> AuthErrors.sessionInvalid());
    }

    private Mono<AuthResult> rotate(UserSession current, RefreshSessionCommand cmd, Instant now) {
        return users.findById(current.getUserId())
                .switchIfEmpty(Mono.error(AuthErrors.sessionInvalid()))
                .flatMap(user -> {
                    if (user.isBlocked()) {
                        // commit the revocation first: an error inside the same transaction would roll it back
                        return sessions.revokeFamily(current.getFamilyId(), now, REASON_BLOCKED)
                                .as(tx::transactional)
                                .then(Mono.<AuthResult>error(AuthErrors.accountBlocked()));
                    }
                    SessionFactory.NewSession next = sessionFactory.create(user, current.getFamilyId(), cmd.client());
                    UserSession retired = current.withRevokedAt(now).withRevokedReason(REASON_ROTATED)
                            .withReplacedBy(next.session().getId());
                    // saving `retired` bumps @Version: a concurrent rotation of the same token fails here
                    return sessions.save(retired)
                            .then(sessions.save(next.session()))
                            .thenReturn(new AuthResult(user.getId(), false, jwt.issue(user, next.session().getId()),
                                    next.rawToken(), next.session().getExpiresAt()))
                            .as(tx::transactional);
                });
    }

    private Mono<AuthResult> onReuse(UserSession reused, RefreshSessionCommand cmd, Instant now) {
        log.warn("Refresh token reuse detected for user {} family {} from {}",
                reused.getUserId(), reused.getFamilyId(), cmd.client().ipAddress());
        String userId = reused.getUserId().toString();
        return sessions.revokeFamily(reused.getFamilyId(), now, REASON_REUSE)
                .then(outbox.append(AuthEvents.TOPIC, AuthEvents.AGGREGATE, userId, AuthEvents.SESSION_REUSE_DETECTED,
                        new AuthEvents.SessionReuseDetected(UUID.randomUUID().toString(), userId,
                                reused.getFamilyId().toString(), cmd.client().ipAddress(), now)))
                .as(tx::transactional)
                .then(Mono.<AuthResult>error(AuthErrors.sessionInvalid()));
    }

    @Override
    public Class<RefreshSessionCommand> commandType() {
        return RefreshSessionCommand.class;
    }
}
