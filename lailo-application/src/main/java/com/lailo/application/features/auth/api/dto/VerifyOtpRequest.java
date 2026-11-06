package com.lailo.application.features.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifyOtpRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank(message = "Enter the code we sent you.") @Pattern(regexp = "\\d{4,8}", message = "The code is numbers only.") String otp) {
    @Override
    public String toString() {
        return "VerifyOtpRequest[requestId=" + requestId + "]";
    }
}
