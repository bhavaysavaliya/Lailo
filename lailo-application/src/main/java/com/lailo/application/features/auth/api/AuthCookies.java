package com.lailo.application.features.auth.api;

import com.lailo.application.features.auth.application.AuthResult;
import com.lailo.application.features.auth.config.AuthProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Access token: HttpOnly, SameSite=Lax, path=/ (sent on API calls and SSR page loads).
 * Refresh token: HttpOnly, SameSite=Strict, path=/api/v1/auth - only ever sent to the refresh/logout endpoints.
 */
@Component
public class AuthCookies {

    public static final String REFRESH_PATH = "/api/v1/auth";

    private final AuthProperties.Cookies cfg;
    private final Clock clock;

    public AuthCookies(AuthProperties props, Clock clock) {
        this.cfg = props.cookies();
        this.clock = clock;
    }

    public void write(ServerHttpResponse response, AuthResult result) {
        Instant now = Instant.now(clock);
        response.addCookie(base(cfg.accessName(), result.accessToken().value(), "/", "Lax")
                .maxAge(Duration.between(now, result.accessToken().expiresAt())).build());
        response.addCookie(base(cfg.refreshName(), result.refreshToken(), REFRESH_PATH, "Strict")
                .maxAge(Duration.between(now, result.refreshExpiresAt())).build());
    }

    public void clear(ServerHttpResponse response) {
        response.addCookie(base(cfg.accessName(), "", "/", "Lax").maxAge(0).build());
        response.addCookie(base(cfg.refreshName(), "", REFRESH_PATH, "Strict").maxAge(0).build());
    }

    public String refreshCookieName() {
        return cfg.refreshName();
    }

    public String accessCookieName() {
        return cfg.accessName();
    }

    private ResponseCookie.ResponseCookieBuilder base(String name, String value, String path, String sameSite) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cfg.secure())
                .sameSite(sameSite)
                .path(path);
        if (!cfg.domain().isBlank()) {
            b.domain(cfg.domain());
        }
        return b;
    }
}
