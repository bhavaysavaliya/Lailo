package com.lailo.application.features.auth.infrastructure.persistence;

import com.lailo.application.features.auth.domain.UserSession;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public interface UserSessionRepository extends ReactiveCrudRepository<UserSession, UUID> {

    Mono<UserSession> findByTokenHash(String tokenHash);

    @Modifying
    @Query("""
            UPDATE user_sessions
               SET revoked_at = :at, revoked_reason = :reason, version = version + 1
             WHERE family_id = :familyId AND revoked_at IS NULL
            """)
    Mono<Integer> revokeFamily(UUID familyId, Instant at, String reason);
}
