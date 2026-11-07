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

/** Write-model aggregate (Postgres). A user has at least one of email / phone. */
@Table("users")
@Getter
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    @Id
    private UUID id;
    private String email;
    private String phone;
    private boolean emailVerified;
    private boolean phoneVerified;
    private UserStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastLoginAt;
    /** Optimistic locking; null marks a new entity for Spring Data. */
    @Version
    private Long version;

    /**
     * Creates a new active account whose identifier is already verified (the OTP proved ownership).
     *
     * @param id new user id
     * @param identifier verified email or phone
     * @param now creation and first-login time
     * @return an unsaved user with a {@code null} version, so Spring Data inserts it
     */
    public static User register(UUID id, Identifier identifier, Instant now) {
        boolean email = identifier.type() == IdentifierType.EMAIL;
        return User.builder()
                .id(id)
                .email(email ? identifier.value() : null)
                .phone(email ? null : identifier.value())
                .emailVerified(email)
                .phoneVerified(!email)
                .status(UserStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .lastLoginAt(now)
                .build();
    }

    /**
     * Returns a copy with the login time updated and the used identifier marked verified.
     *
     * @param identifier the identifier just verified
     * @param now login time
     * @return the updated user (immutable style)
     */
    public User recordLogin(Identifier identifier, Instant now) {
        User u = withLastLoginAt(now).withUpdatedAt(now);
        return identifier.type() == IdentifierType.EMAIL ? u.withEmailVerified(true) : u.withPhoneVerified(true);
    }

    /**
     * Whether the account is suspended.
     *
     * @return {@code true} if status is {@code BLOCKED}
     */
    public boolean isBlocked() {
        return status == UserStatus.BLOCKED;
    }
}
