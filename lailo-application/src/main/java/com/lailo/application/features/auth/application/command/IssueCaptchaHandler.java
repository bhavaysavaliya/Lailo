package com.lailo.application.features.auth.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.features.auth.application.AuthErrors;
import com.lailo.application.features.auth.config.AuthProperties;
import com.lailo.application.features.auth.infrastructure.captcha.CaptchaChallenge;
import com.lailo.application.features.auth.infrastructure.captcha.CaptchaService;
import com.lailo.application.features.auth.infrastructure.redis.RedisRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Issues captchas, rate-limited per IP so the endpoint cannot be used to hammer image rendering. */
@Component
@RequiredArgsConstructor
public class IssueCaptchaHandler implements CommandHandler<IssueCaptchaCommand, CaptchaChallenge> {

    private final CaptchaService captchaService;
    private final RedisRateLimiter rateLimiter;
    private final AuthProperties props;

    /**
     * Checks the per-IP captcha quota, then delegates to the configured captcha provider.
     *
     * @param cmd the request
     * @return the challenge, or {@code RATE_LIMITED}
     */
    @Override
    public Mono<CaptchaChallenge> handle(IssueCaptchaCommand cmd) {
        AuthProperties.RateLimit rl = props.rateLimit();
        return rateLimiter.tryAcquire("captcha:ip:" + cmd.client().ipAddress(), rl.captchaPerIpPerWindow(), rl.captchaWindow())
                .flatMap(ok -> ok ? captchaService.issue() : Mono.error(AuthErrors.tooManyRequests(rl.captchaWindow())));
    }

    /**
     * Serves {@link IssueCaptchaCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<IssueCaptchaCommand> commandType() {
        return IssueCaptchaCommand.class;
    }
}
