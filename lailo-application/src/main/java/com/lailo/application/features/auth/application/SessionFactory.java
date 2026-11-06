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

    public record NewSession(UserSession session, String rawToken) {
        @Override
        public String toString() {
            return "NewSession[" + session.getId() + "]";
        }
    }
}
