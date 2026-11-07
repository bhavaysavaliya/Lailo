package com.lailo.application.features.auth.infrastructure.captcha;

import com.lailo.application.features.auth.config.AuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Cloudflare Turnstile verification (server-side "siteverify"). Fails closed: any error = not human.
 * https://developers.cloudflare.com/turnstile/get-started/server-side-validation/
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "lailo.auth.captcha", name = "provider", havingValue = "turnstile")
public class TurnstileCaptchaService implements CaptchaService {

    private final AuthProperties.Captcha cfg;
    private final WebClient webClient;

    /**
     * Creates the service with a WebClient pointed at the configured siteverify host.
     *
     * @param props captcha settings
     * @param builder Spring's shared WebClient builder
     */
    public TurnstileCaptchaService(AuthProperties props, WebClient.Builder builder) {
        this.cfg = props.captcha();
        this.webClient = builder.baseUrl(cfg.turnstileBaseUrl()).build();
    }

    /**
     * Turnstile challenges are rendered by the widget, so this only returns the site key.
     *
     * @return challenge with provider {@code turnstile} and the site key
     */
    @Override
    public Mono<CaptchaChallenge> issue() {
        return Mono.just(new CaptchaChallenge("turnstile", null, null, cfg.turnstileSiteKey(), 300));
    }

    /**
     * Verifies the widget token with Cloudflare's siteverify API (5 s timeout). Any error or timeout counts as
     * not verified.
     *
     * @param captchaId ignored by this provider
     * @param token the widget token
     * @param remoteIp caller IP, forwarded to Cloudflare when known
     * @return {@code true} only when Cloudflare reports success
     */
    @Override
    public Mono<Boolean> verify(String captchaId, String token, String remoteIp) {
        if (token == null || token.isBlank()) {
            return Mono.just(false);
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", cfg.turnstileSecret());
        form.add("response", token);
        if (remoteIp != null) {
            form.add("remoteip", remoteIp);
        }
        return webClient.post()
                .uri("/turnstile/v0/siteverify")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(SiteVerifyResponse.class)
                .timeout(Duration.ofSeconds(5))
                .map(SiteVerifyResponse::success)
                .onErrorResume(ex -> {
                    log.warn("Turnstile verification failed: {}", ex.toString());
                    return Mono.just(false);
                });
    }

    /**
     * The part of the siteverify response the service relies on.
     *
     * @param success whether the token was valid
     */
    record SiteVerifyResponse(boolean success) {
    }
}
