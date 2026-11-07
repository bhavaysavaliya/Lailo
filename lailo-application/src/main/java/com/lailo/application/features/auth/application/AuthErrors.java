package com.lailo.application.features.auth.application;

import com.lailo.application.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Map;

/** Catalogue of auth error codes. Messages are written for the end user. */
public final class AuthErrors {

    /** Not instantiable: static factory holder. */
    private AuthErrors() {
    }

    /**
     * Captcha answer was wrong, expired or already used.
     *
     * @return 400 {@code CAPTCHA_INVALID}
     */
    public static ApiException captchaInvalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "CAPTCHA_INVALID",
                "The characters didn't match. Try the new image.");
    }

    /**
     * Caller exceeded a rate limit.
     *
     * @param retryAfter window length, reported to the client as {@code retryAfterSeconds}
     * @return 429 {@code RATE_LIMITED}
     */
    public static ApiException tooManyRequests(Duration retryAfter) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Too many attempts. Wait a few minutes and try again.",
                Map.of("retryAfterSeconds", Math.max(1, retryAfter.toSeconds())));
    }

    /**
     * A code was sent to this identifier moments ago.
     *
     * @param wait remaining cooldown, reported as {@code retryAfterSeconds}
     * @return 429 {@code OTP_RESEND_COOLDOWN}
     */
    public static ApiException resendCooldown(Duration wait) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RESEND_COOLDOWN",
                "A code was just sent. You can request a new one in " + Math.max(1, wait.toSeconds()) + " seconds.",
                Map.of("retryAfterSeconds", Math.max(1, wait.toSeconds())));
    }

    /**
     * OTP request is unknown, expired or already used.
     *
     * @return 410 {@code OTP_EXPIRED}
     */
    public static ApiException otpExpired() {
        return new ApiException(HttpStatus.GONE, "OTP_EXPIRED",
                "This code has expired. Request a new one.");
    }

    /**
     * Code did not match.
     *
     * @param attemptsLeft remaining tries before the code is burned, reported as {@code attemptsLeft}
     * @return 401 {@code OTP_INVALID}
     */
    public static ApiException otpInvalid(int attemptsLeft) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "OTP_INVALID",
                attemptsLeft == 1 ? "Incorrect code. 1 attempt left." : "Incorrect code. " + attemptsLeft + " attempts left.",
                Map.of("attemptsLeft", attemptsLeft));
    }

    /**
     * Too many wrong codes; the OTP request has been burned.
     *
     * @return 429 {@code OTP_ATTEMPTS_EXCEEDED}
     */
    public static ApiException otpLocked() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_ATTEMPTS_EXCEEDED",
                "Too many incorrect codes. Request a new one.");
    }

    /**
     * Account is blocked.
     *
     * @return 403 {@code ACCOUNT_BLOCKED}
     */
    public static ApiException accountBlocked() {
        return new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_BLOCKED",
                "This account is suspended. Contact support for help.");
    }

    /**
     * Session is missing, expired, revoked or reused.
     *
     * @return 401 {@code SESSION_INVALID}
     */
    public static ApiException sessionInvalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_INVALID",
                "Your session has ended. Sign in again.");
    }
}
