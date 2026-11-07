package com.lailo.application.features.auth.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.features.auth.application.AuthErrors;
import com.lailo.application.features.auth.config.AuthProperties;
import com.lailo.application.features.auth.domain.Identifier;
import com.lailo.application.features.auth.infrastructure.captcha.CaptchaService;
import com.lailo.application.features.auth.infrastructure.otp.OtpGenerator;
import com.lailo.application.features.auth.infrastructure.otp.OtpHasher;
import com.lailo.application.features.auth.infrastructure.otp.OtpSender;
import com.lailo.application.features.auth.infrastructure.redis.OtpChallenge;
import com.lailo.application.features.auth.infrastructure.redis.RedisOtpStore;
import com.lailo.application.features.auth.infrastructure.redis.RedisRateLimiter;
import com.lailo.application.features.auth.infrastructure.token.RefreshTokenCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Step 1 of passwordless sign-in. Order matters:
 * IP throttle -> captcha (single use) -> per-identifier throttle -> resend cooldown -> issue + deliver.
 * Captcha comes before the identifier quota so bots can't exhaust a victim's OTP budget.
 * The response is identical for new and existing accounts (no account enumeration).
 */
@Component
@RequiredArgsConstructor
public class SendOtpHandler implements CommandHandler<SendOtpCommand, SendOtpCommand.Result> {

    private final AuthProperties props;
    private final CaptchaService captchaService;
    private final RedisRateLimiter rateLimiter;
    private final RedisOtpStore otpStore;
    private final OtpGenerator generator;
    private final OtpHasher hasher;
    private final OtpSender sender;
    private final RefreshTokenCodec digest;

    /**
     * Validates the identifier, then runs the throttles and captcha check in order and issues the code.
     *
     * @param cmd the request
     * @return the send result, or an {@code ApiException}: {@code INVALID_IDENTIFIER}, {@code RATE_LIMITED},
     *         {@code CAPTCHA_INVALID} or {@code OTP_RESEND_COOLDOWN}
     */
    @Override
    public Mono<SendOtpCommand.Result> handle(SendOtpCommand cmd) {
        return Mono.fromCallable(() -> Identifier.parse(cmd.identifier(), props.defaultCountryCode()))
                .flatMap(identifier -> throttleIp(cmd)
                        .then(Mono.defer(() -> verifyCaptcha(cmd)))
                        .then(Mono.defer(() -> throttleIdentifier(identifier)))
                        .then(Mono.defer(() -> issue(identifier))));
    }

    /**
     * Enforces the per-IP OTP quota for the current window.
     *
     * @param cmd the request
     * @return empty when allowed, {@code RATE_LIMITED} otherwise
     */
    private Mono<Void> throttleIp(SendOtpCommand cmd) {
        AuthProperties.RateLimit rl = props.rateLimit();
        return rateLimiter.tryAcquire("otp:ip:" + cmd.client().ipAddress(), rl.otpPerIpPerWindow(), rl.otpWindow())
                .flatMap(ok -> ok ? Mono.empty() : Mono.error(AuthErrors.tooManyRequests(rl.otpWindow())));
    }

    /**
     * Verifies and consumes the captcha.
     *
     * @param cmd the request
     * @return empty when solved, {@code CAPTCHA_INVALID} otherwise
     */
    private Mono<Void> verifyCaptcha(SendOtpCommand cmd) {
        return captchaService.verify(cmd.captchaId(), cmd.captchaAnswer(), cmd.client().ipAddress())
                .flatMap(ok -> ok ? Mono.empty() : Mono.error(AuthErrors.captchaInvalid()));
    }

    /**
     * Applies the resend cooldown, then the per-identifier quota. Keys use a hash of the identifier.
     *
     * @param identifier normalised destination
     * @return empty when allowed, {@code OTP_RESEND_COOLDOWN} or {@code RATE_LIMITED} otherwise
     */
    private Mono<Void> throttleIdentifier(Identifier identifier) {
        AuthProperties.RateLimit rl = props.rateLimit();
        String idKey = digest.hash(identifier.value()); // keep raw PII out of Redis key names
        return rateLimiter.tryCooldown("otp:" + idKey, props.otp().resendCooldown())
                .flatMap(wait -> Mono.<Void>error(AuthErrors.resendCooldown(wait)))
                .then(Mono.defer(() -> rateLimiter.tryAcquire("otp:id:" + idKey, rl.otpPerIdentifierPerWindow(), rl.otpWindow())))
                .flatMap(ok -> ok ? Mono.empty() : Mono.error(AuthErrors.tooManyRequests(rl.otpWindow())));
    }

    /**
     * Generates the code, stores its keyed hash under a new request id and hands it to the sender.
     *
     * @param identifier normalised destination
     * @return the send result
     */
    private Mono<SendOtpCommand.Result> issue(Identifier identifier) {
        AuthProperties.Otp cfg = props.otp();
        String requestId = UUID.randomUUID().toString();
        String code = generator.generate(cfg.length());
        OtpChallenge challenge = new OtpChallenge(requestId, identifier, hasher.hash(requestId, code), 0);
        return otpStore.save(challenge, cfg.ttl())
                .then(Mono.defer(() -> sender.send(identifier, code)))
                .thenReturn(new SendOtpCommand.Result(requestId, identifier.type().name(), identifier.masked(),
                        cfg.ttl().toSeconds(), cfg.resendCooldown().toSeconds()));
    }

    /**
     * Serves {@link SendOtpCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<SendOtpCommand> commandType() {
        return SendOtpCommand.class;
    }
}
