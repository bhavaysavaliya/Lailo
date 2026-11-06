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

@Component
@RequiredArgsConstructor
public class IssueCaptchaHandler implements CommandHandler<IssueCaptchaCommand, CaptchaChallenge> {

    private final CaptchaService captchaService;
    private final RedisRateLimiter rateLimiter;
    private final AuthProperties props;

    @Override
    public Mono<CaptchaChallenge> handle(IssueCaptchaCommand cmd) {
        AuthProperties.RateLimit rl = props.rateLimit();
        return rateLimiter.tryAcquire("captcha:ip:" + cmd.client().ipAddress(), rl.captchaPerIpPerWindow(), rl.captchaWindow())
                .flatMap(ok -> ok ? captchaService.issue() : Mono.error(AuthErrors.tooManyRequests(rl.captchaWindow())));
    }

    @Override
    public Class<IssueCaptchaCommand> commandType() {
        return IssueCaptchaCommand.class;
    }
}
