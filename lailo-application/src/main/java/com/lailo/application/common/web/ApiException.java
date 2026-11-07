package com.lailo.application.common.web;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Business error with a stable machine-readable {@code code} the frontend can switch on.
 * Messages are user-safe: never put secrets or internal details here.
 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Map<String, Object> details;

    /**
     * Creates a business error without extra details.
     *
     * @param status HTTP status to return
     * @param code stable machine-readable error code, e.g. {@code OTP_INVALID}
     * @param message user-facing message (safe to display)
     */
    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    /**
     * Creates a business error with extra properties that are added to the problem response.
     *
     * @param status HTTP status to return
     * @param code stable machine-readable error code
     * @param message user-facing message (safe to display)
     * @param details extra properties, e.g. {@code attemptsLeft}; must not contain secrets
     */
    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }
}
