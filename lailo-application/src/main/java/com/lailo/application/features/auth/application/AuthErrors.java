package com.lailo.application.features.auth.application;

import com.lailo.application.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Map;

/** Catalogue of auth error codes. Messages are written for the end user. */
public final class AuthErrors {

    private AuthErrors() {
    }

    public static ApiException captchaInvalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "CAPTCHA_INVALID",
                "The characters didn't match. Try the new image.");
    }

    public static ApiException tooManyRequests(Duration retryAfter) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Too many attempts. Wait a few minutes and try again.",
                Map.of("retryAfterSeconds", Math.max(1, retryAfter.toSeconds())));
    }

    public static ApiException resendCooldown(Duration wait) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RESEND_COOLDOWN",
                "A code was just sent. You can request a new one in " + Math.max(1, wait.toSeconds()) + " seconds.",
                Map.of("retryAfterSeconds", Math.max(1, wait.toSeconds())));
    }

    public static ApiException otpExpired() {
        return new ApiException(HttpStatus.GONE, "OTP_EXPIRED",
                "This code has expired. Request a new one.");
    }

    public static ApiException otpInvalid(int attemptsLeft) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "OTP_INVALID",
                attemptsLeft == 1 ? "Incorrect code. 1 attempt left." : "Incorrect code. " + attemptsLeft + " attempts left.",
                Map.of("attemptsLeft", attemptsLeft));
    }

    public static ApiException otpLocked() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_ATTEMPTS_EXCEEDED",
                "Too many incorrect codes. Request a new one.");
    }

    public static ApiException accountBlocked() {
        return new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_BLOCKED",
                "This account is suspended. Contact support for help.");
    }

    public static ApiException sessionInvalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_INVALID",
                "Your session has ended. Sign in again.");
    }
}
