package com.lailo.application.features.order.domain;

/**
 * Where an order stands. The shop only ever moves forward through this list, except for {@code CANCELLED},
 * which is reachable until the parcel is handed to the courier.
 */
public enum OrderStatus {
    /** Placed, stock reserved, waiting for the gateway to authorise the money. */
    PENDING_PAYMENT,
    /** Paid (or COD accepted) and queued for the warehouse. */
    CONFIRMED,
    /** Picked and packed, waiting for pickup. */
    PACKED,
    /** Handed to the courier and on its way. */
    SHIPPED,
    /** Received by the shopper. */
    DELIVERED,
    /** Called off before dispatch, by the shopper or because payment failed. */
    CANCELLED;

    /**
     * Whether the parcel has left the warehouse. This is the line the "Shipped" tab is drawn on: a delivered
     * order was shipped, so it stays in that view rather than disappearing.
     *
     * @return {@code true} for {@code SHIPPED} and {@code DELIVERED}
     */
    public boolean isDispatched() {
        return this == SHIPPED || this == DELIVERED;
    }

    /**
     * Whether the order is still waiting to go out, which is what the "To be shipped" tab lists.
     *
     * @return {@code true} for {@code PENDING_PAYMENT}, {@code CONFIRMED} and {@code PACKED}
     */
    public boolean isAwaitingDispatch() {
        return this == PENDING_PAYMENT || this == CONFIRMED || this == PACKED;
    }

    /**
     * Whether the order can still be called off. Once it is with the courier, cancellation becomes a return,
     * which is a different flow.
     *
     * @return {@code true} while the order has not been dispatched or cancelled
     */
    public boolean isCancellable() {
        return isAwaitingDispatch();
    }
}
