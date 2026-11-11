package com.lailo.application.features.payment.infrastructure.gateway;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;

/**
 * Port for the payment provider, in the same spirit as {@code ObjectStorage} and {@code EmailSender}:
 * swapping Razorpay for Cashfree or Stripe is a bean, not a rewrite.
 *
 * <p>Deliberately narrow. Lailo opens an attempt, learns whether it was approved, and can give the money
 * back. Everything that would involve card or UPI credentials happens between the client and the provider.
 * </p>
 */
public interface PaymentGateway {

    /**
     * Name recorded on every payment row this gateway handles, so old rows stay interpretable after a
     * provider migration.
     *
     * @return the provider name, e.g. {@code razorpay}
     */
    String provider();

    /**
     * Opens the payment on the provider's side.
     *
     * @param request what to charge and for which order
     * @return the provider's reference and, where it applies, where to send the shopper
     */
    Mono<GatewayIntent> createIntent(GatewayIntentRequest request);

    /**
     * Asks the provider for the final word on an attempt. Called when the client reports it finished, and
     * again when a webhook arrives - the provider is the authority either way, so a lying client changes
     * nothing.
     *
     * @param providerReference the gateway's reference for the attempt
     * @return the provider's decision
     */
    Mono<GatewayOutcome> confirm(String providerReference);

    /**
     * Returns authorised money.
     *
     * @param providerReference the gateway's reference for the attempt
     * @param amount amount to return
     * @return the provider's decision on the refund
     */
    Mono<GatewayOutcome> refund(String providerReference, BigDecimal amount);

    /**
     * Verifies that a webhook really came from the provider. Implementations must compare in constant time
     * and must fail closed: an unverifiable callback is an attacker, not a payment.
     *
     * @param payload raw request body, exactly as received
     * @param signature value of the provider's signature header, or {@code null}
     * @return {@code true} when the signature is valid
     */
    boolean verifySignature(String payload, String signature);
}
