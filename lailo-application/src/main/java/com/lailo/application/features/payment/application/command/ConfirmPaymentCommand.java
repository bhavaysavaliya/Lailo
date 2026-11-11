package com.lailo.application.features.payment.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.features.payment.application.query.PaymentView;

import java.util.UUID;

/**
 * Asks the gateway for the final word on an attempt and records it.
 *
 * <p>Nothing about the outcome is taken from the caller: the client reports only that it finished, and the
 * handler then asks the provider. That is what makes it safe to expose to the browser and to reuse for the
 * provider's webhook.</p>
 *
 * @param paymentId the attempt to decide
 * @param userId the caller, or {@code null} when the provider's webhook is driving it
 */
public record ConfirmPaymentCommand(UUID paymentId, UUID userId) implements Command<PaymentView> {
}
