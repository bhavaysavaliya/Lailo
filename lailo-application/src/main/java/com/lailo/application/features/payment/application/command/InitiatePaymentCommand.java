package com.lailo.application.features.payment.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.features.payment.application.query.PaymentView;
import com.lailo.application.features.payment.domain.PaymentMethod;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Opens a payment attempt for an order. Dispatched by the checkout saga once stock has been reserved, never
 * straight from the API: a payment that exists for an order with no reserved units would have to be refunded.
 *
 * @param orderId order being paid for
 * @param orderNumber human-readable reference, shown by the gateway
 * @param userId the shopper
 * @param amount amount to charge
 * @param currency ISO 4217 code
 * @param method how the shopper chose to pay
 */
@Builder
public record InitiatePaymentCommand(UUID orderId, String orderNumber, UUID userId, BigDecimal amount,
                                     String currency, PaymentMethod method) implements Command<PaymentView> {
}
