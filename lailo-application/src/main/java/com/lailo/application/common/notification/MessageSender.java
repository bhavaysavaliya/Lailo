package com.lailo.application.common.notification;

import reactor.core.publisher.Mono;

/**
 * Outbound short-message port (SMS today), shared by every feature exactly as {@link EmailSender} is.
 *
 * <p>Kept separate from email rather than hidden behind one "send anything" interface: the two have
 * different providers, different costs and different failure modes, and a caller frequently wants one and
 * not the other.</p>
 */
public interface MessageSender {

    /**
     * Delivers one short message.
     *
     * @param message the message to deliver
     * @return completion signal; an error means delivery failed and may be retried
     */
    Mono<Void> send(TextMessage message);
}
