package com.lailo.application.features.auth.infrastructure.otp;

import com.lailo.application.features.auth.domain.Identifier;
import reactor.core.publisher.Mono;

/** Delivery channel for OTPs (SMS for phones, email for addresses). */
public interface OtpSender {
    Mono<Void> send(Identifier destination, String code);
}
