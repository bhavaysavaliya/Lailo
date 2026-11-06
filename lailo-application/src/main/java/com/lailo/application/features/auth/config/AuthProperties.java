package com.lailo.application.features.auth.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** All auth tunables. Secrets come from environment variables, never from committed files. */
@Validated
@ConfigurationProperties(prefix = "lailo.auth")
public record AuthProperties(
        @Valid Otp otp,
        @Valid Captcha captcha,
        @Valid Jwt jwt,
        @Valid Session session,
        @Valid Cookies cookies,
        @Valid RateLimit rateLimit,
        @DefaultValue("IN") @Pattern(regexp = "[A-Z]{2}") String defaultRegion,
        @DefaultValue("91") @Pattern(regexp = "\\d{1,3}") String defaultCountryCode) {

    public record Otp(
            @DefaultValue("6") @Min(4) int length,
            @DefaultValue("5m") Duration ttl,
            @DefaultValue("30s") Duration resendCooldown,
            @DefaultValue("5") @Min(1) int maxVerifyAttempts,
            /* When set, every OTP equals this value. DEV ONLY - must be blank in production. */
            @DefaultValue("") String fixedCode,
            @NotBlank @Size(min = 32, message = "must be at least 32 characters") String hashSecret) {
    }

    public record Captcha(
            /* builtin = server-rendered image; turnstile = Cloudflare Turnstile widget */
            @DefaultValue("builtin") @Pattern(regexp = "builtin|turnstile") String provider,
            @DefaultValue("2m") Duration ttl,
            @DefaultValue("5") @Min(4) int length,
            @DefaultValue("") String turnstileSiteKey,
            @DefaultValue("") String turnstileSecret,
            @DefaultValue("https://challenges.cloudflare.com") String turnstileBaseUrl) {
    }

    public record Jwt(
            @NotBlank @Size(min = 32, message = "must be at least 32 characters (256-bit HMAC key)") String secret,
            @DefaultValue("lailo") String issuer,
            @DefaultValue("15m") Duration accessTokenTtl) {
    }

    public record Session(
            @DefaultValue("30d") Duration refreshTokenTtl) {
    }

    public record Cookies(
            @DefaultValue("true") boolean secure,
            @DefaultValue("") String domain,
            @DefaultValue("lailo_at") String accessName,
            @DefaultValue("lailo_rt") String refreshName) {
    }

    public record RateLimit(
            @DefaultValue("10") int otpPerIpPerWindow,
            @DefaultValue("5") int otpPerIdentifierPerWindow,
            @DefaultValue("15m") Duration otpWindow,
            @DefaultValue("30") int captchaPerIpPerWindow,
            @DefaultValue("5m") Duration captchaWindow) {
    }
}
