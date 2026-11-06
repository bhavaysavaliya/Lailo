package com.lailo.application.features.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendOtpRequest(
        @NotBlank(message = "Enter your email or mobile number.") @Size(max = 254) String identifier,
        @Size(max = 64) String captchaId,
        @NotBlank(message = "Enter the characters shown in the image.") @Size(max = 4096) String captchaAnswer) {
    @Override
    public String toString() {
        return "SendOtpRequest[captchaId=" + captchaId + "]"; // keep PII out of debug logs
    }
}
