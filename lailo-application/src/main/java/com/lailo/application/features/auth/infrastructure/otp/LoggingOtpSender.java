package com.lailo.application.features.auth.infrastructure.otp;

import com.lailo.application.features.auth.domain.Identifier;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/** Development stand-in. Never logs the code itself. */
@Slf4j
public class LoggingOtpSender implements OtpSender {
    @Override
    public Mono<Void> send(Identifier destination, String code) {
        return Mono.fromRunnable(() ->
                log.info("[DEV] OTP issued for {} via {} - no delivery provider configured",
                        destination.masked(), destination.type()));
    }
}
