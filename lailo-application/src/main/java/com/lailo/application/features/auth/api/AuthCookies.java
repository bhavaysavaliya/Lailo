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

    /**
     * Creates the cookie writer.
     *
     * @param props auth settings (cookie names, secure flag, domain)
     * @param clock time source used to compute cookie {@code Max-Age}
     */
    public AuthCookies(AuthProperties props, Clock clock) {
        this.cfg = props.cookies();
        this.clock = clock;
    }

    /**
     * Adds the access cookie ({@code SameSite=Lax}, path {@code /}) and the refresh cookie
     * ({@code SameSite=Strict}, path {@code /api/v1/auth}), both HttpOnly, with lifetimes matching the tokens.
     *
     * @param response response to add the cookies to
     * @param result issued tokens and their expiry times
     */
    public void write(ServerHttpResponse response, AuthResult result) {
        Instant now = Instant.now(clock);
        response.addCookie(base(cfg.accessName(), result.accessToken().value(), "/", "Lax")
                .maxAge(Duration.between(now, result.accessToken().expiresAt())).build());
        response.addCookie(base(cfg.refreshName(), result.refreshToken(), REFRESH_PATH, "Strict")
                .maxAge(Duration.between(now, result.refreshExpiresAt())).build());
    }

    /**
     * Expires both auth cookies immediately ({@code Max-Age=0}) with the same attributes they were set with.
     *
     * @param response response to add the expiring cookies to
     */
    public void clear(ServerHttpResponse response) {
        response.addCookie(base(cfg.accessName(), "", "/", "Lax").maxAge(0).build());
        response.addCookie(base(cfg.refreshName(), "", REFRESH_PATH, "Strict").maxAge(0).build());
    }

    /**
     * Returns the refresh-token cookie name.
     *
     * @return configured name, {@code lailo_rt} by default
     */
    public String refreshCookieName() {
        return cfg.refreshName();
    }

    /**
     * Returns the access-token cookie name.
     *
     * @return configured name, {@code lailo_at} by default
     */
    public String accessCookieName() {
        return cfg.accessName();
    }

    /**
     * Starts a cookie with the shared hardening attributes (HttpOnly, Secure flag, SameSite, path, optional domain).
     *
     * @param name cookie name
     * @param value cookie value
     * @param path cookie path
     * @param sameSite {@code Lax} or {@code Strict}
     * @return a builder the caller completes with {@code maxAge}
     */
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
