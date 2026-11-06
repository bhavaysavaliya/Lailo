package com.lailo.application.features.auth.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class RedisCaptchaStore {

    private static final String PREFIX = "captcha:";
    private final ReactiveStringRedisTemplate redis;

    public Mono<Boolean> save(String captchaId, String answer, Duration ttl) {
        return redis.opsForValue().set(PREFIX + captchaId, answer, ttl);
    }

    /** GETDEL: a captcha can be attempted exactly once, right or wrong. */
    public Mono<String> consume(String captchaId) {
        return redis.opsForValue().getAndDelete(PREFIX + captchaId);
    }
}
