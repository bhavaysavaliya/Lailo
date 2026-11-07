package com.lailo.application.features.auth.infrastructure.captcha;

import reactor.core.publisher.Mono;

/** Strategy: swap the built-in image captcha for Turnstile / reCAPTCHA with configuration only. */
public interface CaptchaService {

    /**
     * Creates a challenge for the client to display.
     *
     * @return provider name plus whatever that provider needs (image and id, or site key)
     */
    Mono<CaptchaChallenge> issue();

    /**
     * @param captchaId challenge id (builtin) - ignored by token-based providers
     * @param answer    user's answer (builtin) or widget token (turnstile)
     */
    Mono<Boolean> verify(String captchaId, String answer, String remoteIp);
}
