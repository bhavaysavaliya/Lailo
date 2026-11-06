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

    public TurnstileCaptchaService(AuthProperties props, WebClient.Builder builder) {
        this.cfg = props.captcha();
        this.webClient = builder.baseUrl(cfg.turnstileBaseUrl()).build();
    }

    @Override
    public Mono<CaptchaChallenge> issue() {
        return Mono.just(new CaptchaChallenge("turnstile", null, null, cfg.turnstileSiteKey(), 300));
    }

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

    record SiteVerifyResponse(boolean success) {
    }
}
