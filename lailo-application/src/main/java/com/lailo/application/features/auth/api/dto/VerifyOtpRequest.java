package com.lailo.application.features.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/otp/verify}.
 *
 * @param requestId id returned by the send-OTP call
 * @param otp the 4 to 8 digit code the user entered
 */
public record VerifyOtpRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank(message = "Enter the code we sent you.") @Pattern(regexp = "\\d{4,8}", message = "The code is numbers only.") String otp) {
    /**
     * Omits the code so it never reaches logs.
     *
     * @return a log-safe representation
     */
    @Override
    public String toString() {
        return "VerifyOtpRequest[requestId=" + requestId + "]";
    }
}
