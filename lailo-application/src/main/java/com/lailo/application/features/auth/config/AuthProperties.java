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

    /**
     * OTP settings.
     *
     * @param length number of digits
     * @param ttl how long a code stays valid
     * @param resendCooldown minimum gap between two codes for one identifier
     * @param maxVerifyAttempts wrong codes allowed before the request is burned
     * @param fixedCode when non-blank every code equals this value; development only
     * @param hashSecret HMAC key for stored codes, at least 32 characters
     */
    public record Otp(
            @DefaultValue("6") @Min(4) int length,
            @DefaultValue("5m") Duration ttl,
            @DefaultValue("30s") Duration resendCooldown,
            @DefaultValue("5") @Min(1) int maxVerifyAttempts,
            /* When set, every OTP equals this value. DEV ONLY - must be blank in production. */
            @DefaultValue("") String fixedCode,
            @NotBlank @Size(min = 32, message = "must be at least 32 characters") String hashSecret) {
    }

    /**
     * Captcha settings.
     *
     * @param provider {@code builtin} (image) or {@code turnstile} (Cloudflare)
     * @param ttl lifetime of a built-in challenge
     * @param length characters in a built-in challenge
     * @param turnstileSiteKey public key the web widget uses
     * @param turnstileSecret server key for siteverify calls
     * @param turnstileBaseUrl siteverify host (overridden in tests)
     */
    public record Captcha(
            /* builtin = server-rendered image; turnstile = Cloudflare Turnstile widget */
            @DefaultValue("builtin") @Pattern(regexp = "builtin|turnstile") String provider,
            @DefaultValue("2m") Duration ttl,
            @DefaultValue("5") @Min(4) int length,
            @DefaultValue("") String turnstileSiteKey,
            @DefaultValue("") String turnstileSecret,
            @DefaultValue("https://challenges.cloudflare.com") String turnstileBaseUrl) {
    }

    /**
     * Access-token settings.
     *
     * @param secret HMAC-SHA256 key, at least 32 characters
     * @param issuer {@code iss} claim written and required
     * @param accessTokenTtl access-token lifetime
     */
    public record Jwt(
            @NotBlank @Size(min = 32, message = "must be at least 32 characters (256-bit HMAC key)") String secret,
            @DefaultValue("lailo") String issuer,
            @DefaultValue("15m") Duration accessTokenTtl) {
    }

    /**
     * Refresh-session settings.
     *
     * @param refreshTokenTtl refresh-token lifetime
     */
    public record Session(
            @DefaultValue("30d") Duration refreshTokenTtl) {
    }

    /**
     * Auth cookie settings.
     *
     * @param secure whether cookies carry the {@code Secure} flag (false only for http://localhost)
     * @param domain cookie domain, blank for host-only cookies
     * @param accessName access-token cookie name
     * @param refreshName refresh-token cookie name
     */
    public record Cookies(
            @DefaultValue("true") boolean secure,
            @DefaultValue("") String domain,
            @DefaultValue("lailo_at") String accessName,
            @DefaultValue("lailo_rt") String refreshName) {
    }

    /**
     * Rate-limit settings (fixed windows in Redis).
     *
     * @param otpPerIpPerWindow OTP requests allowed per IP per window
     * @param otpPerIdentifierPerWindow OTP requests allowed per email or phone per window
     * @param otpWindow OTP window length
     * @param captchaPerIpPerWindow captchas allowed per IP per window
     * @param captchaWindow captcha window length
     */
    public record RateLimit(
            @DefaultValue("10") int otpPerIpPerWindow,
            @DefaultValue("5") int otpPerIdentifierPerWindow,
            @DefaultValue("15m") Duration otpWindow,
            @DefaultValue("30") int captchaPerIpPerWindow,
            @DefaultValue("5m") Duration captchaWindow) {
    }
}
