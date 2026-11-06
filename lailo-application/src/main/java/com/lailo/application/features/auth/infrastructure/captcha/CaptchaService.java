package com.lailo.application.features.auth.infrastructure.captcha;

import reactor.core.publisher.Mono;

/** Strategy: swap the built-in image captcha for Turnstile / reCAPTCHA with configuration only. */
public interface CaptchaService {

    Mono<CaptchaChallenge> issue();

    /**
     * @param captchaId challenge id (builtin) - ignored by token-based providers
     * @param answer    user's answer (builtin) or widget token (turnstile)
     */
    Mono<Boolean> verify(String captchaId, String answer, String remoteIp);
}
