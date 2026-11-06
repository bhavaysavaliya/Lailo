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

    private static String code(Throwable e) {
        return ((ApiException) e).getCode();
    }

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

    @Test
    void malformedIdentifierIsRejectedWithoutConsumingCaptcha() {
        String captchaId = captcha("AB3CD");

        StepVerifier.create(handler.handle(new SendOtpCommand("not-a-phone", captchaId, "AB3CD", uniqueClient())))
                .expectErrorSatisfies(e -> assertThat(code(e)).isEqualTo("INVALID_IDENTIFIER"))
                .verify();

        assertThat(redis.hasKey("captcha:" + captchaId).block()).isTrue();
    }

    @Test
    void redisKeysNeverContainTheRawIdentifier() {
        String email = uniqueEmail();
        handler.handle(new SendOtpCommand(email, captcha("AB3CD"), "AB3CD", uniqueClient())).block();

        List<String> keys = redis.scan(ScanOptions.scanOptions().match("*").build()).collectList().block();
        assertThat(keys).noneMatch(k -> k.contains(email) || k.contains(email.substring(0, 13)));
    }
}
