package com.lailo.application.features.auth.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.With;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One refresh token in a rotation chain. All tokens descending from one login share a {@code familyId};
 * presenting an already-rotated token revokes the whole family (refresh-token reuse detection, RFC 9700).
 * Only a SHA-256 hash of the token is stored.
 */
@Table("user_sessions")
@Getter
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSession {
    @Id
    private UUID id;
    private UUID userId;
    private UUID familyId;
    private String tokenHash;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant revokedAt;
    private String revokedReason;
    private UUID replacedBy;
    private String ipAddress;
    private String userAgent;
    @Version
    private Long version;

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
