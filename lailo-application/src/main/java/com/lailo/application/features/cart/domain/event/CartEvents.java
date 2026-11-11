package com.lailo.application.features.cart.domain.event;

import java.time.Instant;

/**
 * Integration events for the cart, published through the outbox like every other aggregate's.
 *
 * <p>Nothing projects them yet: the cart is read from its write model (see {@code GetCartHandler}). They
 * exist because the consumers that need them are the ones nobody can add later without them - abandoned-cart
 * reminders, demand signals and merchandising analytics all want the stream, and an event that was never
 * published cannot be replayed.</p>
 */
public final class CartEvents {

    public static final String TOPIC = "lailo.cart.cart-events.v1";
    public static final String AGGREGATE = "Cart";

    public static final String CART_ITEM_ADDED = "CartItemAdded";
    public static final String CART_ITEM_QUANTITY_CHANGED = "CartItemQuantityChanged";
    public static final String CART_ITEM_REMOVED = "CartItemRemoved";
    public static final String CART_CLEARED = "CartCleared";

    /** Not instantiable: constants and event types only. */
    private CartEvents() {
    }

    /**
     * A product was put in the cart for the first time.
     *
     * @param eventId unique event id, used for idempotency
     * @param userId whose cart changed
     * @param productId product added
     * @param quantity units now on the line
     * @param occurredAt change time
     */
    public record CartItemAdded(String eventId, String userId, String productId, int quantity,
                                Instant occurredAt) {
    }

    /**
     * A line's quantity moved, either by adding the same product again or by editing the line.
     *
     * @param eventId unique event id
     * @param userId whose cart changed
     * @param productId product whose line changed
     * @param quantity units now on the line
     * @param occurredAt change time
     */
    public record CartItemQuantityChanged(String eventId, String userId, String productId, int quantity,
                                          Instant occurredAt) {
    }

    /**
     * A line was taken out of the cart.
     *
     * @param eventId unique event id
     * @param userId whose cart changed
     * @param productId product removed
     * @param occurredAt change time
     */
    public record CartItemRemoved(String eventId, String userId, String productId, Instant occurredAt) {
    }

    /**
     * The whole cart was emptied, either by the shopper or because its contents became an order.
     *
     * @param eventId unique event id
     * @param userId whose cart was emptied
     * @param reason {@code CHECKED_OUT} or {@code CLEARED_BY_USER}
     * @param occurredAt change time
     */
    public record CartCleared(String eventId, String userId, String reason, Instant occurredAt) {
    }
}
