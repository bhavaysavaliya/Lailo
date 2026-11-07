package com.lailo.application.features.auth.infrastructure.captcha;

import com.lailo.application.features.auth.config.AuthProperties;
import com.lailo.application.features.auth.infrastructure.redis.RedisCaptchaStore;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Self-hosted image captcha: answer kept in Redis (single use, short TTL), case-insensitive. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "lailo.auth.captcha", name = "provider", havingValue = "builtin", matchIfMissing = true)
public class BuiltinCaptchaService implements CaptchaService {

    /** No 0/O, 1/I/L - avoids ambiguous glyphs. */
    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final AuthProperties props;
    private final RedisCaptchaStore store;
    private final CaptchaImageRenderer renderer;
    private final SecureRandom random;

    /**
     * Generates random text, renders it off the event loop and stores the answer in Redis with the configured TTL.
     *
     * @return the challenge with a PNG data URI
     */
    @Override
    public Mono<CaptchaChallenge> issue() {
        String id = UUID.randomUUID().toString();
        String text = randomText(props.captcha().length());
        return Mono.fromCallable(() -> renderer.renderDataUri(text, random))
                .subscribeOn(Schedulers.boundedElastic()) // AWT rendering is CPU/blocking work
                .flatMap(image -> store.save(id, text, props.captcha().ttl())
                        .thenReturn(new CaptchaChallenge("builtin", id, image, null,
                                props.captcha().ttl().toSeconds())));
    }

    /**
     * Consumes the stored answer (GETDEL) and compares it case-insensitively in constant time. Every attempt,
     * right or wrong, uses up the challenge.
     *
     * @param captchaId the challenge id
     * @param answer the user's answer
     * @param remoteIp ignored by this provider
     * @return {@code true} only for a correct answer to a live, unused challenge
     */
    @Override
    public Mono<Boolean> verify(String captchaId, String answer, String remoteIp) {
        if (captchaId == null || answer == null || answer.isBlank()) {
            return Mono.just(false);
        }
        return store.consume(captchaId)
                .map(expected -> MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        answer.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)))
                .defaultIfEmpty(false);
    }

    /**
     * Builds random captcha text from the unambiguous alphabet.
     *
     * @param len number of characters
     * @return the text
     */
    private String randomText(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
