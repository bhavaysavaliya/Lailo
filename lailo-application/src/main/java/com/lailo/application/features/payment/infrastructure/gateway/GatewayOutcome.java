package com.lailo.application.features.payment.infrastructure.gateway;

/**
 * What the gateway decided.
 *
 * @param approved whether the funds are guaranteed
 * @param providerReference the gateway's reference for the transaction
 * @param failureReason why it was declined, or {@code null} when approved
 */
public record GatewayOutcome(boolean approved, String providerReference, String failureReason) {

    /**
     * An approved outcome.
     *
     * @param providerReference the gateway's reference
     * @return the outcome
     */
    public static GatewayOutcome approved(String providerReference) {
        return new GatewayOutcome(true, providerReference, null);
    }

    /**
     * A declined outcome.
     *
     * @param providerReference the gateway's reference, when it gave one
     * @param reason why it was declined, safe to show the shopper
     * @return the outcome
     */
    public static GatewayOutcome declined(String providerReference, String reason) {
        return new GatewayOutcome(false, providerReference, reason);
    }
}
