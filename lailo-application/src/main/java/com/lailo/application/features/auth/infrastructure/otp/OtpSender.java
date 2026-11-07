package com.lailo.application.features.auth.infrastructure.otp;

import com.lailo.application.features.auth.domain.Identifier;
import reactor.core.publisher.Mono;

/** Delivery channel for OTPs (SMS for phones, email for addresses). */
public interface OtpSender {
    /**
     * Delivers a code to the user.
     *
     * @param destination email or phone to deliver to
     * @param code the plain code
     * @return completion signal; an error means delivery failed
     */
    Mono<Void> send(Identifier destination, String code);
}
