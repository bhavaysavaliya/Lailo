package com.lailo.application.features.auth.infrastructure.redis;

import com.lailo.application.features.auth.domain.Identifier;
import com.lailo.application.features.auth.domain.IdentifierType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/** OTP challenges as Redis hashes with TTL; attempts are incremented atomically (HINCRBY). */
@Component
@RequiredArgsConstructor
public class RedisOtpStore {

    private static final String PREFIX = "otp:req:";
    private final ReactiveStringRedisTemplate redis;

    public Mono<Boolean> save(OtpChallenge c, Duration ttl) {
        String key = PREFIX + c.requestId();
        Map<String, String> fields = Map.of(
                "type", c.identifier().type().name(),
                "value", c.identifier().value(),
                "hash", c.codeHash(),
                "attempts", Integer.toString(c.attempts()));
        return redis.opsForHash().putAll(key, fields).then(redis.expire(key, ttl));
    }

    public Mono<OtpChallenge> find(String requestId) {
        String key = PREFIX + requestId;
        return redis.<String, String>opsForHash().entries(key)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .filter(m -> m.containsKey("hash"))
                .map(m -> new OtpChallenge(requestId,
                        new Identifier(IdentifierType.valueOf(m.get("type")), m.get("value")),
                        m.get("hash"),
                        Integer.parseInt(m.getOrDefault("attempts", "0"))));
    }

    public Mono<Long> incrementAttempts(String requestId) {
        return redis.opsForHash().increment(PREFIX + requestId, "attempts", 1);
    }

    /** Single-use guarantee: only the caller that actually deletes the key may proceed. */
    public Mono<Boolean> consume(String requestId) {
        return redis.delete(PREFIX + requestId).map(n -> n > 0);
    }
}
