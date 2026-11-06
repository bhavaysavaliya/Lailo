package com.lailo.application.features.auth.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.outbox.OutboxPublisher;
import com.lailo.application.features.auth.domain.event.AuthEvents;
import com.lailo.application.features.auth.infrastructure.persistence.UserSessionRepository;
import com.lailo.application.features.auth.infrastructure.token.RefreshTokenCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Revokes this device's session family. Idempotent: an unknown token still "succeeds". */
@Component
@RequiredArgsConstructor
public class LogoutHandler implements CommandHandler<LogoutCommand, Boolean> {

    private final UserSessionRepository sessions;
    private final RefreshTokenCodec codec;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    @Override
    public Mono<Boolean> handle(LogoutCommand cmd) {
        if (cmd.refreshToken() == null || cmd.refreshToken().isBlank()) {
            return Mono.just(false);
        }
        Instant now = Instant.now(clock);
        return sessions.findByTokenHash(codec.hash(cmd.refreshToken()))
                .filter(s -> !s.isRevoked())
                .flatMap(s -> sessions.revokeFamily(s.getFamilyId(), now, "LOGOUT")
                        .then(outbox.append(AuthEvents.TOPIC, AuthEvents.AGGREGATE, s.getUserId().toString(),
                                AuthEvents.USER_LOGGED_OUT, new AuthEvents.UserLoggedOut(UUID.randomUUID().toString(),
                                        s.getUserId().toString(), s.getId().toString(), now)))
                        .thenReturn(true)
                        .as(tx::transactional))
                .defaultIfEmpty(false);
    }

    @Override
    public Class<LogoutCommand> commandType() {
        return LogoutCommand.class;
    }
}
