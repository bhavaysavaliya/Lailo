package com.lailo.application.features.payment.domain;

/** Lifecycle of one payment attempt, as Lailo sees it. */
public enum PaymentStatus {
    /** Created and waiting for the shopper (or, for COD, never used). */
    PENDING,
    /** The gateway guaranteed the funds; the order may be confirmed. */
    AUTHORIZED,
    /** Declined, abandoned or timed out. The order is cancelled and its stock released. */
    FAILED,
    /** Authorised money was given back after a cancellation. */
    REFUNDED;

    /**
     * Whether this attempt can still change.
     *
     * @return {@code true} once no further transition is expected
     */
    public boolean isFinal() {
        return this == FAILED || this == REFUNDED;
    }
}
