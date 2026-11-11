package com.lailo.application.features.payment.application.command;

import com.lailo.application.common.cqrs.Command;

import java.util.UUID;

/**
 * Gives authorised money back. Dispatched by the checkout saga when an order is cancelled after payment;
 * there is no API route for it, because a shopper cancels an order, not a payment.
 *
 * @param paymentId the attempt to refund
 */
public record RefundPaymentCommand(UUID paymentId) implements Command<Boolean> {
}
