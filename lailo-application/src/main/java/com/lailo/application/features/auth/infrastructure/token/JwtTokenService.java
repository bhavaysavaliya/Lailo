package com.lailo.application.features.auth.infrastructure.token;

import com.lailo.application.features.auth.config.AuthProperties;
import com.lailo.application.features.auth.domain.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Short-lived access JWT (default 15 min). Carries only the user id and roles - never PII.
 * HS256 is fine for a single backend; move to RS256/ES256 + a JWKS endpoint when other services verify tokens.
 */
@Component
public class JwtTokenService {

    private final JwtEncoder encoder;
    private final AuthProperties.Jwt cfg;
    private final Clock clock;

    public JwtTokenService(JwtEncoder encoder, AuthProperties props, Clock clock) {
        this.encoder = encoder;
        this.cfg = props.jwt();
        this.clock = clock;
    }

    public AccessToken issue(User user, UUID sessionId) {
        Instant now = Instant.now(clock);
        Instant exp = now.plus(cfg.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .id(UUID.randomUUID().toString())
                .issuer(cfg.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(exp)
                .claim("sid", sessionId.toString())
                .claim("roles", List.of("CUSTOMER"))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(token, exp);
    }
}
