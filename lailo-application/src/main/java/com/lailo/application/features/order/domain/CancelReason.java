package com.lailo.application.features.order.domain;

/** Why an order was cancelled. Recorded on the order and carried on {@code OrderCancelled}. */
public enum CancelReason {
    /** The shopper changed their mind. */
    REQUESTED_BY_CUSTOMER,
    /** The gateway declined, or the shopper never completed the payment. */
    PAYMENT_FAILED,
    /** Stock ran out between adding to the cart and placing the order. */
    OUT_OF_STOCK,
    /** Support or operations called it off. */
    CANCELLED_BY_SUPPORT
}
