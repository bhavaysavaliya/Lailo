package com.lailo.application.features.payment.infrastructure.gateway;

import lombok.Builder;

import java.time.Instant;

/**
 * The provider's half of a payment, handed to the client so it can complete the transaction directly with
 * the gateway. Nothing sensitive passes through Lailo.
 *
 * @param providerReference the gateway's id for the attempt
 * @param checkoutUrl where to send the shopper, or {@code null} when the client uses an SDK instead
 * @param expiresAt when the provider stops accepting it, or {@code null} when it does not expire
 */
@Builder
public record GatewayIntent(String providerReference, String checkoutUrl, Instant expiresAt) {
}
