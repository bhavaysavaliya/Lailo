package com.lailo.application.features.auth.application.command;

import com.lailo.application.common.web.ApiException;
import com.lailo.application.common.web.ClientInfo;
import com.lailo.application.features.auth.domain.Identifier;
import com.lailo.application.features.auth.domain.IdentifierType;
import com.lailo.application.features.auth.infrastructure.otp.OtpHasher;
import com.lailo.application.features.auth.infrastructure.redis.OtpChallenge;
import com.lailo.application.features.auth.infrastructure.redis.RedisCaptchaStore;
import com.lailo.application.features.auth.infrastructure.redis.RedisOtpStore;
import com.lailo.application.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** SendOtpHandler against real Redis (captcha store, rate limiter, OTP store). */
class SendOtpHandlerIT extends IntegrationTest {

    @Autowired SendOtpHandler handler;
    @Autowired RedisCaptchaStore captchaStore;
    @Autowired RedisOtpStore otpStore;
    @Autowired OtpHasher hasher;
    @Autowired ReactiveStringRedisTemplate redis;

    /** A solved captcha, stored exactly as BuiltinCaptchaService would. */
    private String captcha(String answer) {
        String id = UUID.randomUUID().toString();
        captchaStore.save(id, answer, Duration.ofMinutes(2)).block();
        return id;
    }

    /**
     * Extracts the error code from an {@code ApiException}.
     *
     * @param e the error signal
     * @return its code
     */
    private static String code(Throwable e) {
        return ((ApiException) e).getCode();
    }

    /**
     * A valid request stores a hashed OTP in Redis and delivers it.
     * <ul>
     *   <li><b>Given</b> a new phone number and a solved captcha</li>
     *   <li><b>When</b> the handler runs</li>
     *   <li><b>Then</b> the masked result is returned; Redis holds the challenge with attempts 0, a 64-char HMAC (not the code) and a TTL of about 5 minutes; the sender receives {@code 123456}; the captcha key is gone</li>
     * </ul>
     */
    @Test
    void storesHashedOtpInRedisAndDeliversIt() {
        String phone = uniquePhone();
        String captchaId = captcha("AB3CD");

        SendOtpCommand.Result result = handler.handle(new SendOtpCommand(phone, captchaId, "ab3cd", uniqueClient())).block();

        assertThat(result.identifierType()).isEqualTo("PHONE");
        assertThat(result.maskedIdentifier()).startsWith("+91").endsWith(phone.substring(6)).doesNotContain(phone);
        assertThat(result.expiresInSeconds()).isEqualTo(300);
        assertThat(result.resendAfterSeconds()).isEqualTo(30);

        OtpChallenge stored = otpStore.find(result.requestId()).block();
        assertThat(stored.identifier()).isEqualTo(new Identifier(IdentifierType.PHONE, "+91" + phone));
        assertThat(stored.attempts()).isZero();
        assertThat(stored.codeHash()).isNotEqualTo("123456").hasSize(64);
        assertThat(hasher.matches(result.requestId(), "123456", stored.codeHash())).isTrue();
        assertThat(redis.getExpire("otp:req:" + result.requestId()).block()).isBetween(Duration.ofSeconds(290), Duration.ofSeconds(300));

        verify(otpSender).send(eq(new Identifier(IdentifierType.PHONE, "+91" + phone)), eq("123456"));
        assertThat(redis.hasKey("captcha:" + captchaId).block()).as("captcha is single use").isFalse();
    }

    /**
     * A wrong captcha issues nothing and uses up the captcha.
     * <ul>
     *   <li><b>Given</b> a stored captcha and a new email</li>
     *   <li><b>When</b> a wrong answer is submitted, then the correct one</li>
     *   <li><b>Then</b> both fail with {@code CAPTCHA_INVALID}, the captcha key is deleted and no OTP is sent</li>
     * </ul>
     */
    @Test
    void wrongCaptchaIssuesNothingAndBurnsTheCaptcha() {
        String email = uniqueEmail();
        String captchaId = captcha("AB3CD");

        StepVerifier.create(handler.handle(new SendOtpCommand(email, captchaId, "WRONG", uniqueClient())))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("CAPTCHA_INVALID"))
                .verify();

        assertThat(redis.hasKey("captcha:" + captchaId).block()).isFalse();
        verify(otpSender, never()).send(eq(Identifier.parse(email, "91")), any());

