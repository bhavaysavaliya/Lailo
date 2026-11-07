package com.lailo.application.features.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/otp}.
 *
 * @param identifier email address or mobile number as typed by the user
 * @param captchaId id of the captcha challenge (built-in provider only)
 * @param captchaAnswer the characters the user read, or the Turnstile widget token
 */
public record SendOtpRequest(
        @NotBlank(message = "Enter your email or mobile number.") @Size(max = 254) String identifier,
        @Size(max = 64) String captchaId,
        @NotBlank(message = "Enter the characters shown in the image.") @Size(max = 4096) String captchaAnswer) {
    /**
     * Masks personal data so the debug logging aspect never prints the identifier or captcha answer.
     *
     * @return a log-safe representation
     */
    @Override
    public String toString() {
        return "SendOtpRequest[captchaId=" + captchaId + "]"; // keep PII out of debug logs
    }
}
