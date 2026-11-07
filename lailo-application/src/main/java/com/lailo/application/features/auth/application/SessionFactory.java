package com.lailo.application.features.auth.application;

import com.lailo.application.common.web.ClientInfo;
import com.lailo.application.features.auth.config.AuthProperties;
import com.lailo.application.features.auth.domain.User;
import com.lailo.application.features.auth.domain.UserSession;
import com.lailo.application.features.auth.infrastructure.token.RefreshTokenCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Creates refresh-token sessions; shared by sign-in and rotation. */
@Component
@RequiredArgsConstructor
public class SessionFactory {

    private final RefreshTokenCodec codec;
    private final AuthProperties props;
    private final Clock clock;

    /**
     * Builds a new refresh-token session (not yet saved) with a fresh random token.
     *
     * @param user the session owner
     * @param familyId rotation chain id: a new random id at sign-in, the existing one on rotation
     * @param client caller IP and user agent, stored for auditing
     * @return the session entity plus the raw token, which exists only in memory and in the cookie
     */
    public NewSession create(User user, UUID familyId, ClientInfo client) {
        Instant now = Instant.now(clock);
        String raw = codec.newToken();
        UserSession session = UserSession.builder()
                .id(UUID.randomUUID())
                .userId(user.getId())
                .familyId(familyId)
                .tokenHash(codec.hash(raw))
                .createdAt(now)
                .expiresAt(now.plus(props.session().refreshTokenTtl()))
                .ipAddress(client.ipAddress())
                .userAgent(client.userAgent())
                .build();
        return new NewSession(session, raw);
    }

    /**
     * A session ready to persist together with the raw refresh token to hand to the client.
     *
     * @param session entity holding only the token's SHA-256 hash
     * @param rawToken the token value for the cookie; never persisted
     */
    public record NewSession(UserSession session, String rawToken) {
        /**
         * Prints only the session id, never the token.
         *
         * @return a log-safe representation
         */
        @Override
        public String toString() {
            return "NewSession[" + session.getId() + "]";
        }
    }
}