        // the correct answer no longer works either
        StepVerifier.create(handler.handle(new SendOtpCommand(email, captchaId, "AB3CD", uniqueClient())))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("CAPTCHA_INVALID"))
                .verify();
    }

    /**
     * A second request within the cooldown is refused.
     * <ul>
     *   <li><b>Given</b> an OTP was just sent to an email</li>
     *   <li><b>When</b> another OTP is requested for it with a new captcha</li>
     *   <li><b>Then</b> it fails with {@code OTP_RESEND_COOLDOWN} and {@code retryAfterSeconds} between 1 and 30</li>
     * </ul>
     */
    @Test
    void secondRequestWithinCooldownIsRejected() {
        String email = uniqueEmail();
        handler.handle(new SendOtpCommand(email, captcha("AAAAA"), "AAAAA", uniqueClient())).block();

        StepVerifier.create(handler.handle(new SendOtpCommand(email, captcha("BBBBB"), "BBBBB", uniqueClient())))
                .expectErrorSatisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getCode()).isEqualTo("OTP_RESEND_COOLDOWN");
                    assertThat((Long) ex.getDetails().get("retryAfterSeconds")).isBetween(1L, 30L);
                })
                .verify();
    }

    /**
     * An IP over its limit is throttled before the captcha is touched.
     * <ul>
     *   <li><b>Given</b> the IP's counter in Redis is already at the limit (10)</li>
     *   <li><b>When</b> an OTP is requested with a valid captcha</li>
     *   <li><b>Then</b> it fails with {@code RATE_LIMITED} and the captcha is still in Redis</li>
     * </ul>
     */
    @Test
    void ipOverItsWindowLimitIsThrottledBeforeCaptchaIsTouched() {
        ClientInfo client = uniqueClient();
        redis.opsForValue().set("rl:otp:ip:" + client.ipAddress(), "10", Duration.ofMinutes(15)).block();
        String captchaId = captcha("AB3CD");

        StepVerifier.create(handler.handle(new SendOtpCommand(uniquePhone(), captchaId, "AB3CD", client)))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("RATE_LIMITED"))
                .verify();

        assertThat(redis.hasKey("captcha:" + captchaId).block()).as("captcha untouched").isTrue();
    }

    /**
     * An identifier over its limit is throttled.
     * <ul>
     *   <li><b>Given</b> 5 OTPs were sent to one email (cooldowns cleared between them)</li>
     *   <li><b>When</b> a 6th is requested</li>
     *   <li><b>Then</b> it fails with {@code RATE_LIMITED}</li>
     * </ul>
     */
    @Test
    void identifierOverItsWindowLimitIsThrottled() {
        String email = uniqueEmail();
        for (int i = 0; i < 5; i++) {
            handler.handle(new SendOtpCommand(email, captcha("AAAAA"), "AAAAA", uniqueClient())).block();
            redis.scan(ScanOptions.scanOptions().match("rl:cd:otp:*").build()).flatMap(redis::delete).blockLast();
        }

        StepVerifier.create(handler.handle(new SendOtpCommand(email, captcha("AAAAA"), "AAAAA", uniqueClient())))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("RATE_LIMITED"))
                .verify();
    }

    /**
     * A malformed identifier fails before any Redis work.
     * <ul>
     *   <li><b>Given</b> the input {@code not-a-phone} and a valid captcha</li>
     *   <li><b>When</b> the handler runs</li>
     *   <li><b>Then</b> it fails with {@code INVALID_IDENTIFIER} and the captcha is not consumed</li>
     * </ul>
     */
    @Test
    void malformedIdentifierIsRejectedWithoutConsumingCaptcha() {
        String captchaId = captcha("AB3CD");

        StepVerifier.create(handler.handle(new SendOtpCommand("not-a-phone", captchaId, "AB3CD", uniqueClient())))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("INVALID_IDENTIFIER"))
                .verify();

        assertThat(redis.hasKey("captcha:" + captchaId).block()).isTrue();
    }

    /**
     * Redis key names contain no personal data.
     * <ul>
     *   <li><b>Given</b> an OTP has been sent to a new email</li>
     *   <li><b>When</b> all Redis keys are listed</li>
     *   <li><b>Then</b> none contains the email or its local part</li>
     * </ul>
     */
    @Test
    void redisKeysNeverContainTheRawIdentifier() {
        String email = uniqueEmail();
        handler.handle(new SendOtpCommand(email, captcha("AB3CD"), "AB3CD", uniqueClient())).block();

        List<String> keys = redis.scan(ScanOptions.scanOptions().match("*").build()).collectList().block();
        assertThat(keys).noneMatch(k -> k.contains(email) || k.contains(email.substring(0, 13)));
    }
}
