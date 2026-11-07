package com.lailo.application.features.auth.infrastructure.persistence;

import com.lailo.application.features.auth.domain.UserSession;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/** R2DBC repository for refresh-token sessions (table {@code user_sessions}). */
public interface UserSessionRepository extends ReactiveCrudRepository<UserSession, UUID> {

    /**
     * Finds a session by the SHA-256 of its refresh token.
     *
     * @param tokenHash lowercase hex SHA-256
     * @return the session, or empty
     */
    Mono<UserSession> findByTokenHash(String tokenHash);

    /**
     * Revokes every still-active session in a family.
     *
     * @param familyId rotation chain id
     * @param at revocation time
     * @param reason why, e.g. {@code LOGOUT} or {@code REUSE_DETECTED}
     * @return number of sessions revoked
     */
    @Modifying
    @Query("""
            UPDATE user_sessions
               SET revoked_at = :at, revoked_reason = :reason, version = version + 1
             WHERE family_id = :familyId AND revoked_at IS NULL
            """)
    Mono<Integer> revokeFamily(UUID familyId, Instant at, String reason);
}
