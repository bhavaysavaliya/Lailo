package com.lailo.application.common.notification;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Development stand-in for an SMS provider (MSG91, Twilio, Gupshup). Bodies are never logged, for the same
 * reason {@link LoggingEmailSender} does not log them.
 */
@Slf4j
public class LoggingMessageSender implements MessageSender {

    /**
     * Pretends to deliver the text message and records that it would have gone out.
     *
     * @param message the message that would be delivered
     * @return completion signal
     */
    @Override
    public Mono<Void> send(TextMessage message) {
        return Mono.fromRunnable(() ->
                log.info("[DEV] text message ({} chars) to {} - no SMS provider configured",
                        message.body().length(), Masks.mask(message.to())));
    }
}
