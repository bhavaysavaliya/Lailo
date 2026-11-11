package com.lailo.application.common.notification;

import reactor.core.publisher.Mono;

/**
 * Outbound email port, shared by every feature (auth OTPs, order confirmations, payment receipts).
 *
 * <p>Implementations must be idempotent-friendly but need not deduplicate: {@link NotificationDispatcher}
 * owns deduplication, so a provider adapter only has to deliver what it is handed.</p>
 */
public interface EmailSender {

    /**
     * Delivers one email.
     *
     * @param message the message to deliver
     * @return completion signal; an error means delivery failed and may be retried
     */
    Mono<Void> send(EmailMessage message);
}
