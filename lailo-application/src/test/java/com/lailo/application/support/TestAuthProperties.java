package com.lailo.application.support;

import com.lailo.application.features.auth.config.AuthProperties;

import java.time.Duration;

public final class TestAuthProperties {

    public static final String OTP_SECRET = "test-otp-hmac-secret-0123456789abcdef";
    public static final String JWT_SECRET = "test-jwt-hmac-secret-0123456789abcdef";

    private TestAuthProperties() {
    }

    public static AuthProperties create() {
        return new AuthProperties(
                new AuthProperties.Otp(6, Duration.ofMinutes(5), Duration.ofSeconds(30), 5, "", OTP_SECRET),
                new AuthProperties.Captcha("builtin", Duration.ofMinutes(2), 5, "", "", "http://localhost"),
                new AuthProperties.Jwt(JWT_SECRET, "lailo", Duration.ofMinutes(15)),
                new AuthProperties.Session(Duration.ofDays(30)),
                new AuthProperties.Cookies(false, "", "lailo_at", "lailo_rt"),
                new AuthProperties.RateLimit(10, 5, Duration.ofMinutes(15), 30, Duration.ofMinutes(5)),
                "IN", "91");
    }
}
