package com.lailo.application.features.payment.infrastructure.gateway;

import com.lailo.application.features.payment.domain.PaymentMethod;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What a gateway needs to open a payment on its side.
 *
 * @param paymentId Lailo's id for the attempt, sent as the provider's idempotency key
 * @param orderNumber human-readable reference, shown to the shopper by the provider
 * @param amount amount to charge
 * @param currency ISO 4217 code
 * @param method how the shopper chose to pay
 */
@Builder
public record GatewayIntentRequest(UUID paymentId, String orderNumber, BigDecimal amount, String currency,
                                   PaymentMethod method) {
}
