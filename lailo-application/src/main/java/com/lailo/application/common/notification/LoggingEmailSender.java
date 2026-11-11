package com.lailo.application.common.notification;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Development stand-in for a real provider (SES, Postmark, MSG91 Email). Bodies are never logged: they carry
 * OTPs and order details, and dev logs are the easiest place to leak them from.
 */
@Slf4j
public class LoggingEmailSender implements EmailSender {

    /**
     * Pretends to deliver the email and records that it would have gone out.
     *
     * @param message the message that would be delivered
     * @return completion signal
     */
    @Override
    public Mono<Void> send(EmailMessage message) {
        return Mono.fromRunnable(() ->
                log.info("[DEV] email \"{}\" to {} - no email provider configured",
                        message.subject(), Masks.mask(message.to())));
    }
}
