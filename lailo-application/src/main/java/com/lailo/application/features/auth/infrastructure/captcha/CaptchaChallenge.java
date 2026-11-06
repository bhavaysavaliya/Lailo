package com.lailo.application.features.auth.infrastructure.captcha;

/**
 * What the client needs to render a captcha. For {@code builtin}: {@code captchaId} + {@code image};
 * for {@code turnstile}: {@code siteKey} (the widget produces the token sent back as the answer).
 */
public record CaptchaChallenge(String provider, String captchaId, String image, String siteKey,
                               long expiresInSeconds) {
}
