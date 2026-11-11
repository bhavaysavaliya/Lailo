package com.lailo.application.features.payment.application.command;

import com.lailo.application.common.cqrs.Command;

/**
 * Processes a callback from the payment provider.
 *
 * @param payload raw request body, exactly as received - re-serialising it would break the signature
 * @param signature value of the provider's signature header, or {@code null}
 */
public record HandlePaymentWebhookCommand(String payload, String signature) implements Command<Boolean> {

    /**
     * Keeps the body and the signature out of the logs: the logging aspect prints command arguments at DEBUG,
     * and a valid signature is a credential.
     *
     * @return a log-safe representation
     */
    @Override
    public String toString() {
        return "HandlePaymentWebhookCommand[bytes=" + (payload == null ? 0 : payload.length())
                + ", signed=" + (signature != null) + "]";
    }
}
