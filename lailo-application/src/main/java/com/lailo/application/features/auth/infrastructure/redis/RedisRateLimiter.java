package com.lailo.application.features.auth.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Fixed-window counters and cooldown locks in Redis, shared by all app instances.
 * Keys are namespaced {@code rl:*} and always carry a TTL.
 */
@Component
@RequiredArgsConstructor
public class RedisRateLimiter {

    private final ReactiveStringRedisTemplate redis;

    /** @return true while the caller is within {@code limit} hits for the current window */
    public Mono<Boolean> tryAcquire(String key, int limit, Duration window) {
        String k = "rl:" + key;
        return redis.opsForValue().increment(k)
                .flatMap(count -> (count == 1 ? redis.expire(k, window) : Mono.just(true))
                        .thenReturn(count <= limit));
    }

    /** @return empty if the cooldown was acquired, otherwise the remaining wait */
    public Mono<Duration> tryCooldown(String key, Duration cooldown) {
        String k = "rl:cd:" + key;
        return redis.opsForValue().setIfAbsent(k, "1", cooldown)
                .flatMap(acquired -> acquired
                        ? Mono.<Duration>empty()
                        : redis.getExpire(k).defaultIfEmpty(cooldown));
    }

    /**
     * Ends a cooldown early so the next request is allowed immediately.
     *
     * @param key cooldown key (without the {@code rl:cd:} prefix)
     * @return {@code true} if a cooldown was removed
     */
    public Mono<Boolean> clearCooldown(String key) {
        return redis.delete("rl:cd:" + key).map(n -> n > 0);
    }
}
