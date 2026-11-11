package com.lailo.application.features.order.application.command;

import com.lailo.application.common.cqrs.Command;

import java.util.UUID;

/**
 * Points an order at the payment attempt now in play, so a shopper who comes back to an unpaid order can
 * resume it. Dispatched by the checkout saga when a payment is opened.
 *
 * @param orderId the order
 * @param paymentId the attempt
 */
public record AttachPaymentToOrderCommand(UUID orderId, UUID paymentId) implements Command<Boolean> {
}
